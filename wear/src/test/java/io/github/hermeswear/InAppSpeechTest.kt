package io.github.hermeswear

import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class InAppSpeechTest {
    private fun available() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).addResolveInfoForIntent(
            Intent("android.speech.RecognitionService"),ResolveInfo().apply {serviceInfo=ServiceInfo().apply {packageName="test.speech";name="Recognizer"}})
    }
    private fun bundle(text:String)=Bundle().apply {putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,arrayListOf(text))}
    @Test fun partialDoesNotSubmitAndFinalSubmitsOnlyOnce() {
        available();val results=mutableListOf<String>();val partials=mutableListOf<String>()
        val speech=InAppSpeech(RuntimeEnvironment.getApplication(),{}, {partials.add(it)}, {}, {results.add(it)})
        speech.toggle()
        val engine=shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        engine.triggerOnPartialResults(bundle("hello"));assertTrue(results.isEmpty());assertEquals(listOf("hello"),partials)
        engine.triggerOnResults(bundle("hello Hermes"));engine.triggerOnResults(bundle("duplicate"))
        assertEquals(listOf("hello Hermes"),results);assertFalse(speech.busy);assertTrue(engine.isDestroyed)
    }
    @Test fun cancellationAndTimeoutRejectLateResults() {
        available();val results=mutableListOf<String>();val states=mutableListOf<String>()
        val speech=InAppSpeech(RuntimeEnvironment.getApplication(),{states.add(it)},{},{},{results.add(it)})
        speech.toggle();val old=shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer());speech.cancel()
        old.triggerOnResults(bundle("late"));assertTrue(results.isEmpty())
        speech.toggle();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(46))
        assertFalse(speech.busy);assertTrue(states.last().contains("timed out"));assertTrue(results.isEmpty())
    }
    @Test fun wakeRequiresExactPhraseAndConfidence() {
        assertTrue(WakeWordListener.matches("""{"text":"hey hermes","result":[{"conf":0.99},{"conf":0.95}]}"""))
        assertFalse(WakeWordListener.matches("""{"text":"hey hermes","result":[{"conf":0.4},{"conf":0.95}]}"""))
        assertFalse(WakeWordListener.matches("""{"text":"hermes"}"""));assertFalse(WakeWordListener.matches("invalid"))
    }
}
