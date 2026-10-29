package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorchStateControllerTest {
    @Test fun startsOffAndUpdatesOnlyAfterSuccessfulCameraCompletion() {
        val torch = TorchStateController()
        assertFalse(torch.isEnabled)
        val turnOn = torch.beginToggle(hasFlashUnit = true)
        assertNotNull(turnOn)
        assertFalse(torch.isEnabled)
        assertTrue(torch.isPending)

        assertTrue(torch.complete(turnOn!!, succeeded = true))
        assertTrue(torch.isEnabled)
        assertFalse(torch.isPending)
    }

    @Test fun failedRequestRestoresPreviousStateAndNoFlashCannotToggle() {
        val torch = TorchStateController()
        assertNull(torch.beginToggle(hasFlashUnit = false))
        val turnOn = torch.beginToggle(hasFlashUnit = true)!!
        assertTrue(torch.complete(turnOn, succeeded = true))
        val turnOff = torch.beginToggle(hasFlashUnit = true)!!

        assertTrue(torch.complete(turnOff, succeeded = false))
        assertTrue(torch.isEnabled)
    }

    @Test fun cameraStopInvalidatesPendingRequestAndResetsTorchOff() {
        val torch = TorchStateController()
        val request = torch.beginToggle(hasFlashUnit = true)!!

        torch.reset()

        assertFalse(torch.complete(request, succeeded = true))
        assertFalse(torch.isEnabled)
        assertFalse(torch.isPending)
    }
}
