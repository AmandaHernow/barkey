package net.hernow.barkey

import kotlin.math.floor

/** Pure sizing decisions for the docked keyboard's measured window content. */
internal object ResponsiveKeyboardLayoutPolicy {
    data class HeightBounds(val minimumPx: Int, val maximumPx: Int)
    data class InputViewContentHeights(
        val permanentContentHeightPx: Int,
        val scrollableControlsHeightPx: Int,
    ) {
        val fixedContentHeightPx: Int
            get() = permanentContentHeightPx.coerceAtLeast(0) + scrollableControlsHeightPx.coerceAtLeast(0)
    }
    data class InputViewLayoutPlan(val inputViewHeightPx: Int, val previewHeightPx: Int)
    data class ToolbarLayoutPlan(
        val controlGroupWidthPx: Int,
        val bottomRowWidthPx: Int,
        val editingColumns: Int,
        val editingRowWidthPx: Int,
        val editingCellWidthPx: Int,
    )

    /** Excludes the top system inset from the height available to the IME. */
    fun imeAvailableHeight(displayHeightPx: Int, topInsetPx: Int): Int =
        (displayHeightPx - topInsetPx).coerceAtLeast(1)

    /** Keeps fixed controls visible above the smallest allowed preview. */
    fun minimumInputViewHeight(content: InputViewContentHeights, minimumPreviewHeightPx: Int): Int =
        (content.fixedContentHeightPx + minimumPreviewHeightPx.coerceAtLeast(0)).coerceAtLeast(1)

    /** Limits the keyboard while leaving part of the host app visible. */
    fun heightBounds(
        usableDisplayHeightPx: Int,
        measuredFixedContentHeightPx: Int,
        minimumPreviewHeightPx: Int,
        leaveApplicationVisiblePx: Int,
        maximumFraction: Float = 0.75f,
    ): HeightBounds {
        val usable = usableDisplayHeightPx.coerceAtLeast(1)
        val minContent = (measuredFixedContentHeightPx + minimumPreviewHeightPx).coerceAtLeast(1)
        val maximum = minOf(
            (usable * maximumFraction.coerceIn(0f, 1f)).toInt().coerceAtLeast(1),
            (usable - leaveApplicationVisiblePx).coerceAtLeast(1),
        )
        // If all controls plus a preview cannot fit while leaving application space, clamp to the
        // real maximum and let the controls ScrollView provide access to overflow content.
        val boundedMaximum = maximum.coerceAtMost(usable)
        val minimum = minOf(minContent, boundedMaximum)
        return HeightBounds(minimum, boundedMaximum)
    }

    /** Converts a drag distance to a height inside the measured limits. */
    fun resizedHeight(currentHeightPx: Int, dragDistanceYpx: Int, bounds: HeightBounds): Int =
        (currentHeightPx - dragDistanceYpx).coerceIn(bounds.minimumPx, bounds.maximumPx)

    /** Checks whether controls and preview fit without scrolling. */
    fun controlsFit(viewportHeightPx: Int, fixedContentHeightPx: Int, previewHeightPx: Int): Boolean =
        viewportHeightPx >= fixedContentHeightPx + previewHeightPx

    /** Fits as many editing buttons as possible without making them too narrow. */
    fun toolbarColumns(
        availableWidthPx: Int,
        density: Float,
        buttonMinimumDp: Int = 52,
        fontScale: Float = 1f,
        maxColumns: Int = 6,
    ): Int {
        if (availableWidthPx <= 0 || density <= 0f) return 1
        val minimumCellWidthDp = buttonMinimumDp * fontScale.coerceAtLeast(1f) + BUTTON_HORIZONTAL_MARGINS_DP
        return floor(availableWidthPx / (density * minimumCellWidthDp))
            .toInt()
            .coerceIn(1, maxColumns.coerceAtLeast(1))
    }

