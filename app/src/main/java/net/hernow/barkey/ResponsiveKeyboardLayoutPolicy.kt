package net.hernow.barkey

import kotlin.math.floor

/** Pure sizing decisions for the docked keyboard's measured window content. */
internal object ResponsiveKeyboardLayoutPolicy {
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

    fun shouldShowDeveloperSignature(
        viewportHeightPx: Int,
        requiredHeightWithoutSignaturePx: Int,
        signatureHeightPx: Int,
    ): Boolean = viewportHeightPx >= requiredHeightWithoutSignaturePx + signatureHeightPx

    private const val BUTTON_HORIZONTAL_MARGINS_DP = 4
}
