package net.hernow.barkey

import kotlin.math.floor

/** Pure sizing decisions for the docked keyboard's measured window content. */
internal object ResponsiveKeyboardLayoutPolicy {
    fun toolbarColumns(availableWidthPx: Int, density: Float, buttonMinimumDp: Int = 48): Int {
        if (availableWidthPx <= 0 || density <= 0f) return 1
        return floor(availableWidthPx / (density * buttonMinimumDp)).toInt().coerceAtLeast(1)
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
}
