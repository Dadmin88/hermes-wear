package io.github.hermeswear

import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException

/** App-to-app Data Layer bridge. Watch receives no HTTPS credentials. */
class PhoneListenerService : WearableListenerService() {
    // Cloud speech may block on an external API. It must never occupy chat workers.
    private val chatWorkers = Executors.newFixedThreadPool(2)
    private val speechWorkers = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        ThreadPoolExecutor.AbortPolicy()
    )
    override fun onMessageReceived(event: MessageEvent) {
        if(event.path=="/hermes-wear/config/request") {ConnectionSync.send(applicationContext,event.sourceNodeId);return}
        if(event.path=="/hermes-wear/trace" && event.data.size<=9000) {
            val packet=try { JSONObject(String(event.data,Charsets.UTF_8)) } catch (_:Exception) { return }
            val id=packet.optString("id")
            Trace.record(applicationContext,id,"phone_watch_trace_received")
            Diagnostics.enqueue(applicationContext,packet)
            return
        }
        if (event.path != "/hermes-wear/request" || event.data.size > 12000) return
        val peer = event.sourceNodeId
        val request = try { JSONObject(String(event.data, StandardCharsets.UTF_8)) }
                      catch (_: Exception) { return }
        val id = request.optString("id")
        val op = request.optString("op")
        Trace.record(applicationContext,id,"phone_request_received")
        if(op=="chat" || op=="decision") {
            try {
                if(op=="chat") PhoneTurns.submit(applicationContext,peer,request)
                else PhoneTurns.approve(applicationContext,peer,request)
            } catch (_:Exception) {
                val turnId=if(op=="decision") request.optString("turnId") else id
                val record=PhoneTurns.load(applicationContext,turnId)
                val body=JSONObject().put("ok",false).put("state","failed")
                    .put("error","Phone could not schedule this request. Open Hermes Bridge, then Retry.")
                    .put("retryable",true).put("id",turnId).put("seq",(record?.optLong("seq") ?: 0)+1)
                if(record!=null) PhoneTurns.publish(applicationContext,turnId,body)
                PhoneTurns.send(applicationContext,peer,body)
            }
            return
        }
        val worker = if (op == "tts") speechWorkers else chatWorkers
        try { worker.execute {
            val result = try {
                if (op == "tts") deliverSpeech(peer, request)
                else RelayHttp(applicationContext).call(op, request)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error",
                    (e.message ?: "Phone relay error").take(160))
            }
            result.put("id", id)
            Wearable.getMessageClient(applicationContext).sendMessage(
                peer, "/hermes-wear/response",
                result.toString().toByteArray(StandardCharsets.UTF_8)
            )
        } } catch (_: RejectedExecutionException) {
            val response = JSONObject().put("id", id).put("ok", false)
                .put("error", "Voice busy; chat remains available")
            Wearable.getMessageClient(applicationContext).sendMessage(
                peer, "/hermes-wear/response", response.toString().toByteArray(StandardCharsets.UTF_8)
            )
        }
    }
    /** Deliberately small, ordered chunks: Wear OS MessageClient is not a file-transfer API. */
    private fun deliverSpeech(peer: String, request: JSONObject): JSONObject {
        val id = request.optString("id")
        val audio = RelayHttp(applicationContext).synthesize(request)
        val chunkSize = 16 * 1024
        val count = (audio.size + chunkSize - 1) / chunkSize
        if (count < 1 || count > 40) throw java.io.IOException("Audio clip is too large")
        val client = Wearable.getMessageClient(applicationContext)
        for (i in 0 until count) {
            val from = i * chunkSize
            val to = minOf(audio.size, from + chunkSize)
            val packet = JSONObject()
                .put("id", id)
                .put("index", i)
                .put("count", count)
                .put("data", Base64.encodeToString(audio.copyOfRange(from, to), Base64.NO_WRAP))
            Tasks.await(
                client.sendMessage(peer, "/hermes-wear/audio",
                    packet.toString().toByteArray(StandardCharsets.UTF_8)),
                15, TimeUnit.SECONDS
            )
        }
        return JSONObject().put("ok", true).put("state", "audio_delivered")
    }

    override fun onDestroy() {
        chatWorkers.shutdown()
        speechWorkers.shutdownNow()
        super.onDestroy()
    }
}
