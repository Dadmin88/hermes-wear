package io.github.hermeswear

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SettingsProtocolTest {
    private val c get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){listOf("hermes_watch","hermes_appearance","hermes_settings_actions").forEach {c.getSharedPreferences(it,0).edit().clear().commit()};WatchSettingsBridge.actionHandler=null}
    @After fun close(){reset()}
    private fun apply(p:JSONObject,revision:String=WatchPreferences.revision(WatchPreferences.values(c)))=WatchPreferences.apply(c,p,revision)
    private fun rejects(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    @Test fun strictPatchDoesNotTouchSessionOrExposeCredentials(){
        val prefs=c.getSharedPreferences("hermes_watch",0);prefs.edit().putString("session","preserve-session").putString("pending_turn","preserve-turn").commit()
        apply(JSONObject().put("speak",false).put("tts_speed",1.1).put("theme","amber"))
        assertFalse(prefs.getBoolean("speak",true));assertEquals("preserve-session",prefs.getString("session",null));assertEquals("preserve-turn",prefs.getString("pending_turn",null))
        assertEquals("amber",ThemeStore.selected(c).id)
        assertEquals(WatchPreferences.defaults.keys+"theme",WatchPreferences.values(c).keys().asSequence().toSet())
        rejects{apply(JSONObject().put("credential","forbidden"))}
        rejects{apply(JSONObject().put("session","forbidden"))}
    }
    @Test fun sliderEndpointsAcceptFloatRoundoff(){
        apply(JSONObject().put("tts_speed",.65f).put("tts_pitch",1.25f))
        assertEquals(.65f,c.getSharedPreferences("hermes_watch",0).getFloat("tts_speed",0f))
        apply(JSONObject().put("tts_speed",1.45f).put("tts_pitch",.75f))
    }
    @Test fun staleRevisionAndInvalidRangesCannotPartiallyApply(){
        val revision=WatchPreferences.revision(WatchPreferences.values(c))
        apply(JSONObject().put("wake_word",true),revision)
        rejects{apply(JSONObject().put("speak",false),revision)}
        rejects{apply(JSONObject().put("speak",false).put("tts_pitch",9))}
        assertTrue(WatchPreferences.values(c).getBoolean("speak"))
        rejects{apply(JSONObject().put("speak","true"))}
        rejects{apply(JSONObject().put("theme","unknown-theme"))}
        rejects{apply(JSONObject().put("nous_voice",true))}
    }
    @Test fun voicesMustComeFromWatchInventoryAndEngineChangeResetsVoice(){
        rejects{apply(JSONObject().put("tts_voice","phone-only-voice"))}
        c.getSharedPreferences("hermes_watch",0).edit().putString("settings_capabilities",JSONObject()
            .put("engines",JSONArray().put(JSONObject().put("id","watch.engine")))
            .put("voices",JSONArray().put(JSONObject().put("id","watch.voice"))).toString()).commit()
        apply(JSONObject().put("tts_voice","watch.voice"));assertEquals("watch.voice",WatchPreferences.values(c).getString("tts_voice"))
        apply(JSONObject().put("tts_engine","watch.engine"));assertEquals("",WatchPreferences.values(c).getString("tts_voice"))
        assertFalse(c.getSharedPreferences("hermes_watch",0).contains("settings_capabilities"))
    }
    private fun action(id:String="test-action-123")=JSONObject().put("requestId",id).put("createdMs",System.currentTimeMillis()).put("action","new_chat")
    @Test fun duplicateActionsExecuteOnceAndExpiredActionsFail(){
        var count=0;WatchSettingsBridge.actionHandler={count++;"Executed"}
        val packet=action();assertEquals("Executed",WatchSettingsBridge.executeAction(c,packet));assertEquals("Executed",WatchSettingsBridge.executeAction(c,packet));assertEquals(1,count)
        rejects{WatchSettingsBridge.executeAction(c,action("expired-action").put("createdMs",System.currentTimeMillis()-61000))};assertEquals(1,count)
    }
    @Test fun reservedActionSurvivesProcessLossWithoutRepeatedSideEffects(){
        c.getSharedPreferences("hermes_settings_actions",0).edit().putString("test-action-123",JSONObject().put("state","executing").put("createdMs",System.currentTimeMillis()).put("message","Outcome uncertain").toString()).commit()
        var count=0;WatchSettingsBridge.actionHandler={count++;"Should not run"}
        rejects{WatchSettingsBridge.executeAction(c,action())};assertEquals(0,count)
    }
    @Test fun actionNeedsForegroundHandlerButPreferencesDoNot(){
        rejects{WatchSettingsBridge.executeAction(c,action())}
        apply(JSONObject().put("wake_word",true));assertTrue(WatchPreferences.values(c).getBoolean("wake_word"))
    }
}
