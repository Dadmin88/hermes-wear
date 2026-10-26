package io.github.hermeswear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Foreground, single-shot dictation. Only final results may create a Hermes turn. */
internal class InAppSpeech(private val context:Context,private val status:(String)->Unit,
    private val partial:(String)->Unit,private val level:(Float)->Unit,private val result:(String)->Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private var recognizer:SpeechRecognizer?=null
    private var generation=0
    var busy=false;private set
    private var stopping=false
    fun toggle() {
        if(busy) { if(!stopping) {stopping=true;status("Transcribing…");level(0f);try {recognizer?.stopListening()}catch(_:Exception){cancel();status("Could not finish dictation. Tap to retry.")}};return }
        if(!SpeechRecognizer.isRecognitionAvailable(context)) {status("Speech service unavailable. Open Settings for System dictation.");return}
        val epoch=++generation
        busy=true;stopping=false
        try {
            val engine=SpeechRecognizer.createSpeechRecognizer(context);recognizer=engine
            fun current()=busy && epoch==generation
            engine.setRecognitionListener(object:RecognitionListener {
                override fun onReadyForSpeech(params:Bundle?) {if(current()) status("Listening… Tap to finish")}
                override fun onBeginningOfSpeech() {if(current()) status("Listening… Tap to finish")}
                override fun onRmsChanged(rmsdB:Float) {if(current() && !stopping) level(((rmsdB+2f)/12f).coerceIn(0f,1f))}
                override fun onBufferReceived(buffer:ByteArray?) {}
                override fun onEndOfSpeech() {if(current()) {stopping=true;level(0f);status("Transcribing…")}}
                override fun onError(error:Int) {
                    if(!current()) return
                    cancel()
                    status(when(error) {
                        SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap to try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required. Tap to retry."
                        SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network unavailable. Tap to retry."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech engine busy. Tap to retry."
                        else -> "Speech input failed ($error). Tap to retry or use System dictation in Settings."
                    })
                }
                override fun onPartialResults(results:Bundle?) {if(current()) partial(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())}
                override fun onResults(results:Bundle?) {
                    if(!current()) return
                    val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                    cancel()
                    if(text.isNotEmpty()) result(text) else status("Didn't catch that. Tap to try again.")
                }
                override fun onEvent(eventType:Int,params:Bundle?) {}
            })
            status("Listening…")
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-CA")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
            })
            handler.postDelayed({if(current()) {cancel();status("Speech timed out. Tap to try again.")}},45000)
        } catch(_:Exception) {cancel();status("Speech service unavailable. Use System dictation in Settings.")}
    }
    fun cancel() {
        generation++;busy=false;stopping=false
        handler.removeCallbacksAndMessages(null)
        runCatching {recognizer?.cancel()};runCatching {recognizer?.destroy()};recognizer=null
        partial("");level(0f)
    }
}