    /** Keeps the editing group centred and capped on wide screens. */
    fun toolbarLayoutPlan(
        availableWidthPx: Int,
        density: Float,
        fontScale: Float,
        maxControlWidthDp: Int = MAX_CONTROL_WIDTH_DP,
        minimumButtonWidthDp: Int = MINIMUM_EDITING_BUTTON_WIDTH_DP,
        preferredCellWidthDp: Int = PREFERRED_EDITING_CELL_WIDTH_DP,
        maxColumns: Int = 6,
    ): ToolbarLayoutPlan {
        if (availableWidthPx <= 0 || density <= 0f) {
            return ToolbarLayoutPlan(1, 1, 1, 1, 1)
        }
        val controlGroupWidth = minOf(availableWidthPx, (maxControlWidthDp * density).toInt()).coerceAtLeast(1)
        val columns = toolbarColumns(
            availableWidthPx = controlGroupWidth,
            density = density,
            buttonMinimumDp = minimumButtonWidthDp,
            fontScale = fontScale,
            maxColumns = maxColumns,
        )
        val scale = fontScale.coerceAtLeast(1f)
        val minimumCellWidth = ((minimumButtonWidthDp * scale) + BUTTON_HORIZONTAL_MARGINS_DP) * density
        val preferredCellWidth = ((preferredCellWidthDp * scale) + BUTTON_HORIZONTAL_MARGINS_DP) * density
        val editingCellWidth = preferredCellWidth.coerceAtLeast(minimumCellWidth).toInt().coerceAtLeast(1)
        val editingRowWidth = minOf(controlGroupWidth, editingCellWidth * columns).coerceAtLeast(1)
        return ToolbarLayoutPlan(controlGroupWidth, controlGroupWidth, columns, editingRowWidth, editingCellWidth)
    }

    /** Sizes each row to its buttons rather than stretching a partial row. */
    fun toolbarRowWidth(plan: ToolbarLayoutPlan, buttonCount: Int): Int =
        minOf(plan.controlGroupWidthPx, plan.editingCellWidthPx * buttonCount.coerceAtLeast(1)).coerceAtLeast(1)

    /** Gives extra height to the preview after fixed controls are measured. */
    fun inputViewLayoutPlan(
        requestedInputViewHeightPx: Int,
        bounds: HeightBounds,
        content: InputViewContentHeights,
        minimumPreviewHeightPx: Int,
        automaticPreviewMaximumPx: Int,
        manuallyResized: Boolean,
    ): InputViewLayoutPlan {
        val inputHeight = requestedInputViewHeightPx.coerceIn(bounds.minimumPx, bounds.maximumPx)
        val remainingAfterPermanentContent = inputHeight - content.permanentContentHeightPx.coerceAtLeast(0)
        val spaceAfterControls = remainingAfterPermanentContent - content.scrollableControlsHeightPx.coerceAtLeast(0)
        val previewHeight = when {
            remainingAfterPermanentContent <= 0 -> 0
            spaceAfterControls < minimumPreviewHeightPx ->
                minOf(minimumPreviewHeightPx, remainingAfterPermanentContent)
            manuallyResized -> spaceAfterControls
            else -> spaceAfterControls.coerceAtMost(automaticPreviewMaximumPx.coerceAtLeast(minimumPreviewHeightPx))
        }
        return InputViewLayoutPlan(inputHeight, previewHeight.coerceAtLeast(0))
    }

    private const val BUTTON_HORIZONTAL_MARGINS_DP = 4
    const val MAX_CONTROL_WIDTH_DP = 760
    private const val MINIMUM_EDITING_BUTTON_WIDTH_DP = 52
    private const val PREFERRED_EDITING_CELL_WIDTH_DP = 84
}

/** Service-lifetime height selection; callers keep this instance across input and camera changes. */
internal class KeyboardHeightState {
    private var selectedHeightPx: Int? = null
    private var manuallyResized = false

    /** Returns the selected height clamped to the current display bounds. */
    fun height(bounds: ResponsiveKeyboardLayoutPolicy.HeightBounds): Int {
        val selected = selectedHeightPx
        if (selected == null) {
            selectedHeightPx = bounds.minimumPx
            return bounds.minimumPx
        }
        val clamped = selected.coerceIn(bounds.minimumPx, bounds.maximumPx)
        selectedHeightPx = clamped
        return clamped
    }

    /** Uses the measured height until the user has resized the keyboard. */
    fun useMeasuredDefault(heightPx: Int, bounds: ResponsiveKeyboardLayoutPolicy.HeightBounds): Int {
        if (manuallyResized) return height(bounds)
        val requested = heightPx.coerceIn(bounds.minimumPx, bounds.maximumPx)
        selectedHeightPx = requested
        return requested
    }

    /** Saves a user drag and keeps the selected height within the current bounds. */
    fun resizeFromDrag(
        dragStartHeightPx: Int,
        totalDragDistanceYpx: Int,
        bounds: ResponsiveKeyboardLayoutPolicy.HeightBounds,
    ): Int {
        val resized = ResponsiveKeyboardLayoutPolicy.resizedHeight(
            dragStartHeightPx,
            totalDragDistanceYpx,
            bounds,
        )
        if (resized != dragStartHeightPx) manuallyResized = true
        selectedHeightPx = resized
        return resized
    }

    /** Lets layout choose between its default preview size and the user's size. */
    fun isManuallyResized(): Boolean = manuallyResized
}
