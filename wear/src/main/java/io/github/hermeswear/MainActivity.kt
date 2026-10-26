package io.github.hermeswear

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("hermes_watch", MODE_PRIVATE) }
    private lateinit var stateText: TextView
    private lateinit var answerText: TextView
    private lateinit var promptText: TextView
    private lateinit var approveText: TextView
    private lateinit var approvalPanel: LinearLayout
    private lateinit var entry: EditText
    private lateinit var wakeWord:WakeWordListener
    private lateinit var wakeButton:android.widget.Switch
    private lateinit var wakeStatus:TextView
    private var playbackBusy=false
    private var wakeEnabled=false
    private val wakeTick=object:Runnable {
        override fun run() {
            if(!active || !wakeEnabled || !this@MainActivity::wakeWord.isInitialized) return
            if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {wakeWord.stop();return}
            wakeStatus.text=if(wakeWord.active) "Listening for Hey Hermes" else "Paused · close Settings to listen"
            val eligible=hasWindowFocus() && !controls.isShowing && pending==null && !inAppSpeech.busy && !voiceSettings.isPlaying && !cloudEnabled
            if(eligible) wakeWord.start() else wakeWord.stop()
            face.wakeReady=wakeWord.active
            handler.postDelayed(this,1000)
        }
    }
    private lateinit var inAppSpeech:InAppSpeech
    private lateinit var face: HermesFaceView
    private lateinit var controls: android.app.Dialog
    private lateinit var voiceToggle: Button
    private var sessionId = ""
    private var runId = ""
    private var promptId = ""
    private var generation = -1
    private var pending: JSONObject? = null
    private var retries = 0
    private var active = false
    private var speaking = true
    private lateinit var voiceSettings: VoiceSettings
    private lateinit var cloudPlayer: NousAudioPlayer
    private var cloudEnabled: Boolean = false
    private var cloudVoice: String = "onyx"
    private var cloudRequestId: String = ""
    private var cloudFallbackText: String = ""
    private var lastSequence=-1L
    private var accepted=false
    private var recovered=false
    private val inboxListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key=="turn_response") handler.post { prefs.getString("turn_response",null)?.let { handle(it) } }
    }
    private val watchdog=object:Runnable {
        override fun run() {
            val turn=pending
            if(turn!=null && active) {
                val age=System.currentTimeMillis()-turn.optLong("createdMs")
                val state=try { JSONObject(prefs.getString("turn_response","") ?: "").optString("state") }
                          catch (_:Exception) { "" }
                val decision=try { JSONObject(prefs.getString("pending_decision","") ?: "") } catch (_:Exception) { null }
                if(decision!=null && System.currentTimeMillis()-decision.optLong("createdMs")>=15000) {
                    prefs.edit().remove("pending_decision").apply()
                    stateText.text="Decision not acknowledged. Tap Approve or Deny again."
                    approvalPanel.visibility=View.VISIBLE
                }
                if(state!="approval" && TurnPolicy.expired(turn.optLong("createdMs"),System.currentTimeMillis(),accepted)) {
                    finishError(if(accepted) "Request exceeded 3 minutes. Tap Retry to recover it."
                        else "Phone did not acknowledge within 15 seconds. Open Hermes Bridge, then Retry.")
                } else if(!accepted && ((age>=3000 && retries<2) || (age>=8000 && retries<3))) transmit()
                else if(accepted && age>=20000 && !recovered && state!="approval") {
                    recovered=true
                    transmit() // one bounded completion-recovery message, not run polling
                }
            }
            if(active) handler.postDelayed(this,1000)
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float = 14f, tint: Int = Color.WHITE) =
        TextView(this).apply { text = s; textSize = size; setTextColor(tint); setPadding(0, dp(6), 0, dp(6)) }
    private fun button(s: String, color: Int = Color.rgb(186,255,41)) =
        Button(this).apply {
            text = s; isAllCaps = false; textSize = 14f
            setTextColor(if(color==Color.rgb(186,255,41)) Color.rgb(22,7,43) else Color.rgb(240,229,255))
            backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = prefs.getString("session", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("session", it).apply()
        }
        runId = prefs.getString("run", "") ?: ""
        // Migrate an in-flight v0.2 run into phone ownership without creating a new run.
        if(runId.isNotBlank() && !prefs.contains("pending_turn")) {
            val migration=JSONObject().put("id",UUID.randomUUID().toString()).put("op","chat")
                .put("sessionId",sessionId).put("runId",runId).put("createdMs",System.currentTimeMillis())
            prefs.edit().putString("pending_turn",migration.toString()).putString("retry_turn",migration.toString()).commit()
        }
        speaking = prefs.getBoolean("speak", true)
        cloudEnabled = prefs.getBoolean("nous_voice", false) && prefs.getBoolean("remote_voice_available",false)
        cloudVoice = prefs.getString("nous_voice_style", "onyx") ?: "onyx"
        voiceSettings = VoiceSettings(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
            setBackgroundColor(Color.rgb(16,6,32))
        }
        layout.addView(label("✦ HERMES", 21f, Color.rgb(186,255,41)).apply {
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        stateText = label("Ready to talk", 12f, Color.rgb(182,160,214)).apply { gravity = android.view.Gravity.CENTER }
        layout.addView(label(packageManager.getPackageInfo(packageName, 0).versionName ?: "", 10f, Color.rgb(182,160,214)).apply {
            gravity = android.view.Gravity.CENTER
        })
        voiceSettings.onWaveformLevel = { value -> if(this::face.isInitialized && (!this::inAppSpeech.isInitialized || !inAppSpeech.busy)) face.audioLevel=value }
        voiceSettings.onPlaybackStatus = { msg ->
            playbackBusy=msg.startsWith("Speaking") || msg.startsWith("Waiting for speech")
            if(pending==null && (!this::inAppSpeech.isInitialized || !inAppSpeech.busy)) stateText.text = msg
            if(this::voiceToggle.isInitialized) updateVoice()
        }
        cloudPlayer = NousAudioPlayer(this).apply {
            onStatus = { if(pending==null) stateText.text = it }
            onPlaybackStarted = {
                cloudRequestId = ""
                cloudFallbackText = ""
            }
            onPlaybackFailed = { fallbackFromNous(it) }
        }
        layout.addView(button("System dictation (separate screen)").apply {setOnClickListener {inAppSpeech.cancel();launchSystemVoice()}})
        wakeEnabled=prefs.getBoolean("wake_word",false)
        wakeStatus=label(if(wakeEnabled) "Close Settings to listen · app must stay open" else "Wake word off",12f,Color.rgb(182,160,214))
        wakeButton=android.widget.Switch(this).apply {
            text="Hey Hermes wake word";textOn="ON";textOff="OFF";showText=true
            isChecked=wakeEnabled
            setTextColor(Color.rgb(240,229,255));setPadding(12,16,12,16)
            thumbTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Color.rgb(186,255,41),Color.rgb(182,160,214)))
            trackTintList=android.content.res.ColorStateList.valueOf(Color.rgb(74,35,125))
            setOnClickListener {
                val toggle=this
                if(wakeEnabled) {
                    wakeEnabled=false;prefs.edit().putBoolean("wake_word",false).apply();wakeWord.stop();handler.removeCallbacks(wakeTick)
                    toggle.isChecked=false;face.wakeReady=false;wakeStatus.text="Wake word off";stateText.text="Wake word off"
                } else android.app.AlertDialog.Builder(this@MainActivity).setTitle("Enable Hey Hermes?")
                    .setMessage("Downloads a free 40 MB offline model once. Listens locally only while Hermes is open and the screen is active. Stops for dictation, replies and menus. Uses extra battery. It does not wake a closed app.")
                    .setPositiveButton("Enable") {_,_ ->
                        wakeWord.retry();wakeEnabled=true;prefs.edit().putBoolean("wake_word",true).apply();toggle.isChecked=true;wakeStatus.text="Close Settings to listen · app must stay open"
                        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO),43)
                        else {handler.removeCallbacks(wakeTick);handler.post(wakeTick)}
                    }.setNegativeButton("Cancel") {_,_ -> toggle.isChecked=false}
                    .setOnCancelListener {toggle.isChecked=false}.show()
            }
        }
        layout.addView(wakeButton)
        layout.addView(wakeStatus)
        layout.addView(stateText)
        promptText = label(prefs.getString("last_input", "") ?: "", 12f, Color.rgb(182,160,214))
        layout.addView(promptText)
        layout.addView(button("🎙 Talk").apply { setOnClickListener { launchVoice() } })
        entry = EditText(this).apply {
            hint = "Type message"
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(182,160,214))
            textSize = 14f; maxLines = 3
        }
        layout.addView(entry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(63)))
        layout.addView(button("Send text", Color.rgb(113,66,178)).apply {
            setOnClickListener { ask(entry.text.toString()) }
        })
        approvalPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
        }
        approveText = label("", 13f, Color.rgb(186,255,41))
        approvalPanel.addView(approveText)
        approvalPanel.addView(button("Approve once").apply { setOnClickListener { decide("once") } })
        approvalPanel.addView(button("Deny", Color.rgb(75,31,139)).apply {
            setOnClickListener { decide("deny") }
        })
        layout.addView(approvalPanel)
        answerText = label(prefs.getString("last_text", "Ask Hermes something.") ?: "", 14f)
        layout.addView(answerText)
        voiceToggle = button("", Color.rgb(113,66,178))
        updateVoice()
        voiceToggle.setOnClickListener {
            speaking = !speaking
            prefs.edit().putBoolean("speak", speaking).apply()
            updateVoice()
            if (!speaking) {
                voiceSettings.stop();playbackBusy=false
                if(pending==null) stateText.text="Voice off"
                cloudRequestId = ""
                cloudFallbackText = ""
                cloudPlayer.cancel()
            }
        }
        layout.addView(voiceToggle)
        val cloudToggle = button("", Color.rgb(113,66,178))
        fun refreshCloudLabel() {
            cloudToggle.text = if (cloudEnabled) "☁ Nous voice: ON" else "☁ Nous voice: OFF"
        }
        refreshCloudLabel()
        cloudToggle.setOnClickListener {
            if(!prefs.getBoolean("remote_voice_available",false)) {stateText.text="This relay uses watch voice; remote voice is unavailable";return@setOnClickListener}
            cloudEnabled = !cloudEnabled
            prefs.edit().putBoolean("nous_voice", cloudEnabled).apply()
            refreshCloudLabel()
            if (!cloudEnabled && cloudRequestId.isNotEmpty()) {
                fallbackFromNous("Cloud voice disabled")
            }
        }
        layout.addView(cloudToggle)
        layout.addView(button("☁ Choose Nous voice", Color.rgb(113,66,178)).apply {
            setOnClickListener { chooseNousVoice() }
        })
        layout.addView(button("▶ Preview Nous voice", Color.rgb(113,66,178)).apply {
            setOnClickListener { requestCloudVoice("Hello, I'm Hermes. This voice comes from Nous Portal.") }
        })
        layout.addView(button("▶ Test speaker", Color.rgb(113,66,178)).apply {
            setOnClickListener {
                voiceSettings.speak("Hello, this is Hermes. You should hear me through your watch speaker.", "speaker-test")
            }
        })
        layout.addView(button("↻ Read last reply", Color.rgb(113,66,178)).apply {
            setOnClickListener {
                val previous = prefs.getString("last_text", "") ?: ""
                if (previous.isBlank()) stateText.text = "No reply to read yet"
                else if (cloudEnabled) requestCloudVoice(previous)
                else voiceSettings.speak(previous, "replay-" + UUID.randomUUID().toString())
            }
        })
        layout.addView(button("🎛 Choose voice", Color.rgb(113,66,178)).apply {
            setOnClickListener { voiceSettings.showSettings() }
        })
        layout.addView(button("Retry last request", Color.rgb(113,66,178)).apply {
            setOnClickListener { retryTurn() }
        })
        layout.addView(button("Saved conversations and tasks").apply {
            setOnClickListener {
                val tasks=WatchHistory.all(this@MainActivity)
                if(tasks.isEmpty())stateText.text="No saved tasks yet" else android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Recent tasks").setItems(tasks.map { task ->
                        val question=task.getJSONObject("request").optString("text").take(35)
                        val state=task.optJSONObject("result")?.optString("state") ?: "recoverable"
                        "$question · $state"
                    }.toTypedArray()) { _,index ->
                        val task=tasks[index]
                        val packet=task.getJSONObject("request")
                        val response=task.optJSONObject("result")
                        sessionId=packet.optString("sessionId",sessionId)
                        prefs.edit().putString("session",sessionId).apply()
                        if(response?.optString("state")=="completed") {
                            pending=null
                            prefs.edit().remove("pending_turn").remove("turn_response").putString("last_text",response.optString("text")).commit()
                            answerText.text=response.optString("text");stateText.text="Saved answer"
                        } else {
                            pending=null;request("chat",JSONObject(packet.toString()).put("retry",true))
                        }
                    }.setNegativeButton("Close",null).show()
            }
        })
        layout.addView(button("Cancel current task",Color.rgb(75,31,139)).apply {
            setOnClickListener {
                val turn=pending
                if(turn==null)stateText.text="No active task" else {
                    val packet=JSONObject().put("op","cancel").put("id",turn.optString("id"))
                    if(runId.isNotBlank())packet.put("runId",runId)
                    sendPacket(packet)
                }
            }
        })
        layout.addView(button("Enable task notifications").apply {
            setOnClickListener {
                if(android.os.Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),22)
                else stateText.text="Task notifications available"
            }
        })
        layout.addView(button("Connection status").apply {
            setOnClickListener { stateText.text=(if(ConnectionSettings(this@MainActivity).configured()) "Paired · " else "Not paired · ")+prefs.getString("transport","Companion") }
        })
        layout.addView(button("Open source notices").apply { setOnClickListener {
            val notice=TextView(this@MainActivity).apply {
                text=resources.openRawResource(R.raw.wake_license).bufferedReader().use {it.readText()}
                setTextColor(Color.rgb(240,229,255));setPadding(dp(12),dp(12),dp(12),dp(12))
            }
            android.app.AlertDialog.Builder(this@MainActivity).setTitle("Vosk offline recognition")
                .setView(ScrollView(this@MainActivity).apply {addView(notice)}).setPositiveButton("Close",null).show()
        } })
        layout.addView(label("Hermes Wear " + packageManager.getPackageInfo(packageName,0).versionName, 11f, Color.rgb(182,160,214)))
        layout.addView(button("New chat", Color.rgb(113,66,178)).apply {
            setOnClickListener { newChat() }
        })
        controls = android.app.Dialog(this, R.style.HermesTheme).apply {
            setContentView(ScrollView(this@MainActivity).apply { isFillViewport=true; addView(layout) })
        }
        val root=android.widget.FrameLayout(this)
        face=HermesFaceView(this).apply {
            onSettingsRequested = { showControls() }
            setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); launchVoice() }
            setOnLongClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); showControls(); true }
        }
        wakeWord=WakeWordListener(this,{if(pending==null && !inAppSpeech.busy)stateText.text=it}) {
            face.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);launchVoice()
        }
        inAppSpeech=InAppSpeech(this,{stateText.text=it},{face.transcript=it},{face.audioLevel=it}) {spoken -> face.transcript="";ask(spoken)}
        root.addView(face, android.widget.FrameLayout.LayoutParams(-1,-1))
        // Edge controls follow the circular bezel; the centre remains tap-to-speak.
        val screen=resources.displayMetrics.widthPixels
        for((title,left,callback) in listOf<Triple<String,Boolean,()->Unit>>(
            Triple("Reply",true,{ showReply() }), Triple("Settings",false,{ showControls() })
        )) {
            val button=EdgeActionButton(this,title,left).apply {
                setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); callback() }
            }
            root.addView(button,android.widget.FrameLayout.LayoutParams(screen/2,(screen*.43f).toInt(),
                android.view.Gravity.BOTTOM or if(left) android.view.Gravity.LEFT else android.view.Gravity.RIGHT))
        }
        fun observe(view:TextView, changed:()->Unit) {
            view.addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { changed() }
                override fun afterTextChanged(s:android.text.Editable?) {}
            })
        }
        observe(stateText) {
            face.status=stateText.text.toString()
            if(stateText.text.toString()=="Approval required") face.post { if(approvalPanel.visibility==View.VISIBLE) showControls() }
        }
        observe(answerText) {face.reply=answerText.text.toString()}
        face.status=stateText.text.toString();face.reply=answerText.text.toString()
        setContentView(root)
    }

    private fun showControls() {
        if(this::wakeWord.isInitialized) {wakeWord.stop();face.wakeReady=false}
        if(this::inAppSpeech.isInitialized && inAppSpeech.busy) {inAppSpeech.cancel();face.transcript="";stateText.text="Ready to talk"}
        if(!controls.isShowing) controls.show()
    }
    private fun showComposer() {
        val input=EditText(this).apply {
            hint="Message Hermes";setTextColor(Color.WHITE);maxLines=4
            setPadding(dp(12),dp(8),dp(12),dp(8))
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        android.app.AlertDialog.Builder(this).setTitle("Type a message").setView(input)
            .setPositiveButton("Send") {_,_->ask(input.text.toString())}
            .setNegativeButton("Cancel",null).show()
    }
    private fun showReply() {
        val body=label(answerText.text.toString(),16f).apply {setPadding(dp(22),dp(16),dp(22),dp(16));setTextIsSelectable(true)}
        android.app.AlertDialog.Builder(this).setTitle("Conversation")
            .setView(ScrollView(this).apply {addView(body)})
            .setPositiveButton("Close",null).show()
    }
    private fun chooseNousVoice() {
        val voices = arrayOf("onyx", "alloy", "echo", "fable", "nova", "shimmer")
        val names = arrayOf("Onyx", "Alloy", "Echo", "Fable", "Nova", "Shimmer")
        android.app.AlertDialog.Builder(this).setTitle("Nous Portal voices")
            .setSingleChoiceItems(names, voices.indexOf(cloudVoice)) { dialog, i ->
                cloudVoice = voices[i]
                prefs.edit().putString("nous_voice_style", cloudVoice).apply()
                dialog.dismiss()
                requestCloudVoice("Hello! This is Hermes with the " + names[i] + " voice.")
            }.setNegativeButton("Cancel", null).show()
    }

    /** Speech is independent of pending chat requests. A slow TTS response cannot block ask(). */
    private fun requestCloudVoice(reply: String) {
        if(!prefs.getBoolean("remote_voice_available",false)) {stateText.text="Remote voice unavailable; using watch voice";voiceSettings.speak(reply,"native-"+UUID.randomUUID().toString());return}
        val text = reply.take(700)
        if (text.isBlank()) return
        val id = UUID.randomUUID().toString()
        cloudRequestId = id
        cloudFallbackText = reply
        cloudPlayer.begin(id)
        if(pending==null) stateText.text = "Sending Nous voice request…"
        val packet = JSONObject()
            .put("id", id).put("op", "tts")
            .put("text", text).put("voice", cloudVoice)
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            // Another voice preview may have superseded this one.
            if (cloudRequestId != id) return@addOnSuccessListener
            val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            if (node == null) {
                fallbackFromNous("Phone disconnected")
                return@addOnSuccessListener
            }
            Wearable.getMessageClient(this).sendMessage(
                node.id, "/hermes-wear/request",
                packet.toString().toByteArray(StandardCharsets.UTF_8)
            ).addOnSuccessListener {
                if (cloudRequestId == id && pending==null) stateText.text = "Waiting for Nous voice…"
            }.addOnFailureListener {
                if (cloudRequestId == id) fallbackFromNous("Could not send Nous request")
            }
        }.addOnFailureListener {
            if (cloudRequestId == id) fallbackFromNous("Phone connection unavailable")
        }
        handler.postDelayed({
            if (cloudRequestId == id) fallbackFromNous("Nous voice timed out")
        }, 95000L)
    }

    private fun fallbackFromNous(reason: String) {
        if (cloudRequestId.isBlank()) return
        val text = cloudFallbackText
        cloudRequestId = ""
        cloudFallbackText = ""
        cloudPlayer.cancel()
        if(pending==null) stateText.text = reason + "; using watch voice"
        if (speaking && text.isNotBlank()) {
            voiceSettings.speak(text, "local-fallback-" + UUID.randomUUID().toString())
        }
    }

    private fun updateVoice() {
        voiceToggle.text = if (!speaking) "🔇 Voice off" else if(voiceSettings.available) "🔊 Voice ready" else "Voice enabled · unavailable"
    }
    private fun newChat() {
        sessionId = UUID.randomUUID().toString()
        runId = ""; promptId = ""; generation = -1; pending = null
        cloudRequestId = ""; cloudFallbackText = ""; cloudPlayer.cancel()
        prefs.edit().putString("session", sessionId).remove("run").remove("pending_turn").remove("pending_decision").remove("turn_response").remove("retry_turn")
            .putString("last_text", "New chat started.").apply()
        answerText.text = "New chat started."
        promptText.text = ""
        prefs.edit().remove("last_input").apply()
        approvalPanel.visibility = View.GONE
        stateText.text = "Ready to talk"
    }
    private fun launchVoice() {
        if(inAppSpeech.busy) {inAppSpeech.toggle();return}
        if(pending!=null) {stateText.text="Hermes is still working. Wait or cancel the task in Settings.";return}
        if(controls.isShowing) controls.dismiss()
        wakeWord.stop();face.wakeReady=false;playbackBusy=false
        voiceSettings.stop();cloudPlayer.cancel();face.transcript=""
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO),42);return
        }
        inAppSpeech.toggle()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==43) {
            if(grantResults.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED) handler.post(wakeTick)
            else {wakeEnabled=false;wakeButton.isChecked=false;wakeStatus.text="Microphone permission required";prefs.edit().putBoolean("wake_word",false).apply();stateText.text="Wake word needs microphone permission. Re-enable in Settings."}
        }
        if(requestCode==42) {
            if(grantResults.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED) launchVoice()
            else stateText.text="Microphone permission denied. Enable it in watch app permissions or use System dictation in Settings."
        }
    }
    private fun launchSystemVoice() {
        wakeWord.stop();face.wakeReady=false;playbackBusy=false
        voiceSettings.stop()
        cloudPlayer.cancel()
        stateText.text="Listening…"
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-CA")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Hermes")
            }, 11)
        } catch (_: Exception) {
            stateText.text = "Speech input unavailable. Type a message."
        }
    }
    @Deprecated("Using standard voice recognition Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==11 && resultCode!=RESULT_OK) stateText.text="Ready to talk"
        if (requestCode == 11 && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) ask(spoken)
        }
    }
    private fun ask(raw: String) {
        val message=raw.trim()
        if(message.isEmpty()) return
        if(pending!=null) { stateText.text="Hermes is still working. Wait or start a new chat."; return }
        if(message.length>2000) { stateText.text="Message too long"; return }
        entry.setText("")
        approvalPanel.visibility=View.GONE
        answerText.text="You: $message"
        promptText.text="You: $message"
        prefs.edit().putString("last_input", promptText.text.toString()).apply()
        prefs.edit().putString("last_text","You: $message").apply()
        request("chat",JSONObject().put("sessionId",sessionId).put("text",message))
    }
    private fun decide(choice:String) {
        val turn=pending ?: return
        if(runId.isBlank() || promptId.isBlank() || generation<0) {
            stateText.text="Approval expired. Retry to refresh approval."; return
        }
        turn.put("createdMs",System.currentTimeMillis())
        prefs.edit().putString("pending_turn",turn.toString()).commit()
        val packet=JSONObject().put("id",UUID.randomUUID().toString()).put("op","decision")
            .put("createdMs",System.currentTimeMillis())
            .put("turnId",turn.optString("id")).put("runId",runId)
            .put("requestId",promptId).put("generation",generation).put("choice",choice)
        approvalPanel.visibility=View.GONE
        stateText.text="Sending decision…"
        prefs.edit().putString("pending_decision",packet.toString()).commit()
        sendPacket(packet)
        // The original turn remains the response identity.
    }
    private fun request(op:String,data:JSONObject) {
        if(pending!=null) return
        data.put("id",data.optString("id").ifBlank { UUID.randomUUID().toString() })
            .put("op",op).put("createdMs",System.currentTimeMillis())
        WatchHistory.saveRequest(this,data)
        pending=data
        retries=0
        lastSequence=-1
        accepted=false
        recovered=false
        check(prefs.edit().putString("pending_turn",data.toString())
            .putString("retry_turn",data.toString()).remove("turn_response").commit())
        Trace.record(this,data.optString("id"),"watch_request_created")
        stateText.text="Sending…"
        transmit()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog,1000)
    }
    private fun retryTurn() {
        if(pending!=null) { stateText.text="Request is active"; return }
        val packet=try { JSONObject(prefs.getString("retry_turn","") ?: "") }
                   catch (_:Exception) { stateText.text="Type a message first"; return }
        packet.put("retry",true)
        request("chat",packet)
    }
    private fun sendPacket(packet:JSONObject) {
        if(ConnectionSettings(this).configured()) {
            WatchJobs.enqueue(applicationContext,packet)
            stateText.text="Connecting securely…"
            return
        }
        sendViaPhone(packet)
    }
    private fun sendViaPhone(packet:JSONObject) {
        val id=packet.optString("id")
        Trace.record(this,id,"watch_nodes_requested")
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if(packet.optString("op")=="chat" && pending?.optString("id")!=id) return@addOnSuccessListener
            val node=nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            if(node==null) {
                stateText.text="Phone disconnected. Open Hermes Bridge; reconnect, then Retry."
                Trace.record(this,id,"watch_no_phone")
                return@addOnSuccessListener
            }
            Trace.record(this,id,"watch_send_invoked",JSONObject().put("nearby",node.isNearby).put("attempt",retries))
            // Trace travels separately, keeping maximum UTF-8 prompts below the 12KB request limit.
            Wearable.getMessageClient(this).sendMessage(node.id,"/hermes-wear/request",
                packet.toString().toByteArray(StandardCharsets.UTF_8))
                .addOnSuccessListener {
                    // Submission is not remote receipt. Only the phone acknowledgement changes state.
                    Trace.record(this,id,"watch_send_submitted")
                    reportTrace(id)
                }.addOnFailureListener {
                    Trace.record(this,id,"watch_send_failed")
                    if(pending?.optString("id")==id) stateText.text="Phone link failed. Retrying…"
                }
        }.addOnFailureListener {
            Trace.record(this,id,"watch_nodes_failed")
            if(pending?.optString("id")==id) stateText.text="Wear OS link unavailable. Retrying…"
        }
    }
    private fun transmit() {
        val packet=pending ?: return
        retries++
        sendPacket(JSONObject(packet.toString()))
    }
    private fun reportTrace(id:String) {
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            val node=nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return@addOnSuccessListener
            val trace=JSONObject().put("id",id).put("source","watch").put("events",Trace.events(this,id))
            Wearable.getMessageClient(this).sendMessage(node.id,"/hermes-wear/trace",
                trace.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }
    override fun onMessageReceived(event:MessageEvent) {
        // Chat responses have one authoritative writer: WatchListenerService -> WatchInbox.
        // Speech errors still belong to their independent foreground audio lane.
        if(event.path=="/hermes-wear/response") runOnUiThread {
            val obj=try { JSONObject(String(event.data,StandardCharsets.UTF_8)) } catch (_:Exception) { null }
            if(obj!=null && cloudRequestId.isNotBlank() && obj.optString("id")==cloudRequestId &&
                !obj.optBoolean("ok")) fallbackFromNous(obj.optString("error","Nous voice failed"))
        }
        if(event.path=="/hermes-wear/audio") runOnUiThread {
            try { cloudPlayer.accept(JSONObject(String(event.data,StandardCharsets.UTF_8))) }
            catch (_:Exception) { fallbackFromNous("Damaged Nous audio") }
        }
    }
    private fun handle(value:String) {
        val obj=try { JSONObject(value) } catch (_:Exception) { return }
        val turn=pending ?: return
        if(obj.optString("id")!=turn.optString("id")) return
        val seq=obj.optLong("seq",-1)
        if(seq<=lastSequence) return
        lastSequence=seq
        accepted=true
        val state=obj.optString("state")
        val incomingRun=obj.optString("runId")
        if(incomingRun.isNotBlank()) { runId=incomingRun; prefs.edit().putString("run",runId).apply() }
        when(state) {
            "queued" -> stateText.text="Queued on phone; contacting Hermes…"
            "retrying" -> stateText.text="Backend reconnecting; request is saved…"
            "accepted" -> stateText.text="Server accepted it; starting Hermes…"
            "working" -> {
                prefs.edit().remove("pending_decision").apply()
                stateText.text="Hermes is working…"; approvalPanel.visibility=View.GONE
            }
            "approval" -> {
                prefs.edit().remove("pending_decision").apply()
                stateText.text="Approval required"
                val approval=obj.optJSONObject("approval")
                promptId=approval?.optString("requestId") ?: ""
                generation=approval?.optInt("generation",-1) ?: -1
                approveText.text=(approval?.optString("prompt") ?: "Review action").take(420)
                approvalPanel.visibility=if(promptId.isNotBlank() && generation>=0) View.VISIBLE else View.GONE
            }
            "completed" -> {
                val response=obj.optString("text").ifBlank { "Done." }
                answerText.text=response
                stateText.text="Completed"
                approvalPanel.visibility=View.GONE
                val id=turn.optString("id")
                val speakOnce=prefs.getString("last_done","")!=id
                pending=null; runId=""
                prefs.edit().remove("pending_turn").remove("pending_decision").remove("run").remove("retry_turn")
                    .putString("last_text",response).putString("last_done",id).commit()
                if(speakOnce && speaking) {
                    if(cloudEnabled) requestCloudVoice(response) else voiceSettings.speak(response,id)
                }
            }
            "failed","cancelled","interrupted" -> finishError(obj.optString("error").ifBlank { "Run ended. Tap Retry." })
            else -> finishError("Unexpected phone response. Update Hermes Bridge, then Retry.")
        }
        Trace.record(this,turn.optString("id"),"watch_ui_updated",JSONObject().put("seq",seq).put("state",state))
        reportTrace(turn.optString("id"))
    }
    private fun finishError(message:String) {
        val id=pending?.optString("id") ?: ""
        pending=null; runId=""
        prefs.edit().remove("pending_turn").remove("pending_decision").remove("run").commit()
        stateText.text=message.take(180)
        approvalPanel.visibility=View.GONE
        Trace.record(this,id,"watch_error_visible")
        if(id.isNotBlank()) reportTrace(id)
    }
    override fun onResume() {
        handler.removeCallbacks(wakeTick);handler.postDelayed(wakeTick,1000)
        if(this::voiceSettings.isInitialized) voiceSettings.setVisualizationActive(true)
        super.onResume()
        active=true
        prefs.edit().putBoolean("activity_active",true).apply()
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.firstOrNull()?.let { Wearable.getMessageClient(this).sendMessage(it.id,"/hermes-wear/config/request",byteArrayOf()) }
        }
        prefs.registerOnSharedPreferenceChangeListener(inboxListener)
        Wearable.getMessageClient(this).addListener(this)
        pending=try { JSONObject(prefs.getString("pending_turn","") ?: "") } catch (_:Exception) { null }
        lastSequence=-1; accepted=false
        pending?.let {
            stateText.text="Recovering conversation…"
            prefs.getString("turn_response",null)?.let { value -> handle(value) }
            if(pending!=null) {
                transmit()
                Trace.record(this,it.optString("id"),"watch_resumed")
                try { JSONObject(prefs.getString("pending_decision","") ?: "") }
                    catch (_:Exception) { null }?.let { decision -> sendPacket(decision) }
            }
        }
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
    }
    override fun onPause() {
        handler.removeCallbacks(wakeTick)
        if(this::wakeWord.isInitialized) {wakeWord.stop();face.wakeReady=false}
        if(this::voiceSettings.isInitialized) voiceSettings.setVisualizationActive(false)
        if(this::inAppSpeech.isInitialized && inAppSpeech.busy) {inAppSpeech.cancel();face.transcript="";stateText.text="Recording cancelled"}
        active=false
        prefs.edit().putBoolean("activity_active",false).apply()
        handler.removeCallbacks(watchdog)
        prefs.unregisterOnSharedPreferenceChangeListener(inboxListener)
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacks(wakeTick)
        if(this::wakeWord.isInitialized) wakeWord.close()
        if(this::inAppSpeech.isInitialized) inAppSpeech.cancel()
        handler.removeCallbacks(watchdog)
        if(this::controls.isInitialized) controls.dismiss()
        cloudPlayer.close()
        voiceSettings.close()
        super.onDestroy()
    }
}
