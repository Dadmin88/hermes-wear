package io.github.hermeswear

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Strict preference-only protocol. Never accepts credentials, history or arbitrary keys. */
object WatchPreferences {
    val defaults=linkedMapOf<String,Any>("speak" to true,"wake_word" to false,"tts_engine" to "","tts_voice" to "","tts_speed" to .92f,"tts_pitch" to 1f,"nous_voice" to false,"nous_voice_style" to "onyx")
    fun values(c:Context):JSONObject {
        val p=c.getSharedPreferences("hermes_watch",0);val v=JSONObject()
        defaults.forEach {(k,d) -> v.put(k,when(d) {is Boolean -> p.getBoolean(k,d);is Float -> p.getFloat(k,d);else -> p.getString(k,d.toString())})}
        return v.put("theme",ThemeStore.selected(c).id)
    }
    fun revision(v:JSONObject):String=MessageDigest.getInstance("SHA-256").digest(v.toString().toByteArray()).joinToString(""){"%02x".format(it)}
    @Synchronized fun apply(c:Context,patch:JSONObject,expected:String) {
        require(expected==revision(values(c))) {"Settings changed on the watch. Refresh and try again."}
        require(patch.length() in 1..10) {"Empty or oversized settings change"}
        val p=c.getSharedPreferences("hermes_watch",0)
        for(k in patch.keys()) {
            val x=patch.get(k)
            when(k) {
                "speak","wake_word","nous_voice" -> {require(x is Boolean);if(k=="nous_voice" && x==true)require(p.getBoolean("remote_voice_available",false)){"Remote voice is unavailable"}}
                "tts_speed","tts_pitch" -> {require(x is Number);val n=x.toDouble();require(n.isFinite() && n in (if(k=="tts_speed") .649999..1.450001 else .749999..1.250001)) {"Invalid speech range"}}
                "tts_engine","tts_voice" -> {require(x is String && x.length<=256);if(x.isNotEmpty()) {
                    val caps=try {JSONObject(p.getString("settings_capabilities","{}")!!)}catch(_:Exception){JSONObject()}
                    val list=caps.optJSONArray(if(k=="tts_engine") "engines" else "voices")
                    require(list!=null && (0 until list.length()).any {list.getJSONObject(it).optString("id")==x}) {"Voice selection unavailable. Open Hermes on the watch and Refresh."}
                }}
                "nous_voice_style" -> require(x is String && x in setOf("alloy","echo","fable","onyx","nova","shimmer"))
                "theme" -> require(x is String && ThemeStore.choices(c).any {it.id==x}) {"Unknown watch theme"}
                else -> error("Unsupported setting")
            }
        }
        val edit=p.edit()
        for(k in patch.keys()) {val x=patch.get(k);when {
            k=="theme" -> ThemeStore.select(c,x as String)
            x is Boolean -> edit.putBoolean(k,x)
            k=="tts_speed" || k=="tts_pitch" -> edit.putFloat(k,(x as Number).toFloat())
            else -> edit.putString(k,x as String)
        }}
        if(patch.has("tts_engine") && !patch.has("tts_voice")) edit.putString("tts_voice","").remove("settings_capabilities")
        check(edit.putLong("settings_refresh",System.currentTimeMillis()).commit())
    }
}
