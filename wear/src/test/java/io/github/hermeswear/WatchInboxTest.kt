package io.github.hermeswear

import android.content.Context
import android.os.Looper
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class WatchInboxTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val prefs get()=context.getSharedPreferences("hermes_watch",Context.MODE_PRIVATE)
    @Before fun clean() { prefs.edit().clear().commit();context.getSharedPreferences("hermes_history",0).edit().clear().commit() }
    private fun begin(id:String="turn-123456") {
        prefs.edit().putString("session","session-123456")
            .putBoolean("speak",false)
            .putString("pending_turn",JSONObject().put("id",id).put("op","chat")
                .put("sessionId","session-123456").put("text","hi")
                .put("createdMs",System.currentTimeMillis()).toString()).commit()
    }
    private fun event(id:String="turn-123456",seq:Int=1,state:String="working") =
        JSONObject().put("id",id).put("seq",seq).put("ok",true).put("state",state)
            .put("runId","run_123456").put("text","OK").toString()

    @Test fun replyReceivedWithNoActivitySurvivesRecreationAndRenders() {
        begin()
        assertTrue(WatchInbox.receive(context,event(seq=3,state="completed")))
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        val activity=controller.get()
        val answer=MainActivity::class.java.getDeclaredField("answerText").apply { isAccessible=true }
            .get(activity) as android.widget.TextView
        assertEquals("OK",answer.text.toString())
        assertFalse(prefs.contains("pending_turn"))
        assertFalse(prefs.contains("run"))
        assertEquals("OK",prefs.getString("last_text",""))
        controller.pause().stop().destroy()
    }
    @Test fun outOfOrderAndDuplicateMessagesCannotRegressInbox() {
        begin()
        assertTrue(WatchInbox.receive(context,event(seq=1)))
        assertTrue(WatchInbox.receive(context,event(seq=3,state="completed")))
        assertFalse(WatchInbox.receive(context,event(seq=2)))
        assertFalse(WatchInbox.receive(context,event(seq=3,state="completed")))
        assertEquals("completed",JSONObject(prefs.getString("turn_response","")!!).getString("state"))
    }
    @Test fun lateSpeechAndPriorConversationCannotReplaceActiveReply() {
        begin("new-turn-123")
        assertFalse(WatchInbox.receive(context,event(id="old-turn-123")))
        assertFalse(WatchInbox.receive(context,event(id="voice-turn-123",state="audio_delivered")))
        assertFalse(prefs.contains("turn_response"))
    }
    @Test fun consecutiveTurnsAcceptTheirOwnSequences() {
        begin()
        assertTrue(WatchInbox.receive(context,event(seq=3,state="completed")))
        begin("turn-456789")
        assertTrue(WatchInbox.receive(context,event(id="turn-456789",seq=1)))
        assertFalse(WatchInbox.receive(context,event(id="turn-123456",seq=4,state="completed")))
    }
    @Test fun noAckAndRunTimeoutsAreBoundedEvenAfterClockAdvanceDuringSleep() {
        assertFalse(TurnPolicy.expired(0,14999,false))
        assertTrue(TurnPolicy.expired(0,15000,false))
        assertFalse(TurnPolicy.expired(0,179999,true))
        assertTrue(TurnPolicy.expired(0,180000,true))
    }

    @Test fun expiredUnacknowledgedRequestShowsActionableErrorOnWake() {
        begin()
        val packet=JSONObject(prefs.getString("pending_turn","")!!).put("createdMs",System.currentTimeMillis()-16000)
        prefs.edit().putString("pending_turn",packet.toString()).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        val state=MainActivity::class.java.getDeclaredField("stateText").apply { isAccessible=true }
            .get(controller.get()) as android.widget.TextView
        assertTrue(state.text.toString().contains("15 seconds"))
        assertFalse(prefs.contains("pending_turn"))
        controller.pause().stop().destroy()
    }
    @Test fun backgroundTaskResultSurvivesSwitchingChatsAndLateEvents() {
        val packet=JSONObject().put("id","old-turn-123").put("text","Long task").put("sessionId","session-old")
        WatchHistory.saveRequest(context,packet)
        begin("new-turn-123")
        assertTrue(WatchInbox.receive(context,event(id="old-turn-123",seq=4,state="completed")))
        assertFalse(prefs.contains("turn_response"))
        assertEquals("OK",WatchHistory.get(context,"old-turn-123")!!.getJSONObject("result").getString("text"))
        assertFalse(WatchInbox.receive(context,event(id="old-turn-123",seq=5,state="working")))
        assertEquals("completed",WatchHistory.get(context,"old-turn-123")!!.getJSONObject("result").getString("state"))
    }

}
