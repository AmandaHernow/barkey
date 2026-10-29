package net.hernow.barkey

import java.util.concurrent.atomic.AtomicReference

internal enum class ScanMode(
    val labelResId: Int,
    val descriptionResId: Int,
) {
    FAST(R.string.scan_mode_fast, R.string.scan_mode_fast_accessibility),
    ACCURATE(R.string.scan_mode_accurate, R.string.scan_mode_accurate_accessibility),
}

internal data class ScanModeSnapshot(val mode: ScanMode, val generation: Long)

/** Keeps mode and generation together so every frame uses one immutable selection. */
internal class ScanModeController {
    private val state = AtomicReference(ScanModeSnapshot(ScanMode.FAST, 0L))

    fun snapshot(): ScanModeSnapshot = state.get()

    fun toggle(): ScanModeSnapshot = state.updateAndGet { current ->
        ScanModeSnapshot(
            mode = if (current.mode == ScanMode.FAST) ScanMode.ACCURATE else ScanMode.FAST,
            generation = current.generation + 1,
        )
    }

    fun isCurrent(generation: Long): Boolean = state.get().generation == generation
}
