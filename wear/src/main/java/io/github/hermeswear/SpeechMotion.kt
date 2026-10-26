package io.github.hermeswear
import kotlin.math.sin
/** Playback animation fallback, not a measurement of speaker output. */
internal object SpeechMotion {
    fun level(measured:Float?,seconds:Float):Float {
        if(measured!=null && measured.isFinite()) return measured.coerceIn(0f,1f)
        val syllable=(.5f+.5f*sin(seconds*12.7f))*(.6f+.4f*sin(seconds*4.3f+1f))
        return (.18f+.42f*syllable).coerceIn(.12f,.65f)
    }
}
