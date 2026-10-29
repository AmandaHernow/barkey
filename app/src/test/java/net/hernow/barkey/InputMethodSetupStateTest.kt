package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Test

class InputMethodSetupStateTest {
    @Test
    fun reportsDisabledAndUnselectedWhenServiceIsNotEnabled() {
        assertEquals(
            InputMethodSetupState(enabled = false, selected = false),
            InputMethodSetupState.resolve(
                enabledIds = listOf("other.package/OtherKeyboard"),
                selectedId = "net.hernow.barkey/net.hernow.barkey.BarcodeInputService",
                componentId = "net.hernow.barkey/net.hernow.barkey.BarcodeInputService",
            ),
        )
    }

    @Test
    fun reportsEnabledButNotSelected() {
        val component = "net.hernow.barkey/net.hernow.barkey.BarcodeInputService"
        assertEquals(
            InputMethodSetupState(enabled = true, selected = false),
            InputMethodSetupState.resolve(
                enabledIds = listOf(component, "other.package/OtherKeyboard"),
                selectedId = "other.package/OtherKeyboard",
                componentId = component,
            ),
        )
    }

    @Test
    fun selectedRequiresAndReportsEnabledKeyboard() {
        val component = "net.hernow.barkey/net.hernow.barkey.BarcodeInputService"
        assertEquals(
            InputMethodSetupState(enabled = true, selected = true),
            InputMethodSetupState.resolve(
                enabledIds = listOf(component),
                selectedId = component,
                componentId = component,
            ),
        )
    }

    @Test
    fun componentIdMatchingIgnoresCase() {
        val component = "net.hernow.barkey/net.hernow.barkey.BarcodeInputService"
        assertEquals(
            InputMethodSetupState(enabled = true, selected = true),
            InputMethodSetupState.resolve(
                enabledIds = listOf(component.uppercase()),
                selectedId = component.uppercase(),
                componentId = component,
            ),
        )
    }
}
