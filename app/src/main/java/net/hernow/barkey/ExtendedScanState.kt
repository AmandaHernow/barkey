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
        values.asSequence().filter(String::isNotEmpty).distinct().forEach { value ->
            if (seenAt.containsKey(value) || seenAt.size < maxCandidates.coerceAtLeast(1)) seenAt[value] = nowMillis
        }
        expire(nowMillis)
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
}

/** A field key deliberately contains no field contents. */
internal data class EditorFieldKey(val connection: Any, val packageName: String?, val fieldId: Int) {
    /** Android field IDs are strongest when supplied; connection identity is the fallback for ID 0. */
    fun identifiesSameField(next: EditorFieldKey): Boolean = packageName == next.packageName &&
        (if (fieldId > 0 && next.fieldId > 0) fieldId == next.fieldId
        else fieldId == next.fieldId && connection === next.connection)
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
    @Synchronized fun reset() { field = null; consumed = false }
}

/** Geometry helpers for the draggable Focus square and its production camera crop mapping. */
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
        mapToCropLocal: (RectF) -> Unit,
    ): ImageRect? {
        if (crop.isEmpty || !previewRect.isFiniteAndNonEmpty()) return null
        val mapped = previewRect.copy()
        try {
            mapToCropLocal(mapped)
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

    fun clampSquare(left: Float, top: Float, size: Float, width: Float, height: Float): RectF {
        if (!left.isFinite() || !top.isFinite() || !size.isFinite() || !width.isFinite() || !height.isFinite() ||
            width <= 0f || height <= 0f || size <= 0f
        ) return RectF(0f, 0f, 0f, 0f)
        val side = size.coerceAtMost(width).coerceAtMost(height)
        val x = left.coerceIn(0f, width - side)
        val y = top.coerceIn(0f, height - side)
        return RectF(x, y, x + side, y + side)
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

/** Invalidates camera results captured before a mode or surface transition. */
internal class CallbackGeneration {
    private val value = AtomicLong(0L)
    fun current(): Long = value.get()
    fun advance(): Long = value.incrementAndGet()
    fun accepts(callbackGeneration: Long): Boolean = value.get() == callbackGeneration
}
