package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks Preview, Focus, per-field protection and geometry. */
class ExtendedScanStateTest {
    @Test fun previewSuppressesAutomaticInsertionAndAllowsOnlyLiveDistinctCandidates() {
        val state = PreviewCandidateState(expiryMillis = 1_000)
        assertTrue(PreviewDeliveryPolicy.mayInsertAutomatically(previewEnabled = false))
        assertFalse(PreviewDeliveryPolicy.mayInsertAutomatically(previewEnabled = true))
        assertEquals(listOf("A", "B"), state.update(listOf("A", "A", "B", ""), 10))
        assertTrue(PreviewDeliveryPolicy.maySelect(true, state, "A", 10))
        assertFalse(PreviewDeliveryPolicy.maySelect(false, state, "A", 10))
        assertFalse(PreviewDeliveryPolicy.maySelect(true, state, "missing", 10))
        assertEquals(listOf("A", "B"), state.update(listOf("B"), 900))
        assertEquals(listOf("B"), state.expire(1_101))
        assertFalse(PreviewDeliveryPolicy.maySelect(true, state, "A", 1_101))
        assertTrue(PreviewDeliveryPolicy.maySelect(true, state, "B", 1_101))
        assertEquals(emptyList<String>(), state.expire(1_901))
        assertEquals(listOf("1", "2"), PreviewCandidateState(maxCandidates = 2).update(listOf("1", "2", "3"), 0))
    }

    @Test fun previewExpiresBeforeApplyingCapacityLimit() {
        val state = PreviewCandidateState(expiryMillis = 100, maxCandidates = 2)
        assertEquals(listOf("old-a", "old-b"), state.update(listOf("old-a", "old-b"), 0))
        assertEquals(listOf("new"), state.update(listOf("new"), 101))
    }

    @Test fun previewKeepsCompleteLongValuesAndRejectsSelectionsAfterClearOrSessionChange() {
        val state = PreviewCandidateState()
        val longValue = "A".repeat(500)
        state.update(listOf(longValue), 100)
        val oldSelection = state.selectionToken()
        assertTrue(PreviewDeliveryPolicy.maySelect(true, state, longValue, 100, oldSelection))
        state.clear() // field, camera, mode, Help or IME visibility transition
        assertFalse(PreviewDeliveryPolicy.maySelect(true, state, longValue, 101, oldSelection))
        val callbacks = CallbackGeneration()
        val capturedGeneration = callbacks.current()
        assertTrue(callbacks.accepts(capturedGeneration))
        callbacks.advance()
        assertFalse(callbacks.accepts(capturedGeneration))
    }

    @Test fun previewSelectionRequiresTheExactProducingInputConnectionAndEditorMetadata() {
        val state = PreviewCandidateState()
        val connection = Any()
        val producedFor = EditorFieldKey(connection, "example.app", 17)
        val single = SingleScanGate().apply { moveTo(producedFor) }
        state.update(listOf("code"), 100)
        val token = state.selectionToken()

        assertTrue(PreviewDeliveryPolicy.maySelectFromContext(
            true, state, "code", 100, producedFor, EditorFieldKey(connection, "example.app", 17), token,
        ))
        assertFalse(single.isConsumed()) // A failed editor commit leaves the allowance available.
        assertFalse(PreviewDeliveryPolicy.maySelectFromContext(
            true, state, "code", 100, producedFor, EditorFieldKey(Any(), "example.app", 17), token,
        )) // Same positive field ID, recreated connection.
        assertFalse(PreviewDeliveryPolicy.maySelectFromContext(
            true, state, "code", 100, producedFor, EditorFieldKey(connection, "example.app", 18), token,
        ))
        assertFalse(PreviewDeliveryPolicy.maySelectFromContext(true, state, "code", 100, producedFor, null, token))
        assertTrue(single.consume()) // Only a successful insertion consumes it.
    }

    @Test fun previewContextValidationRetainsExpiryAndRejectsSelectionsAfterSessionChange() {
        val state = PreviewCandidateState(expiryMillis = 100)
        val connection = Any()
        val context = EditorFieldKey(connection, "example.app", 22)
        val sessions = ScanSessionGate()
        val originalSession = sessions.beginSession()
        state.update(listOf("session code"), 0)
        val token = state.selectionToken()
        assertTrue(PreviewDeliveryPolicy.maySelectFromContext(true, state, "session code", 50, context, context, token))
        assertFalse(PreviewDeliveryPolicy.maySelectFromContext(true, state, "session code", 101, context, context, token))

        sessions.endSession()
        sessions.beginSession()
        state.clear() // Input-session changes clear candidate state in the service.
        assertFalse(sessions.isCurrent(originalSession))
        assertFalse(PreviewDeliveryPolicy.maySelectFromContext(true, state, "session code", 102, context, context, token))
    }

