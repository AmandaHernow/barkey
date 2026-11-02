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

/** The optional automatic action performed after a successful scan. */
internal enum class AutomaticScanAction { TAB, ENTER }

/** Three user-selectable states shared by Auto Tab and Auto Enter. */
internal enum class AutomaticActionState { OFF, ONCE, CONTINUOUS }

/** Cycles Auto Tab and Auto Enter while enforcing mutual exclusion and Preview compatibility. */
internal class AutomaticActionController {
    private var selectedAction: AutomaticScanAction? = null
    private var selectedState = AutomaticActionState.OFF
    private var awaitingFieldChange = false
    /** Package that began this Auto workflow; prevents it crossing into another app. */
    private var workflowPackageName: String? = null

    /** Returns Off for the action that is not currently selected. */
    @Synchronized
    fun state(action: AutomaticScanAction): AutomaticActionState =
        if (selectedAction == action) selectedState else AutomaticActionState.OFF

    /** Tapping the selected action advances its cycle; choosing the other starts at Once. */
    @Synchronized
    fun cycle(
        action: AutomaticScanAction,
        previewEnabled: Boolean,
        editorPackageName: String? = null,
    ): AutomaticActionState {
        if (previewEnabled) return AutomaticActionState.OFF
        val continuingSelection = selectedAction == action
        val next = if (continuingSelection) when (selectedState) {
            AutomaticActionState.OFF -> AutomaticActionState.ONCE
            AutomaticActionState.ONCE -> AutomaticActionState.CONTINUOUS
            AutomaticActionState.CONTINUOUS -> AutomaticActionState.OFF
        } else AutomaticActionState.ONCE
        selectedAction = action.takeUnless { next == AutomaticActionState.OFF }
        selectedState = next
        awaitingFieldChange = false
        if (next == AutomaticActionState.OFF) {
            workflowPackageName = null
        } else if (!continuingSelection) {
            workflowPackageName = editorPackageName?.takeIf(String::isNotBlank)
        }
        return next
    }

    /** Preview suspends automatic actions and clears their selection. */
    @Synchronized
    fun onPreviewEnabled() = reset()

    /** Returns the action that replaces a managed suffix, or null while both controls are Off. */
    @Synchronized
    fun actionForScan(previewEnabled: Boolean): AutomaticScanAction? =
        if (previewEnabled || awaitingFieldChange || selectedState == AutomaticActionState.OFF) null else selectedAction

    @Synchronized
    fun stateForScan(previewEnabled: Boolean): AutomaticActionState =
        if (previewEnabled || awaitingFieldChange) AutomaticActionState.OFF else selectedState

    /** Once actions always turn off after one successful insertion. */
    @Synchronized
    fun completeOnce() {
        if (selectedState == AutomaticActionState.ONCE) reset()
    }

    /** Continuous actions pause until a different stable field identity is observed. */
    @Synchronized
    fun awaitFieldChange() {
        if (selectedState == AutomaticActionState.CONTINUOUS) awaitingFieldChange = true
    }

    /** Keeps an armed action across same-app editor sessions when its originating app is known. */
    @Synchronized
    fun onInputSessionFinished() {
        // Android ends one editor session while moving to another. Keep Once and Continuous when
        // the originating package is known; if it is unknown, there is no safe app boundary check.
        if (workflowPackageName == null) reset()
    }

    /** App changes terminate an automatic workflow even when Android supplies no field ID. */
    @Synchronized
    fun observeEditorPackage(editorPackageName: String?) {
        val workflowPackage = workflowPackageName
        if (selectedAction != null && (workflowPackage == null || workflowPackage != editorPackageName)) reset()
    }

    /** Resumes ∞ only after a reliable field transition in the app where the workflow began. */
    @Synchronized
    fun onVerifiedFieldChanged(editorPackageName: String?): Boolean {
        observeEditorPackage(editorPackageName)
        if (selectedAction == null || workflowPackageName == null ||
            workflowPackageName != editorPackageName
        ) {
            reset()
            return false
        }
        awaitingFieldChange = false
        return true
    }

    /** A verified Undo permits a fresh scan while retaining the selected continuous mode. */
    @Synchronized
    fun onVerifiedUndo() { awaitingFieldChange = false }

    /** An explicit Scan Again tap authorises the deliberate field-local retry. */
    @Synchronized
    fun onExplicitRearm(editorPackageName: String?) {
        observeEditorPackage(editorPackageName)
        if (selectedAction == null) return
        if (workflowPackageName == null || workflowPackageName != editorPackageName) {
            reset()
            return
        }
        awaitingFieldChange = false
    }

