package io.github.hermeswear

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Private MP3 assembly and playback from the paired phone, never the public internet. */
class NousAudioPlayer(private val context: Context) {
    var onStatus: ((String) -> Unit)? = null
    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackFailed: ((String) -> Unit)? = null
    private var requestId: String = ""
    private var pieces: Array<ByteArray?>? = null
    private var received = 0
    private var media: MediaPlayer? = null
    private var audioFile: File? = null

    fun begin(id: String) {
        cancelCurrent(clearPlayback = true)
        requestId = id
        received = 0
    }

    /** Returns true for a packet associated with the current request. */
    fun accept(packet: JSONObject): Boolean {
        val id = packet.optString("id")
        if (id.isBlank() || id != requestId) return false
        val index = packet.optInt("index", -1)
        val count = packet.optInt("count", -1)
        if (count !in 1..40 || index !in 0 until count) {
            fail("Invalid audio packet")
            return true
        }
        if (pieces == null) pieces = arrayOfNulls(count)
        val buffers = pieces ?: return true
        if (buffers.size != count) {
            fail("Audio packet count changed")
            return true
        }
        val part = try { Base64.decode(packet.getString("data"), Base64.DEFAULT) }
                   catch (_: Exception) {
                       fail("Damaged voice packet")
                       return true
                   }
        if (part.isEmpty() || part.size > 16384) {
            fail("Invalid voice packet size")
            return true
        }
        if (buffers[index] == null) {
            buffers[index] = part
            received++
        }
        onStatus?.invoke("Receiving voice: $received/$count")
        if (received == count) {
            val out = ByteArrayOutputStream()
            for (piece in buffers) {
                if (piece == null) return true
                out.write(piece)
                if (out.size() > 550000) {
                    fail("Voice is too large")
                    return true
                }
            }
            requestId = ""
            pieces = null
            play(out.toByteArray())
        }
        return true
    }

    private fun play(audio: ByteArray) {
        try {
            val file = File.createTempFile("hermes-nous-", ".mp3", context.cacheDir)
            file.writeBytes(audio)
            audioFile = file
            val player = MediaPlayer()
            media = player
            player.setAudioAttributes(AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA).build())
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                try {
                    it.start()
                    onPlaybackStarted?.invoke()
                    onStatus?.invoke("Playing Nous voice…")
                } catch (_: Exception) { fail("Watch cannot play voice") }
            }
            player.setOnCompletionListener {
                onStatus?.invoke("Voice played")
                releasePlayer()
            }
            player.setOnErrorListener { _, _, _ ->
                fail("Audio playback failed")
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            fail("Cannot prepare speech playback")
        }
    }

    private fun fail(message: String) {
        cancelCurrent(clearPlayback = true)
        onPlaybackFailed?.invoke(message)
    }

    private fun releasePlayer() {
        try { media?.release() } catch (_: Exception) { }
        media = null
        audioFile?.delete()
        audioFile = null
    }

    private fun cancelCurrent(clearPlayback: Boolean) {
        requestId = ""
        pieces = null
        received = 0
        if (clearPlayback) releasePlayer()
    }

    fun cancel() = cancelCurrent(clearPlayback = true)
    fun close() = cancel()
}
