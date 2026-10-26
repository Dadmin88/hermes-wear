package io.github.hermeswear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Durable direct transport. Closing the Activity never cancels an admitted server run. */
object WatchJobs {
    fun enqueue(c:Context,packet:JSONObject) {
        val id=if(packet.optString("op")=="decision")packet.optString("turnId") else packet.optString("id")
        c.getSharedPreferences("hermes_watch",Context.MODE_PRIVATE).edit().putString("job-$id",packet.toString()).commit()
        val work=OneTimeWorkRequestBuilder<DirectWorker>().setInputData(workDataOf("id" to id))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(c).enqueueUniqueWork("direct-$id",if(packet.optString("op")!="chat") ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,work)
    }
    fun notify(c:Context,id:String,approval:Boolean) {
        val p=c.getSharedPreferences("hermes_watch",Context.MODE_PRIVATE)
        if(p.getBoolean("activity_active",false))return
        val nm=c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("hermes-results","Hermes tasks",NotificationManager.IMPORTANCE_DEFAULT))
        val open=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {nm.notify(id.hashCode(),Notification.Builder(c,"hermes-results")
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(if(approval)"Hermes needs approval" else "Hermes task completed")
            .setContentText("Open Hermes to review").setContentIntent(open).setAutoCancel(true).build())}
        catch(_:SecurityException){} // Notification permission is optional; durable results remain.
    }
}
class DirectWorker(c:Context,params:WorkerParameters):Worker(c,params) {
    override fun doWork():Result {
        val c=applicationContext
        val prefs=c.getSharedPreferences("hermes_watch",Context.MODE_PRIVATE)
        val id=inputData.getString("id") ?: return Result.failure()
        val packet=try{JSONObject(prefs.getString("job-$id","") ?: "")}catch(_:Exception){return Result.failure()}
        val http=DeviceHttp(c)
        val original=WatchHistory.get(c,id)?.optJSONObject("request") ?: packet
        var run=prefs.getString("direct-run-$id","") ?: ""
        fun fallback():Boolean=try {
            val nodes=Tasks.await(Wearable.getNodeClient(c).connectedNodes,3,TimeUnit.SECONDS)
            val node=nodes.firstOrNull{it.isNearby} ?: nodes.firstOrNull()
            if(node==null)false else {
                val body=if(packet.optString("op")!="chat")packet else JSONObject(original.toString()).put("runId",run)
                Tasks.await(Wearable.getMessageClient(c).sendMessage(node.id,"/hermes-wear/request",body.toString().toByteArray()),3,TimeUnit.SECONDS)
                prefs.edit().putString("transport","Companion fallback").apply();true
            }
        }catch(_:Exception){false}
        fun publish(reply:RunReply) {
            val event=JSONObject().put("id",id).put("ok",reply.ok).put("state",reply.state).put("runId",reply.runId)
                .put("text",reply.text).put("error",reply.error).put("retryable",reply.retryable).put("seq",reply.seq)
            if(reply.approval.isNotBlank())event.put("approval",JSONObject(reply.approval))
            if(WatchInbox.receive(c,event.toString()) && reply.state in setOf("completed","approval")) WatchJobs.notify(c,id,reply.state=="approval")
        }
        fun convert(r:JSONObject)=RunReply(r.optBoolean("ok"),r.optString("state","working"),r.optString("runId"),r.optString("text"),r.optString("error"),r.optJSONObject("approval")?.toString() ?: "",r.optBoolean("retryable"),r.optLong("seq",0))
        try {
            if(packet.optString("op")=="cancel") {
                val result=http.call("cancel",packet)
                if(result.optBoolean("ok")) {publish(convert(result));return Result.success()}
                if(fallback())return Result.success()
                return Result.retry()
            }
            // Probe/recover before deciding: an applied approval must not be applied again.
            if(packet.optString("op")=="decision") {
                val current=http.call("status",JSONObject().put("id",id).put("runId",packet.optString("runId")))
                val result=if(current.optBoolean("ok") && current.optString("state")!="approval")current else http.call("approve",packet)
                if(!result.optBoolean("ok")) {if(fallback())return Result.success();return Result.retry()}
                run=packet.optString("runId")
            }
            val backend=object:RunBackend {
                override fun call(op:String,runId:String):RunReply {
                    val body=if(op=="ask")JSONObject(original.toString()) else JSONObject().put("runId",runId)
                    body.put("id",id)
                    val start=android.os.SystemClock.elapsedRealtime()
                    val response=http.call(op,body)
                    Trace.record(c,id,"watch_https_received",JSONObject().put("durationMs",android.os.SystemClock.elapsedRealtime()-start))
                    if(!response.optBoolean("ok") && response.optBoolean("retryable") && fallback())throw CompanionFallback()
                    prefs.edit().putString("transport","Direct HTTPS").apply()
                    return convert(response)
                }
            }
            RunLoop(backend,{System.currentTimeMillis()},{Thread.sleep(it)},{isStopped})
                .run(run,System.currentTimeMillis()+TurnPolicy.TURN_TIMEOUT_MS,
                    {prefs.edit().putString("direct-run-$id",it).commit()},::publish)
            return Result.success()
        }catch(_:CompanionFallback){return Result.success()}
        catch(_:InterruptedException){return Result.retry()}
        catch(_:Exception){if(fallback())return Result.success();return Result.retry()}
    }
    private class CompanionFallback:RuntimeException()
}
