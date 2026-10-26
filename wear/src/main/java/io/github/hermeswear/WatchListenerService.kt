package io.github.hermeswear
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Own response correlation and persistence even while the Activity is absent. */
class WatchListenerService:WearableListenerService() {
    override fun onMessageReceived(event:MessageEvent) {
        if(event.path=="/hermes-wear/config" && event.data.size<=4096) {
            try {
                val data=org.json.JSONObject(String(event.data,Charsets.UTF_8))
                val settings=ConnectionSettings(applicationContext)
                if(settings.validUrl(data.optString("url")) && data.optString("token").length>=24) {
                    settings.url=data.getString("url");settings.token=data.getString("token")
                    getSharedPreferences("hermes_watch",MODE_PRIVATE).edit().putBoolean("remote_voice_available",data.optBoolean("remoteVoice",false)).apply()
                }
            }catch(_:Exception){}
            return
        }
        if(event.path!="/hermes-wear/response" || event.data.size>90000) return
        val value=String(event.data,Charsets.UTF_8)
        if(WatchInbox.receive(applicationContext,value)) {
            val body=org.json.JSONObject(value)
            if(body.optString("state") in setOf("completed","approval")) WatchJobs.notify(applicationContext,body.optString("id"),body.optString("state")=="approval")
        }
    }
}
