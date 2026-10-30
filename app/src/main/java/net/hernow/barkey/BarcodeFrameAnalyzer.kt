package net.hernow.barkey

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicBoolean

/** Decodes CameraX frames on its single analysis worker and never retains a frame. */
internal class BarcodeFrameAnalyzer(
    private val sessionId: Long,
    private val cameraGeneration: Long,
    private val modes: ScanModeController,
    private val decoder: BarcodeFrameDecoder,
    private val timing: DecodeTimingRecorder,
    private val previewEnabled: () -> Boolean = { false },
    private val focusEnabled: () -> Boolean = { false },
    private val scanCrop: (ImageProxy) -> Rect? = { null },
    private val settingsGeneration: () -> Long = { 0L },
    private val onDecoded: (sessionId: Long, cameraGeneration: Long, modeGeneration: Long, settingsGeneration: Long, values: List<String>) -> Unit,
    private val onFailure: (sessionId: Long, cameraGeneration: Long, modeGeneration: Long, settingsGeneration: Long) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reportedFailure = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        val snapshot = modes.snapshot()
        val settings = settingsGeneration()
        val candidates = previewEnabled()
        val focused = focusEnabled()
        val crop = scanCrop(image)
        val outcome = timing.measure {
            FrameDecodeRunner.run(image) {
                when (FrameDecodePolicy.request(candidates, focused, crop != null)) {
                    FrameDecodeRequest.FOCUS_UNAVAILABLE -> DecodeOutcome.NotFound
                    FrameDecodeRequest.MULTIPLE_CANDIDATES -> decoder.decodeCandidates(it, snapshot.mode, crop)
                    FrameDecodeRequest.FOCUSED_SINGLE -> decoder.decodeFocused(it, snapshot.mode, crop!!)
                    FrameDecodeRequest.NORMAL -> decoder.decode(it, snapshot.mode)
                }
            }
        }
        when (outcome) {
            is DecodeOutcome.Value -> onDecoded(sessionId, cameraGeneration, snapshot.generation, settings, listOf(outcome.text))
            is DecodeOutcome.Values -> onDecoded(sessionId, cameraGeneration, snapshot.generation, settings, outcome.texts)
            DecodeOutcome.Failure -> if (reportedFailure.compareAndSet(false, true)) {
                onFailure(sessionId, cameraGeneration, snapshot.generation, settings)
            }
            DecodeOutcome.NotFound -> if (candidates) {
                onDecoded(sessionId, cameraGeneration, snapshot.generation, settings, emptyList())
            }
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