    @Test fun focusWithoutPreviewUsesSingleCroppedDecodeAndInvalidCropFailsClosed() {
        assertEquals(FrameDecodeRequest.NORMAL, FrameDecodePolicy.request(false, false, true))
        assertEquals(FrameDecodeRequest.FOCUSED_SINGLE, FrameDecodePolicy.request(false, true, true))
        assertEquals(FrameDecodeRequest.MULTIPLE_CANDIDATES, FrameDecodePolicy.request(true, false, true))
        assertEquals(FrameDecodeRequest.MULTIPLE_CANDIDATES, FrameDecodePolicy.request(true, true, true))
        assertEquals(FrameDecodeRequest.FOCUS_UNAVAILABLE, FrameDecodePolicy.request(false, true, false))
        assertEquals(FrameDecodeRequest.FOCUS_UNAVAILABLE, FrameDecodePolicy.request(true, true, false))
    }

    @Test fun focusProductionCropMapperHandlesAllRightAngleRotationsAndAspectRatios() {
        val crop = FocusRegionGeometry.ImageRect(31, 17, 431, 217)
        val previewRect = FocusRegionGeometry.RectF(20f, 10f, 80f, 60f)
        val previewWidth = 100f
        val previewHeight = 100f
        val cropWidth = 400f
        val cropHeight = 200f

        // These affine transforms exercise the same preview-to-buffer mapping used in production.
        val expected = mapOf(
            0 to FocusRegionGeometry.ImageRect(111, 37, 351, 137),
            90 to FocusRegionGeometry.ImageRect(71, 57, 271, 177),
            180 to FocusRegionGeometry.ImageRect(111, 97, 351, 197),
            270 to FocusRegionGeometry.ImageRect(191, 57, 391, 177),
        )
        for (rotation in listOf(0, 90, 180, 270)) {
            val mapped = FocusRegionGeometry.mapPreviewRectToImageCrop(previewRect, crop) { rect ->
                val l = rect.left / previewWidth
                val t = rect.top / previewHeight
                val r = rect.right / previewWidth
                val b = rect.bottom / previewHeight
                when (rotation) {
                    0 -> FocusRegionGeometry.RectF(crop.left + l * cropWidth, crop.top + t * cropHeight, crop.left + r * cropWidth, crop.top + b * cropHeight)
                    90 -> FocusRegionGeometry.RectF(crop.left + t * cropWidth, crop.top + (1f - r) * cropHeight, crop.left + b * cropWidth, crop.top + (1f - l) * cropHeight)
                    180 -> FocusRegionGeometry.RectF(crop.left + (1f - r) * cropWidth, crop.top + (1f - b) * cropHeight, crop.left + (1f - l) * cropWidth, crop.top + (1f - t) * cropHeight)
                    270 -> FocusRegionGeometry.RectF(crop.left + (1f - b) * cropWidth, crop.top + l * cropHeight, crop.left + (1f - t) * cropWidth, crop.top + r * cropHeight)
                    else -> error("Unsupported test rotation $rotation")
                }
            }
            assertEquals("rotation $rotation", expected[rotation], mapped)
        }
    }

    @Test fun focusMapperClipsToNonZeroCropAndRejectsInvalidRegionsAndStaleTransforms() {
        val crop = FocusRegionGeometry.ImageRect(20, 10, 380, 190)
        val mapped = FocusRegionGeometry.mapPreviewRectToImageCrop(
            FocusRegionGeometry.RectF(-20f, 10f, 30f, 60f), crop,
        ) { rect ->
            // Different preview/analysis aspect ratios and a PreviewView resize are in buffer space.
            FocusRegionGeometry.RectF(20f + rect.left * 4f, 10f + rect.top * 2f, 20f + rect.right * 4f, 10f + rect.bottom * 2f)
        }
        assertEquals(FocusRegionGeometry.ImageRect(20, 30, 140, 130), mapped)
        assertNull(FocusRegionGeometry.mapPreviewRectToImageCrop(
            FocusRegionGeometry.RectF(0f, 0f, 10f, 10f), crop,
        ) { throw IllegalStateException("stale transform") })
        assertNull(FocusRegionGeometry.mapPreviewRectToImageCrop(
            FocusRegionGeometry.RectF(0f, 0f, 0f, 10f), crop,
        ) { it })
        assertNull(FocusRegionGeometry.mapPreviewRectToImageCrop(
            FocusRegionGeometry.RectF(0f, 0f, 10f, 10f), FocusRegionGeometry.ImageRect(4, 4, 4, 9),
        ) { it })
        assertNull(FocusRegionGeometry.mapPreviewRectToImageCrop(
            FocusRegionGeometry.RectF(0f, 0f, 10f, 10f), crop,
        ) { FocusRegionGeometry.RectF(500f, it.top, 600f, it.bottom) })
        assertNull(FocusRegionGeometry.mapPreviewRectToImageCrop(
            FocusRegionGeometry.RectF(0f, 0f, 10f, 10f), crop,
        ) { FocusRegionGeometry.RectF(Float.NaN, it.top, it.right, it.bottom) })
    }

