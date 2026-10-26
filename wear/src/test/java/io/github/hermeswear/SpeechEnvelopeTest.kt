package io.github.hermeswear

import org.junit.Assert.*
import org.junit.Test

class SpeechEnvelopeTest {
    @Test fun pcmChunksPreservePartialSamplesAndTiming() {
        val envelope=SpeechEnvelope(1000,2,1)
        val bytes=ByteArray(200) {if(it%2==0)0 else 32} // amplitude .25
        envelope.append(bytes.copyOfRange(0,1));assertNull(envelope.at(0))
        envelope.append(bytes.copyOfRange(1,bytes.size))
        assertEquals(.75f,envelope.at(0)!!,.001f)
        assertEquals(.75f,envelope.at(50)!!,.001f)
        assertNull(envelope.at(100))
    }
    @Test fun silenceAndUnsignedPcmAreNotConfused() {
        val envelope=SpeechEnvelope(1000,3,1)
        envelope.append(ByteArray(50){128.toByte()})
        assertEquals(0f,envelope.at(0)!!,0f)
    }
    @Test fun unknownFormatAndLongAudioRemainBounded() {
        val unknown=SpeechEnvelope(1000,99,1);unknown.append(ByteArray(100));assertNull(unknown.at(0))
        val bounded=SpeechEnvelope(20,2,1);bounded.append(ByteArray(60000))
        assertNotNull(bounded.at(599950));assertNull(bounded.at(600000))
    }
}
