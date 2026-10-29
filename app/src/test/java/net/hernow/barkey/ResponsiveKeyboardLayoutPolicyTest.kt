package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsiveKeyboardLayoutPolicyTest {
    @Test fun toolbarRowsNeverAssumeTabletWidth() {
        assertEquals(2, ResponsiveKeyboardLayoutPolicy.toolbarColumns(390, 3f))
        assertEquals(4, ResponsiveKeyboardLayoutPolicy.toolbarColumns(500, 2.5f))
        assertEquals(1, ResponsiveKeyboardLayoutPolicy.toolbarColumns(120, 3f))
    }

    @Test fun previewSizingIsStableAcrossRepeatedLayoutPasses() {
        val repeatedHeight = List(8) {
            ResponsiveKeyboardLayoutPolicy.previewHeight(700, 480, 100, 400)
        }
        assertEquals(List(8) { 220 }, repeatedHeight)
    }

    @Test fun previewTracksViewportAndOrientationChangesWithoutOscillating() {
        val portrait = ResponsiveKeyboardLayoutPolicy.previewHeight(1100, 650, 120, 380)
        val landscape = ResponsiveKeyboardLayoutPolicy.previewHeight(600, 400, 120, 380)
        val shortLandscape = ResponsiveKeyboardLayoutPolicy.previewHeight(400, 340, 120, 380)
        val rotatedBack = ResponsiveKeyboardLayoutPolicy.previewHeight(1100, 650, 120, 380)

        assertEquals(380, portrait)
        assertEquals(200, landscape)
        assertEquals(120, shortLandscape)
        assertEquals(portrait, rotatedBack)
        repeat(5) {
            assertEquals(200, ResponsiveKeyboardLayoutPolicy.previewHeight(600, 400, 120, 380))
        }
    }

    @Test fun controlsChangingHeightDoesNotDependOnPreviousPreviewHeight() {
        val withPermission = ResponsiveKeyboardLayoutPolicy.previewHeight(700, 560, 120, 380)
        val withoutPermission = ResponsiveKeyboardLayoutPolicy.previewHeight(700, 500, 120, 380)
        assertEquals(140, withPermission)
        assertEquals(200, withoutPermission)
    }
}
