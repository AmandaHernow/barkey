package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveKeyboardLayoutPolicyTest {
    @Test fun keyboardMinimumComesFromMeasuredControlsAndPreview() {
        val bounds = ResponsiveKeyboardLayoutPolicy.heightBounds(
            usableDisplayHeightPx = 2_000,
            measuredFixedContentHeightPx = 780,
            minimumPreviewHeightPx = 240,
            leaveApplicationVisiblePx = 360,
        )
        assertEquals(1_020, bounds.minimumPx)
        assertEquals(1_500, bounds.maximumPx)
        assertTrue(ResponsiveKeyboardLayoutPolicy.controlsFit(bounds.minimumPx, 780, 240))
    }

    @Test fun maximumHeightHonoursDisplayBoundsAndLeavesApplicationRoom() {
        val bounds = ResponsiveKeyboardLayoutPolicy.heightBounds(usableDisplayHeightPx = 1_000,
            measuredFixedContentHeightPx = 300, minimumPreviewHeightPx = 100,
            leaveApplicationVisiblePx = 200)
        assertEquals(400, bounds.minimumPx)
        assertEquals(750, bounds.maximumPx)

        val constrained = ResponsiveKeyboardLayoutPolicy.heightBounds(500, 420, 100, 150)
        assertEquals(500, constrained.minimumPx)
        assertEquals(500, constrained.maximumPx)
    }

    @Test fun dragDirectionDistanceAndBoundsAreAppliedToRealWindowHeight() {
        val bounds = ResponsiveKeyboardLayoutPolicy.HeightBounds(400, 900)
        assertEquals(550, ResponsiveKeyboardLayoutPolicy.resizedHeight(500, -50, bounds))
        assertEquals(450, ResponsiveKeyboardLayoutPolicy.resizedHeight(500, 50, bounds))
        assertEquals(900, ResponsiveKeyboardLayoutPolicy.resizedHeight(850, -100, bounds))
        assertEquals(400, ResponsiveKeyboardLayoutPolicy.resizedHeight(450, 100, bounds))
    }

    @Test fun selectedHeightPersistsAcrossLifecycleAndClampsAfterOrientationChange() {
        val state = KeyboardHeightState()
        val portrait = ResponsiveKeyboardLayoutPolicy.HeightBounds(400, 1_200)
        assertEquals(400, state.height(portrait))
        assertEquals(700, state.resize(-300, portrait))

        // Input/camera/help lifecycle changes do not own or reset service-lifetime height state.
        assertEquals(700, state.height(portrait))
        assertEquals(550, state.height(ResponsiveKeyboardLayoutPolicy.HeightBounds(350, 550)))
        assertTrue(state.isManuallyResized())
    }

    @Test fun tinyLandscapeConstrainedPreviewNeverClaimsTheControlsFit() {
        assertEquals(0, ResponsiveKeyboardLayoutPolicy.constrainedPreviewHeight(280, 300, 80))
        assertEquals(false, ResponsiveKeyboardLayoutPolicy.controlsFit(280, 300, 0))
    }

    @Test fun toolbarRowsNeverAssumeTabletWidth() {
        assertEquals(2, ResponsiveKeyboardLayoutPolicy.toolbarColumns(390, 3f))
        assertEquals(3, ResponsiveKeyboardLayoutPolicy.toolbarColumns(500, 2.5f))
        assertEquals(1, ResponsiveKeyboardLayoutPolicy.toolbarColumns(120, 3f))
    }

    @Test fun toolbarKeepsSixBalancedColumnsWhenAvailableAndWrapsOnNarrowScreens() {
        assertEquals(6, ResponsiveKeyboardLayoutPolicy.toolbarColumns(1080, 3f))
        assertEquals(5, ResponsiveKeyboardLayoutPolicy.toolbarColumns(1032, 3f))
        assertEquals(6, ResponsiveKeyboardLayoutPolicy.toolbarColumns(2400, 2f))
    }

    @Test fun developerSignatureYieldsWhenItWouldCrowdFunctionalContent() {
        assertEquals(true, ResponsiveKeyboardLayoutPolicy.shouldShowDeveloperSignature(500, 460, 40))
        assertEquals(false, ResponsiveKeyboardLayoutPolicy.shouldShowDeveloperSignature(499, 460, 40))
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
