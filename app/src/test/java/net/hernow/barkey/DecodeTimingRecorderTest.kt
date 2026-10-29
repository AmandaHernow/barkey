package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeTimingRecorderTest {
    @Test fun recordsMonotonicDurationWithoutChangingDecodeResult() {
        var now = 100L
        val recorder = DecodeTimingRecorder(MonotonicClock { now })

        val result = recorder.measure {
            now = 175L
            "decoded"
        }

        assertEquals("decoded", result)
        assertEquals(75L, recorder.lastDurationNanos)
    }

    @Test fun negativeClockDeltaIsClampedToZero() {
        var now = 100L
        val recorder = DecodeTimingRecorder(MonotonicClock { now })

        recorder.measure { now = 90L }

        assertEquals(0L, recorder.lastDurationNanos)
    }
}
