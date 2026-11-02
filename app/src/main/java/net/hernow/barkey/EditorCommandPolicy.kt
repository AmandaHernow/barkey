package net.hernow.barkey

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Describes the safe Enter action for the current editor. */
internal sealed class EnterCommand {
    data class PerformEditorAction(val action: Int) : EnterCommand()
    object InsertNewline : EnterCommand()
    object SendEnterKey : EnterCommand()
}

/** Tab only advances when the editor says it supports that action. */
internal enum class NextFieldCommand {
    PERFORM_EDITOR_ACTION,
    UNSUPPORTED,
}

/** Selects an explicitly advertised navigation action or a harmless no-op. */
internal object EditorCommandPolicy {
    /** Chooses an editor action, newline or Enter key from the field metadata. */
    fun enter(imeOptions: Int, inputType: Int): EnterCommand {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action in RECOGNIZED_ENTER_ACTIONS) {
            return EnterCommand.PerformEditorAction(action)
        }
        return if (isMultilineText(inputType)) EnterCommand.InsertNewline else EnterCommand.SendEnterKey
    }

    /** Refuses Tab when the host field does not advertise forward navigation. */
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

/** Converts host InputConnection exceptions into ordinary rejected-operation results. */
internal object EditorOperationGuard {
    /**
     * Runs one editor operation and reports RuntimeException through a caller-owned safe logger.
     * @return the operation's result, or false when the host rejected it by throwing.
     */
    fun run(operation: () -> Boolean, onFailure: (RuntimeException) -> Unit): Boolean = try {
        operation()
    } catch (error: RuntimeException) {
        onFailure(error)
        false
    }
}