    @Test fun focusRegionClampsAtEachPreviewEdgeAndTracksNormalizedCoordinates() {
        val positions = listOf(
            FocusRegionGeometry.clampRect(-50f, -50f, 40f, 40f, 100f, 80f, 10f, 10f),
            FocusRegionGeometry.clampRect(90f, -50f, 40f, 40f, 100f, 80f, 10f, 10f),
            FocusRegionGeometry.clampRect(-50f, 70f, 40f, 40f, 100f, 80f, 10f, 10f),
            FocusRegionGeometry.clampRect(90f, 70f, 40f, 40f, 100f, 80f, 10f, 10f),
        )
        assertEquals(FocusRegionGeometry.RectF(0f, 0f, 40f, 40f), positions[0])
        assertEquals(FocusRegionGeometry.RectF(60f, 0f, 100f, 40f), positions[1])
        assertEquals(FocusRegionGeometry.RectF(0f, 40f, 40f, 80f), positions[2])
        assertEquals(FocusRegionGeometry.RectF(60f, 40f, 100f, 80f), positions[3])
        val normalised = FocusRegionGeometry.pixelsToNormalized(positions[0], 100f, 80f)
        assertEquals(FocusRegionGeometry.RectF(0f, 0f, 0.4f, 0.5f), normalised)
    }

    @Test fun focusResizeSupportsNarrowWideMinimumMaximumAndPortraitLandscapeBounds() {
        val original = FocusRegionGeometry.RectF(20f, 10f, 60f, 30f)
        val narrow = FocusRegionGeometry.resizeFromBottomRight(original, -100f, -100f, 120f, 80f, 16f, 14f)
        assertEquals(FocusRegionGeometry.RectF(20f, 10f, 36f, 24f), narrow)
        val wide = FocusRegionGeometry.resizeFromBottomRight(original, 100f, 70f, 120f, 80f, 16f, 14f)
        assertEquals(FocusRegionGeometry.RectF(20f, 10f, 120f, 80f), wide)

        val portrait = FocusRegionGeometry.resizeFromBottomRight(
            FocusRegionGeometry.RectF(20f, 100f, 50f, 140f), 100f, 100f, 120f, 240f, 20f, 20f,
        )
        assertEquals(FocusRegionGeometry.RectF(20f, 100f, 120f, 240f), portrait)
        val landscape = FocusRegionGeometry.resizeFromBottomRight(
            FocusRegionGeometry.RectF(180f, 20f, 220f, 60f), 100f, 100f, 240f, 120f, 20f, 20f,
        )
        assertEquals(FocusRegionGeometry.RectF(180f, 20f, 240f, 120f), landscape)

        assertEquals(
            FocusRegionGeometry.RectF(0f, 0f, 40f, 30f),
            FocusRegionGeometry.clampRect(-5f, -5f, 40f, 30f, 100f, 80f, 20f, 20f),
        )
        assertEquals(
            FocusRegionGeometry.RectF(0f, 0f, 100f, 80f),
            FocusRegionGeometry.clampRect(40f, 30f, 200f, 200f, 100f, 80f, 20f, 20f),
        )
        assertEquals(FocusRegionGeometry.RectF(0f, 0f, 0f, 0f),
            FocusRegionGeometry.resizeFromBottomRight(original, Float.NaN, 10f, 100f, 100f, 10f, 10f))
    }

    @Test fun focusRegionKeepsNormalizedRectangleAcrossPreviewResizeAndCanResetToCentre() {
        val selected = FocusRegionGeometry.RectF(0.2f, 0.1f, 0.7f, 0.8f)
        val portrait = FocusRegionGeometry.normalizedToPixels(selected, 200f, 400f, 24f, 24f)
        val landscape = FocusRegionGeometry.normalizedToPixels(selected, 800f, 300f, 24f, 24f)
        assertEquals(FocusRegionGeometry.RectF(40f, 40f, 140f, 320f), portrait)
        assertEquals(FocusRegionGeometry.RectF(160f, 30f, 560f, 240f), landscape)
        assertEquals(selected, FocusRegionGeometry.pixelsToNormalized(landscape!!, 800f, 300f))

        assertEquals(FocusRegionGeometry.RectF(40f, 30f, 80f, 70f),
            FocusRegionGeometry.centeredRect(120f, 100f, 40f, 40f))

        val callbacks = CallbackGeneration()
        val regionState = FocusRegionState(callbacks, PreviewCandidateState(), selected)
        val oldGeneration = callbacks.current()
        val reset = FocusRegionGeometry.pixelsToNormalized(
            FocusRegionGeometry.centeredRect(120f, 100f, 40f, 40f), 120f, 100f,
        )!!
        assertTrue(regionState.update(reset, focusEnabled = true))
        assertFalse(callbacks.accepts(oldGeneration))
        assertEquals(reset, regionState.snapshot().region)
    }

    @Test fun focusResizingInvalidatesOldSuggestionsButPreservesSingleScanAllowance() {
        val callbacks = CallbackGeneration()
        val candidates = PreviewCandidateState()
        val focus = FocusRegionState(callbacks, candidates)
        val single = SingleScanGate()
        single.moveTo(EditorFieldKey(Any(), "example.app", 41))
        assertTrue(single.consume())
        focus.update(FocusRegionGeometry.RectF(0.2f, 0.2f, 0.6f, 0.6f), focusEnabled = true)
        candidates.update(listOf("before resize"), 10)
        val oldToken = candidates.selectionToken()
        val oldGeneration = callbacks.current()

        assertTrue(focus.update(FocusRegionGeometry.RectF(0.2f, 0.2f, 0.9f, 0.45f), focusEnabled = true))
        assertFalse(callbacks.accepts(oldGeneration))
        assertFalse(PreviewDeliveryPolicy.maySelect(true, candidates, "before resize", 11, oldToken))
        assertTrue(single.isConsumed())
    }

