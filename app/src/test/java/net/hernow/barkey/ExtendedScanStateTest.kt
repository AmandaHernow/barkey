package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

        // Inject the same crop-local transform callback used to wrap CameraX CoordinateTransform
        // in production. These affine equivalents exercise the mapper's rotation/clipping/offset.
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
                    0 -> FocusRegionGeometry.RectF(l * cropWidth, t * cropHeight, r * cropWidth, b * cropHeight)
                    90 -> FocusRegionGeometry.RectF(t * cropWidth, (1f - r) * cropHeight, b * cropWidth, (1f - l) * cropHeight)
                    180 -> FocusRegionGeometry.RectF((1f - r) * cropWidth, (1f - b) * cropHeight, (1f - l) * cropWidth, (1f - t) * cropHeight)
                    270 -> FocusRegionGeometry.RectF((1f - b) * cropWidth, l * cropHeight, (1f - t) * cropWidth, r * cropHeight)
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
            // Different preview/analysis aspect ratios and a PreviewView resize are represented
            // by CameraX's current transform; its result must remain crop-local.
            FocusRegionGeometry.RectF(rect.left * 4f, rect.top * 2f, rect.right * 4f, rect.bottom * 2f)
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

    @Test fun focusSquareClampsAtEachPreviewEdgeAndTracksResizedPreviewCoordinates() {
        val positions = listOf(
            FocusRegionGeometry.clampSquare(-50f, -50f, 40f, 100f, 80f),
            FocusRegionGeometry.clampSquare(90f, -50f, 40f, 100f, 80f),
            FocusRegionGeometry.clampSquare(-50f, 70f, 40f, 100f, 80f),
            FocusRegionGeometry.clampSquare(90f, 70f, 40f, 100f, 80f),
        )
        assertEquals(FocusRegionGeometry.RectF(0f, 0f, 40f, 40f), positions[0])
        assertEquals(FocusRegionGeometry.RectF(60f, 0f, 100f, 40f), positions[1])
        assertEquals(FocusRegionGeometry.RectF(0f, 40f, 40f, 80f), positions[2])
        assertEquals(FocusRegionGeometry.RectF(60f, 40f, 100f, 80f), positions[3])
        val resized = FocusRegionGeometry.RectF(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(200f, resized.left * 800f, 0f)
        assertEquals(100f, resized.top * 400f, 0f)
        assertEquals(FocusRegionGeometry.RectF(0f, 0f, 0f, 0f), FocusRegionGeometry.clampSquare(0f, 0f, 20f, 0f, 50f))
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
        assertFalse(singleScanDecodeAllowed(enabled = true, gate = single))
        assertEquals(emptyList<String>(), candidates.expire(30))
        single.moveTo(EditorFieldKey(Any(), "example.app", 10))
        assertTrue(singleScanDecodeAllowed(enabled = true, gate = single))
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

    @Test fun singleScanUsesConnectionFallbackForMissingIdsAndTreatsReusedIdsConservatively() {
        val gate = SingleScanGate()
        val connection = Any()
        gate.moveTo(EditorFieldKey(connection, "example.app", 0))
        assertTrue(gate.consume())
        assertFalse(gate.moveTo(EditorFieldKey(connection, "example.app", 0)))
        assertTrue(gate.isConsumed())
        assertTrue(gate.moveTo(EditorFieldKey(Any(), "example.app", 0)))
        assertFalse(gate.isConsumed())

        gate.moveTo(EditorFieldKey(connection, "example.app", 12))
        gate.consume()
        assertFalse(gate.moveTo(EditorFieldKey(Any(), "example.app", 12))) // A reused positive ID is ambiguous; stay blocked.
        assertTrue(gate.isConsumed())
    }

    @Test fun singleScanInputSessionRestartKeepsStableFieldAllowanceAndReleasesConnectionReference() {
        val gate = SingleScanGate()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 31))
        assertTrue(gate.consume())
        gate.finishInput() // Simulate a host finishing and reopening this same identifiable field.
        assertFalse(gate.moveTo(EditorFieldKey(Any(), "example.app", 31)))
        assertTrue(gate.isConsumed())
        assertTrue(gate.moveTo(EditorFieldKey(Any(), "example.app", 32)))
        assertFalse(gate.isConsumed())
    }

    @Test fun singleScanWithMissingFieldIdResetsAtSessionBoundaryBecauseIdentityIsUnavailable() {
        val gate = SingleScanGate()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 0))
        assertTrue(gate.consume())
        gate.finishInput()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 0))
        assertFalse(gate.isConsumed())
    }

    @Test fun singleScanPreviewFailedSelectionAndSuffixDoNotConsumeOrResetAllowance() {
        val preview = PreviewCandidateState()
        val single = SingleScanGate()
        val connection = Any()
        single.moveTo(EditorFieldKey(connection, "example.app", 5))
        preview.update(listOf("chosen", "other"), 100)
        assertTrue(PreviewDeliveryPolicy.maySelect(true, preview, "chosen", 100))
        assertFalse(single.isConsumed()) // rejected editor insertion leaves the allowance available
        assertTrue(single.consume()) // successful preview insertion
        assertFalse(single.consume()) // repeated Preview selection is rejected in the consumed field
        // Enter/Tab has no effect unless Android later reports a different field.
        assertTrue(single.isConsumed())
        assertFalse(single.moveTo(EditorFieldKey(Any(), "example.app", 5)))
        assertTrue(single.isConsumed())
        single.reset() // disabling Single Scan returns to continuous mode
        single.moveTo(EditorFieldKey(Any(), "example.app", 5))
        assertFalse(single.isConsumed())
        single.consume()
        single.moveTo(EditorFieldKey(Any(), "example.app", 5)) // re-enable while still in same field remains blocked
        assertTrue(single.isConsumed())
    }

    @Test fun singleScanDecodePolicySkipsOnlyConsumedEnabledFieldsAndResumesOnModeOrFieldChange() {
        val gate = SingleScanGate()
        val callbacks = CallbackGeneration()
        val firstConnection = Any()
        gate.moveTo(EditorFieldKey(firstConnection, "example.app", 51))
        assertTrue(singleScanDecodeAllowed(enabled = false, gate = gate)) // Continuous mode.
        assertTrue(singleScanDecodeAllowed(enabled = true, gate = gate)) // Unused field.
        assertTrue(gate.consume())
        assertFalse(singleScanDecodeAllowed(enabled = true, gate = gate)) // One successful insertion.
        assertTrue(singleScanDecodeAllowed(enabled = false, gate = gate)) // Disabling immediately resumes.
        val delayedResultGeneration = callbacks.current()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 52))
        callbacks.advance() // Production field synchronization invalidates queued results here.
        assertTrue(singleScanDecodeAllowed(enabled = true, gate = gate)) // Recognised field change.
        assertFalse(callbacks.accepts(delayedResultGeneration))
    }

    @Test fun singleScanDecodePolicyLeavesFailedInsertionActiveAndSurvivesCameraRestartAndStableSessionRestart() {
        val gate = SingleScanGate()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 61))
        assertTrue(singleScanDecodeAllowed(enabled = true, gate = gate)) // Failed commit does not consume.
        // Camera restart has no gate mutation; the same field remains eligible until insertion succeeds.
        assertTrue(singleScanDecodeAllowed(enabled = true, gate = gate))
        assertTrue(gate.consume())
        gate.finishInput()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 61))
        assertFalse(singleScanDecodeAllowed(enabled = true, gate = gate)) // Same identifiable field.
        gate.moveTo(EditorFieldKey(Any(), "example.app", 62))
        assertTrue(singleScanDecodeAllowed(enabled = true, gate = gate))
    }

    @Test fun modeChangesRejectCallbacksCapturedByAnOlderSettingsGeneration() {
        val generation = CallbackGeneration()
        val before = generation.current()
        assertTrue(generation.accepts(before))
        generation.advance() // Preview, Focus, Single Scan or Fast/Accurate changed while decoding.
        assertFalse(generation.accepts(before))
        assertTrue(generation.accepts(generation.current()))
    }

    private fun singleScanDecodeAllowed(enabled: Boolean, gate: SingleScanGate): Boolean =
        SingleScanDecodePolicy.shouldDecode(enabled, gate.isConsumed())
}