    @Synchronized
    fun isAwaitingFieldChange(): Boolean = awaitingFieldChange

    @Synchronized
    fun reset() {
        selectedAction = null
        selectedState = AutomaticActionState.OFF
        awaitingFieldChange = false
        workflowPackageName = null
    }
}

/** A field key deliberately contains no field contents; editor metadata is for identity only. */
internal data class EditorFieldKey(
    val connection: Any?,
    val packageName: String?,
    val fieldId: Int,
    val inputType: Int = 0,
    val imeOptions: Int = 0,
) {
    /** Same live editor context keeps callbacks valid, including editors with field ID zero. */
    fun isSameEditorContext(next: EditorFieldKey): Boolean =
        connection != null && connection === next.connection && packageName == next.packageName &&
            fieldId == next.fieldId && inputType == next.inputType && imeOptions == next.imeOptions

    /** A field is reliably identified only by its package and positive Android field ID. */
    fun isSameReliableField(next: EditorFieldKey): Boolean =
        fieldId > 0 && next.fieldId > 0 && !packageName.isNullOrEmpty() &&
            packageName == next.packageName && fieldId == next.fieldId

    /** Returns true only when both keys prove that they refer to different fields. */
    fun identifiesDifferentReliableField(next: EditorFieldKey): Boolean =
        fieldId > 0 && next.fieldId > 0 && !packageName.isNullOrEmpty() &&
            !next.packageName.isNullOrEmpty() && !isSameReliableField(next)

    /** Pending Preview choices require the exact connection and editor metadata that produced them. */
    fun isSamePreviewContext(next: EditorFieldKey): Boolean =
        isSameEditorContext(next)

    /** A package and positive field ID are required before another field can be inferred. */
    val hasReliableFieldIdentity: Boolean
        get() = fieldId > 0 && !packageName.isNullOrEmpty()
}

/** One accepted insertion per current editor; only a verified ID change or explicit action rearms. */
internal class SingleScanGate {
    /** Keep the last reliable ID when Android temporarily supplies incomplete editor metadata. */
    private var lastReliableField: EditorFieldKey? = null
    /** Remember reliable fields already scanned if an editor changes during commitText(). */
    private val consumedReliableFields = linkedSetOf<ReliableFieldIdentity>()
    /** Holds only the live current key; release it when Android finishes the editor session. */
    private var currentField: EditorFieldKey? = null
    private var hasCurrentEditor = false
    private var consumed = false

    /** Moves the current key and rearms only when both keys prove a different field. */
    @Synchronized
    fun moveTo(next: EditorFieldKey): Boolean {
        val previous = lastReliableField
        val changed = previous?.identifiesDifferentReliableField(next) == true
        if (changed) consumed = reliableIdentity(next) in consumedReliableFields
        if (next.hasReliableFieldIdentity) lastReliableField = next
        currentField = next
        hasCurrentEditor = true
        return changed
    }

    /** Reports whether this field has already received its one scan. */
    @Synchronized fun isConsumed(): Boolean = consumed

    /** Records insertion against its captured field, never against a synchronous destination. */
    @Synchronized fun consume(): Boolean {
        if (!hasCurrentEditor || consumed) return false
        consumed = true
        return true
    }

    /**
     * Records the field that received the scan. If Android changed editors inside commitText(),
     * leave a verified destination's allowance untouched and conservatively block ambiguous ones.
     * @return true when the current editor was the scanned editor and became consumed.
     */
    @Synchronized
    fun consume(scannedField: EditorFieldKey): Boolean {
        if (!hasCurrentEditor) {
            rememberConsumedField(scannedField)
            consumed = true
            return false
        }
        val activeField = currentField ?: return false
        if (activeField.isSameEditorContext(scannedField)) return consume()

        // Retain only insertion records whose editor changed during commitText(); ordinary field
        // navigation continues to use the existing single-current-field gate behaviour.
        rememberConsumedField(scannedField)
        if (!scannedField.identifiesDifferentReliableField(activeField)) {
            // A connection or metadata change alone cannot establish a safe new-field allowance.
            consumed = true
            return false
        }
        consumed = reliableIdentity(activeField) in consumedReliableFields
        return false
    }

    /** Makes the field available again when Undo leaves it empty. */
    @Synchronized fun rearmAfterUndo(fieldIsEmpty: Boolean): Boolean {
        if (!consumed || !fieldIsEmpty) return false
        consumed = false
        currentField?.let(::forgetConsumedField)
        return true
    }
    /** Explicit user action to allow another scan in the current field. */
    @Synchronized
    fun rearmExplicitly(): Boolean {
        if (!consumed) return false
        consumed = false
        currentField?.let(::forgetConsumedField)
        return true
    }

