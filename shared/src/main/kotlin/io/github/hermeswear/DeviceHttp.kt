package io.github.hermeswear

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Shared direct/fallback HTTPS protocol. Payload IDs survive every transport change. */
class DeviceHttp(context:Context) {
    private val settings=ConnectionSettings(context)
    fun call(op:String,body:JSONObject):JSONObject {
        if(!settings.configured()) return JSONObject().put("ok",false).put("retryable",true).put("error","Pair Hermes Bridge and sync the watch connection")
        val route=when(op){"ask","status","approve","cancel","events","auth","trace"->"/watch/$op";else->throw IllegalArgumentException("Unsupported operation")}
        return request(settings.url+route,body,settings.token)
    }
    fun request(endpoint:String,body:JSONObject,credential:String=""):JSONObject {
        val c=URL(endpoint).openConnection() as HttpURLConnection
        return try {
            c.requestMethod="POST"; c.instanceFollowRedirects=false
            c.connectTimeout=4000;c.readTimeout=12000;c.doOutput=true
            c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Cache-Control","no-store")
            if(credential.isNotEmpty()) c.setRequestProperty("Authorization","Bearer $credential")
            c.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
            val code=c.responseCode
            val stream=if(code in 200..299)c.inputStream else c.errorStream
            val bytes=stream?.use{input ->
                val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(4096)
                while(true){val n=input.read(chunk);if(n<0)break;out.write(chunk,0,n);require(out.size()<=80000)}
                out.toByteArray()
            } ?: ByteArray(0)
            require(bytes.size<=80000){"Response too large"}
            JSONObject(String(bytes,Charsets.UTF_8)).put("retryable",code==429 || code>=500)
        }catch(_:Exception){JSONObject().put("ok",false).put("retryable",true).put("error","Network unavailable; request remains recoverable")}
        finally{c.disconnect()}
    }
}
