package net.hernow.barkey

import android.content.Context
import android.content.RestrictionsManager
import android.view.KeyEvent

/** Optional key sent after a scan, as set by device management. */
internal enum class ScanSuffix(val keyCode: Int?) {
    NONE(null),
    ENTER(KeyEvent.KEYCODE_ENTER),
    TAB(KeyEvent.KEYCODE_TAB);

    companion object {
        /** Parses only the suffix values supported by managed configuration. */
        fun fromValue(value: String?): ScanSuffix? = when (value?.lowercase()) {
            "none" -> NONE
            "enter" -> ENTER
            "tab" -> TAB
            else -> null
        }

        /** Uses no suffix when the managed value is missing or invalid. */
        fun resolve(managedValue: String?): ScanSuffix = fromValue(managedValue) ?: NONE
    }
}

/** Reads the managed scan suffix and falls back to no action. */
internal object ScanSettings {
    const val SUFFIX_RESTRICTION = "scan_suffix"

    /** Reads the optional suffix without storing scanned text. */
    fun suffixFor(context: Context): ScanSuffix {
        val restrictions = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
        val managedValue = restrictions?.applicationRestrictions?.getString(SUFFIX_RESTRICTION)
        return ScanSuffix.resolve(managedValue)
    }
}
