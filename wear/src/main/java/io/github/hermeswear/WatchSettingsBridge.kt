package io.github.hermeswear

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import org.json.JSONArray

/** Signed Data Layer messages; no public HTTP settings endpoint or secret export. */
object WatchSettingsBridge {
    const val REQUEST="/hermes-wear/settings/request"
    const val RESPONSE="/hermes-wear/settings/response"
    var actionHandler:((JSONObject)->String)?=null
    private val main=Handler(Looper.getMainLooper())
    fun snapshot(c:Context):JSONObject {
        val p=c.getSharedPreferences("hermes_watch",0);val values=WatchPreferences.values(c)
        val caps=try {JSONObject(p.getString("settings_capabilities","{}")!!)}catch(_:Exception){JSONObject()}
        val history=JSONArray()
        WatchHistory.all(c).take(20).forEach {record ->
            val req=record.optJSONObject("request") ?: return@forEach
            val result=record.optJSONObject("result")
            history.put(JSONObject().put("id",req.optString("id")).put("question",req.optString("text").take(160)).put("text",result?.optString("text")?.take(2000) ?: "").put("state",result?.optString("state") ?: "recoverable"))
        }
        val turn=try {JSONObject(p.getString("pending_turn","")!!)}catch(_:Exception){JSONObject()}
        val response=try {JSONObject(p.getString("turn_response","")!!)}catch(_:Exception){JSONObject()}
        val approval=if(response.optString("state")=="approval" && response.optString("id")==turn.optString("id")) response.optJSONObject("approval") else null
        return JSONObject().put("values",values).put("revision",WatchPreferences.revision(values)).put("capabilities",caps)
            .put("active",actionHandler!=null).put("turnId",turn.optString("id")).put("approval",approval)
            .put("history",history).put("lastReply",p.getString("last_text","")?.take(2000))
            .put("remoteVoice",p.getBoolean("remote_voice_available",false)).put("paired",ConnectionSettings(c).configured())
            .put("transport",p.getString("transport","Companion")).put("microphone",c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)
            .put("themes",JSONArray().apply {ThemeStore.choices(c).forEach {put(JSONObject().put("id",it.id).put("label",it.name))}})
    }
    internal fun executeAction(c:Context,request:JSONObject):String {
        require(kotlin.math.abs(System.currentTimeMillis()-request.optLong("createdMs"))<60000) {"Action expired. Try again."}
        val id=request.optString("requestId")
        require(id.matches(Regex("[a-zA-Z0-9_-]{8,100}")))
        val cache=c.getSharedPreferences("hermes_settings_actions",0)
        cache.getString(id,null)?.let {raw ->
            val prior=JSONObject(raw)
            if(prior.optString("state")=="done")return prior.getString("message")
            error(prior.optString("message","Action outcome uncertain. Refresh before repeating."))
        }
        val handler=actionHandler ?: error("Open Hermes on the watch to use this action")
        val now=System.currentTimeMillis()
        val prune=cache.edit()
        cache.all.forEach {(key,value) ->
            val created=try {JSONObject(value.toString()).optLong("createdMs")}catch(_:Exception){0L}
            if(now-created>=60000)prune.remove(key)
        }
        check(prune.commit());require(cache.all.size<128) {"Too many watch actions. Wait a minute."}
        fun save(state:String,message:String) {check(cache.edit().putString(id,JSONObject().put("state",state).put("message",message).put("createdMs",now).toString()).commit())}
        // Reserve before side effects. A restart can leave an uncertain result, never repeat execution.
        save("executing","Action outcome uncertain. Refresh before repeating.")
        return try {val result=handler(request);save("done",result);result} catch(e:Exception) {
            save("failed",(e.message ?: "Watch action failed").take(180));throw e
        }
    }
    fun receive(c:Context,event:MessageEvent):Boolean {
        if(event.path!=REQUEST)return false
        if(event.data.size>16000)return true
        val request=try {JSONObject(String(event.data,Charsets.UTF_8))}catch(_:Exception){return true}
        val id=request.optString("requestId")
        if(!id.matches(Regex("[a-zA-Z0-9_-]{8,100}")))return true
        main.post {
            val reply=JSONObject().put("requestId",id)
            try {
                when(request.optString("op")) {
                    "read" -> reply.put("message","Watch settings refreshed")
                    "patch" -> {WatchPreferences.apply(c,request.getJSONObject("patch"),request.getString("revision"));reply.put("message","Saved on watch")}
                    "action" -> {
                        reply.put("message",executeAction(c,request))
                    }
                    else -> error("Unsupported settings operation")
                }
                reply.put("ok",true)
            }catch(e:Exception){reply.put("ok",false).put("error",(e.message ?: "Watch settings unavailable").take(180))}
            reply.put("snapshot",snapshot(c))
            val data=reply.getJSONObject("snapshot")
            val history=data.getJSONArray("history")
            while(reply.toString().toByteArray().size>85000 && history.length()>0)history.remove(history.length()-1)
            val voices=data.optJSONObject("capabilities")?.optJSONArray("voices")
            while(reply.toString().toByteArray().size>85000 && voices!=null && voices.length()>0)voices.remove(voices.length()-1)
            val bytes=reply.toString().toByteArray()
            if(bytes.size<=90000)Wearable.getMessageClient(c).sendMessage(event.sourceNodeId,RESPONSE,bytes)
        }
        return true
    }
}
