package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanModeTest {
    @Test fun startsFastAndTogglesToAccurateAndBackWithMatchingLabels() {
        val modes = ScanModeController()
        val initial = modes.snapshot()
        assertEquals(ScanMode.FAST, initial.mode)
        assertEquals(R.string.scan_mode_fast, initial.mode.labelResId)

        val accurate = modes.toggle()
        assertEquals(ScanMode.ACCURATE, accurate.mode)
        assertEquals(R.string.scan_mode_accurate, accurate.mode.labelResId)
        assertTrue(modes.isCurrent(accurate.generation))

        val fastAgain = modes.toggle()
        assertEquals(ScanMode.FAST, fastAgain.mode)
        assertEquals(R.string.scan_mode_fast, fastAgain.mode.labelResId)
        assertFalse(modes.isCurrent(accurate.generation))
    }

    @Test fun modeSnapshotSurvivesLayoutOrOrientationWithoutBeingReset() {
        val modes = ScanModeController()
        val accurate = modes.toggle()

        // Layout changes do not mutate the service-owned mode controller.
        assertEquals(accurate, modes.snapshot())
        assertTrue(modes.isCurrent(accurate.generation))
    }
}
