package net.hernow.barkey

/** Keeps callbacks and duplicate suppression scoped to one editor input session. */
internal class ScanSessionGate(
    private val duplicateWindowMillis: Long = DEFAULT_DUPLICATE_WINDOW_MILLIS,
) {
    private var active = false
    private var generation = 0L
    private var lastAcceptedValue: String? = null
    private var lastAcceptedAtMillis = 0L

    fun beginSession(): Long {
        generation += 1
        active = true
        clearLastValue()
        return generation
    }

    fun endSession() {
        generation += 1
        active = false
        clearLastValue()
    }

    fun isCurrent(sessionId: Long): Boolean = active && sessionId == generation

    fun shouldAccept(sessionId: Long, value: String, nowMillis: Long): Boolean {
        if (!isCurrent(sessionId) || value.isEmpty()) return false

        val elapsed = nowMillis - lastAcceptedAtMillis
        if (value == lastAcceptedValue && elapsed in 0 until duplicateWindowMillis) {
            return false
        }

        lastAcceptedValue = value
        lastAcceptedAtMillis = nowMillis
        return true
    }

    private fun clearLastValue() {
        lastAcceptedValue = null
        lastAcceptedAtMillis = 0L
    }

    private companion object {
        const val DEFAULT_DUPLICATE_WINDOW_MILLIS = 1_000L
    }
}
