package net.hernow.barkey

import java.util.concurrent.atomic.AtomicLong

/** Small in-memory set of currently visible Preview candidates. */
internal class PreviewCandidateState(
    private val expiryMillis: Long = 1_200L,
    private val maxCandidates: Int = 8,
) {
    private val seenAt = linkedMapOf<String, Long>()
    private var selectionGeneration = 0L

    @Synchronized
    fun update(values: Collection<String>, nowMillis: Long): List<String> {
        expire(nowMillis)
        values.asSequence().filter(String::isNotEmpty).distinct().forEach { value ->
            if (seenAt.containsKey(value) || seenAt.size < maxCandidates.coerceAtLeast(1)) seenAt[value] = nowMillis
        }
        return seenAt.keys.toList()
    }

    @Synchronized
    fun expire(nowMillis: Long): List<String> {
        seenAt.entries.removeAll { nowMillis - it.value > expiryMillis }
        return seenAt.keys.toList()
    }

    @Synchronized
    fun selectionToken(): Long = selectionGeneration

    @Synchronized
    fun isSelectable(value: String, nowMillis: Long, token: Long = selectionGeneration): Boolean {
        expire(nowMillis)
        return token == selectionGeneration && seenAt.containsKey(value)
    }

    @Synchronized
    fun clear() {
        seenAt.clear()
        selectionGeneration += 1
    }
}

/** Owns the selected Focus region together with the generation used to validate its scan results. */
internal class FocusRegionState(
    private val callbacks: CallbackGeneration,
    private val candidates: PreviewCandidateState,
    initialRegion: FocusRegionGeometry.RectF = FocusRegionGeometry.RectF(0f, 0f, 0f, 0f),
) {
    data class Snapshot(val region: FocusRegionGeometry.RectF, val generation: Long)

    private var region = initialRegion

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(region, callbacks.current())

    /** Returns false without invalidating anything when layout or a drag leaves the region unchanged. */
    @Synchronized
    fun update(next: FocusRegionGeometry.RectF, focusEnabled: Boolean): Boolean {
        if (region == next) return false
        region = next
        if (focusEnabled) {
            callbacks.advance()
            candidates.clear()
        }
        return true
    }

    /** A worker must not use a transform paired with a different Focus generation. */
    @Synchronized
    fun snapshotFor(expectedGeneration: Long): Snapshot? =
        Snapshot(region, callbacks.current()).takeIf { it.generation == expectedGeneration }
}

/** Preview never sends a decoded value directly to the editor while selection is enabled. */
internal object PreviewDeliveryPolicy {
    fun mayInsertAutomatically(previewEnabled: Boolean): Boolean = !previewEnabled

    fun maySelect(
        previewEnabled: Boolean,
        candidates: PreviewCandidateState,
        value: String,
        nowMillis: Long,
        selectionToken: Long = candidates.selectionToken(),
    ): Boolean = previewEnabled && candidates.isSelectable(value, nowMillis, selectionToken)

    fun maySelectFromContext(
        previewEnabled: Boolean,
        candidates: PreviewCandidateState,
        value: String,
        nowMillis: Long,
        producedFor: EditorFieldKey,
        current: EditorFieldKey?,
        selectionToken: Long = candidates.selectionToken(),
    ): Boolean = current != null && producedFor.isSamePreviewContext(current) &&
        maySelect(previewEnabled, candidates, value, nowMillis, selectionToken)
}

/** A field key deliberately contains no field contents. */
internal data class EditorFieldKey(val connection: Any?, val packageName: String?, val fieldId: Int) {
    /** Android field IDs are strongest when supplied; connection identity is the fallback for ID 0. */
    fun identifiesSameField(next: EditorFieldKey): Boolean =
        if (fieldId > 0 && next.fieldId > 0 && !packageName.isNullOrEmpty() && packageName == next.packageName) {
            fieldId == next.fieldId
        } else {
            fieldId == next.fieldId && connection != null && connection === next.connection
        }

    /** Pending Preview choices require the exact connection and editor metadata that produced them. */
    fun isSamePreviewContext(next: EditorFieldKey): Boolean =
        connection != null && connection === next.connection && packageName == next.packageName && fieldId == next.fieldId
}

/** One accepted insertion per editor identity; input-view recreation does not reset it. */
internal class SingleScanGate {
    private var field: EditorFieldKey? = null
    private var consumed = false

