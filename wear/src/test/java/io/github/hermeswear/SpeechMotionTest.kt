package io.github.hermeswear

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SpeechMotionTest {
    @Test fun missingPcmProducesChangingBoundedAnimation(){
        val levels=(0..40).map {SpeechMotion.level(null,it*.05f)}
        assertTrue(levels.all {it in .12f.. .65f});assertTrue(levels.max()-levels.min()>.2f)
        assertTrue(SpeechMotion.level(Float.NaN,1f).isFinite())
    }
    @Test fun measuredSilenceAndSpeechAreRespected(){
        assertEquals(0f,SpeechMotion.level(0f,1f));assertEquals(.8f,SpeechMotion.level(.8f,1f));assertEquals(1f,SpeechMotion.level(2f,1f))
    }
    @Test fun explicitPlaybackStateDrivesWaveAndStopsEvenWithStaleStatus(){
        val face=HermesFaceView(RuntimeEnvironment.getApplication())
        face.status="Completed";face.speechActive=true
        assertTrue(face.currentWaveLevel(.2f)>0f)
        face.speechLevel=0f;assertEquals(0f,face.currentWaveLevel(.2f))
        face.speechLevel=.7f;assertEquals(.7f,face.currentWaveLevel(.2f))
        face.status="Speaking…";face.speechActive=false;assertEquals(0f,face.currentWaveLevel(.2f));assertNull(face.speechLevel)
        face.audioLevel=.5f;assertEquals(.5f,face.currentWaveLevel(.2f,true))
    }
}
