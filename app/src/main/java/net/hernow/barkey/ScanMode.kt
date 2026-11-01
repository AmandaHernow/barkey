package net.hernow.barkey

import java.util.concurrent.atomic.AtomicReference

/** Fast favours speed; Accurate gives difficult codes more time. */
internal enum class ScanMode(
    val labelResId: Int,
    val descriptionResId: Int,
) {
    FAST(R.string.scan_mode_fast, R.string.scan_mode_fast_accessibility),
    ACCURATE(R.string.scan_mode_accurate, R.string.scan_mode_accurate_accessibility),
}

/** Keeps the selected mode with the version used to reject old frame results. */
internal data class ScanModeSnapshot(val mode: ScanMode, val generation: Long)

/** Keeps mode and generation together so every frame uses one immutable selection. */
internal class ScanModeController {
    private val state = AtomicReference(ScanModeSnapshot(ScanMode.FAST, 0L))

    /** Reads the mode and generation together for one camera frame. */
    fun snapshot(): ScanModeSnapshot = state.get()

    /** Changes modes and invalidates results started under the previous mode. */
    fun toggle(): ScanModeSnapshot = state.updateAndGet { current ->
        ScanModeSnapshot(
            mode = if (current.mode == ScanMode.FAST) ScanMode.ACCURATE else ScanMode.FAST,
            generation = current.generation + 1,
        )
    }

    /** Drops a decode result if the mode changed while it was running. */
    fun isCurrent(generation: Long): Boolean = state.get().generation == generation
}
