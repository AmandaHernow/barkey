package net.hernow.barkey

import kotlin.math.floor

/** Pure sizing decisions for the docked keyboard's measured window content. */
internal object ResponsiveKeyboardLayoutPolicy {
    data class HeightBounds(val minimumPx: Int, val maximumPx: Int)

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
        // On very short displays, use the full safe display height and let preview reach its
        // constrained fallback rather than clipping the editing controls below the IME window.
        val minimum = minOf(minContent, usable)
        return HeightBounds(minimum, maxOf(maximum, minimum).coerceAtMost(usable))
    }

    fun resizedHeight(currentHeightPx: Int, dragDistanceYpx: Int, bounds: HeightBounds): Int =
        (currentHeightPx - dragDistanceYpx).coerceIn(bounds.minimumPx, bounds.maximumPx)

    fun controlsFit(viewportHeightPx: Int, fixedContentHeightPx: Int, previewHeightPx: Int): Boolean =
        viewportHeightPx >= fixedContentHeightPx + previewHeightPx

    fun toolbarColumns(
        availableWidthPx: Int,
        density: Float,
        buttonMinimumDp: Int = 56,
        maxColumns: Int = 6,
    ): Int {
        if (availableWidthPx <= 0 || density <= 0f) return 1
        val minimumCellWidthDp = buttonMinimumDp + BUTTON_HORIZONTAL_MARGINS_DP
        return floor(availableWidthPx / (density * minimumCellWidthDp))
            .toInt()
            .coerceIn(1, maxColumns.coerceAtLeast(1))
    }

    fun previewHeight(
        viewportHeightPx: Int,
        fixedContentHeightPx: Int,
        minPreviewHeightPx: Int,
        maxPreviewHeightPx: Int,
    ): Int {
        if (minPreviewHeightPx <= 0 || maxPreviewHeightPx < minPreviewHeightPx) {
            return 1
        }
        if (viewportHeightPx <= 0) return minPreviewHeightPx
        val availableHeight = viewportHeightPx - fixedContentHeightPx
        return availableHeight.coerceIn(minPreviewHeightPx, maxPreviewHeightPx)
    }

    fun constrainedPreviewHeight(viewportHeightPx: Int, fixedContentHeightPx: Int, preferredMinPx: Int): Int =
        (viewportHeightPx - fixedContentHeightPx).coerceAtLeast(0).coerceAtMost(preferredMinPx)

    fun shouldShowDeveloperSignature(
        viewportHeightPx: Int,
        requiredHeightWithoutSignaturePx: Int,
        signatureHeightPx: Int,
    ): Boolean = viewportHeightPx >= requiredHeightWithoutSignaturePx + signatureHeightPx

    private const val BUTTON_HORIZONTAL_MARGINS_DP = 4
}

/** Service-lifetime height selection; callers keep this instance across input and camera changes. */
internal class KeyboardHeightState {
    private var selectedHeightPx: Int? = null
    private var manuallyResized = false

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

    fun useMeasuredDefault(heightPx: Int, bounds: ResponsiveKeyboardLayoutPolicy.HeightBounds): Int {
        if (manuallyResized) return height(bounds)
        val requested = heightPx.coerceIn(bounds.minimumPx, bounds.maximumPx)
        selectedHeightPx = requested
        return requested
    }

    fun resize(dragDistanceYpx: Int, bounds: ResponsiveKeyboardLayoutPolicy.HeightBounds): Int {
        val current = height(bounds)
        val resized = ResponsiveKeyboardLayoutPolicy.resizedHeight(current, dragDistanceYpx, bounds)
        if (resized != current) manuallyResized = true
        selectedHeightPx = resized
        return resized
    }

    fun isManuallyResized(): Boolean = manuallyResized
}
