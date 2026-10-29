package net.hernow.barkey

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

/** Decodes CameraX frames on its single analysis worker and never retains a frame. */
internal class BarcodeFrameAnalyzer(
    private val sessionId: Long,
    private val cameraGeneration: Long,
    private val modes: ScanModeController,
    private val decoder: BarcodeFrameDecoder,
    private val timing: DecodeTimingRecorder,
    private val onDecoded: (sessionId: Long, cameraGeneration: Long, modeGeneration: Long, value: String) -> Unit,
    private val onFailure: (sessionId: Long, cameraGeneration: Long, modeGeneration: Long) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reportedFailure = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        val snapshot = modes.snapshot()
        val outcome = timing.measure {
            FrameDecodeRunner.run(image) { frame -> decoder.decode(frame, snapshot.mode) }
        }
        when (outcome) {
            is DecodeOutcome.Value -> onDecoded(sessionId, cameraGeneration, snapshot.generation, outcome.text)
            DecodeOutcome.Failure -> if (reportedFailure.compareAndSet(false, true)) {
                onFailure(sessionId, cameraGeneration, snapshot.generation)
            }
            DecodeOutcome.NotFound -> Unit
        }
    }
}

/** Owns closing the frame exactly once, even if decoding or close itself fails. */
internal object FrameDecodeRunner {
    fun <T : AutoCloseable> run(frame: T, decode: (T) -> DecodeOutcome): DecodeOutcome {
        var decoded: DecodeOutcome = DecodeOutcome.Failure
        var fatalError: Throwable? = null
        try {
            decoded = decode(frame)
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) fatalError = error
        }
        val closeFailed = try {
            frame.close()
            false
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) {
                if (fatalError == null) fatalError = error
            }
            true
        }
        fatalError?.let { throw it }
        return if (closeFailed) DecodeOutcome.Failure else decoded
    }
}
