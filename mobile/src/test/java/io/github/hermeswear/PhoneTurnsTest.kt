package io.github.hermeswear
import android.content.Context
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PhoneTurnsTest {
    private val c:Context get()=RuntimeEnvironment.getApplication()
    @Before fun clean() { c.getSharedPreferences("hermes_turns",0).edit().clear().commit() }
    @Test fun durableRunIdentityAndCompletionSurviveReloadForRetry() {
        PhoneTurns.save(c,"turn-123456",JSONObject().put("runId","run_123456").put("seq",0)
            .put("created",1).put("request",JSONObject().put("id","turn-123456")))
        val completed=PhoneTurns.publish(c,"turn-123456",JSONObject().put("state","completed").put("text","OK"))
        val reloaded=PhoneTurns.load(c,"turn-123456")!!
        assertEquals("run_123456",reloaded.getString("runId"))
        assertEquals("completed",reloaded.getJSONObject("result").getString("state"))
        assertEquals("OK",reloaded.getJSONObject("result").getString("text"))
        assertEquals(1,completed.getInt("seq"))
    }
    @Test fun sequenceIncreasesAcrossStoredIntermediateEvents() {
        PhoneTurns.save(c,"turn-123456",JSONObject().put("seq",0))
        assertEquals(0,PhoneTurns.publish(c,"turn-123456",JSONObject().put("state","accepted")).getInt("seq"))
        assertEquals(1,PhoneTurns.publish(c,"turn-123456",JSONObject().put("state","working")).getInt("seq"))
    }

    @Test fun terminalCompletionCannotBeOverwrittenByLateWorker() {
        PhoneTurns.save(c,"turn-123456",JSONObject().put("seq",0))
        PhoneTurns.publish(c,"turn-123456",JSONObject().put("state","completed").put("text","OK"))
        PhoneTurns.publish(c,"turn-123456",JSONObject().put("state","working"))
        assertEquals("completed",PhoneTurns.load(c,"turn-123456")!!.getJSONObject("result").getString("state"))
    }
    @Test fun serverSequenceSurvivesFallbackAndRejectsLateLowerSequence() {
        PhoneTurns.save(c,"turn-123456",JSONObject().put("seq",0))
        PhoneTurns.publish(c,"turn-123456",JSONObject().put("state","working").put("seq",7))
        val replay=PhoneTurns.publish(c,"turn-123456",JSONObject().put("state","accepted").put("seq",2))
        assertEquals(7,replay.getInt("seq"))
        assertEquals("working",replay.getString("state"))
    }

}
