package net.hernow.barkey

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanSuffixTest {
    @Test
    fun missingAndInvalidManagedValuesDefaultToNoSuffix() {
        assertEquals(ScanSuffix.NONE, ScanSuffix.resolve(null))
        assertEquals(ScanSuffix.NONE, ScanSuffix.resolve("unsupported"))
    }

    @Test
    fun allSupportedValuesMapToExpectedKeyEvents() {
        assertEquals(ScanSuffix.NONE, ScanSuffix.fromValue("none"))
        assertEquals(KeyEvent.KEYCODE_ENTER, ScanSuffix.fromValue("enter")?.keyCode)
        assertEquals(KeyEvent.KEYCODE_TAB, ScanSuffix.fromValue("tab")?.keyCode)
    }

    @Test
    fun managedValueSelectsSuffixAndInvalidValueFallsBackToNone() {
        assertEquals(ScanSuffix.TAB, ScanSuffix.resolve("tab"))
        assertEquals(ScanSuffix.ENTER, ScanSuffix.resolve("enter"))
        assertEquals(ScanSuffix.NONE, ScanSuffix.resolve("bad"))
    }
}
