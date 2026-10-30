package net.hernow.barkey

import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.atomic.AtomicLong

/** Small in-memory set of currently visible Preview candidates. */
internal class PreviewCandidateState(
    private val expiryMillis: Long = 1_200L,
) {
    private val seenAt = linkedMapOf<String, Long>()

    @Synchronized
    fun update(values: Collection<String>, nowMillis: Long): List<String> {
        values.filter(String::isNotEmpty).forEach { seenAt[it] = nowMillis }
        expire(nowMillis)
        return seenAt.keys.toList()
    }

    @Synchronized
    fun expire(nowMillis: Long): List<String> {
        seenAt.entries.removeAll { nowMillis - it.value > expiryMillis }
        return seenAt.keys.toList()
    }

    @Synchronized
    fun isSelectable(value: String, nowMillis: Long): Boolean {
        expire(nowMillis)
        return seenAt.containsKey(value)
    }

    @Synchronized
    fun clear() = seenAt.clear()
}

/** A field key deliberately contains no field contents. */
internal data class EditorFieldKey(val connection: Any, val packageName: String?, val fieldId: Int)

/** One accepted insertion per editor identity; input-view recreation does not reset it. */
internal class SingleScanGate {
    private var field: EditorFieldKey? = null
    private var consumed = false

    @Synchronized
    fun moveTo(next: EditorFieldKey) {
        val sameField = field?.let { previous ->
            previous.packageName == next.packageName &&
                (if (previous.fieldId > 0 && next.fieldId > 0) previous.fieldId == next.fieldId
                else previous.fieldId == next.fieldId && previous.connection === next.connection)
        } == true
        if (!sameField) consumed = false
        field = next
    }

    @Synchronized fun isConsumed(): Boolean = consumed
    @Synchronized fun consume(): Boolean {
        if (field == null || consumed) return false
        consumed = true
        return true
    }
    @Synchronized fun reset() { field = null; consumed = false }
}

/** Maps preview-local coordinates into the unrotated ImageProxy crop using CameraX's FILL_CENTER geometry. */
internal object ScanRegionGeometry {
    data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun contains(rect: RectF, x: Float, y: Float): Boolean =
        x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom

    fun mapPreviewSquare(
        square: RectF,
        previewWidth: Int,
        previewHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        crop: RectF = RectF(0f, 0f, frameWidth.toFloat(), frameHeight.toFloat()),
    ): RectF? {
        if (previewWidth <= 0 || previewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val cropWidth = if (rotation == 90 || rotation == 270) crop.bottom - crop.top else crop.right - crop.left
        val cropHeight = if (rotation == 90 || rotation == 270) crop.right - crop.left else crop.bottom - crop.top
        if (cropWidth <= 0f || cropHeight <= 0f) return null
        val scale = max(previewWidth / cropWidth, previewHeight / cropHeight) // PreviewView.FILL_CENTER
        val shownWidth = cropWidth * scale
        val shownHeight = cropHeight * scale
        val ox = (previewWidth - shownWidth) / 2f
        val oy = (previewHeight - shownHeight) / 2f
        var left = ((square.left - ox) / scale).coerceIn(0f, cropWidth)
        var right = ((square.right - ox) / scale).coerceIn(0f, cropWidth)
        var top = ((square.top - oy) / scale).coerceIn(0f, cropHeight)
        var bottom = ((square.bottom - oy) / scale).coerceIn(0f, cropHeight)
        if (right <= left || bottom <= top) return null
        val mapped = when (rotation) {
            90 -> RectF(crop.left + top, crop.bottom - right, crop.left + bottom, crop.bottom - left)
            180 -> RectF(crop.right - right, crop.bottom - bottom, crop.right - left, crop.bottom - top)
            270 -> RectF(crop.right - bottom, crop.top + left, crop.right - top, crop.top + right)
            else -> RectF(crop.left + left, crop.top + top, crop.left + right, crop.top + bottom)
        }
        left = mapped.left.coerceIn(crop.left, crop.right)
        right = mapped.right.coerceIn(crop.left, crop.right)
        top = mapped.top.coerceIn(crop.top, crop.bottom)
        bottom = mapped.bottom.coerceIn(crop.top, crop.bottom)
        return if (right > left && bottom > top) RectF(left, top, right, bottom) else null
    }

    fun clampSquare(left: Float, top: Float, size: Float, width: Float, height: Float): RectF {
        val side = min(size, min(width, height)).coerceAtLeast(0f)
        val x = left.coerceIn(0f, (width - side).coerceAtLeast(0f))
        val y = top.coerceIn(0f, (height - side).coerceAtLeast(0f))
        return RectF(x, y, x + side, y + side)
    }
}

/** Swedish manual keyboard state: shift is one-shot; symbol layout is toggled separately. */
internal class SwedishKeyboardState {
    var shifted: Boolean = false
        private set
    var symbols: Boolean = false
        private set
    private var capsLock = false

    fun toggleShift() { shifted = !shifted }
    fun toggleSymbols() { symbols = !symbols; shifted = false }
    fun configure(inputType: Int) {
        val inputClass = inputType and android.text.InputType.TYPE_MASK_CLASS
        symbols = inputClass == android.text.InputType.TYPE_CLASS_NUMBER ||
            inputClass == android.text.InputType.TYPE_CLASS_PHONE
        capsLock = inputClass == android.text.InputType.TYPE_CLASS_TEXT &&
            (inputType and android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0
        shifted = capsLock
    }
    fun character(key: String): String = if ((shifted || capsLock) && key.length == 1) key.uppercase() else key.lowercase()
    fun consumeCharacter(key: String): String = character(key).also { if (!capsLock) shifted = false }
}

/** Explicit Scanner/Keyboard transition state, independent of camera lifecycle resources. */
internal class ScannerKeyboardMode {
    @Volatile private var keyboardActive = false
    fun showKeyboard(): Boolean {
        if (keyboardActive) return false
        keyboardActive = true
        return true
    }
    fun showScanner(): Boolean {
        if (!keyboardActive) return false
        keyboardActive = false
        return true
    }
    fun isKeyboardActive(): Boolean = keyboardActive
}

/** Invalidates camera results captured before a mode or surface transition. */
internal class CallbackGeneration {
    private val value = AtomicLong(0L)
    fun current(): Long = value.get()
    fun advance(): Long = value.incrementAndGet()
    fun accepts(callbackGeneration: Long): Boolean = value.get() == callbackGeneration
}
