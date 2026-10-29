package net.hernow.barkey

/** Minimal editor surface used by editing logic and its platform-independent tests. */
internal interface EditorConnection {
    val identity: Any

    fun finishComposingText(): Boolean
    fun commitText(text: String, newCursorPosition: Int): Boolean
    fun cursorSnapshot(beforeLength: Int, afterLength: Int): EditorCursorSnapshot?
    fun textBeforeCursor(length: Int): String?
    fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    fun performEditorAction(action: Int): Boolean
    fun sendKeyEvent(keyCode: Int, action: Int): Boolean
}

/** Snapshot only carries local cursor context and selection coordinates, never a whole field. */
internal data class EditorCursorSnapshot(
    val textBeforeCursor: String?,
    val textAfterCursor: String?,
    val selectionStart: Int,
    val selectionEnd: Int,
    val absoluteCursorPosition: Int?,
) {
    val hasSelection: Boolean
        get() = selectionStart != selectionEnd
}
