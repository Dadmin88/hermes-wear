package io.github.hermeswear

import android.content.Context
import org.json.JSONObject

/** The service is the only response writer; the Activity observes this durable inbox. */
object WatchInbox {
    @Synchronized fun receive(context: Context, value: String): Boolean {
        val obj = try { JSONObject(value) } catch (_: Exception) { return false }
        val prefs = context.getSharedPreferences("hermes_watch", Context.MODE_PRIVATE)
        val active = try { JSONObject(prefs.getString("pending_turn", "") ?: "") } catch (_:Exception){null}
        val current=active?.optString("id")==obj.optString("id")
        if(!current) return WatchHistory.result(context,obj)
        val request=active ?: return false
        val previous = try { JSONObject(prefs.getString("turn_response", "") ?: "") }
                       catch (_: Exception) { JSONObject() }
        val same = previous.optString("id") == request.optString("id")
        if (!TurnPolicy.accept(request.optString("id"), obj.optString("id"),
            if (same) previous.optLong("seq", -1) else -1, obj.optLong("seq", 0),
            if (same) previous.optString("state") else "")) {
            Trace.record(context, obj.optString("id"), "watch_response_ignored")
            return false
        }
        Trace.record(context, obj.optString("id"), "watch_response_received",
            JSONObject().put("seq", obj.optLong("seq")).put("state", obj.optString("state")))
        // commit before returning from the system-managed service.
        WatchHistory.result(context,obj)
        return prefs.edit().putString("turn_response", obj.toString()).commit()
    }
}