    @Test fun focusMovementAndResizeGeometryRejectInvalidOrEmptyCropRegions() {
        val moved = FocusRegionGeometry.clampRect(80f, 15f, 30f, 20f, 100f, 60f, 10f, 10f)
        assertEquals(FocusRegionGeometry.RectF(70f, 15f, 100f, 35f), moved)
        assertNull(FocusRegionGeometry.normalizedToPixels(
            FocusRegionGeometry.RectF(0.5f, 0.5f, 0.5f, 0.8f), 100f, 100f, 10f, 10f,
        ))
        assertNull(FocusRegionGeometry.pixelsToNormalized(FocusRegionGeometry.RectF(0f, 0f, 0f, 10f), 100f, 100f))
        assertNull(FocusRegionGeometry.mapPreviewRectToImageCrop(
            FocusRegionGeometry.RectF(0f, 0f, 20f, 20f), FocusRegionGeometry.ImageRect(0, 0, 0, 20),
        ) { it })
    }

    @Test fun focusMovementInvalidatesOldResultsAndPreviewCandidatesBeforeNewPositionIsAccepted() {
        val callbacks = CallbackGeneration()
        val candidates = PreviewCandidateState()
        val focus = FocusRegionState(callbacks, candidates)
        val first = FocusRegionGeometry.RectF(0.1f, 0.1f, 0.4f, 0.4f)
        val second = FocusRegionGeometry.RectF(0.6f, 0.2f, 0.9f, 0.5f)

        assertTrue(focus.update(first, focusEnabled = true))
        val oldResultGeneration = callbacks.current()
        val oldSelectionToken = candidates.selectionToken()
        candidates.update(listOf("old location"), 10)
        assertTrue(focus.update(second, focusEnabled = true))
        assertFalse(callbacks.accepts(oldResultGeneration))
        assertNull(focus.snapshotFor(oldResultGeneration))
        assertFalse(PreviewDeliveryPolicy.maySelect(true, candidates, "old location", 11, oldSelectionToken))

        val newSnapshot = focus.snapshotFor(callbacks.current())
        assertEquals(second, newSnapshot?.region)
        assertEquals(emptyList<String>(), candidates.update(emptyList(), 12))
        candidates.update(listOf("new location"), 13)
        assertTrue(PreviewDeliveryPolicy.maySelect(true, candidates, "new location", 13))
    }

    @Test fun rapidFocusMovementsKeepOnlyTheLatestGenerationAndDoNotInvalidateUnchangedRegion() {
        val callbacks = CallbackGeneration()
        val candidates = PreviewCandidateState()
        val focus = FocusRegionState(callbacks, candidates)
        val positions = listOf(
            FocusRegionGeometry.RectF(0.0f, 0.0f, 0.3f, 0.3f),
            FocusRegionGeometry.RectF(0.2f, 0.2f, 0.5f, 0.5f),
            FocusRegionGeometry.RectF(0.7f, 0.7f, 1.0f, 1.0f),
        )
        positions.forEach { focus.update(it, focusEnabled = true) }
        val latestGeneration = callbacks.current()
        assertEquals(positions.last(), focus.snapshotFor(latestGeneration)?.region)
        assertNull(focus.snapshotFor(latestGeneration - 1))
        candidates.update(listOf("candidate"), 20)
        val candidateToken = candidates.selectionToken()
        assertFalse(focus.update(positions.last(), focusEnabled = true))
        assertEquals(latestGeneration, callbacks.current())
        assertTrue(PreviewDeliveryPolicy.maySelect(true, candidates, "candidate", 20, candidateToken))
    }

    @Test fun focusMovementWithPreviewAndSingleScanInvalidatesCandidatesButKeepsFieldAllowance() {
        val callbacks = CallbackGeneration()
        val candidates = PreviewCandidateState()
        val focus = FocusRegionState(callbacks, candidates)
        val single = SingleScanGate()
        single.moveTo(EditorFieldKey(Any(), "example.app", 9))
        assertTrue(single.consume())
        candidates.update(listOf("visible"), 30)
        val previousGeneration = callbacks.current()
        assertTrue(focus.update(FocusRegionGeometry.RectF(0.3f, 0.3f, 0.7f, 0.7f), focusEnabled = true))
        assertFalse(callbacks.accepts(previousGeneration))
        assertFalse(singleScanDecodeAllowed(gate = single))
        assertEquals(emptyList<String>(), candidates.expire(30))
        single.moveTo(EditorFieldKey(Any(), "example.app", 10))
        assertTrue(singleScanDecodeAllowed(gate = single))
    }

