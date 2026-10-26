package io.github.hermeswear

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.*

/** Best-effort telemetry has its own bounded executor and durable spool.
 * It can never occupy a chat worker or delay displaying a reply. */
object Diagnostics {
    private val executor=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1),ThreadPoolExecutor.DiscardOldestPolicy())
    @Synchronized fun enqueue(context:Context, packet:JSONObject) {
        val id=packet.optString("id")
        if(!id.matches(Regex("[a-zA-Z0-9_-]{8,100}"))) return
        val source=packet.optString("source")
        if(source !in setOf("watch","phone")) return
        val dir=File(context.filesDir,"trace-spool").apply { mkdirs() }
        File(dir,source+"-"+id+".json").writeText(packet.toString())
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(64)?.forEach { it.delete() }
        flush(context.applicationContext)
    }
    fun flush(context:Context) {
        executor.execute {
            val dir=File(context.filesDir,"trace-spool")
            for(file in dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()) {
                val content=synchronized(this) { try { file.readText() } catch (_:Exception) { null } } ?: continue
                val packet=try { JSONObject(content) } catch (_:Exception) { file.delete(); continue }
                val result=RelayHttp(context).call("trace",packet)
                if(!result.optBoolean("ok")) break
                synchronized(this) {
                    // A newer snapshot may have replaced this file during the upload.
                    if(file.exists() && file.readText()==content) file.delete()
                }
            }
        }
    }
}
