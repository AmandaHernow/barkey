package net.hernow.barkey

import android.content.Context

/** Stores only permission-flow state; no scanned text or camera data is persisted. */
internal object PermissionState {
    const val ACTION_PERMISSION_RESULT = "net.hernow.barkey.CAMERA_PERMISSION_RESULT"
    private const val PREFERENCES = "camera_permission_state"
    private const val KEY_SETTINGS_REQUIRED = "settings_required"

    fun isSettingsRequired(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETTINGS_REQUIRED, false)

    fun recordResult(context: Context, granted: Boolean, settingsRequired: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETTINGS_REQUIRED, !granted && settingsRequired)
            .apply()
    }
}
