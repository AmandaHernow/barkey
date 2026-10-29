package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameDecodeRunnerTest {
    @Test fun closesFrameExactlyOnceAfterSuccess() {
        val frame = FakeFrame()

        val result = FrameDecodeRunner.run(frame) { DecodeOutcome.Value("value") }

        assertEquals(DecodeOutcome.Value("value"), result)
        assertEquals(1, frame.closeCount)
    }

    @Test fun closesFrameExactlyOnceAfterDecodeFailure() {
        val frame = FakeFrame()

        val result = FrameDecodeRunner.run(frame) { throw IllegalStateException("decoder failed") }

        assertEquals(DecodeOutcome.Failure, result)
        assertEquals(1, frame.closeCount)
    }

    @Test fun failedCloseIsReportedAsFailure() {
        val frame = FakeFrame(failOnClose = true)

        val result = FrameDecodeRunner.run(frame) { DecodeOutcome.NotFound }

        assertEquals(DecodeOutcome.Failure, result)
        assertEquals(1, frame.closeCount)
    }

    private class FakeFrame(private val failOnClose: Boolean = false) : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
            if (failOnClose) error("close failed")
        }
    }
}
