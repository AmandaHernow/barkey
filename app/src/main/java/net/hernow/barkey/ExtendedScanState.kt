package net.hernow.barkey

import java.util.concurrent.atomic.AtomicLong

/** Maximum number of values shown in Preview at once. */
internal const val MAX_PREVIEW_CANDIDATES = 8

/** Small in-memory set of currently visible Preview candidates. */
internal class PreviewCandidateState(
    private val expiryMillis: Long = 1_200L,
    private val maxCandidates: Int = MAX_PREVIEW_CANDIDATES,
) {
    private val seenAt = linkedMapOf<String, Long>()
    private var selectionGeneration = 0L

    /** Adds live values, removes expired ones and keeps the row within its limit. */
    @Synchronized
    fun update(values: Collection<String>, nowMillis: Long): List<String> {
        expire(nowMillis)
        values.asSequence().filter(String::isNotEmpty).distinct().forEach { value ->
            if (seenAt.containsKey(value) || seenAt.size < maxCandidates.coerceAtLeast(1)) seenAt[value] = nowMillis
        }
        return seenAt.keys.toList()
    }

    /** Returns only suggestions seen within the expiry window. */
    @Synchronized
    fun expire(nowMillis: Long): List<String> {
        seenAt.entries.removeAll { nowMillis - it.value > expiryMillis }
        return seenAt.keys.toList()
    }

    /** Tags buttons so a cleared candidate cannot be selected later. */
    @Synchronized
    fun selectionToken(): Long = selectionGeneration

    /** Checks both the button generation and the candidate's expiry. */
    @Synchronized
    fun isSelectable(value: String, nowMillis: Long, token: Long = selectionGeneration): Boolean {
        expire(nowMillis)
        return token == selectionGeneration && seenAt.containsKey(value)
    }

    /** Clears suggestions and invalidates every button already on screen. */
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
    /** Reads the region together with its current callback generation. */
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
    /** Preview requires a tap before any value can reach the editor. */
    fun mayInsertAutomatically(previewEnabled: Boolean): Boolean = !previewEnabled

    /** Allows only a live value that was shown to the user. */
    fun maySelect(
        previewEnabled: Boolean,
        candidates: PreviewCandidateState,
        value: String,
        nowMillis: Long,
        selectionToken: Long = candidates.selectionToken(),
    ): Boolean = previewEnabled && candidates.isSelectable(value, nowMillis, selectionToken)

    /** Keeps a suggestion tied to the editor connection that produced it. */
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

/** Keeps Preview selection and one-per-field Single Scan as separate choices. */
internal object ScanOptionPolicy {
    /** Preview is unavailable while Single Scan is active. */
    fun canEnablePreview(singleScanEnabled: Boolean): Boolean = !singleScanEnabled

    /** Single Scan is unavailable while Preview is active. */
    fun canEnableSingleScan(previewEnabled: Boolean): Boolean = !previewEnabled
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

    /** Reports whether this field has already received its one scan. */
    @Synchronized fun isConsumed(): Boolean = consumed

    /** Uses the field's allowance after a successful insertion. */
    @Synchronized fun consume(): Boolean {
        if (field == null || consumed) return false
        consumed = true
        return true
    }

    /** Makes the field available again when Undo leaves it empty. */
    @Synchronized fun rearmAfterUndo(fieldIsEmpty: Boolean): Boolean {
        if (!consumed || !fieldIsEmpty) return false
        reset()
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
     * Maps a preview rectangle into absolute image-buffer coordinates and clips it to the valid crop.
     */
    fun mapPreviewRectToImageCrop(
        previewRect: RectF,
        crop: ImageRect,
        mapToImageBuffer: (RectF) -> RectF,
    ): ImageRect? {
        if (crop.isEmpty || !previewRect.isFiniteAndNonEmpty()) return null
        val mapped = try {
            mapToImageBuffer(previewRect.copy())
        } catch (_: RuntimeException) {
            return null
        }
        if (!mapped.isFiniteAndNonEmpty()) return null
        val left = snapNearInteger(mapped.left).coerceIn(crop.left.toFloat(), crop.right.toFloat())
        val top = snapNearInteger(mapped.top).coerceIn(crop.top.toFloat(), crop.bottom.toFloat())
        val right = snapNearInteger(mapped.right).coerceIn(crop.left.toFloat(), crop.right.toFloat())
        val bottom = snapNearInteger(mapped.bottom).coerceIn(crop.top.toFloat(), crop.bottom.toFloat())
        if (right <= left || bottom <= top) return null
        val result = ImageRect(
            kotlin.math.floor(left).toInt(),
            kotlin.math.floor(top).toInt(),
            kotlin.math.ceil(right).toInt(),
            kotlin.math.ceil(bottom).toInt(),
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

    /** Places a region in the centre and clamps it to the preview. */
    fun centeredRect(boundsWidth: Float, boundsHeight: Float, width: Float, height: Float): RectF =
        clampRect((boundsWidth - width) / 2f, (boundsHeight - height) / 2f, width, height,
            boundsWidth, boundsHeight, minWidth = 1f, minHeight = 1f)

    /** Restores a preview-relative region at the current view size. */
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

    /** Saves a pixel region as proportions so it survives preview resizing. */
    fun pixelsToNormalized(rect: RectF, width: Float, height: Float): RectF? {
        if (!rect.isFiniteAndNonEmpty() || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
        val normalized = RectF(rect.left / width, rect.top / height, rect.right / width, rect.bottom / height)
        return normalized.takeIf { it.isFiniteAndNonEmpty() }
    }

    private fun RectF.isFiniteAndNonEmpty(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && right > left && bottom > top

    /** Prevents float transform noise from adding a spurious pixel at integer boundaries. */
    private fun snapNearInteger(value: Float): Float {
        val nearest = kotlin.math.round(value)
        return if (kotlin.math.abs(value - nearest) <= 0.0001f) nearest else value
    }
}

/** Chooses the decoder path for the current scanner controls. */
internal enum class FrameDecodeRequest { NORMAL, MULTIPLE_CANDIDATES, FOCUSED_SINGLE, FOCUS_UNAVAILABLE }

/** Keeps multi-result decoding opt-in and refuses to decode Focus frames without a valid crop. */
internal object FrameDecodePolicy {
    /** Fails closed when Focus has no valid crop for this frame. */
    fun request(previewEnabled: Boolean, focusEnabled: Boolean, cropAvailable: Boolean): FrameDecodeRequest = when {
        focusEnabled && !cropAvailable -> FrameDecodeRequest.FOCUS_UNAVAILABLE
        previewEnabled -> FrameDecodeRequest.MULTIPLE_CANDIDATES
        focusEnabled -> FrameDecodeRequest.FOCUSED_SINGLE
        else -> FrameDecodeRequest.NORMAL
    }
}

/** Camera-worker-safe gate: it reads only the volatile mode and synchronized insertion allowance. */
internal object SingleScanDecodePolicy {
    /** Skips camera work only after the enabled field has accepted a scan. */
    fun shouldDecode(singleScanEnabled: Boolean, allowanceConsumed: Boolean): Boolean =
        !singleScanEnabled || !allowanceConsumed
}

/** Invalidates camera results captured before a mode or surface transition. */
internal class CallbackGeneration {
    private val value = AtomicLong(0L)
    /** Reads the version captured by pending camera work. */
    fun current(): Long = value.get()

    /** Invalidates results captured before a setting or surface change. */
    fun advance(): Long = value.incrementAndGet()

    /** Checks whether a delayed result still belongs to the current state. */
    fun accepts(callbackGeneration: Long): Boolean = value.get() == callbackGeneration
}
