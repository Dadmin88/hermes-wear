package io.github.hermeswear

import kotlin.math.sqrt

/** Bounded 50 ms RMS envelope; raw synthesized audio is never retained. PCM encodings: Android 2/3/4. */
internal class SpeechEnvelope(rate:Int,private val format:Int,channels:Int) {
    private val bytes=when(format) {2->2;3->1;4->4;else->0}
    private val window=(rate.coerceAtLeast(1)*channels.coerceAtLeast(1)/20).coerceAtLeast(1)
    private var tail=byteArrayOf()
    private var square=0.0
    private var count=0
    private val levels=ArrayList<Float>()
    @Synchronized fun append(audio:ByteArray) {
        if(bytes==0 || levels.size>=12000) return
        val data=tail+audio;var i=0
        while(i+bytes<=data.size && levels.size<12000) {
            val v=when(format) {
                3 -> ((data[i].toInt() and 255)-128)/128f
                2 -> (((data[i].toInt() and 255) or (data[i+1].toInt() shl 8)).toShort())/32768f
                else -> Float.fromBits((data[i].toInt() and 255) or ((data[i+1].toInt() and 255) shl 8) or ((data[i+2].toInt() and 255) shl 16) or (data[i+3].toInt() shl 24))
            }.let {if(it.isFinite()) it.coerceIn(-1f,1f) else 0f}
            square+=v*v;count++;i+=bytes
            if(count==window) {levels.add((sqrt(square/count)*3).toFloat().coerceIn(0f,1f));square=0.0;count=0}
        }
        tail=if(i<data.size && levels.size<12000) data.copyOfRange(i,data.size) else byteArrayOf()
    }
    @Synchronized fun at(milliseconds:Long):Float?=levels.getOrNull((milliseconds.coerceAtLeast(0)/50).toInt())
}
