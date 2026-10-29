package net.hernow.barkey

import android.content.Context
import android.content.RestrictionsManager
import android.view.KeyEvent

internal enum class ScanSuffix(val keyCode: Int?) {
    NONE(null),
    ENTER(KeyEvent.KEYCODE_ENTER),
    TAB(KeyEvent.KEYCODE_TAB);

    companion object {
        fun fromValue(value: String?): ScanSuffix? = when (value?.lowercase()) {
            "none" -> NONE
            "enter" -> ENTER
            "tab" -> TAB
            else -> null
        }

        fun resolve(managedValue: String?): ScanSuffix = fromValue(managedValue) ?: NONE
    }
}

internal object ScanSettings {
    const val SUFFIX_RESTRICTION = "scan_suffix"

    fun suffixFor(context: Context): ScanSuffix {
        val restrictions = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
        val managedValue = restrictions?.applicationRestrictions?.getString(SUFFIX_RESTRICTION)
        return ScanSuffix.resolve(managedValue)
    }
}
