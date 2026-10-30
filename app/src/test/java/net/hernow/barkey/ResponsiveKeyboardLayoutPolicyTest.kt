package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveKeyboardLayoutPolicyTest {
    private val tabletContent = ResponsiveKeyboardLayoutPolicy.InputViewContentHeights(
        permanentContentHeightPx = 180, // Handle, signature, root padding and safe insets.
        scrollableControlsHeightPx = 520, // Status, permission action, editing rows and footer buttons.
    )

    @Test fun measuredInputViewMinimumIncludesFixedControlsSignatureInsetsAndPreview() {
        val minimum = ResponsiveKeyboardLayoutPolicy.minimumInputViewHeight(tabletContent, 120)
        assertEquals(820, minimum)

        val bounds = ResponsiveKeyboardLayoutPolicy.heightBounds(
            usableDisplayHeightPx = 2_000,
            measuredFixedContentHeightPx = tabletContent.fixedContentHeightPx,
            minimumPreviewHeightPx = 120,
            leaveApplicationVisiblePx = 300,
        )
        val plan = ResponsiveKeyboardLayoutPolicy.inputViewLayoutPlan(
            requestedInputViewHeightPx = bounds.minimumPx,
            bounds = bounds,
            content = tabletContent,
            minimumPreviewHeightPx = 120,
            automaticPreviewMaximumPx = 280,
            manuallyResized = false,
        )
        assertEquals(minimum, plan.inputViewHeightPx)
        assertEquals(120, plan.previewHeightPx)
    }

    @Test fun attachedInputViewHeightAndPreviewPlanUseTheSameClampedTarget() {
        val bounds = ResponsiveKeyboardLayoutPolicy.HeightBounds(820, 1_400)
        val expanded = ResponsiveKeyboardLayoutPolicy.inputViewLayoutPlan(
            requestedInputViewHeightPx = 1_250,
            bounds = bounds,
            content = tabletContent,
            minimumPreviewHeightPx = 120,
            automaticPreviewMaximumPx = 280,
            manuallyResized = true,
        )
        assertEquals(1_250, expanded.inputViewHeightPx)
        assertEquals(550, expanded.previewHeightPx)

        val clamped = ResponsiveKeyboardLayoutPolicy.inputViewLayoutPlan(
            requestedInputViewHeightPx = 2_000,
            bounds = bounds,
            content = tabletContent,
            minimumPreviewHeightPx = 120,
            automaticPreviewMaximumPx = 280,
            manuallyResized = true,
        )
        assertEquals(1_400, clamped.inputViewHeightPx)
        assertEquals(700, clamped.previewHeightPx)
    }

    @Test fun manuallyExpandedPreviewUsesExtraHeightBeyondAutomaticCap() {
        val bounds = ResponsiveKeyboardLayoutPolicy.HeightBounds(820, 1_500)
        val requestedHeight = 1_300
        val automatic = ResponsiveKeyboardLayoutPolicy.inputViewLayoutPlan(
            requestedHeight, bounds, tabletContent, 120, 280, manuallyResized = false,
        )
        val manual = ResponsiveKeyboardLayoutPolicy.inputViewLayoutPlan(
            requestedHeight, bounds, tabletContent, 120, 280, manuallyResized = true,
        )
        assertEquals(280, automatic.previewHeightPx)
        assertEquals(600, manual.previewHeightPx)
        assertEquals(requestedHeight, manual.inputViewHeightPx)
    }

    @Test fun visibleCameraPermissionControlIsIncludedInMeasuredMinimum() {
        val withoutPermission = ResponsiveKeyboardLayoutPolicy.minimumInputViewHeight(tabletContent, 120)
        val permissionVisible = tabletContent.copy(scrollableControlsHeightPx = tabletContent.scrollableControlsHeightPx + 52)
        val withPermission = ResponsiveKeyboardLayoutPolicy.minimumInputViewHeight(permissionVisible, 120)
        assertEquals(52, withPermission - withoutPermission)
    }

    @Test fun constrainedDisplayKeepsPreviewVisibleAndScrollsControlsBeforeCollapsingIt() {
        val content = ResponsiveKeyboardLayoutPolicy.InputViewContentHeights(
            permanentContentHeightPx = 120,
            scrollableControlsHeightPx = 500,
        )
        val bounds = ResponsiveKeyboardLayoutPolicy.heightBounds(
            usableDisplayHeightPx = 420,
            measuredFixedContentHeightPx = content.fixedContentHeightPx,
            minimumPreviewHeightPx = 80,
            leaveApplicationVisiblePx = 100,
        )
        assertEquals(315, bounds.minimumPx)
        assertEquals(315, bounds.maximumPx)

        val plan = ResponsiveKeyboardLayoutPolicy.inputViewLayoutPlan(
            bounds.minimumPx, bounds, content, 80, 280, manuallyResized = false,
        )
        val controlsViewport = plan.inputViewHeightPx - content.permanentContentHeightPx - plan.previewHeightPx
        assertEquals(80, plan.previewHeightPx)
        assertTrue(controlsViewport in 1 until content.scrollableControlsHeightPx)
    }

    @Test fun previewPlanDoesNotDependOnPreviousClippedPreviewOrRepeatedLayoutPasses() {
        val bounds = ResponsiveKeyboardLayoutPolicy.HeightBounds(820, 1_500)
        val targetHeight = 1_100
        val plans = List(8) {
            ResponsiveKeyboardLayoutPolicy.inputViewLayoutPlan(
                targetHeight, bounds, tabletContent, 120, 280, manuallyResized = true,
            )
        }
        assertEquals(List(8) { 400 }, plans.map { it.previewHeightPx })
        assertEquals(List(8) { targetHeight }, plans.map { it.inputViewHeightPx })
    }

    @Test fun maximumHeightLeavesApplicationVisibleAndClampsImpossibleMinimum() {
        val normal = ResponsiveKeyboardLayoutPolicy.heightBounds(
            usableDisplayHeightPx = 1_000,
            measuredFixedContentHeightPx = 400,
            minimumPreviewHeightPx = 100,
            leaveApplicationVisiblePx = 200,
        )
        assertEquals(500, normal.minimumPx)
        assertEquals(750, normal.maximumPx)

        val constrained = ResponsiveKeyboardLayoutPolicy.heightBounds(
            usableDisplayHeightPx = 500,
            measuredFixedContentHeightPx = 420,
            minimumPreviewHeightPx = 100,
            leaveApplicationVisiblePx = 150,
        )
        assertEquals(350, constrained.minimumPx)
        assertEquals(350, constrained.maximumPx)
    }

    @Test fun dragDirectionAndDistanceClampTheInputViewHeight() {
        val bounds = ResponsiveKeyboardLayoutPolicy.HeightBounds(400, 900)
        assertEquals(550, ResponsiveKeyboardLayoutPolicy.resizedHeight(500, -50, bounds))
        assertEquals(450, ResponsiveKeyboardLayoutPolicy.resizedHeight(500, 50, bounds))
        assertEquals(900, ResponsiveKeyboardLayoutPolicy.resizedHeight(850, -100, bounds))
        assertEquals(400, ResponsiveKeyboardLayoutPolicy.resizedHeight(450, 100, bounds))
    }

    @Test fun topAndSideInsetsReduceAvailableHeightWhileBottomInsetIsReservedAsContentOnce() {
        val usableWindowHeight = ResponsiveKeyboardLayoutPolicy.imeAvailableHeight(
            displayHeightPx = 2_400,
            topInsetPx = 72,
        )
        val content = ResponsiveKeyboardLayoutPolicy.InputViewContentHeights(
            permanentContentHeightPx = 180 + 72, // Includes one delivered bottom-safe padding.
            scrollableControlsHeightPx = 520,
        )
        val minimum = ResponsiveKeyboardLayoutPolicy.minimumInputViewHeight(content, 120)
        val bounds = ResponsiveKeyboardLayoutPolicy.heightBounds(
            usableWindowHeight, content.fixedContentHeightPx, 120, leaveApplicationVisiblePx = 200,
        )
        assertEquals(2_328, usableWindowHeight)
        assertEquals(892, minimum)
        assertEquals(minimum, bounds.minimumPx)
    }

    @Test fun selectedHeightSurvivesServiceLifecycleAndClampsWhenOrientationShrinks() {
        val state = KeyboardHeightState()
        val portrait = ResponsiveKeyboardLayoutPolicy.HeightBounds(820, 1_500)
        assertEquals(820, state.height(portrait))
        assertEquals(1_120, state.resizeFromDrag(820, -300, portrait))

        // Camera, Help, input-field and repeated measurement callbacks do not reset this state.
        assertEquals(1_120, state.height(portrait))
        assertEquals(1_120, state.useMeasuredDefault(900, portrait))
        assertEquals(600, state.height(ResponsiveKeyboardLayoutPolicy.HeightBounds(500, 600)))
        assertTrue(state.isManuallyResized())
    }

    @Test fun toolbarRowsAdaptToWindowWidthAndFontScale() {
        assertEquals(2, ResponsiveKeyboardLayoutPolicy.toolbarColumns(390, 3f))
        assertEquals(3, ResponsiveKeyboardLayoutPolicy.toolbarColumns(500, 2.5f))
        assertEquals(1, ResponsiveKeyboardLayoutPolicy.toolbarColumns(120, 3f))
        assertEquals(6, ResponsiveKeyboardLayoutPolicy.toolbarColumns(2_400, 2f))
    }

    @Test fun toolbarLayoutCoversPhoneAndTabletWidthsWithoutWideEditingButtons() {
        val narrowPhone = ResponsiveKeyboardLayoutPolicy.toolbarLayoutPlan(
            availableWidthPx = dp(264, 2.5f), density = 2.5f, fontScale = 1f,
        )
        assertEquals(4, narrowPhone.editingColumns)
        assertEquals(dp(264, 2.5f), narrowPhone.controlGroupWidthPx)
        assertEquals(dp(264, 2.5f), narrowPhone.editingRowWidthPx)
        assertEquals(dp(176, 2.5f), ResponsiveKeyboardLayoutPolicy.toolbarRowWidth(narrowPhone, 2))

        val normalPhone = ResponsiveKeyboardLayoutPolicy.toolbarLayoutPlan(
            availableWidthPx = dp(374, 3f), density = 3f, fontScale = 1f,
        )
        assertEquals(6, normalPhone.editingColumns)
        assertEquals(dp(374, 3f), normalPhone.editingRowWidthPx)

        val landscapePhone = ResponsiveKeyboardLayoutPolicy.toolbarLayoutPlan(
            availableWidthPx = dp(820, 2f), density = 2f, fontScale = 1f,
        )
        assertEquals(6, landscapePhone.editingColumns)
        assertEquals(dp(528, 2f), landscapePhone.editingRowWidthPx)

        val tabletLandscape = ResponsiveKeyboardLayoutPolicy.toolbarLayoutPlan(
            availableWidthPx = dp(1_260, 2f), density = 2f, fontScale = 1f,
        )
        assertEquals(dp(760, 2f), tabletLandscape.controlGroupWidthPx)
        assertEquals(dp(760, 2f), tabletLandscape.bottomRowWidthPx)
        assertEquals(dp(528, 2f), tabletLandscape.editingRowWidthPx)
        assertEquals(6, tabletLandscape.editingColumns)

        val tabletPortrait = ResponsiveKeyboardLayoutPolicy.toolbarLayoutPlan(
            availableWidthPx = dp(784, 2f), density = 2f, fontScale = 1f,
        )
        assertEquals(dp(760, 2f), tabletPortrait.controlGroupWidthPx)
        assertEquals(tabletLandscape, tabletPortrait)
    }

    @Test fun largerFontScaleWrapsEditingButtonsBeforeLabelsBecomeTooNarrow() {
        val defaultText = ResponsiveKeyboardLayoutPolicy.toolbarLayoutPlan(
            availableWidthPx = dp(374, 3f), density = 3f, fontScale = 1f,
        )
        val largeText = ResponsiveKeyboardLayoutPolicy.toolbarLayoutPlan(
            availableWidthPx = dp(374, 3f), density = 3f, fontScale = 1.5f,
        )
        assertEquals(6, defaultText.editingColumns)
        assertEquals(4, largeText.editingColumns)
        assertEquals(dp(374, 3f), largeText.editingRowWidthPx)
        assertEquals(dp(260, 3f), ResponsiveKeyboardLayoutPolicy.toolbarRowWidth(largeText, 2))
    }

    @Test fun repeatedToolbarMeasurementsProduceTheSamePlanAndDoNotRequireRebuildingRows() {
        val plans = List(10) {
            ResponsiveKeyboardLayoutPolicy.toolbarLayoutPlan(
                availableWidthPx = dp(1_260, 2f), density = 2f, fontScale = 1.25f,
            )
        }
        assertTrue(plans.all { it == plans.first() })
        assertEquals(6, plans.first().editingColumns)
        assertEquals(dp(760, 2f), plans.first().bottomRowWidthPx)
    }

    @Test fun compactEditingAndBottomRowsReduceMinimumHeightWithoutReducingPreviewFloor() {
        val previousRows = ResponsiveKeyboardLayoutPolicy.InputViewContentHeights(
            permanentContentHeightPx = 180,
            scrollableControlsHeightPx = 52 * 3,
        )
        val compactRows = ResponsiveKeyboardLayoutPolicy.InputViewContentHeights(
            permanentContentHeightPx = 180,
            scrollableControlsHeightPx = 52 * 2,
        )
        val previousMinimum = ResponsiveKeyboardLayoutPolicy.minimumInputViewHeight(previousRows, 120)
        val compactMinimum = ResponsiveKeyboardLayoutPolicy.minimumInputViewHeight(compactRows, 120)
        assertEquals(52, previousMinimum - compactMinimum)

        val bounds = ResponsiveKeyboardLayoutPolicy.heightBounds(
            usableDisplayHeightPx = 2_000,
            measuredFixedContentHeightPx = compactRows.fixedContentHeightPx,
            minimumPreviewHeightPx = 120,
            leaveApplicationVisiblePx = 300,
        )
        val compactPlan = ResponsiveKeyboardLayoutPolicy.inputViewLayoutPlan(
            requestedInputViewHeightPx = compactMinimum,
            bounds = bounds,
            content = compactRows,
            minimumPreviewHeightPx = 120,
            automaticPreviewMaximumPx = 280,
            manuallyResized = false,
        )
        assertEquals(120, compactPlan.previewHeightPx)
    }

    private fun dp(value: Int, density: Float): Int = (value * density).toInt()
}
