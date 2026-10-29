package net.hernow.barkey

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/** Session-scoped editing operations, isolated from platform InputConnection details. */
internal class InputConnectionEditor {
    private var undoRecord: UndoRecord? = null

    /** Commits directly at the cursor and records Undo only from a positively verified snapshot. */
    fun commitScan(
        connection: EditorConnection,
        sessionId: Long,
        value: String,
        recordUndo: Boolean,
    ): Boolean {
        undoRecord = null
        val compositionFinished = connection.finishComposingText()
        val before = if (recordUndo && compositionFinished && value.length <= MAX_UNDO_TEXT_LENGTH) {
            connection.cursorSnapshot(CONTEXT_CHARS, CONTEXT_CHARS)
                ?.takeIf { it.selectionStart == it.selectionEnd && it.absoluteCursorPosition != null }
        } else {
            null
        }
        if (!connection.commitText(value, 1)) return false

        if (before != null) {
            val initialCursor = before.absoluteCursorPosition ?: return true
            val expectedBefore = before.textBeforeCursor?.plus(value)
            val expectedAfter = before.textAfterCursor
            val expectedCursor = initialCursor + value.length
            if (expectedBefore != null && expectedAfter != null) {
                val after = connection.cursorSnapshot(expectedBefore.length, expectedAfter.length)
                if (after != null && after.selectionStart == after.selectionEnd &&
                    after.textBeforeCursor == expectedBefore && after.textAfterCursor == expectedAfter &&
                    after.absoluteCursorPosition == expectedCursor
                ) {
                    undoRecord = UndoRecord(
                        connectionIdentity = connection.identity,
                        sessionId = sessionId,
                        insertedText = value,
                        expectedBefore = expectedBefore,
                        expectedAfter = expectedAfter,
                        expectedCursorPosition = expectedCursor,
                        originalCursorPosition = initialCursor,
                    )
                }
            }
        }
        return true
    }

    fun canUndo(connection: EditorConnection?, sessionId: Long): Boolean {
        val record = undoRecord ?: return false
        if (connection == null || connection.identity !== record.connectionIdentity ||
            sessionId != record.sessionId
        ) {
            undoRecord = null
            return false
        }
        return true
    }

    fun undo(connection: EditorConnection?, sessionId: Long): Boolean {
        val record = undoRecord ?: return false
        if (connection == null || !matches(record, connection, sessionId)) {
            undoRecord = null
            return false
        }

        if (connection.deleteSurroundingTextInCodePoints(
                record.insertedText.codePointCount(0, record.insertedText.length),
                0,
            )
        ) {
            undoRecord = null
            return verifyUndoResult(record, connection)
        }

        // API 24–32 editors can reject the code-point method. Verify the complete snapshot again
        // before deleting the exact UTF-16 length of the known scan.
        if (!matches(record, connection, sessionId)) {
            undoRecord = null
            return false
        }
        val deleted = connection.deleteSurroundingText(record.insertedText.length, 0)
        undoRecord = null
        return deleted && verifyUndoResult(record, connection)
    }

    /** Invalidates a record as soon as Android reports a selection or cursor move. */
    fun onSelectionChanged(
        connection: EditorConnection?,
        sessionId: Long,
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        val record = undoRecord ?: return
        if (connection == null || connection.identity !== record.connectionIdentity ||
            sessionId != record.sessionId || selectionStart != record.expectedCursorPosition ||
            selectionEnd != record.expectedCursorPosition
        ) {
            undoRecord = null
        }
    }

    fun clearUndo() {
        undoRecord = null
    }