    @Synchronized
    fun moveTo(next: EditorFieldKey): Boolean {
        val sameField = field?.identifiesSameField(next) == true
        if (!sameField) consumed = false
        field = next
        return !sameField
    }

    @Synchronized fun isConsumed(): Boolean = consumed
    @Synchronized fun consume(): Boolean {
        if (field == null || consumed) return false
        consumed = true
        return true
    }
    /**
     * Ends an IME input session without dropping a recognisable field's allowance. Keep only the
     * host package and Android field ID; release the old InputConnection reference. If Android
     * supplied no stable field ID, the allowance cannot safely cross the session boundary.
     */
    @Synchronized
    fun finishInput() {
        val previous = field ?: return
        if (previous.fieldId > 0 && !previous.packageName.isNullOrEmpty()) {
            field = previous.copy(connection = null)
        } else {
            field = null
            consumed = false
        }
    }

    @Synchronized fun reset() { field = null; consumed = false }
}

/** Geometry helpers for the movable and resizable Focus region and its production camera crop mapping. */
internal object FocusRegionGeometry {
    data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)
    data class ImageRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isEmpty: Boolean get() = right <= left || bottom <= top
    }

    /**
     * Maps a preview rectangle using CameraX's CoordinateTransform, whose destination is crop-local
     * when ImageProxyTransformFactory.setUsingCropRect(true). The result is then offset exactly
     * once into ImageProxy buffer coordinates and clipped to its valid crop rectangle.
     */
    fun mapPreviewRectToImageCrop(
        previewRect: RectF,
        crop: ImageRect,
        mapToCropLocal: (RectF) -> RectF,
    ): ImageRect? {
        if (crop.isEmpty || !previewRect.isFiniteAndNonEmpty()) return null
        val mapped = try {
            mapToCropLocal(previewRect.copy())
        } catch (_: RuntimeException) {
            return null
        }
        if (!mapped.isFiniteAndNonEmpty()) return null
        val cropWidth = (crop.right - crop.left).toFloat()
        val cropHeight = (crop.bottom - crop.top).toFloat()
        val left = mapped.left.coerceIn(0f, cropWidth)
        val top = mapped.top.coerceIn(0f, cropHeight)
        val right = mapped.right.coerceIn(0f, cropWidth)
        val bottom = mapped.bottom.coerceIn(0f, cropHeight)
        if (right <= left || bottom <= top) return null
        val result = ImageRect(
            crop.left + kotlin.math.floor(left).toInt(),
            crop.top + kotlin.math.floor(top).toInt(),
            crop.left + kotlin.math.ceil(right).toInt(),
            crop.top + kotlin.math.ceil(bottom).toInt(),
        )
        val clipped = ImageRect(
            result.left.coerceIn(crop.left, crop.right), result.top.coerceIn(crop.top, crop.bottom),
            result.right.coerceIn(crop.left, crop.right), result.bottom.coerceIn(crop.top, crop.bottom),
        )
        return clipped.takeUnless { it.isEmpty }
    }

    /** Clamps a movable rectangle to preview bounds while enforcing per-axis size limits. */
    fun clampRect(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        boundsWidth: Float,
        boundsHeight: Float,
        minWidth: Float,
        minHeight: Float,
        maxWidth: Float = boundsWidth,
        maxHeight: Float = boundsHeight,
    ): RectF {
        if (listOf(left, top, width, height, boundsWidth, boundsHeight, minWidth, minHeight, maxWidth, maxHeight).any { !it.isFinite() } ||
            boundsWidth <= 0f || boundsHeight <= 0f || width <= 0f || height <= 0f || minWidth <= 0f || minHeight <= 0f
        ) return RectF(0f, 0f, 0f, 0f)
        val minW = minWidth.coerceAtMost(boundsWidth)
        val minH = minHeight.coerceAtMost(boundsHeight)
        val actualWidth = width.coerceIn(minW, maxWidth.coerceIn(minW, boundsWidth))
        val actualHeight = height.coerceIn(minH, maxHeight.coerceIn(minH, boundsHeight))
        val x = left.coerceIn(0f, boundsWidth - actualWidth)
        val y = top.coerceIn(0f, boundsHeight - actualHeight)
        return RectF(x, y, x + actualWidth, y + actualHeight)
    }

    /** Resizes from the bottom-right corner, keeping the opposite corner anchored. */
    fun resizeFromBottomRight(
        current: RectF,
        deltaWidth: Float,
        deltaHeight: Float,
        boundsWidth: Float,
        boundsHeight: Float,
        minWidth: Float,
        minHeight: Float,
    ): RectF {
        if (!current.isFiniteAndNonEmpty() || !deltaWidth.isFinite() || !deltaHeight.isFinite() ||
            !boundsWidth.isFinite() || !boundsHeight.isFinite() || !minWidth.isFinite() || !minHeight.isFinite() ||
            boundsWidth <= 0f || boundsHeight <= 0f || minWidth <= 0f || minHeight <= 0f
        ) return RectF(0f, 0f, 0f, 0f)
        val left = current.left.coerceIn(0f, boundsWidth)
        val top = current.top.coerceIn(0f, boundsHeight)
        val availableWidth = (boundsWidth - left).coerceAtLeast(0f)
        val availableHeight = (boundsHeight - top).coerceAtLeast(0f)
        if (availableWidth == 0f || availableHeight == 0f) return RectF(0f, 0f, 0f, 0f)
        val minW = minWidth.coerceAtLeast(1f).coerceAtMost(availableWidth)
        val minH = minHeight.coerceAtLeast(1f).coerceAtMost(availableHeight)
        val width = (current.right - current.left + deltaWidth).coerceIn(minW, availableWidth)
        val height = (current.bottom - current.top + deltaHeight).coerceIn(minH, availableHeight)
        return RectF(left, top, left + width, top + height)
    }

    fun centeredRect(boundsWidth: Float, boundsHeight: Float, width: Float, height: Float): RectF =
        clampRect((boundsWidth - width) / 2f, (boundsHeight - height) / 2f, width, height,
            boundsWidth, boundsHeight, minWidth = 1f, minHeight = 1f)

    fun normalizedToPixels(
        normalized: RectF,
        width: Float,
        height: Float,
        minWidth: Float,
        minHeight: Float,
    ): RectF? {
        if (!normalized.isFiniteAndNonEmpty() || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
        return clampRect(
            normalized.left * width, normalized.top * height,
            (normalized.right - normalized.left) * width, (normalized.bottom - normalized.top) * height,
            width, height, minWidth, minHeight,
        ).takeIf { it.isFiniteAndNonEmpty() }
    }

    fun pixelsToNormalized(rect: RectF, width: Float, height: Float): RectF? {
        if (!rect.isFiniteAndNonEmpty() || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
        val normalized = RectF(rect.left / width, rect.top / height, rect.right / width, rect.bottom / height)
        return normalized.takeIf { it.isFiniteAndNonEmpty() }
    }

    private fun RectF.isFiniteAndNonEmpty(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && right > left && bottom > top
}

internal enum class FrameDecodeRequest { NORMAL, MULTIPLE_CANDIDATES, FOCUSED_SINGLE, FOCUS_UNAVAILABLE }

/** Keeps multi-result decoding opt-in and refuses to decode Focus frames without a valid crop. */
internal object FrameDecodePolicy {
    fun request(previewEnabled: Boolean, focusEnabled: Boolean, cropAvailable: Boolean): FrameDecodeRequest = when {
        focusEnabled && !cropAvailable -> FrameDecodeRequest.FOCUS_UNAVAILABLE
        previewEnabled -> FrameDecodeRequest.MULTIPLE_CANDIDATES
        focusEnabled -> FrameDecodeRequest.FOCUSED_SINGLE
        else -> FrameDecodeRequest.NORMAL
    }
}

/** Camera-worker-safe gate: it reads only the volatile mode and synchronized insertion allowance. */
internal object SingleScanDecodePolicy {
    fun shouldDecode(singleScanEnabled: Boolean, allowanceConsumed: Boolean): Boolean =
        !singleScanEnabled || !allowanceConsumed
}

/** Invalidates camera results captured before a mode or surface transition. */
internal class CallbackGeneration {
    private val value = AtomicLong(0L)
    fun current(): Long = value.get()
    fun advance(): Long = value.incrementAndGet()
    fun accepts(callbackGeneration: Long): Boolean = value.get() == callbackGeneration
}
