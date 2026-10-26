package io.github.hermeswear

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class RelayHttp(private val context: Context) {
    private val settings = PhoneSettings(context)

    fun call(op: String, payload: JSONObject): JSONObject {
        val route = when (op) {
            "ask" -> "/watch/ask"
            "status" -> "/watch/status"
            "cancel" -> "/watch/cancel"
            "approve" -> "/watch/approve"
            "trace" -> "/watch/trace"
            else -> return JSONObject().put("ok", false).put("error", "Unsupported request")
        }
        return request(route, "POST", payload)
    }

    /** Check both HTTPS connectivity and the saved bearer token without starting an agent run. */
    /** Pair directly from the phone using a one-time setup code.
     * The long relay credential is returned over private HTTPS and saved locally.
     * No browser clipboard or token transcription required.
     */
    fun pairWithCode(codeInput: String): JSONObject {
        val code = codeInput.trim().replace("-", "").uppercase(java.util.Locale.ROOT)
        if (!code.matches(Regex("[A-F0-9]{12}"))) {
            return JSONObject().put("ok", false).put("error", "Enter the 12-character pairing code")
        }
        val endpoint = settings.url
        val connection = (URL(endpoint + "/setup/redeem").openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 12000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Origin", endpoint)
            connection.setRequestProperty("Cache-Control", "no-store")
            val bytes = JSONObject().put("code", code).toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { it.write(bytes) }
            val http = connection.responseCode
            val stream = if (http in 200..299) connection.inputStream else connection.errorStream
            val result = stream?.bufferedReader()?.use { JSONObject(it.readText().take(4096)) }
                ?: JSONObject()
            if (http !in 200..299 || !result.optBoolean("ok")) {
                JSONObject().put("ok", false).put("error",
                    result.optString("error", "Pairing failed (HTTP $http)"))
            } else {
                val receivedUrl = result.optString("url")
                val receivedToken = result.optString("token")
                if (!settings.validUrl(receivedUrl) || receivedToken.length < 32) {
                    JSONObject().put("ok", false).put("error", "Unexpected pairing response")
                } else {
                    settings.url = receivedUrl
                    settings.token = receivedToken
                    val check = test()
                    if (check.optBoolean("ok")) {
                        JSONObject().put("ok", true).put("message", "Authenticated. Watch is ready.")
                    } else {
                        JSONObject().put("ok", false).put("error",
                            "Paired, but verification failed: " + check.optString("error"))
                    }
                }
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error",
                "Pairing network error: " + (e.message ?: e.javaClass.simpleName).take(150))
        } finally {
            connection.disconnect()
        }
    }

    /** Authenticated Nous speech over the relay host; only synthesized MP3 bytes return. */
    fun synthesize(body: JSONObject): ByteArray {
        if (!settings.validUrl(settings.url) || settings.token.length < 24)
            throw java.io.IOException("Relay is not configured")
        val url = URL(settings.url + "/watch/tts")
        val connection = (url.openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
        connection.connectTimeout = 12000
        connection.readTimeout = 85000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "audio/mpeg")
        connection.setRequestProperty("Authorization", "Bearer " + settings.token)
        connection.setRequestProperty("Cache-Control", "no-store")
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            if (connection.responseCode != 200) {
                val msg = connection.errorStream?.bufferedReader()?.use {
                    JSONObject(it.readText().take(1024)).optString("error")
                } ?: "Speech request failed"
                throw java.io.IOException(msg.take(160))
            }
            if (!connection.contentType.orEmpty().contains("audio/mpeg"))
                throw java.io.IOException("Unexpected speech format")
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            connection.inputStream.use { stream ->
                while (true) {
                    val got = stream.read(buffer)
                    if (got < 0) break
                    result.write(buffer, 0, got)
                    if (result.size() > 550000) throw java.io.IOException("Audio exceeds watch transfer limit")
                }
            }
            if (result.size() < 100) throw java.io.IOException("Empty speech audio")
            result.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    fun test(): JSONObject {
        val response = request("/watch/auth", "POST", JSONObject())
        if (response.optBoolean("ok")) {
            return JSONObject().put("ok", true).put("detail", "Authenticated relay connection")
        }
        if (response.optString("error") == "Unauthorized") {
            return JSONObject().put("ok", false)
                .put("error", "Access token rejected. Copy the full token again, then Save connection.")
        }
        return response
    }

    private fun request(path: String, method: String, payload: JSONObject?): JSONObject {
        if (!settings.validUrl(settings.url)) {
            return JSONObject().put("ok", false).put("error", "Configure an HTTPS relay address")
        }
        if (method == "POST" && settings.token.length < 24) {
            return JSONObject().put("ok", false).put("error", "Save your relay access token on the phone")
        }
        val id=payload?.optString("id") ?: ""
        val traceRequest=path!="/watch/trace"
        val began=android.os.SystemClock.elapsedRealtime()
        if(traceRequest) Trace.record(context,id,"phone_https_started")
        val connection = (URL(settings.url + path).openConnection() as HttpURLConnection)
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = if(traceRequest) 10000 else 1500
        connection.readTimeout = if(traceRequest) 12000 else 1500
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-store")
        if (payload != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + settings.token)
        }
        return try {
            if (payload != null) {
                connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val message = stream?.bufferedReader()?.use { it.readText().take(80000) } ?: "{}"
            JSONObject(message).also {
                if (!it.has("ok")) it.put("ok", status in 200..299)
                it.put("retryable",status==429 || status>=500)
                if(traceRequest) Trace.record(context,id,"phone_https_received",
                    JSONObject().put("http",status).put("durationMs",android.os.SystemClock.elapsedRealtime()-began))
            }
        } catch (e: Exception) {
            if(traceRequest) Trace.record(context,id,"phone_https_failed",
                JSONObject().put("durationMs",android.os.SystemClock.elapsedRealtime()-began))
            JSONObject().put("ok", false).put("retryable",true)
                .put("error","Relay network unavailable ("+e.javaClass.simpleName+"). Retry recovers this same request.")
        } finally {
            connection.disconnect()
        }
    }
}
