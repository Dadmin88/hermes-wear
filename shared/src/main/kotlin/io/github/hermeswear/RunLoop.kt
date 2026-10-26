package io.github.hermeswear

data class RunReply(val ok: Boolean = true, val state: String = "working",
    val runId: String = "", val text: String = "", val error: String = "",
    val approval: String = "", val retryable: Boolean = false, val seq: Long = 0)
interface RunBackend { fun call(op: String, runId: String): RunReply }

/** Phone-owned loop. All retries reuse the original ask identity in the backend. */
class RunLoop(private val backend: RunBackend, private val clock: () -> Long,
    private val sleep: (Long) -> Unit, private val stopped: () -> Boolean = { false }) {
    fun run(existingRun: String, deadline: Long, saveRun: (String) -> Unit, emit: (RunReply) -> Unit) {
        var runId = existingRun
        var failures = 0
        var announced = false
        var lastState=""
        val began=clock()
        while (clock() < deadline) {
            if (stopped()) throw InterruptedException("Worker stopped")
            val starting = runId.isBlank()
            val reply = backend.call(if (starting) "ask" else "status", runId)
            if (!reply.ok) {
                failures++
                if (!reply.retryable || failures >= 3) {
                    emit(reply.copy(state="failed", runId=runId))
                    return
                }
                sleep(500L * failures)
                continue
            }
            failures = 0
            if(reply.runId.isBlank() && reply.state in setOf("accepted","queued","retrying")) {
                if(!announced || lastState!=reply.state) {emit(reply);announced=true;lastState=reply.state}
                sleep(500);continue
            }
            if (reply.runId.isBlank() || (runId.isNotBlank() && runId != reply.runId)) {
                emit(RunReply(false, "failed", runId, error="Invalid run identity from relay"))
                return
            }
            if (runId.isBlank()) { runId = reply.runId; saveRun(runId);announced=false }
            if (reply.state in TurnPolicy.terminal || reply.state == "approval") {
                emit(reply)
                return
            }
            if (!announced || lastState!=reply.state) { emit(reply); announced=true;lastState=reply.state }
            // First status immediately after ask. Bound later observation lag to 500ms.
            if (!starting) sleep(if(clock()-began<30_000)500 else if(clock()-began<60_000)2000 else 10000)
        }
        emit(RunReply(false, "failed", runId, error="Hermes exceeded 3 minutes. Retry to recover this same run.", retryable=true))
    }
}