    @Test fun singleScanConsumesOnlyAfterSuccessfulInsertionAndSurvivesViewRedraw() {
        val connection = Any()
        val gate = SingleScanGate()
        val field = EditorFieldKey(connection, "example.app", 12)
        gate.moveTo(field)
        assertFalse(gate.isConsumed()) // Failed insertion does not call consume.
        assertTrue(gate.consume())
        assertFalse(gate.consume()) // Repeated automatic scan / Preview choice is rejected.
        assertFalse(gate.moveTo(field)) // Input-view recreation in same field.
        assertTrue(gate.isConsumed())
        assertFalse(gate.moveTo(EditorFieldKey(Any(), "example.app", 12))) // Stable non-zero field ID survives connection recreation.
        assertTrue(gate.isConsumed())
        assertTrue(gate.moveTo(EditorFieldKey(connection, "example.app", 13)))
        assertFalse(gate.isConsumed())
    }

    @Test fun missingIdsAndRecreatedConnectionsNeverRearmAutomatically() {
        val gate = SingleScanGate()
        val connection = Any()
        gate.moveTo(EditorFieldKey(connection, "example.app", 0))
        assertTrue(gate.consume())
        assertFalse(gate.moveTo(EditorFieldKey(connection, "example.app", 0)))
        assertTrue(gate.isConsumed())
        assertFalse(gate.moveTo(EditorFieldKey(Any(), "example.app", 0)))
        assertTrue(gate.isConsumed())

        gate.moveTo(EditorFieldKey(connection, "example.app", 12))
        gate.consume()
        assertFalse(gate.moveTo(EditorFieldKey(Any(), "example.app", 12))) // A reused positive ID is ambiguous; stay blocked.
        assertTrue(gate.isConsumed())
    }

