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
        // finishComposingText() may return false even when commitText() and the subsequent
        // bounded before/after verification succeed. The verified insertion is the Undo gate.
        connection.finishComposingText()
        return insertText(connection, sessionId, value, recordUndo)
    }

    /** Inserts text from a BarKey control and records it only when bounded snapshots verify it. */
    fun insertText(
        connection: EditorConnection,
        sessionId: Long,
        value: String,
        recordUndo: Boolean,
    ): Boolean {
        undoRecord = null
        val before = if (recordUndo && value.isNotEmpty() && value.length <= MAX_UNDO_TEXT_LENGTH) {
            connection.cursorSnapshot(CONTEXT_CHARS, CONTEXT_CHARS)?.takeIf {
                it.selectionIsKnown && it.selectionStart == it.selectionEnd &&
                    it.textBeforeCursor != null && it.textAfterCursor != null
            }
        } else {
            null
        }
        if (!connection.commitText(value, 1)) return false

        if (before != null) {
            val expectedBefore = before.textBeforeCursor?.plus(value)
            val expectedAfter = before.textAfterCursor
            if (expectedBefore != null && expectedAfter != null) {
                val after = connection.cursorSnapshot(expectedBefore.length, expectedAfter.length)
                if (after != null && after.selectionIsKnown && after.selectionStart == after.selectionEnd &&
                    after.textBeforeCursor == expectedBefore && after.textAfterCursor == expectedAfter &&
                    (before.absoluteCursorPosition == null ||
                        after.absoluteCursorPosition == before.absoluteCursorPosition + value.length)
                ) {
                    undoRecord = UndoRecord(
                        connectionIdentity = connection.identity,
                        sessionId = sessionId,
                        insertedText = value,
                        expectedBefore = expectedBefore,
                        expectedAfter = expectedAfter,
                        expectedCursorPosition = after.absoluteCursorPosition,
                        originalCursorPosition = before.absoluteCursorPosition,
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
        val result = matches(record, connection, sessionId)
        when (result) {
            MatchResult.MATCH -> Unit
            MatchResult.CHANGED -> undoRecord = null
            MatchResult.UNKNOWN -> Unit
        }
        return result == MatchResult.MATCH
    }

    fun undo(connection: EditorConnection?, sessionId: Long): Boolean {
        val record = undoRecord ?: return false
        if (connection == null) {
            undoRecord = null
            return false
        }
        when (matches(record, connection, sessionId)) {
            MatchResult.MATCH -> Unit
            MatchResult.CHANGED -> { undoRecord = null; return false }
            MatchResult.UNKNOWN -> return false
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
        when (matches(record, connection, sessionId)) {
            MatchResult.MATCH -> Unit
            MatchResult.CHANGED -> undoRecord = null
            MatchResult.UNKNOWN -> return false
        }
        if (undoRecord == null) return false
        val deleted = connection.deleteSurroundingText(record.insertedText.length, 0)
        undoRecord = null
        return deleted && verifyUndoResult(record, connection)
    }

    /** Uses Android selection notifications as a prompt to recheck the live editor context. */
    fun onSelectionChanged(
        connection: EditorConnection?,
        sessionId: Long,
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        val record = undoRecord ?: return
        if (connection == null || connection.identity !== record.connectionIdentity || sessionId != record.sessionId) {
            undoRecord = null
            return
        }
        if (selectionStart == record.expectedCursorPosition && selectionEnd == record.expectedCursorPosition) return
        if (matches(record, connection, sessionId) == MatchResult.CHANGED) {
            undoRecord = null
        }
    }

    fun clearUndo() {
        undoRecord = null
    }

    fun hasUndoRecord(): Boolean = undoRecord != null

    fun backspace(connection: EditorConnection): BackspaceResult {
        val snapshot = connection.cursorSnapshot(CONTEXT_CHARS, CONTEXT_CHARS)
        if (snapshot == null) return sendBackspaceKey(connection, before = null)

        if (!snapshot.selectionIsKnown) return sendBackspaceKey(connection, before = snapshot)
        if (snapshot.selectionIsKnown && snapshot.hasSelection) {
            val deleted = connection.commitText("", 1)
            if (!deleted) return BackspaceResult.FAILED
            undoRecord = null
            return BackspaceResult.DELETED
        }
        val before = snapshot.textBeforeCursor ?: connection.textBeforeCursor(CONTEXT_CHARS)
            ?: return BackspaceResult.FAILED
        if (before.isEmpty()) return BackspaceResult.NO_OP

        if (connection.deleteSurroundingTextInCodePoints(1, 0)) {
            undoRecord = null
            return BackspaceResult.DELETED
        }

        // The old API can return false when an editor does not implement code-point deletion.
        // Check that the attempted deletion made no change, then preserve UTF-16 surrogate pairs.
        if (connection.textBeforeCursor(2) != before.takeLast(2)) return BackspaceResult.FAILED
        val last = before.last()
        val utf16Length = if (last.isLowSurrogate() && before.length > 1 &&
            before[before.lastIndex - 1].isHighSurrogate()
        ) 2 else 1
        if (!connection.deleteSurroundingText(utf16Length, 0)) return BackspaceResult.FAILED
        undoRecord = null
        return BackspaceResult.DELETED
    }

    private fun sendBackspaceKey(
        connection: EditorConnection,
        before: EditorCursorSnapshot?,
    ): BackspaceResult {
        val down = connection.sendKeyEvent(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_DOWN)
        val up = connection.sendKeyEvent(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP)
        if (down || up) undoRecord = null
        if (!down || !up) return BackspaceResult.FAILED

        // An accepted key event is not proof that the editor deleted text. Keep that result
        // distinct, and use snapshots only to decide whether another held repeat is worthwhile.
        val after = connection.cursorSnapshot(CONTEXT_CHARS, CONTEXT_CHARS)
            ?: return BackspaceResult.KEY_EVENT_SENT
        if (before == null) return BackspaceResult.KEY_EVENT_SENT
        if (after.selectionIsKnown && !after.hasSelection && after.textBeforeCursor.isNullOrEmpty()) {
            return BackspaceResult.KEY_EVENT_SENT
        }
        val contextChanged = snapshotContextChanged(before, after)
        return if (contextChanged) BackspaceResult.KEY_EVENT_CONTEXT_CHANGED else BackspaceResult.KEY_EVENT_SENT
    }

    private fun snapshotContextChanged(before: EditorCursorSnapshot, after: EditorCursorSnapshot): Boolean =
        before.textBeforeCursor != after.textBeforeCursor || before.textAfterCursor != after.textAfterCursor ||
            before.absoluteCursorPosition != after.absoluteCursorPosition ||
            before.selectionIsKnown != after.selectionIsKnown ||
            before.selectionStart != after.selectionStart || before.selectionEnd != after.selectionEnd

    fun insertSpace(connection: EditorConnection, sessionId: Long, recordUndo: Boolean): Boolean =
        insertText(connection, sessionId, " ", recordUndo)

    fun insertSpace(connection: EditorConnection): Boolean {
        undoRecord = null
        return connection.commitText(" ", 1)
    }

    fun moveCursorLeft(connection: EditorConnection): Boolean = sendKey(connection, KeyEvent.KEYCODE_DPAD_LEFT)

    fun moveCursorRight(connection: EditorConnection): Boolean = sendKey(connection, KeyEvent.KEYCODE_DPAD_RIGHT)

    fun enter(
        connection: EditorConnection,
        editorInfo: EditorInfo?,
    ): Boolean {
        val imeOptions = editorInfo?.imeOptions ?: 0
        val inputType = editorInfo?.inputType ?: 0
        return when (val command = EditorCommandPolicy.enter(imeOptions, inputType)) {
            is EnterCommand.PerformEditorAction -> connection.performEditorAction(command.action)
            EnterCommand.InsertNewline -> insertText(connection, 0L, "\n", recordUndo = false)
            EnterCommand.SendEnterKey -> sendKey(connection, KeyEvent.KEYCODE_ENTER)
        }
    }

    fun nextField(connection: EditorConnection, editorInfo: EditorInfo?): Boolean {
        return when (EditorCommandPolicy.nextField(editorInfo)) {
            NextFieldCommand.PERFORM_EDITOR_ACTION -> connection.performEditorAction(EditorInfo.IME_ACTION_NEXT)
            NextFieldCommand.UNSUPPORTED -> false
        }
    }

    private fun matches(record: UndoRecord, connection: EditorConnection, sessionId: Long): MatchResult {
        if (connection.identity !== record.connectionIdentity || record.sessionId != sessionId) return MatchResult.CHANGED
        val snapshot = connection.cursorSnapshot(record.expectedBefore.length, record.expectedAfter.length)
            ?: return MatchResult.UNKNOWN
        if (!snapshot.selectionIsKnown) return MatchResult.UNKNOWN
        if (snapshot.selectionStart != snapshot.selectionEnd) return MatchResult.CHANGED
        if (snapshot.textBeforeCursor == null || snapshot.textAfterCursor == null) return MatchResult.UNKNOWN
        val matches = snapshot.textBeforeCursor == record.expectedBefore &&
            snapshot.textAfterCursor == record.expectedAfter &&
            (record.expectedCursorPosition == null || snapshot.absoluteCursorPosition == record.expectedCursorPosition)
        return if (matches) MatchResult.MATCH else MatchResult.CHANGED
    }

    private fun verifyUndoResult(record: UndoRecord, connection: EditorConnection): Boolean {
        val snapshot = connection.cursorSnapshot(record.expectedBefore.length, record.expectedAfter.length) ?: return false
        val originalBefore = record.expectedBefore.removeSuffix(record.insertedText)
        return snapshot.selectionIsKnown && snapshot.selectionStart == snapshot.selectionEnd &&
            (record.originalCursorPosition == null || snapshot.absoluteCursorPosition == record.originalCursorPosition) &&
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
        val expectedCursorPosition: Int?,
        val originalCursorPosition: Int?,
    )

    private enum class MatchResult { MATCH, CHANGED, UNKNOWN }

    private companion object {
        const val CONTEXT_CHARS = 16
        const val MAX_UNDO_TEXT_LENGTH = 4096
    }
}
