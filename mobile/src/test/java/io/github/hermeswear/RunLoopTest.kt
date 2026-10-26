package io.github.hermeswear
import org.junit.Assert.*
import org.junit.Test

class RunLoopTest {
    @Test fun phoneOwnsRunUntilCompletionWithoutWatchPolling() {
        var now = 0L
        val calls = mutableListOf<String>()
        val events = mutableListOf<RunReply>()
        var saved = ""
        val backend = object : RunBackend {
            override fun call(op: String, runId: String): RunReply {
                calls.add(op)
                return if (op == "ask") RunReply(runId="run_123456")
                       else RunReply(state="completed", runId=runId, text="OK")
            }
        }
        RunLoop(backend, { now }, { now += it }).run("", 180000, { saved=it }, { events.add(it) })
        assertEquals("run_123456", saved)
        assertEquals(listOf("ask","status"), calls)
        assertEquals("completed", events.last().state)
        assertEquals("OK", events.last().text)
    }

    @Test fun firstStatusDoesNotWaitForPollTimer() {
        var now=0L
        val callTimes=mutableListOf<Long>()
        val backend=object:RunBackend {
            override fun call(op:String, runId:String):RunReply {
                callTimes.add(now)
                return RunReply(state=if(op=="ask") "working" else "completed",runId="run_123456")
            }
        }
        RunLoop(backend,{now},{now+=it}).run("",180000,{}, {})
        assertEquals(listOf(0L,0L),callTimes)
    }

    @Test fun retryingLostAskResponseReusesBackendIdentityAndStopsAtThreeAttempts() {
        var now=0L
        var calls=0
        val events=mutableListOf<RunReply>()
        val backend=object:RunBackend {
            override fun call(op:String,runId:String):RunReply {
                assertEquals("ask",op); calls++
                return RunReply(false,error="offline",retryable=true)
            }
        }
        RunLoop(backend,{now},{now+=it}).run("",180000,{}, {events.add(it)})
        assertEquals(3,calls)
        assertEquals("failed",events.single().state)
        assertTrue(events.single().retryable)
    }
    @Test fun resumeUsesPersistedRunWithoutSubmittingAnotherMessage() {
        val ops=mutableListOf<String>()
        val events=mutableListOf<RunReply>()
        val backend=object:RunBackend {
            override fun call(op:String,runId:String):RunReply {
                ops.add(op); assertEquals("run_saved1",runId)
                return RunReply(state="completed",runId=runId,text="OK")
            }
        }
        RunLoop(backend,{0},{ }).run("run_saved1",180000,{fail("must not replace run")},{events.add(it)})
        assertEquals(listOf("status"),ops)
        assertEquals("completed",events.single().state)
    }
    @Test fun mismatchedRunIsRejectedBeforeDisplayingAnswer() {
        val events=mutableListOf<RunReply>()
        val backend=object:RunBackend {
            override fun call(op:String,runId:String)=RunReply(state="completed",runId="run_other1",text="WRONG")
        }
        RunLoop(backend,{0},{}).run("run_saved1",180000,{}, {events.add(it)})
        assertEquals("failed",events.single().state)
        assertEquals("",events.single().text)
    }
    @Test fun sleepingOrDelayedWorkerCannotRunPastItsDeadline() {
        val events=mutableListOf<RunReply>()
        val backend=object:RunBackend {
            override fun call(op:String,runId:String):RunReply { fail("expired job must not call backend"); return RunReply() }
        }
        RunLoop(backend,{180000},{}).run("run_saved1",180000,{}, {events.add(it)})
        assertEquals("failed",events.single().state)
        assertTrue(events.single().retryable)
    }
    @Test fun approvalPausesPhonePolling() {
        var calls=0
        val events=mutableListOf<RunReply>()
        val backend=object:RunBackend {
            override fun call(op:String,runId:String):RunReply { calls++; return RunReply(state="approval",runId="run_saved1") }
        }
        RunLoop(backend,{0},{}).run("run_saved1",180000,{}, {events.add(it)})
        assertEquals(1,calls)
        assertEquals("approval",events.single().state)
    }
    @Test(expected=InterruptedException::class) fun systemCancellationStopsNetworkWork() {
        val backend=object:RunBackend {
            override fun call(op:String,runId:String):RunReply { fail("stopped"); return RunReply() }
        }
        RunLoop(backend,{0},{},{true}).run("",180000,{}, {})
    }
    @Test fun durableAcceptancePrecedesNativeAdmissionAndUsesSameRequestLane() {
        var time=0L;var n=0;var saved=""
        val events=mutableListOf<RunReply>()
        val backend=object:RunBackend {
            override fun call(op:String,runId:String):RunReply {
                n++
                return when(n) {
                    1 -> RunReply(state="accepted",seq=1)
                    2 -> {assertEquals("ask",op);RunReply(state="working",runId="run_123456",seq=2)}
                    else -> {assertEquals("status",op);RunReply(state="completed",runId=runId,seq=3,text="OK")}
                }
            }
        }
        RunLoop(backend,{time},{time+=it}).run("",180000,{saved=it},{events.add(it)})
        assertEquals("run_123456",saved)
        assertEquals(listOf("accepted","working","completed"),events.map{it.state})
        assertEquals(listOf(1L,2L,3L),events.map{it.seq})
    }

}
