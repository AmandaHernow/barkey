package net.hernow.barkey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionGateTest {
    @Test
    fun repeatedValueIsSuppressedForOneSecondAndThenAllowed() {
        val gate = ScanSessionGate()
        val session = gate.beginSession()

        assertTrue(gate.shouldAccept(session, "SKU-1", 100L))
        assertFalse(gate.shouldAccept(session, "SKU-1", 999L))
        assertTrue(gate.shouldAccept(session, "SKU-1", 1_100L))
    }

    @Test
    fun identicalValueCanBeScannedAgainAfterAnotherValue() {
        val gate = ScanSessionGate()
        val session = gate.beginSession()

        assertTrue(gate.shouldAccept(session, "SKU-1", 100L))
        assertTrue(gate.shouldAccept(session, "SKU-2", 200L))
        assertTrue(gate.shouldAccept(session, "SKU-1", 300L))
    }

    @Test
    fun startingOrEndingASessionClearsDuplicateStateAndRejectsOldResults() {
        val gate = ScanSessionGate()
        val firstSession = gate.beginSession()
        assertTrue(gate.shouldAccept(firstSession, "SKU-1", 100L))

        val nextSession = gate.beginSession()
        assertFalse(gate.isCurrent(firstSession))
        assertFalse(gate.shouldAccept(firstSession, "STALE", 200L))
        assertTrue(gate.shouldAccept(nextSession, "SKU-1", 200L))

        gate.endSession()
        assertFalse(gate.isCurrent(nextSession))
        assertFalse(gate.shouldAccept(nextSession, "STALE", 300L))
    }

    @Test
    fun emptyResultsAreNeverAccepted() {
        val gate = ScanSessionGate()
        val session = gate.beginSession()

        assertFalse(gate.shouldAccept(session, "", 100L))
    }
}
