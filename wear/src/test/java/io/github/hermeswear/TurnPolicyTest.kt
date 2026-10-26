package io.github.hermeswear
import org.junit.Assert.*
import org.junit.Test

class TurnPolicyTest {
    @Test fun terminalReplyCannotBeOverwrittenByDelayedWorkingReply() {
        assertFalse(TurnPolicy.accept("turn-a", "turn-a", 3, 2, "completed"))
    }
}
