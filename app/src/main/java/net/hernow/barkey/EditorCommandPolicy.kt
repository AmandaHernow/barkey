package net.hernow.barkey

import android.text.InputType
import android.view.inputmethod.EditorInfo

internal sealed class EnterCommand {
    data class PerformEditorAction(val action: Int) : EnterCommand()
    object InsertNewline : EnterCommand()
    object SendEnterKey : EnterCommand()
}

internal enum class NextFieldCommand {
    PERFORM_EDITOR_ACTION,
    SEND_TAB_KEY,
    UNSUPPORTED,
}

/** Makes action-versus-key fallback decisions once, so a failed action is never retried twice. */
internal object EditorCommandPolicy {
    fun enter(imeOptions: Int, inputType: Int): EnterCommand {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action in RECOGNIZED_ENTER_ACTIONS) {
            return EnterCommand.PerformEditorAction(action)
        }
        return if (isMultilineText(inputType)) EnterCommand.InsertNewline else EnterCommand.SendEnterKey
    }

    fun nextField(imeOptions: Int, inputType: Int): NextFieldCommand {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val advertisesNext = action == EditorInfo.IME_ACTION_NEXT ||
            (imeOptions and EditorInfo.IME_FLAG_NAVIGATE_NEXT) != 0
        if (advertisesNext) return NextFieldCommand.PERFORM_EDITOR_ACTION
        return if (isMultilineText(inputType)) NextFieldCommand.UNSUPPORTED
        else NextFieldCommand.SEND_TAB_KEY
    }

    private fun isMultilineText(inputType: Int): Boolean =
        (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

    private val RECOGNIZED_ENTER_ACTIONS = setOf(
        EditorInfo.IME_ACTION_DONE,
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_NEXT,
        EditorInfo.IME_ACTION_PREVIOUS,
        EditorInfo.IME_ACTION_SEARCH,
        EditorInfo.IME_ACTION_SEND,
    )
}
