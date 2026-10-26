package io.github.hermeswear

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * Offline-first watch voice preferences. Voices are supplied by the installed
 * Android TTS engine; available choices depend on the Galaxy Watch6.
 */
class VoiceSettings(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("hermes_watch", Context.MODE_PRIVATE)
    private var tts: TextToSpeech? = null
    private var ready = false
    var available: Boolean = false; private set
    private val audio=activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var remaining=0
    private var speechGeneration=0
    private var started=false
    private val speechHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }.build()
    val isPlaying get()=remaining>0 || pendingSpeech!=null
    private var pendingSpeech: Pair<String, String>? = null
    private val envelopes=java.util.concurrent.ConcurrentHashMap<String,SpeechEnvelope>()
    private val utterances=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var playbackId:String?=null
    private var playbackStart=0L
    private var visualizationActive=true
    var onWaveformLevel:((Float?)->Unit)?=null
    var onSpeakingChanged:((Boolean)->Unit)?=null
    private val envelopeTick=object:Runnable {
        override fun run() {
            val id=playbackId ?: return
            if(!visualizationActive || !utterances.contains(id)) return
            onWaveformLevel?.invoke(envelopes[id]?.at(android.os.SystemClock.elapsedRealtime()-playbackStart))
            speechHandler.postDelayed(this,50)
        }
    }
    fun setVisualizationActive(value:Boolean) {
        visualizationActive=value;speechHandler.removeCallbacks(envelopeTick)
        if(value && playbackId!=null) speechHandler.post(envelopeTick)
    }
    var onPlaybackStatus: ((String) -> Unit)? = null
    private fun status(message: String) {
        activity.runOnUiThread { onPlaybackStatus?.invoke(message) }
    }
    private var speed: Float = prefs.getFloat("tts_speed", 0.92f)
    private var pitch: Float = prefs.getFloat("tts_pitch", 1.0f)
    private var voiceName: String = prefs.getString("tts_voice", "") ?: ""

    init {
        tts = TextToSpeech(activity, { code ->
            activity.runOnUiThread {
                ready = code == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    tts?.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
                        override fun onBeginSynthesis(utteranceId:String?,sampleRateInHz:Int,audioFormat:Int,channelCount:Int) {
                            if(utteranceId!=null && utterances.contains(utteranceId)) envelopes[utteranceId]=SpeechEnvelope(sampleRateInHz,audioFormat,channelCount)
                        }
                        override fun onAudioAvailable(utteranceId:String?,audio:ByteArray?) {
                            if(utteranceId!=null && audio!=null && utterances.contains(utteranceId)) envelopes[utteranceId]?.append(audio)
                        }
                        override fun onStart(utteranceId: String?) { activity.runOnUiThread {
                            if(utteranceId==null || !utterances.contains(utteranceId)) return@runOnUiThread
                            onSpeakingChanged?.invoke(true)
                            started=true;playbackId=utteranceId;playbackStart=android.os.SystemClock.elapsedRealtime()
                            speechHandler.removeCallbacks(envelopeTick)
                            if(visualizationActive) speechHandler.post(envelopeTick)
                            status("Speaking…")
                            Trace.record(activity,utteranceId.substringBefore(":"),"watch_tts_started")
                        } }
                        override fun onStop(utteranceId:String?,interrupted:Boolean) {activity.runOnUiThread {
                            if(utteranceId!=null && utterances.contains(utteranceId)) {stop();status("Speech stopped")}
                        }}
                        override fun onDone(utteranceId: String?) { activity.runOnUiThread {
                            if(utteranceId==null || !utterances.remove(utteranceId)) return@runOnUiThread
                            envelopes.remove(utteranceId)
                            if(playbackId==utteranceId) {playbackId=null;speechHandler.removeCallbacks(envelopeTick);onWaveformLevel?.invoke(null)}
                            remaining=(remaining-1).coerceAtLeast(0)
                            if(remaining==0) { onSpeakingChanged?.invoke(false);audio.abandonAudioFocusRequest(focus);status("Speech finished") }
                        } }
                        override fun onError(utteranceId: String?) { activity.runOnUiThread {if(utteranceId!=null && utterances.contains(utteranceId)) {stop();status("Speech playback failed")}} }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            activity.runOnUiThread {if(utteranceId!=null && utterances.contains(utteranceId)) {stop();status("Speech error $errorCode")}}
                        }
                    })
                    applySettings()
                    refreshCapabilities()
                    status(if(available) "Watch voice ready" else "Voice assets unavailable; choose or install a watch voice")
                    pendingSpeech?.let { (message, id) -> pendingSpeech = null; speak(message, id) }
                } else {
                    status("Speech engine failed to initialize")
                }
            }
        },prefs.getString("tts_engine",null))
    }

    fun refreshCapabilities() {
        if(!ready)return
        val caps=org.json.JSONObject()
        caps.put("engines",org.json.JSONArray().apply {
            tts?.engines.orEmpty().take(16).forEach {put(org.json.JSONObject().put("id",it.name.take(256)).put("label",it.label.take(160)))}
        })
        caps.put("voices",org.json.JSONArray().apply {
            availableVoices().take(80).forEach {put(org.json.JSONObject().put("id",it.name.take(256)).put("label",(it.locale.displayName+" · "+it.name).take(160)))}
        })
        prefs.edit().putString("settings_capabilities",caps.toString()).apply()
    }

    private fun availableVoices(): List<Voice> {
        if (!ready) return emptyList()
        return try {
            val installed = tts?.voices.orEmpty().filter {
                it.locale.language == Locale.ENGLISH.language &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            installed.sortedWith(compareBy<Voice>(
                { if (it.isNetworkConnectionRequired) 1 else 0 },
                { it.locale.toLanguageTag() },
                { it.name }
            ))
        } catch (_: Exception) { emptyList() }
    }

    private fun applySettings() {
        val engine = tts ?: return
        available=false
        engine.setSpeechRate(speed)
        engine.setPitch(pitch)
        if (voiceName.isNotBlank()) {
            val match = availableVoices().firstOrNull { it.name == voiceName }
            if (match != null) {
                engine.setVoice(match)
            } else {
                // Device or engine changed: fall back to its default voice.
                voiceName = ""
                prefs.edit().remove("tts_voice").apply()
                engine.language = Locale.ENGLISH
            }
        } else {
            engine.language = Locale.ENGLISH
            availableVoices().firstOrNull { !it.isNetworkConnectionRequired }?.let {engine.voice=it}
        }
        val voice=engine.voice
        available=voice!=null && !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) && engine.isLanguageAvailable(voice.locale)>=TextToSpeech.LANG_AVAILABLE
    }

    fun speak(message: String, id: String) {
        if (!ready) {
            pendingSpeech = message to id
            status("Waiting for speech engine…")
            return
        }
        applySettings()
        if(!available) {status("Voice assets unavailable; open Choose voice");return}
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            status("Media volume is muted. Increase watch media volume.")
            return
        }
        stop()
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {status("Speaker busy; try Read last reply");return}
        val chunks=message.chunked(minOf(3000,TextToSpeech.getMaxSpeechInputLength()-1))
        remaining=chunks.size
        val generation=++speechGeneration
        started=false
        speechHandler.postDelayed({
            if(generation==speechGeneration && !started && remaining>0) {stop();status("Speech did not start; test speaker or choose another voice")}
        },5000)
        Trace.record(activity,id,"watch_tts_queued")
        for(i in chunks.indices) utterances.add("$id@$generation:$i")
        for((i,chunk) in chunks.withIndex()) {
            if(tts?.speak(chunk,TextToSpeech.QUEUE_ADD,null,"$id@$generation:$i")!=TextToSpeech.SUCCESS) {
                stop();status("Could not start speech playback");return
            }
        }
    }

    fun stop() { onSpeakingChanged?.invoke(false);utterances.clear();envelopes.clear();playbackId=null;speechHandler.removeCallbacks(envelopeTick);onWaveformLevel?.invoke(null); speechGeneration++;pendingSpeech = null; remaining=0;tts?.stop();audio.abandonAudioFocusRequest(focus) }
    fun close() { stop(); tts?.shutdown(); tts = null; ready = false; available=false }

    private val skin by lazy {ThemeTokens(activity)}
    private fun text(value: String, size: Float = 15f) = TextView(activity).apply {
        text = value
        setTextColor(skin.textPrimary)
        textSize = size
        setPadding(12, 12, 12, 12)
    }

    private fun chooseEngine() {
        val engines=tts?.engines.orEmpty()
        if(engines.isEmpty()) {status("No speech engines installed on this watch");return}
        AlertDialog.Builder(activity).setTitle("Speech engine")
            .setItems(engines.map{it.label}.toTypedArray()) { _,i ->
                prefs.edit().putString("tts_engine",engines[i].name).commit()
                stop();activity.recreate()
            }.setNegativeButton("Cancel",null).show()
    }
    fun showSettings() {
        if (!ready) {
            AlertDialog.Builder(activity)
                .setTitle("Voice not ready")
                .setMessage("The watch's speech engine is still loading or is unavailable. Try again shortly.")
                .setPositiveButton("Choose engine") { _,_ -> chooseEngine() }
                .setNegativeButton("Close",null)
                .show()
            return
        }
        val allVoices = availableVoices()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(skin.surface)
            setPadding(24, 8, 24, 12)
        }
        container.addView(Button(activity).apply {
            skin.styleButton(this,skin.surfaceRaised)
            text="Choose speech engine";isAllCaps=false;setOnClickListener {chooseEngine()}
        })
        container.addView(Button(activity).apply {
            skin.styleButton(this,skin.surfaceRaised)
            text="Install voice assets";isAllCaps=false
            setOnClickListener {
                try {activity.startActivity(android.content.Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                    prefs.getString("tts_engine",null)?.let {setPackage(it)}
                })}catch(_:Exception){status("Install speech assets in the watch's text-to-speech settings")}
            }
        })
        val currentLabel = text("", 13f)
        container.addView(currentLabel)
        val pick = Button(activity).apply {
            skin.styleButton(this,skin.surfaceRaised); text = "Choose voice"; isAllCaps = false }
        container.addView(pick)
        val speedText = text("Speed: ${"%.2f".format(Locale.US,speed)}×", 13f)
        container.addView(speedText)
        val speedControl = SeekBar(activity).apply {
            max = 100
            progress = ((speed - 0.65f) / 0.80f * 100f).toInt().coerceIn(0,100)
        }
        container.addView(speedControl)
        val pitchText = text("Pitch: ${"%.2f".format(Locale.US,pitch)}×",13f)
        container.addView(pitchText)
        val pitchControl = SeekBar(activity).apply {
            max = 100
            progress = ((pitch - 0.75f) / 0.50f * 100f).toInt().coerceIn(0,100)
        }
        container.addView(pitchControl)
        val preview = Button(activity).apply {
            skin.styleButton(this,skin.surfaceRaised); text = "▶ Preview"; isAllCaps = false }
        container.addView(preview)
        fun update() {
            currentLabel.text = if (voiceName.isBlank()) {
                "Voice: Watch default"
            } else {
                val v = allVoices.firstOrNull { it.name == voiceName }
                "Voice: ${v?.locale?.displayName ?: "Unavailable"} · ${v?.name ?: voiceName}"
            }
            speedText.text = "Speed: ${"%.2f".format(Locale.US,speed)}×"
            pitchText.text = "Pitch: ${"%.2f".format(Locale.US,pitch)}×"
            applySettings()
        }
        speedControl.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, byUser: Boolean) {
                if (byUser) { speed = 0.65f + (value / 100f) * 0.80f; update() }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        pitchControl.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, byUser: Boolean) {
                if (byUser) { pitch = 0.75f + (value / 100f) * 0.50f; update() }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        pick.setOnClickListener {
            val options = listOf("Watch default") + allVoices.map {
                "${it.locale.toLanguageTag()} · ${it.name}" +
                    if (it.isNetworkConnectionRequired) " (network)" else " (offline)"
            }
            AlertDialog.Builder(activity)
                .setTitle("Available voices")
                .setItems(options.toTypedArray()) { _, index ->
                    voiceName = if (index == 0) "" else allVoices[index-1].name
                    update()
                    speak("Hello! I'm Hermes. How does this voice sound?", "voice-preview")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        preview.setOnClickListener {
            speak("Hello! I'm Hermes, speaking from your watch.", "voice-preview")
        }
        update()
        AlertDialog.Builder(activity)
            .setTitle("Hermes voice")
            .setView(android.widget.ScrollView(activity).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putFloat("tts_speed",speed)
                    .putFloat("tts_pitch",pitch)
                    .putString("tts_voice",voiceName).apply()
            }
            .setNegativeButton("Cancel") { _, _ ->
                speed = prefs.getFloat("tts_speed", 0.92f)
                pitch = prefs.getFloat("tts_pitch", 1.0f)
                voiceName = prefs.getString("tts_voice", "") ?: ""
                stop()
                applySettings()
            }
            .show()
    }
}
