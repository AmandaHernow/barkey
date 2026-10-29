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
    UNSUPPORTED,
}

/** Selects an explicitly advertised navigation action or a harmless no-op. */
internal object EditorCommandPolicy {
    fun enter(imeOptions: Int, inputType: Int): EnterCommand {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action in RECOGNIZED_ENTER_ACTIONS) {
            return EnterCommand.PerformEditorAction(action)
        }
        return if (isMultilineText(inputType)) EnterCommand.InsertNewline else EnterCommand.SendEnterKey
    }

    fun nextField(editorInfo: EditorInfo?): NextFieldCommand {
        if (editorInfo == null) return NextFieldCommand.UNSUPPORTED
        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        val advertisesNext = action == EditorInfo.IME_ACTION_NEXT ||
            (editorInfo.imeOptions and EditorInfo.IME_FLAG_NAVIGATE_NEXT) != 0
        return if (advertisesNext) NextFieldCommand.PERFORM_EDITOR_ACTION
        else NextFieldCommand.UNSUPPORTED
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
