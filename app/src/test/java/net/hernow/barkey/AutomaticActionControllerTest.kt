package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the automatic-action state machine without Android UI dependencies. */
class AutomaticActionControllerTest {
    @Test fun eachActionCyclesOffOnceContinuousOff() {
        for (action in AutomaticScanAction.entries) {
            val controller = AutomaticActionController()
            assertEquals(AutomaticActionState.OFF, controller.state(action))
            assertEquals(AutomaticActionState.ONCE, controller.cycle(action, previewEnabled = false))
            assertEquals(AutomaticActionState.CONTINUOUS, controller.cycle(action, previewEnabled = false))
            assertEquals(AutomaticActionState.OFF, controller.cycle(action, previewEnabled = false))
        }
    }

    @Test fun selectingOtherActionTurnsFirstOffAndStartsOtherAtOnce() {
        val controller = AutomaticActionController()
        controller.cycle(AutomaticScanAction.TAB, previewEnabled = false, editorPackageName = "example.app")
        controller.cycle(AutomaticScanAction.TAB, previewEnabled = false, editorPackageName = "example.app")
        assertEquals(AutomaticActionState.CONTINUOUS, controller.state(AutomaticScanAction.TAB))

        assertEquals(AutomaticActionState.ONCE, controller.cycle(AutomaticScanAction.ENTER, previewEnabled = false))
        assertEquals(AutomaticActionState.OFF, controller.state(AutomaticScanAction.TAB))
        assertEquals(AutomaticActionState.ONCE, controller.state(AutomaticScanAction.ENTER))
    }

    @Test fun previewResetsActionsAndRejectsCyclingUntilPreviewEnds() {
        val controller = AutomaticActionController()
        controller.cycle(AutomaticScanAction.TAB, previewEnabled = false)
        controller.onPreviewEnabled()
        assertEquals(AutomaticActionState.OFF, controller.state(AutomaticScanAction.TAB))
        assertNull(controller.actionForScan(previewEnabled = true))
        assertEquals(AutomaticActionState.OFF,
            controller.cycle(AutomaticScanAction.ENTER, previewEnabled = true))
        assertEquals(AutomaticActionState.OFF, controller.state(AutomaticScanAction.ENTER))
    }

    @Test fun onceCompletesAfterOneSuccessfulInsertionAndContinuousWaitsForNewField() {
        val controller = AutomaticActionController()
        controller.cycle(AutomaticScanAction.TAB, previewEnabled = false)
        assertEquals(AutomaticScanAction.TAB, controller.actionForScan(previewEnabled = false))
        controller.completeOnce()
        assertNull(controller.actionForScan(previewEnabled = false))
        assertEquals(AutomaticActionState.OFF, controller.state(AutomaticScanAction.TAB))

        controller.cycle(AutomaticScanAction.ENTER, previewEnabled = false, editorPackageName = "example.app")
        controller.cycle(AutomaticScanAction.ENTER, previewEnabled = false, editorPackageName = "example.app")
        controller.awaitFieldChange()
        assertTrue(controller.isAwaitingFieldChange())
        assertNull(controller.actionForScan(previewEnabled = false))
        assertEquals(AutomaticActionState.OFF, controller.stateForScan(previewEnabled = false))
        assertTrue(controller.onVerifiedFieldChanged("example.app"))
        assertFalse(controller.isAwaitingFieldChange())
        assertEquals(AutomaticScanAction.ENTER, controller.actionForScan(previewEnabled = false))
    }

    @Test fun continuousModeResumesOnlyAfterGateConfirmsANewStableField() {
        val controller = AutomaticActionController()
        val gate = SingleScanGate()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 41))
        assertTrue(gate.consume())
        controller.cycle(AutomaticScanAction.TAB, previewEnabled = false, editorPackageName = "example.app")
        controller.cycle(AutomaticScanAction.TAB, previewEnabled = false, editorPackageName = "example.app")
        controller.awaitFieldChange()

        assertFalse(gate.moveTo(EditorFieldKey(Any(), "example.app", 41)))
        assertNull(controller.actionForScan(previewEnabled = false))
        assertTrue(gate.isConsumed())

        assertTrue(gate.moveTo(EditorFieldKey(Any(), "example.app", 42)))
        assertTrue(controller.onVerifiedFieldChanged("example.app"))
        assertFalse(gate.isConsumed())
        assertEquals(AutomaticScanAction.TAB, controller.actionForScan(previewEnabled = false))
    }

    @Test fun recreatedConnectionDoesNotRearmAndExplicitScanAgainDoes() {
        val gate = SingleScanGate()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 22))
        assertTrue(gate.consume())
        assertFalse(gate.moveTo(EditorFieldKey(Any(), "example.app", 22)))
        assertTrue(gate.isConsumed())
        assertTrue(gate.rearmExplicitly())
        assertFalse(gate.isConsumed())
    }

    @Test fun idlessFieldRequiresExplicitRearmEvenAcrossInputSessionRestart() {
        val gate = SingleScanGate()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 0))
        assertTrue(gate.consume())
        gate.finishInput()
        gate.moveTo(EditorFieldKey(Any(), "example.app", 0))
        assertTrue(gate.isConsumed())
        assertTrue(gate.rearmExplicitly())
    }

    @Test fun fieldTransitionIsNotProvenByAnIdlessOrRecreatedConnection() {
        val first = EditorFieldKey(Any(), "example.app", 0)
        assertFalse(first.identifiesDifferentReliableField(EditorFieldKey(Any(), "example.app", 0)))
        assertFalse(EditorFieldKey(Any(), "example.app", 51)
            .identifiesDifferentReliableField(EditorFieldKey(Any(), "example.app", 51)))
        assertTrue(EditorFieldKey(Any(), "example.app", 51)
            .identifiesDifferentReliableField(EditorFieldKey(Any(), "example.app", 52)))
    }

    @Test fun automaticActionReplacesManagedSuffixButPreviewNeverRunsIt() {
        assertEquals(
            ScanPostAction.Automatic(AutomaticScanAction.TAB),
            ScanPostActionPolicy.resolve(AutomaticScanAction.TAB, false, ScanSuffix.ENTER),
        )
        assertEquals(
            ScanPostAction.ManagedSuffix(ScanSuffix.ENTER),
            ScanPostActionPolicy.resolve(AutomaticScanAction.ENTER, true, ScanSuffix.ENTER),
        )
        assertEquals(
            ScanPostAction.None,
            ScanPostActionPolicy.resolve(null, false, ScanSuffix.NONE),
        )
    }

    @Test fun focusRemainsCompatibleWithAutomaticActionsWhilePreviewUsesCandidateDecode() {
        val controller = AutomaticActionController()
        controller.cycle(AutomaticScanAction.TAB, previewEnabled = false)
        assertEquals(FrameDecodeRequest.FOCUSED_SINGLE, FrameDecodePolicy.request(
            previewEnabled = false, focusEnabled = true, cropAvailable = true,
        ))
        controller.onPreviewEnabled()
        assertEquals(FrameDecodeRequest.MULTIPLE_CANDIDATES, FrameDecodePolicy.request(
            previewEnabled = true, focusEnabled = true, cropAvailable = true,
        ))
        assertNull(controller.actionForScan(previewEnabled = true))
    }
}
