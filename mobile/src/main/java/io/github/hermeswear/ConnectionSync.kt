package io.github.hermeswear
import android.content.Context
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
object ConnectionSync {
    fun send(c:Context,peer:String) {
        val settings=PhoneSettings(c)
        if(!settings.validUrl(settings.url) || settings.token.length<24)return
        // Data Layer authenticates the paired devices and matching APK signing identity.
        val body=JSONObject().put("url",settings.url).put("token",settings.token).put("remoteVoice",false)
        Wearable.getMessageClient(c).sendMessage(peer,"/hermes-wear/config",body.toString().toByteArray())
    }
    fun all(c:Context) {Wearable.getNodeClient(c).connectedNodes.addOnSuccessListener{nodes->nodes.forEach{send(c,it.id)}}}
}
