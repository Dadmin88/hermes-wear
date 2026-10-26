package io.github.hermeswear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.*
import org.json.JSONObject

/** WorkManager owns process lifetime; the listener only records/enqueues a user turn. */
class ChatWorker(context:Context, params:WorkerParameters):Worker(context,params) {
    override fun getForegroundInfo():ForegroundInfo {
        val nm=applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("hermes-chat","Hermes conversations",NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(8642,Notification.Builder(applicationContext,"hermes-chat")
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Hermes is working")
            .setContentText("Processing your watch message").build())
    }
    override fun doWork():Result {
        val c=applicationContext
        val id=inputData.getString("id") ?: return Result.failure()
        val record=PhoneTurns.load(c,id) ?: return Result.failure()
        val peer=record.optString("peer")
        val packet=record.getJSONObject("request")
        Trace.record(c,id,"phone_worker_started")
        val relay=RelayHttp(c)
        fun emit(reply:RunReply) {
            val body=JSONObject().put("ok",reply.ok).put("state",reply.state)
                .put("runId",reply.runId).put("text",reply.text).put("error",reply.error)
                .put("retryable",reply.retryable).put("seq",reply.seq)
            if(reply.approval.isNotBlank()) body.put("approval",JSONObject(reply.approval))
            val event=PhoneTurns.publish(c,id,body)
            PhoneTurns.send(c,peer,event,reply.state in TurnPolicy.terminal || reply.state=="approval")
        }
        try {
            val previous=record.optJSONObject("result")
            // Completed work can be redelivered after process death. Re-send, never run twice.
            if(previous?.optString("state") in setOf("completed","cancelled","interrupted")) {
                PhoneTurns.send(c,peer,previous!!,true)
                return Result.success()
            }
            val decision=record.optJSONObject("decision")
            if(decision!=null) {
                // A process can die after the server applies a decision but before we save it.
                // Observe first: an already resumed/completed run must not receive a second decision.
                val observed=relay.call("status",JSONObject().put("id",id).put("runId",record.optString("runId")))
                val result=if(observed.optBoolean("ok") && observed.optString("state")!="approval") observed
                           else relay.call("approve",decision.put("id",id))
                if(!result.optBoolean("ok")) {
                    emit(RunReply(false,"failed",record.optString("runId"),error=result.optString("error")))
                    return Result.success()
                }
                synchronized(PhoneTurns) {
                    val updated=PhoneTurns.load(c,id)!!
                    updated.put("appliedDecision",decision)
                    updated.remove("decision")
                    PhoneTurns.save(c,id,updated)
                }
            } else if(previous?.optString("state")=="approval") {
                PhoneTurns.send(c,peer,previous,true)
                return Result.success()
            }
            val backend=object:RunBackend {
                override fun call(op:String, runId:String):RunReply {
                    val body=if(op=="ask") JSONObject(packet.toString()) else JSONObject().put("runId",runId)
                    body.put("id",id)
                    val result=relay.call(op,body)
                    return RunReply(result.optBoolean("ok"),result.optString("state","working"),
                        result.optString("runId"),result.optString("text"),result.optString("error"),
                        result.optJSONObject("approval")?.toString() ?: "",result.optBoolean("retryable"),result.optLong("seq",0))
                }
            }
            RunLoop(backend,{System.currentTimeMillis()},{Thread.sleep(it)},{isStopped})
                .run(record.optString("runId"),record.optLong("created")+TurnPolicy.TURN_TIMEOUT_MS,
                    { run -> synchronized(PhoneTurns) {
                        val saved=PhoneTurns.load(c,id)!!
                        saved.put("runId",run)
                        PhoneTurns.save(c,id,saved)
                    } }, ::emit)
            Diagnostics.enqueue(c,JSONObject().put("id",id).put("source","phone").put("events",Trace.events(c,id)))
            return Result.success()
        } catch (_:InterruptedException) {
            Trace.record(c,id,"phone_worker_interrupted")
            return Result.retry()
        } catch (_:Exception) {
            emit(RunReply(false,"failed",record.optString("runId"),
                error="Phone could not finish this request. Open Hermes Bridge, then Retry.",retryable=true))
            return Result.success()
        }
    }
}
