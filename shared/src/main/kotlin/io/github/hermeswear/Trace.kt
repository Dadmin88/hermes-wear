package io.github.hermeswear

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Metadata only: never log prompts, answers, endpoints, headers or credentials. */
object Trace {
    @Synchronized fun record(context: Context, id: String, stage: String, fields: JSONObject = JSONObject()) {
        val e = JSONObject().put("id", id.take(100)).put("stage", stage.take(64))
            .put("utcMs", System.currentTimeMillis()).put("monoMs", SystemClock.elapsedRealtime())
        for (key in listOf("attempt", "durationMs", "seq", "state", "nearby", "http", "count")) {
            if (fields.has(key)) e.put(key, fields.get(key))
        }
        val file = File(context.filesDir, "wear-trace.ndjson")
        if (file.length() > 512_000) {
            File(context.filesDir, "wear-trace.previous.ndjson").delete()
            file.renameTo(File(context.filesDir, "wear-trace.previous.ndjson"))
        }
        file.appendText(e.toString() + "\n")
        Log.i("HermesWearTrace", e.toString())
    }
    @Synchronized fun events(context: Context, id: String): JSONArray {
        val f = File(context.filesDir, "wear-trace.ndjson")
        val matching = if (f.exists()) f.readLines().mapNotNull {
            try { JSONObject(it) } catch (_: Exception) { null }
        }.filter { it.optString("id") == id } else emptyList()
        // Keep the start of slow requests as well as their terminal events.
        val events=if(matching.size<=40) matching else matching.take(10)+matching.takeLast(30)
        return JSONArray().also { a -> events.forEach { a.put(it) } }
    }
}