    @Test fun idlessConnectionKeepsCallbackContextButCannotProveAFieldTransition() {
        val connection = Any()
        val current = EditorFieldKey(connection, "example.app", 0, inputType = 1, imeOptions = 2)
        val gate = SingleScanGate()
        val controller = AutomaticActionController()
        val coordinator = ScanDeliveryCoordinator(gate, controller)

        coordinator.observeEditorContext(current)
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))
        assertTrue(coordinator.recordSuccessfulInsertion())
        assertFalse(coordinator.mayDeliverAutomatically(previewEnabled = false))
        assertTrue(current.isSameEditorContext(
            EditorFieldKey(connection, "example.app", 0, inputType = 1, imeOptions = 2),
        ))
        assertFalse(current.isSameEditorContext(
            EditorFieldKey(Any(), "example.app", 0, inputType = 1, imeOptions = 2),
        ))
        assertFalse(coordinator.observeEditorContext(
            EditorFieldKey(connection, "example.app", 0, inputType = 1, imeOptions = 2),
        ))
        assertFalse(coordinator.observeEditorContext(
            EditorFieldKey(Any(), "example.app", 0, inputType = 1, imeOptions = 2),
        ))
        assertTrue(gate.isConsumed())
    }

    @Test fun unknownEditorMetadataDoesNotEraseLastReliableIdentity() {
        val gate = SingleScanGate()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 71))
        assertTrue(gate.consume())

        gate.moveTo(EditorFieldKey(Any(), "example.app", 0))
        assertTrue(gate.isConsumed())
        assertTrue(gate.moveTo(EditorFieldKey(Any(), "example.app", 72)))
        assertFalse(gate.isConsumed())
    }

    @Test fun scannedFieldStaysProtectedWhenReturningAfterAnotherFieldWasScanned() {
        val coordinator = ScanDeliveryCoordinator(SingleScanGate(), AutomaticActionController())
        val fieldA = EditorFieldKey(Any(), "example.app", 701)
        val fieldB = EditorFieldKey(Any(), "example.app", 702)

        coordinator.observeEditorContext(fieldA)
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))
        assertTrue(coordinator.recordSuccessfulInsertion(fieldA))

        assertTrue(coordinator.observeEditorContext(fieldB))
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))
        assertTrue(coordinator.recordSuccessfulInsertion(fieldB))

        assertTrue(coordinator.observeEditorContext(EditorFieldKey(Any(), "example.app", 701)))
        assertFalse(coordinator.mayDeliverAutomatically(previewEnabled = false))
    }

    @Test fun scanAgainAndVerifiedUndoRearmOnlyTheCurrentFieldIdentity() {
        val gate = SingleScanGate()
        val coordinator = ScanDeliveryCoordinator(gate, AutomaticActionController())
        val fieldA = EditorFieldKey(Any(), "example.app", 711)
        val fieldB = EditorFieldKey(Any(), "example.app", 712)
        coordinator.observeEditorContext(fieldA)
        assertTrue(coordinator.recordSuccessfulInsertion(fieldA))
        coordinator.observeEditorContext(fieldB)
        assertTrue(coordinator.recordSuccessfulInsertion(fieldB))

        coordinator.observeEditorContext(EditorFieldKey(Any(), "example.app", 711))
        assertFalse(coordinator.rearmAfterVerifiedUndo(fieldIsEmpty = false))
        assertTrue(gate.isConsumed())
        val retryField = EditorFieldKey(Any(), "example.app", 711)
        coordinator.observeEditorContext(retryField)
        assertTrue(coordinator.rearmExplicitly(retryField))
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))
        assertTrue(coordinator.recordSuccessfulInsertion(retryField))
        assertFalse(coordinator.mayDeliverAutomatically(previewEnabled = false))

        assertFalse(coordinator.rearmAfterVerifiedUndo(fieldIsEmpty = false))
        assertTrue(gate.isConsumed())
        assertTrue(coordinator.rearmAfterVerifiedUndo(fieldIsEmpty = true))
        assertFalse(gate.isConsumed())
        coordinator.observeEditorContext(fieldB)
        coordinator.observeEditorContext(EditorFieldKey(Any(), "example.app", 711))
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))
    }

    @Test fun previewSelectionsRemainDeliberateAndProtectThatFieldAfterReturning() {
        val candidates = PreviewCandidateState()
        val gate = SingleScanGate()
        val coordinator = ScanDeliveryCoordinator(gate, AutomaticActionController())
        val fieldA = EditorFieldKey(Any(), "example.app", 721)
        val fieldB = EditorFieldKey(Any(), "example.app", 722)
        coordinator.observeEditorContext(fieldA)
        candidates.update(listOf("one", "two"), 10)
        val token = candidates.selectionToken()

        assertTrue(coordinator.maySelectPreview(true, candidates, "one", 10, fieldA, fieldA, token))
        assertTrue(coordinator.recordSuccessfulInsertion(fieldA))
        assertTrue(coordinator.maySelectPreview(true, candidates, "two", 10, fieldA, fieldA, token))
        assertFalse(coordinator.recordSuccessfulInsertion(fieldA))

        coordinator.observeEditorContext(fieldB)
        assertTrue(coordinator.recordSuccessfulInsertion(fieldB))
        coordinator.observeEditorContext(EditorFieldKey(Any(), "example.app", 721))
        assertFalse(coordinator.mayDeliverAutomatically(previewEnabled = false))
    }

    @Test fun serviceResetClearsIdentityHistoryWithoutRetainingEditorSessions() {
        val gate = SingleScanGate()
        val coordinator = ScanDeliveryCoordinator(gate, AutomaticActionController())
        val fieldA = EditorFieldKey(Any(), "example.app", 731)
        val fieldB = EditorFieldKey(Any(), "example.app", 732)
        coordinator.observeEditorContext(fieldA)
        assertTrue(coordinator.recordSuccessfulInsertion(fieldA))
        coordinator.onInputSessionFinished()
        coordinator.observeEditorContext(fieldB)
        coordinator.reset() // Mirrors InputMethodService.onDestroy and releases service-lifetime IDs.

        coordinator.observeEditorContext(EditorFieldKey(Any(), "example.app", 731))
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))
    }

    @Test fun previewCanRecordSeveralManualScansButNormalAndFocusStayBlocked() {
        val connection = Any()
        val field = EditorFieldKey(connection, "example.app", 0)
        val candidates = PreviewCandidateState()
        val gate = SingleScanGate()
        val coordinator = ScanDeliveryCoordinator(gate, AutomaticActionController())
        coordinator.observeEditorContext(field)
        val token = candidates.selectionToken()
        candidates.update(listOf("first", "second"), 10)

        assertTrue(coordinator.maySelectPreview(true, candidates, "first", 10, field, field, token))
        assertTrue(coordinator.recordSuccessfulInsertion())
        assertTrue(coordinator.maySelectPreview(true, candidates, "second", 10, field, field, token))
        assertFalse(coordinator.recordSuccessfulInsertion()) // Still recorded as used; only Preview bypasses the gate.
        assertFalse(coordinator.mayDeliverAutomatically(previewEnabled = false))
        assertFalse(coordinator.mayDeliverAutomatically(previewEnabled = false)) // Focus uses the same field allowance.
        assertTrue(coordinator.rearmExplicitly(field))
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))
    }

    @Test fun verifiedUndoReconcilesGateAndContinuousWaitOnlyWhenFieldIsEmpty() {
        val gate = SingleScanGate()
        val actions = AutomaticActionController()
        val coordinator = ScanDeliveryCoordinator(gate, actions)
        val field = EditorFieldKey(Any(), "example.app", 81)
        coordinator.observeEditorContext(field)
        assertTrue(coordinator.recordSuccessfulInsertion())
        actions.cycle(AutomaticScanAction.TAB, false, "example.app")
        actions.cycle(AutomaticScanAction.TAB, false, "example.app")
        actions.awaitFieldChange()

        assertFalse(coordinator.rearmAfterVerifiedUndo(fieldIsEmpty = false))
        assertTrue(gate.isConsumed())
        assertTrue(actions.isAwaitingFieldChange())
        assertTrue(coordinator.rearmAfterVerifiedUndo(fieldIsEmpty = true))
        assertFalse(gate.isConsumed())
        assertFalse(actions.isAwaitingFieldChange())
        assertEquals(AutomaticActionState.CONTINUOUS, actions.state(AutomaticScanAction.TAB))
    }

    @Test fun continuousWorkflowSurvivesInputFinishOnlyUntilSameAppFieldIsVerified() {
        val gate = SingleScanGate()
        val actions = AutomaticActionController()
        val coordinator = ScanDeliveryCoordinator(gate, actions)
        coordinator.observeEditorContext(EditorFieldKey(Any(), "example.app", 91))
        coordinator.recordSuccessfulInsertion()
        actions.cycle(AutomaticScanAction.ENTER, false, "example.app")
        actions.cycle(AutomaticScanAction.ENTER, false, "example.app")
        actions.awaitFieldChange()

        coordinator.onInputSessionFinished()
        assertEquals(AutomaticActionState.CONTINUOUS, actions.state(AutomaticScanAction.ENTER))
        assertTrue(actions.isAwaitingFieldChange())
        assertFalse(coordinator.mayDeliverAutomatically(previewEnabled = false))

        coordinator.observeEditorContext(EditorFieldKey(Any(), "example.app", 92))
        assertFalse(actions.isAwaitingFieldChange())
        assertFalse(gate.isConsumed())
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))
        actions.awaitFieldChange()
        coordinator.onInputSessionFinished()
        coordinator.observeEditorContext(EditorFieldKey(Any(), "other.app", 93))
        assertEquals(AutomaticActionState.OFF, actions.state(AutomaticScanAction.ENTER))
    }

    @Test fun continuousWorkflowSurvivesInputFinishAfterFieldTransitionWasAlreadyObserved() {
        val gate = SingleScanGate()
        val actions = AutomaticActionController()
        val coordinator = ScanDeliveryCoordinator(gate, actions)
        coordinator.observeEditorContext(EditorFieldKey(Any(), "example.app", 101))
        coordinator.recordSuccessfulInsertion()
        actions.cycle(AutomaticScanAction.TAB, false, "example.app")
        actions.cycle(AutomaticScanAction.TAB, false, "example.app")
        actions.awaitFieldChange()

        coordinator.observeEditorContext(EditorFieldKey(Any(), "example.app", 102))
        assertFalse(actions.isAwaitingFieldChange())
        coordinator.onInputSessionFinished()

        assertEquals(AutomaticActionState.CONTINUOUS, actions.state(AutomaticScanAction.TAB))
        assertFalse(gate.isConsumed())
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))
    }

    @Test fun onceActionSurvivesSameAppSessionChangeAndEndsAtWorkflowBoundaries() {
        val gate = SingleScanGate()
        val actions = AutomaticActionController()
        val coordinator = ScanDeliveryCoordinator(gate, actions)
        coordinator.observeEditorContext(EditorFieldKey(Any(), "example.app", 111))
        assertEquals(AutomaticActionState.ONCE,
            actions.cycle(AutomaticScanAction.TAB, false, "example.app"))

        coordinator.onInputSessionFinished()
        assertEquals(AutomaticActionState.ONCE, actions.state(AutomaticScanAction.TAB))
        val secondField = EditorFieldKey(Any(), "example.app", 112)
        coordinator.observeEditorContext(secondField)
        assertEquals(AutomaticActionState.ONCE, actions.state(AutomaticScanAction.TAB))
        // A failed insertion does not record success or consume the selected action.
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))
        assertFalse(gate.isConsumed())
        assertEquals(AutomaticActionState.ONCE, actions.state(AutomaticScanAction.TAB))

        assertTrue(coordinator.recordSuccessfulInsertion(secondField))
        actions.completeOnce()
        assertEquals(AutomaticActionState.OFF, actions.state(AutomaticScanAction.TAB))

        actions.cycle(AutomaticScanAction.ENTER, false, "example.app")
        coordinator.onInputSessionFinished()
        coordinator.observeEditorContext(EditorFieldKey(Any(), "other.app", 113))
        assertEquals(AutomaticActionState.OFF, actions.state(AutomaticScanAction.ENTER))

        actions.cycle(AutomaticScanAction.ENTER, false, "other.app")
        coordinator.onKeyboardHidden()
        assertEquals(AutomaticActionState.OFF, actions.state(AutomaticScanAction.ENTER))
        actions.cycle(AutomaticScanAction.ENTER, false, "other.app")
        coordinator.reset()
        assertEquals(AutomaticActionState.OFF, actions.state(AutomaticScanAction.ENTER))

        val unknownOrigin = AutomaticActionController()
        unknownOrigin.cycle(AutomaticScanAction.TAB, previewEnabled = false)
        unknownOrigin.onInputSessionFinished()
        assertEquals(AutomaticActionState.OFF, unknownOrigin.state(AutomaticScanAction.TAB))
    }

    @Test fun synchronousCommitFieldChangeConsumesOnlyTheScannedFieldAllowance() {
        val gate = SingleScanGate()
        val coordinator = ScanDeliveryCoordinator(gate, AutomaticActionController())
        val source = EditorFieldKey(Any(), "example.app", 121)
        val destination = EditorFieldKey(Any(), "example.app", 122)
        coordinator.observeEditorContext(source)
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))

        // Simulate onStartInput/syncActiveEditorField occurring inside the host's commitText call.
        coordinator.observeEditorContext(destination)
        assertFalse(coordinator.recordSuccessfulInsertion(source))
        assertFalse(gate.isConsumed())
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))

        coordinator.observeEditorContext(source)
        assertTrue(gate.isConsumed())
        assertFalse(coordinator.mayDeliverAutomatically(previewEnabled = false))

        coordinator.observeEditorContext(destination)
        assertTrue(coordinator.mayDeliverAutomatically(previewEnabled = false))
        assertTrue(coordinator.recordSuccessfulInsertion(destination))
        coordinator.observeEditorContext(source)
        assertFalse(coordinator.mayDeliverAutomatically(previewEnabled = false))
        coordinator.observeEditorContext(destination)
        assertFalse(coordinator.mayDeliverAutomatically(previewEnabled = false))
    }

    @Test fun synchronousSessionFinishDuringCommitRetainsTheSourceFieldAllowance() {
        val gate = SingleScanGate()
        val coordinator = ScanDeliveryCoordinator(gate, AutomaticActionController())
        val source = EditorFieldKey(Any(), "example.app", 131)
        coordinator.observeEditorContext(source)
        coordinator.onInputSessionFinished()

        assertFalse(coordinator.recordSuccessfulInsertion(source))
        coordinator.observeEditorContext(EditorFieldKey(Any(), "example.app", 131))
        assertTrue(gate.isConsumed())
    }

    @Test fun singleScanInputSessionRestartPreservesReliableFieldIdentity() {
        val gate = SingleScanGate()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 31))
        assertTrue(gate.consume())
        gate.finishInput() // Simulate a host finishing and reopening this same identifiable field.
        assertFalse(gate.moveTo(EditorFieldKey(Any(), "example.app", 31)))
        assertTrue(gate.isConsumed())
        assertTrue(gate.moveTo(EditorFieldKey(Any(), "example.app", 32)))
        assertFalse(gate.isConsumed())
    }

    @Test fun missingFieldIdRemainsConsumedAcrossSessionRecreation() {
        val gate = SingleScanGate()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 0))
        assertTrue(gate.consume())
        gate.finishInput()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 0))
        assertTrue(gate.isConsumed())
    }

    @Test fun singleScanConsumesOnlyAfterSuccessfulInsertionAndCanReset() {
        val single = SingleScanGate()
        val connection = Any()
        single.moveTo(EditorFieldKey(connection, "example.app", 5))
        assertFalse(single.isConsumed()) // rejected editor insertion leaves the allowance available
        assertTrue(single.consume()) // successful insertion uses the allowance
        assertFalse(single.consume()) // repeated scans are rejected in the consumed field
        // Enter/Tab has no effect unless Android later reports a different field.
        assertTrue(single.isConsumed())
        assertFalse(single.moveTo(EditorFieldKey(Any(), "example.app", 5)))
        assertTrue(single.isConsumed())
        single.reset() // Service reset clears all per-field delivery history.
        single.moveTo(EditorFieldKey(Any(), "example.app", 5))
        assertFalse(single.isConsumed())
        single.consume()
        single.moveTo(EditorFieldKey(Any(), "example.app", 5)) // re-enable while still in same field remains blocked
        assertTrue(single.isConsumed())
    }

    @Test fun oneScanDecodePolicySkipsConsumedFieldsUntilAStableFieldChange() {
        val gate = SingleScanGate()
        val callbacks = CallbackGeneration()
        val firstConnection = Any()
        gate.moveTo(EditorFieldKey(firstConnection, "example.app", 51))
        assertTrue(singleScanDecodeAllowed(gate = gate)) // Unused field.
        assertTrue(gate.consume())
        assertFalse(singleScanDecodeAllowed(gate = gate)) // One successful insertion.
        val delayedResultGeneration = callbacks.current()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 52))
        callbacks.advance() // Production field synchronization invalidates queued results here.
        assertTrue(singleScanDecodeAllowed(gate = gate)) // Recognised field change.
        assertFalse(callbacks.accepts(delayedResultGeneration))
    }

    @Test fun oneScanGateLeavesFailedInsertionActiveAndSurvivesCameraRestartAndStableSessionRestart() {
        val gate = SingleScanGate()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 61))
        assertTrue(singleScanDecodeAllowed(gate = gate)) // Failed commit does not consume.
        // Camera restart has no gate mutation; the same field remains eligible until insertion succeeds.
        assertTrue(singleScanDecodeAllowed(gate = gate))
        assertTrue(gate.consume())
        gate.finishInput()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 61))
        assertFalse(singleScanDecodeAllowed(gate = gate)) // Same identifiable field.
        gate.moveTo(EditorFieldKey(Any(), "example.app", 62))
        assertTrue(singleScanDecodeAllowed(gate = gate))
    }

    @Test fun modeChangesRejectCallbacksCapturedByAnOlderSettingsGeneration() {
        val generation = CallbackGeneration()
        val before = generation.current()
        assertTrue(generation.accepts(before))
        generation.advance() // Preview, Focus or Fast/Accurate changed while decoding.
        assertFalse(generation.accepts(before))
        assertTrue(generation.accepts(generation.current()))
    }

    private fun singleScanDecodeAllowed(gate: SingleScanGate): Boolean =
        SingleScanDecodePolicy.shouldDecode(gate.isConsumed())
}