    /**
     * Ends an IME input session while preserving the consumed state. Release the old connection;
     * without a stable field ID, a later session cannot prove that the field changed.
     */
    @Synchronized
    fun finishInput() {
        // Retain the last positive field ID but require a fresh editor key after this boundary.
        hasCurrentEditor = false
        currentField = null
    }

    /** Clears the allowance when the IME service itself is destroyed. */
    @Synchronized fun reset() {
        lastReliableField = null
        consumedReliableFields.clear()
        currentField = null
        hasCurrentEditor = false
        consumed = false
    }

    private fun reliableIdentity(field: EditorFieldKey): ReliableFieldIdentity =
        ReliableFieldIdentity(requireNotNull(field.packageName), field.fieldId)

    private fun rememberConsumedField(field: EditorFieldKey) {
        if (field.hasReliableFieldIdentity) consumedReliableFields += reliableIdentity(field)
    }

    private fun forgetConsumedField(field: EditorFieldKey) {
        if (field.hasReliableFieldIdentity) consumedReliableFields -= reliableIdentity(field)
    }

    private data class ReliableFieldIdentity(val packageName: String, val fieldId: Int)
}

/** Coordinates preview delivery, field protection and post-scan readiness as one tested policy. */
internal class ScanDeliveryCoordinator(
    private val singleScanGate: SingleScanGate,
    private val automaticActions: AutomaticActionController,
) {
    /** Records context and resumes ∞ only after a stable, same-workflow field transition. */
    fun observeEditorContext(next: EditorFieldKey): Boolean {
        automaticActions.observeEditorPackage(next.packageName)
        val fieldChanged = singleScanGate.moveTo(next)
        if (fieldChanged) automaticActions.onVerifiedFieldChanged(next.packageName)
        return fieldChanged
    }

    /** Normal automatic insertion requires Preview Off, an available field and no pending transition. */
    fun mayDeliverAutomatically(previewEnabled: Boolean): Boolean =
        PreviewDeliveryPolicy.mayInsertAutomatically(previewEnabled) &&
            !singleScanGate.isConsumed() && !automaticActions.isAwaitingFieldChange()

    /** Manual Preview taps remain available even after an earlier candidate consumed the field. */
    fun maySelectPreview(
        previewEnabled: Boolean,
        candidates: PreviewCandidateState,
        value: String,
        nowMillis: Long,
        producedFor: EditorFieldKey,
        current: EditorFieldKey?,
        selectionToken: Long,
    ): Boolean = PreviewDeliveryPolicy.maySelectFromContext(
        previewEnabled, candidates, value, nowMillis, producedFor, current, selectionToken,
    )

    /**
     * A successful insertion consumes the allowance belonging to its captured editor context.
     * @return true when the active editor was the scanned editor and became consumed.
     */
    fun recordSuccessfulInsertion(scannedField: EditorFieldKey? = null): Boolean =
        if (scannedField == null) singleScanGate.consume() else singleScanGate.consume(scannedField)

    /** Rearms both linked states only after Undo verification says that the field is empty. */
    fun rearmAfterVerifiedUndo(fieldIsEmpty: Boolean): Boolean {
        if (!singleScanGate.rearmAfterUndo(fieldIsEmpty)) return false
        automaticActions.onVerifiedUndo()
        return true
    }

    /** Scan Again requires a live key and a deliberate call from the status action. */
    fun rearmExplicitly(currentField: EditorFieldKey?): Boolean {
        currentField ?: return false
        if (!singleScanGate.rearmExplicitly()) return false
        automaticActions.observeEditorPackage(currentField.packageName)
        singleScanGate.moveTo(currentField)
        automaticActions.onExplicitRearm(currentField.packageName)
        return true
    }

    /** Input finishing may precede or follow navigation; preserve ∞ until a real workflow boundary. */
    fun onInputSessionFinished() {
        singleScanGate.finishInput()
        automaticActions.onInputSessionFinished()
    }

    /** Hiding the IME ends its visible scanning workflow. */
    fun onKeyboardHidden() {
        // Keep the consumed field allowance: reopening BarKey must not scan again into that field.
        automaticActions.reset()
    }

    /** Destruction releases both per-service delivery and automatic-workflow state. */
    fun reset() {
        automaticActions.reset()
        singleScanGate.reset()
    }
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
    /** Skips automatic decode work after the current field has accepted its scan. */
    fun shouldDecode(allowanceConsumed: Boolean): Boolean = !allowanceConsumed
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