    fun backspace(connection: EditorConnection): Boolean {
        val snapshot = connection.cursorSnapshot(2, 0) ?: return false
        if (snapshot.hasSelection) return connection.commitText("", 1)

        val before = snapshot.textBeforeCursor ?: connection.textBeforeCursor(2) ?: return false
        if (before.isEmpty()) return true
        if (connection.deleteSurroundingTextInCodePoints(1, 0)) return true

        // The old API can return false when an editor does not implement code-point deletion.
        // Check that the attempted deletion made no change, then preserve UTF-16 surrogate pairs.
        if (connection.textBeforeCursor(2) != before) return false
        val last = before.last()
        val utf16Length = if (last.isLowSurrogate() && before.length > 1 &&
            before[before.lastIndex - 1].isHighSurrogate()
        ) 2 else 1
        return connection.deleteSurroundingText(utf16Length, 0)
    }

    fun insertSpace(connection: EditorConnection): Boolean = connection.commitText(" ", 1)

    fun moveCursorLeft(connection: EditorConnection): Boolean = sendKey(connection, KeyEvent.KEYCODE_DPAD_LEFT)

    fun moveCursorRight(connection: EditorConnection): Boolean = sendKey(connection, KeyEvent.KEYCODE_DPAD_RIGHT)

    fun enter(connection: EditorConnection, editorInfo: EditorInfo?): Boolean {
        val imeOptions = editorInfo?.imeOptions ?: 0
        val inputType = editorInfo?.inputType ?: 0
        return when (val command = EditorCommandPolicy.enter(imeOptions, inputType)) {
            is EnterCommand.PerformEditorAction -> connection.performEditorAction(command.action)
            EnterCommand.InsertNewline -> connection.commitText("\n", 1)
            EnterCommand.SendEnterKey -> sendKey(connection, KeyEvent.KEYCODE_ENTER)
        }
    }

    fun nextField(connection: EditorConnection, editorInfo: EditorInfo?): Boolean {
        val imeOptions = editorInfo?.imeOptions ?: 0
        val inputType = editorInfo?.inputType ?: 0
        return when (EditorCommandPolicy.nextField(imeOptions, inputType)) {
            NextFieldCommand.PERFORM_EDITOR_ACTION -> connection.performEditorAction(EditorInfo.IME_ACTION_NEXT)
            NextFieldCommand.SEND_TAB_KEY -> sendKey(connection, KeyEvent.KEYCODE_TAB)
            NextFieldCommand.UNSUPPORTED -> false
        }
    }

    private fun matches(record: UndoRecord, connection: EditorConnection, sessionId: Long): Boolean {
        if (connection.identity !== record.connectionIdentity || record.sessionId != sessionId) return false
        val snapshot = connection.cursorSnapshot(record.expectedBefore.length, record.expectedAfter.length) ?: return false
        return snapshot.selectionStart == snapshot.selectionEnd &&
            snapshot.absoluteCursorPosition == record.expectedCursorPosition &&
            snapshot.textBeforeCursor == record.expectedBefore &&
            snapshot.textAfterCursor == record.expectedAfter
    }

    private fun verifyUndoResult(record: UndoRecord, connection: EditorConnection): Boolean {
        val snapshot = connection.cursorSnapshot(record.expectedBefore.length, record.expectedAfter.length) ?: return false
        val originalBefore = record.expectedBefore.removeSuffix(record.insertedText)
        return snapshot.selectionStart == snapshot.selectionEnd &&
            snapshot.absoluteCursorPosition == record.originalCursorPosition &&
            snapshot.textBeforeCursor == originalBefore &&
            snapshot.textAfterCursor == record.expectedAfter
    }

    private fun sendKey(connection: EditorConnection, keyCode: Int): Boolean {
        val down = connection.sendKeyEvent(keyCode, KeyEvent.ACTION_DOWN)
        val up = connection.sendKeyEvent(keyCode, KeyEvent.ACTION_UP)
        return down && up
    }

    private data class UndoRecord(
        val connectionIdentity: Any,
        val sessionId: Long,
        val insertedText: String,
        val expectedBefore: String,
        val expectedAfter: String,
        val expectedCursorPosition: Int,
        val originalCursorPosition: Int,
    )

    private companion object {
        const val CONTEXT_CHARS = 16
        const val MAX_UNDO_TEXT_LENGTH = 4096
    }
}
