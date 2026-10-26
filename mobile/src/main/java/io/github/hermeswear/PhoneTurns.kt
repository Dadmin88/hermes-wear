package io.github.hermeswear

import android.content.Context
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Durable request/result cache. A retry of an identity can never create a new Hermes identity. */
object PhoneTurns {
    private fun prefs(c: Context) = c.getSharedPreferences("hermes_turns", Context.MODE_PRIVATE)
    @Synchronized fun load(c: Context, id: String): JSONObject? =
        try { JSONObject(prefs(c).getString(id, "") ?: "") } catch (_: Exception) { null }
    @Synchronized fun save(c: Context, id: String, record: JSONObject) {
        val p=prefs(c)
        val edit=p.edit().putString(id, record.toString())
        // Bounded retention. Never evict an active turn.
        val old=p.all.mapNotNull { (key,value) ->
            try { key to JSONObject(value.toString()) } catch (_:Exception) { null }
        }.filter { it.second.optJSONObject("result")?.optString("state") in TurnPolicy.terminal }
            .sortedBy { it.second.optLong("created") }
        old.take(maxOf(0, p.all.size-31)).forEach { edit.remove(it.first) }
        check(edit.commit()) { "Phone cannot save request. Check available storage." }
    }
    @Synchronized fun publish(c: Context, id: String, body: JSONObject): JSONObject {
        val record=load(c,id) ?: error("Unknown turn")
        val previous=record.optJSONObject("result")
        val before=previous?.optString("state") ?: ""
        if(before in TurnPolicy.terminal &&
            !(before=="failed" && body.optString("state") in setOf("accepted","queued"))) return previous!!
        if(previous!=null && body.optLong("seq",-1)>0 && body.optLong("seq")<=previous.optLong("seq",-1)) return previous
        val seq=if(body.has("seq") && body.optLong("seq")>0) body.optLong("seq") else if(body.optString("state") in setOf("accepted","queued")) 0L else record.optLong("seq",0)+1
        body.put("id",id).put("seq",seq)
        record.put("seq",seq).put("result",body)
        if (body.optString("runId").isNotBlank()) record.put("runId",body.optString("runId"))
        save(c,id,record)
        return body
    }
    fun send(c:Context, peer:String, body:JSONObject, wait:Boolean=false) {
        val id=body.optString("id")
        Trace.record(c,id,"phone_send_invoked",JSONObject().put("seq",body.optLong("seq")).put("state",body.optString("state")))
        val task=Wearable.getMessageClient(c).sendMessage(peer,"/hermes-wear/response",body.toString().toByteArray(Charsets.UTF_8))
        task.addOnSuccessListener {
            Trace.record(c,id,"phone_send_submitted",JSONObject().put("seq",body.optLong("seq")))
            Diagnostics.enqueue(c,JSONObject().put("id",id).put("source","phone").put("events",Trace.events(c,id)))
        }.addOnFailureListener {
            Trace.record(c,id,"phone_send_failed")
            Diagnostics.enqueue(c,JSONObject().put("id",id).put("source","phone").put("events",Trace.events(c,id)))
        }
        if(wait) try { Tasks.await(task,3,TimeUnit.SECONDS) } catch (_:Exception) {
            // Result remains durable; watch resume/retry replays it.
            Trace.record(c,id,"phone_send_unconfirmed")
        }
    }
    @Synchronized fun submit(c:Context, peer:String, packet:JSONObject) {
        val id=packet.optString("id")
        require(id.matches(Regex("[a-zA-Z0-9_-]{8,100}"))) { "Invalid turn identity" }
        val existing=load(c,id)
        if(existing!=null) {
            val original=existing.getJSONObject("request")
            require(original.optString("sessionId")==packet.optString("sessionId") &&
                    original.optString("text")==packet.optString("text")) { "Turn identity reused for a different message" }
            val result=existing.optJSONObject("result")
            if(result!=null && !(packet.optBoolean("retry") && result.optString("state")=="failed" && result.optBoolean("retryable"))) {
                send(c,peer,result)
                if(result.optString("state") in TurnPolicy.terminal || result.optString("state")=="approval") return
            }
        } else {
            require(packet.optString("text").isNotBlank() || packet.optString("runId").isNotBlank()) { "Empty message" }
            save(c,id,JSONObject().put("created",System.currentTimeMillis()).put("request",packet)
                .put("peer",peer).put("seq",0).put("runId",packet.optString("runId")))
        }
        val record=load(c,id)!!
        record.put("peer",peer)
        // Retry/recovery gets a new bounded processing window but keeps the same run.
        if(packet.optBoolean("retry")) record.put("created",System.currentTimeMillis())
        save(c,id,record)
        val work=OneTimeWorkRequestBuilder<ChatWorker>()
            .setInputData(workDataOf("id" to id))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(c).enqueueUniqueWork("hermes-chat-"+id,ExistingWorkPolicy.KEEP,work)
        if(existing?.optJSONObject("result")==null ||
            existing.optJSONObject("result")?.optString("state")=="failed") {
            send(c,peer,publish(c,id,JSONObject().put("ok",true).put("state","queued")))
        }
    }
    @Synchronized fun approve(c:Context, peer:String, packet:JSONObject) {
        val id=packet.optString("turnId")
        val record=load(c,id) ?: error("Conversation expired. Retry the message.")
        require(record.optString("peer")==peer && record.optString("runId")==packet.optString("runId")) { "Approval does not match this turn" }
        val prior=record.optJSONObject("result")
        if(prior?.optString("state") in TurnPolicy.terminal) {
            send(c,peer,prior!!); return
        }
        val previousDecision=record.optJSONObject("decision") ?: record.optJSONObject("appliedDecision")
        if(previousDecision!=null &&
            previousDecision.optString("requestId")==packet.optString("requestId") &&
            previousDecision.optInt("generation")==packet.optInt("generation") &&
            previousDecision.optString("choice")==packet.optString("choice")) {
            if(prior!=null) send(c,peer,prior)
            return
        }
        val current=record.optJSONObject("result")?.optJSONObject("approval")
        require(current?.optString("requestId")==packet.optString("requestId") &&
                current.optInt("generation",-1)==packet.optInt("generation",-2)) { "Approval expired" }
        require(packet.optString("choice") in setOf("once","deny")) { "Invalid approval choice" }
        record.put("decision",packet).put("created",System.currentTimeMillis())
        save(c,id,record)
        WorkManager.getInstance(c).enqueueUniqueWork("hermes-chat-"+id,ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ChatWorker>().setInputData(workDataOf("id" to id))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }
}
