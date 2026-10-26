package io.github.hermeswear

/** Protocol rules: one durable turn identity, monotonic events, bounded recovery. */
object TurnPolicy {
    val terminal = setOf("completed", "failed", "cancelled", "interrupted")
    fun accept(expectedId: String, receivedId: String, previousSeq: Long,
               nextSeq: Long, previousState: String): Boolean =
        expectedId.isNotBlank() && expectedId == receivedId &&
        previousState !in terminal && nextSeq > previousSeq
    const val ACK_TIMEOUT_MS = 15_000L
    const val TURN_TIMEOUT_MS = 180_000L
    fun expired(start: Long, now: Long, accepted: Boolean): Boolean =
        now - start >= if (accepted) TURN_TIMEOUT_MS else ACK_TIMEOUT_MS
}
