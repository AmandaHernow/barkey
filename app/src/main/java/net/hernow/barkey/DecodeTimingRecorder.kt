package net.hernow.barkey

import android.os.SystemClock

internal fun interface MonotonicClock {
    fun nowNanos(): Long
}

/** In-memory diagnostic only; it records no image or decoded content. */
internal class DecodeTimingRecorder(
    private val clock: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtimeNanos() },
) {
    @Volatile
    var lastDurationNanos: Long = 0L
        private set

    fun <T> measure(block: () -> T): T {
        val startedAt = clock.nowNanos()
        try {
            return block()
        } finally {
            lastDurationNanos = (clock.nowNanos() - startedAt).coerceAtLeast(0L)
        }
    }
}
