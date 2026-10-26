package io.github.hermeswear
import android.content.Context
import org.json.JSONObject

/** Retain recent task identities and results independently of the currently open chat. */
object WatchHistory {
    private fun prefs(c:Context)=c.getSharedPreferences("hermes_history",Context.MODE_PRIVATE)
    @Synchronized fun get(c:Context,id:String):JSONObject?=try{JSONObject(prefs(c).getString(id,"") ?: "")}catch(_:Exception){null}
    @Synchronized fun all(c:Context)=prefs(c).all.mapNotNull{(_,v)->try{JSONObject(v.toString())}catch(_:Exception){null}}
        .sortedByDescending{it.optLong("at")}
    @Synchronized fun saveRequest(c:Context,body:JSONObject) {
        val id=body.optString("id");val record=get(c,id) ?: JSONObject().put("at",System.currentTimeMillis())
        record.put("request",JSONObject(body.toString()))
        val edit=prefs(c).edit().putString(id,record.toString())
        all(c).filter{it.optJSONObject("result")?.optString("state") in TurnPolicy.terminal}
            .drop(31).forEach{edit.remove(it.getJSONObject("request").optString("id"))}
        check(edit.commit())
    }
    @Synchronized fun result(c:Context,body:JSONObject):Boolean {
        val record=get(c,body.optString("id")) ?: return false
        val previous=record.optJSONObject("result")
        if(previous?.optString("state") in setOf("completed","cancelled","interrupted"))return false
        if(previous!=null && previous.optLong("seq",-1)>=body.optLong("seq",-1))return false
        record.put("result",body)
        return prefs(c).edit().putString(body.optString("id"),record.toString()).commit()
    }
}
