package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedScanStateTest {
    @Test fun previewCandidatesAreUniqueAndExpireWithoutKeepingHistory() {
        val state = PreviewCandidateState(expiryMillis = 1_000)
        assertEquals(listOf("A", "B"), state.update(listOf("A", "A", "B"), 10))
        assertTrue(state.isSelectable("A", 10))
        assertEquals(listOf("A", "B"), state.update(listOf("B"), 900))
        assertEquals(listOf("B"), state.expire(1_500))
        assertFalse(state.isSelectable("A", 1_500))
        assertTrue(state.isSelectable("B", 1_500))
        assertEquals(emptyList<String>(), state.expire(1_901))
        state.clear()
        assertEquals(emptyList<String>(), state.expire(3_000))
    }

    @Test fun previewStateRefreshesRepeatedValuesAndDeduplicates() {
        val state = PreviewCandidateState()
        assertEquals(listOf("SKU", "LOT"), state.update(listOf("SKU", "SKU", "LOT"), 100))
        assertEquals(listOf("SKU", "LOT"), state.update(listOf("LOT", "SKU"), 200))
        assertEquals(emptyList<String>(), state.expire(1_301))
    }

    @Test fun scanRegionMapsPreviewCropAndOrientation() {
        val square = ScanRegionGeometry.RectF(25f, 25f, 75f, 75f)
        val mapped = ScanRegionGeometry.mapPreviewSquare(square, 100, 100, 200, 100, 0)
        assertEquals(75f, mapped!!.left, 0.01f)
        assertEquals(25f, mapped.top, 0.01f)
        assertEquals(125f, mapped.right, 0.01f)
        assertEquals(75f, mapped.bottom, 0.01f)
        assertTrue(ScanRegionGeometry.contains(mapped, 100f, 50f))
        assertFalse(ScanRegionGeometry.contains(mapped, 50f, 50f))

        val rotated = ScanRegionGeometry.mapPreviewSquare(
            ScanRegionGeometry.RectF(50f, 25f, 100f, 75f), 100, 100, 100, 200, 90,
        )
        assertEquals(25f, rotated!!.left, 0.01f)
        assertEquals(50f, rotated.top, 0.01f)
        assertEquals(75f, rotated.right, 0.01f)
        assertEquals(100f, rotated.bottom, 0.01f)

        val upsideDown = ScanRegionGeometry.mapPreviewSquare(
            ScanRegionGeometry.RectF(25f, 25f, 75f, 75f), 100, 100, 100, 200, 180,
        )!!
        assertEquals(25f, upsideDown.left, 0.01f)
        assertEquals(75f, upsideDown.top, 0.01f)
        assertEquals(75f, upsideDown.right, 0.01f)
        assertEquals(125f, upsideDown.bottom, 0.01f)

        val rotated270 = ScanRegionGeometry.mapPreviewSquare(
            ScanRegionGeometry.RectF(50f, 25f, 100f, 75f), 100, 100, 100, 200, 270,
        )!!
        assertEquals(25f, rotated270.left, 0.01f)
        assertEquals(100f, rotated270.top, 0.01f)
        assertEquals(75f, rotated270.right, 0.01f)
        assertEquals(150f, rotated270.bottom, 0.01f)
    }

    @Test fun scanRegionHandlesAspectRatioCropAndClampsToImageCrop() {
        val mapped = ScanRegionGeometry.mapPreviewSquare(
            ScanRegionGeometry.RectF(-20f, 10f, 30f, 60f), 100, 100, 400, 200, 0,
            ScanRegionGeometry.RectF(20f, 10f, 380f, 190f),
        )!!
        assertTrue(mapped.left >= 20f && mapped.top >= 10f)
        assertTrue(mapped.right <= 380f && mapped.bottom <= 190f)
        assertTrue(mapped.right > mapped.left && mapped.bottom > mapped.top)
        assertNull(ScanRegionGeometry.mapPreviewSquare(
            ScanRegionGeometry.RectF(0f, 0f, 10f, 10f), 0, 100, 100, 100, 0,
        ))
        val clamped = ScanRegionGeometry.clampSquare(-10f, 80f, 40f, 100f, 100f)
        assertEquals(0f, clamped.left, 0f)
        assertEquals(60f, clamped.top, 0f)
        assertEquals(40f, clamped.right, 0f)
        assertEquals(100f, clamped.bottom, 0f)
    }

    @Test fun singleScanConsumesOnlyAfterSuccessAndSurvivesViewRecreation() {
        val connection = Any()
        val gate = SingleScanGate()
        val field = EditorFieldKey(connection, "example.app", 12)
        gate.moveTo(field)
        assertFalse(gate.isConsumed()) // failed insertion leaves the allowance available
        assertTrue(gate.consume()) // only a successful insertion calls consume
        gate.moveTo(field) // a keyboard/input-view redraw retains the same editor identity
        assertTrue(gate.isConsumed())
        gate.moveTo(EditorFieldKey(Any(), "example.app", 12)) // the same reported field survives connection recreation
        assertTrue(gate.isConsumed())
        assertFalse(gate.consume())
        gate.moveTo(EditorFieldKey(connection, "example.app", 13))
        assertFalse(gate.isConsumed())
    }

    @Test fun singleScanUsesInputConnectionIdentityAndDoesNotResetForSuffixActions() {
        val gate = SingleScanGate()
        val connection = Any()
        gate.moveTo(EditorFieldKey(connection, "example.app", 0))
        gate.consume()
        // Enter/Tab actions do not call moveTo, so they cannot reset the allowance.
        assertTrue(gate.isConsumed())
        gate.moveTo(EditorFieldKey(Any(), "example.app", 0))
        assertFalse(gate.isConsumed())
    }

    @Test fun previewSelectionConsumesSingleScanOnlyAfterSuccessfulInsertion() {
        val preview = PreviewCandidateState()
        val single = SingleScanGate()
        single.moveTo(EditorFieldKey(Any(), "example.app", 5))
        preview.update(listOf("chosen", "other"), 100)
        assertTrue(preview.isSelectable("chosen", 100))
        assertFalse(single.isConsumed()) // failed delivery does not consume the field allowance
        assertTrue(single.consume()) // the service does this only after commitText succeeds
        assertTrue(single.isConsumed())
    }

    @Test fun swedishKeyboardHandlesCaseSymbolsAndLayoutTransitions() {
        val keyboard = SwedishKeyboardState()
        assertEquals("å", keyboard.consumeCharacter("å"))
        keyboard.toggleShift()
        assertEquals("Ä", keyboard.consumeCharacter("ä"))
        assertFalse(keyboard.shifted)
        keyboard.toggleSymbols()
        assertTrue(keyboard.symbols)
        assertEquals("1", keyboard.consumeCharacter("1"))
        keyboard.toggleSymbols()
        assertFalse(keyboard.symbols)
    }

    @Test fun manualKeyboardTransitionsBetweenScannerAndKeyboard() {
        val mode = ScannerKeyboardMode()
        assertFalse(mode.isKeyboardActive())
        assertTrue(mode.showKeyboard())
        assertTrue(mode.isKeyboardActive())
        assertFalse(mode.showKeyboard())
        assertTrue(mode.showScanner())
        assertFalse(mode.isKeyboardActive())
        assertFalse(mode.showScanner())
    }

    @Test fun modeChangesRejectCallbacksCapturedByAnOlderSettingsGeneration() {
        val generation = CallbackGeneration()
        val before = generation.current()
        assertTrue(generation.accepts(before))
        generation.advance() // Preview, Focus or Keyboard changed while a frame was decoding.
        assertFalse(generation.accepts(before))
        assertTrue(generation.accepts(generation.current()))
    }
}
