package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveKeyboardLayoutPolicyTest {
    @Test fun keyboardMinimumIncludesMeasuredControlsFooterAndPreview() {
        val handle = 48
        val controls = 700
        val footer = 30
        val safeNavigationPadding = 24
        val fixedContentIncludingFooter = handle + controls + footer + safeNavigationPadding
        assertEquals(1_002, ResponsiveKeyboardLayoutPolicy.minimumKeyboardHeight(fixedContentIncludingFooter, 200))

        val bounds = ResponsiveKeyboardLayoutPolicy.heightBounds(
            usableDisplayHeightPx = 2_000,
            measuredFixedContentHeightPx = fixedContentIncludingFooter,
            minimumPreviewHeightPx = 200,
            leaveApplicationVisiblePx = 360,
        )
        assertEquals(1_002, bounds.minimumPx)
        assertEquals(1_500, bounds.maximumPx)
        assertTrue(ResponsiveKeyboardLayoutPolicy.controlsFit(bounds.minimumPx, fixedContentIncludingFooter, 200))
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

    @Test fun bottomAnchoredGeometryMovesOnlyTheTopEdge() {
        val compact = ResponsiveKeyboardLayoutPolicy.keyboardVerticalBounds(displayBottomPx = 2_400, keyboardHeightPx = 1_000)
        val expanded = ResponsiveKeyboardLayoutPolicy.keyboardVerticalBounds(displayBottomPx = 2_400, keyboardHeightPx = 1_400)
        assertEquals(1_400, compact.topPx)
        assertEquals(1_000, expanded.topPx)
        assertEquals(2_400, compact.bottomPx)
        assertEquals(compact.bottomPx, expanded.bottomPx)
    }

    @Test fun navigationInsetIsCountedInContentPaddingNotSubtractedFromImeBottom() {
        val displayHeight = 2_400
        val statusAndTopCutout = 72
        val gestureNavigationPadding = 72
        val usableImeHeight = ResponsiveKeyboardLayoutPolicy.imeAvailableHeight(displayHeight, statusAndTopCutout)
        val measuredFixedContent = 1_000 + gestureNavigationPadding
        val bounds = ResponsiveKeyboardLayoutPolicy.heightBounds(usableImeHeight, measuredFixedContent, 200, 100)
        assertEquals(2_328, usableImeHeight)
        assertEquals(1_272, bounds.minimumPx)
        assertEquals(1_746, bounds.maximumPx)
    }

    @Test fun manuallyResizedPreviewUsesAllExtraHeightAndShrinksFirst() {
        val expanded = ResponsiveKeyboardLayoutPolicy.manuallyResizedPreviewHeight(900, 400)
        val compact = ResponsiveKeyboardLayoutPolicy.manuallyResizedPreviewHeight(430, 400)
        assertEquals(500, expanded) // Grows beyond the automatic 380px preview cap.
        assertEquals(30, compact)
        repeat(6) {
            assertEquals(500, ResponsiveKeyboardLayoutPolicy.manuallyResizedPreviewHeight(900, 400))
        }
    }

    @Test fun selectedHeightPersistsAcrossLifecycleAndClampsAfterOrientationChange() {
        val state = KeyboardHeightState()
        val portrait = ResponsiveKeyboardLayoutPolicy.HeightBounds(400, 1_200)
        assertEquals(400, state.height(portrait))
        assertEquals(700, state.resizeFromDrag(400, -300, portrait))

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
