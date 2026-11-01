// Copyright 2026 Amanda Hernow. Licensed under PolyForm Noncommercial 1.0.0.
package net.hernow.barkey

/** The settings that Android lets an app observe for its own input method. */
internal data class InputMethodSetupState(
    val enabled: Boolean,
    val selected: Boolean,
) {
    companion object {
        /** Compares Android's enabled list and selected service ID with BarKey's ID. */
        fun resolve(enabledIds: Collection<String>, selectedId: String?, componentId: String): InputMethodSetupState {
            val enabled = enabledIds.any { it.equals(componentId, ignoreCase = true) }
            return InputMethodSetupState(
                enabled = enabled,
                selected = enabled && selectedId.equals(componentId, ignoreCase = true),
            )
        }
    }
}
