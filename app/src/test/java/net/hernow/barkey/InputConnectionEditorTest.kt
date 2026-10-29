package net.hernow.barkey

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputConnectionEditorTest {
    private val editor = InputConnectionEditor()

    @Test fun ordinaryInsertionCanBeUndone() {
        val connection = FakeEditorConnection()
        assertTrue(editor.commitScan(connection, 1L, "ABC123", true))
        assertEquals("ABC123", connection.value())
        assertTrue(editor.undo(connection, 1L))
        assertEquals("", connection.value())
    }

    @Test fun emptyFieldBackspaceDoesNotDelete() {
        val connection = FakeEditorConnection()
        assertTrue(editor.backspace(connection))
        assertEquals("", connection.value())
    }

    @Test fun insertionWithTextOnBothSidesIsUndoableOnlyAtOriginalCursor() {
        val connection = FakeEditorConnection("before-after", cursor = 6)
        assertTrue(editor.commitScan(connection, 1L, "CODE", true))
        assertEquals("beforeCODE-after", connection.value())
        assertTrue(editor.undo(connection, 1L))
        assertEquals("before-after", connection.value())
    }

    @Test fun replacingAnExistingSelectionDoesNotCreateUndo() {
        val connection = FakeEditorConnection("abcdef", selectionStart = 2, selectionEnd = 4)
        assertTrue(editor.commitScan(connection, 1L, "X", true))
        assertEquals("abXef", connection.value())
        assertFalse(editor.canUndo(connection, 1L))
    }

    @Test fun unsupportedSelectionSnapshotDisablesUndo() {
        val connection = FakeEditorConnection().apply { supportsSnapshot = false }
        assertTrue(editor.commitScan(connection, 1L, "X", true))
        assertFalse(editor.canUndo(connection, 1L))
        assertFalse(editor.undo(connection, 1L))
        assertEquals("X", connection.value())
    }

    @Test fun platformSelectionApisReturningNullAreTreatedAsUnknown() {
        val connection = nullReturningInputConnection()
        assertNull(AndroidEditorConnection(connection, 31).cursorSnapshot(16, 16))
        assertNull(AndroidEditorConnection(connection, 30).cursorSnapshot(16, 16))
    }

    @Test fun changedCursorOrSurroundingTextRejectsUndo() {
        val moved = FakeEditorConnection()
        assertTrue(editor.commitScan(moved, 1L, "CODE", true))
        moved.setCursor(0)
        assertFalse(editor.undo(moved, 1L))
        assertEquals("CODE", moved.value())

        val modified = FakeEditorConnection()
        assertTrue(editor.commitScan(modified, 2L, "CODE", true))
        modified.replaceAll("other")
        assertFalse(editor.undo(modified, 2L))
        assertEquals("other", modified.value())
    }

    @Test fun undoRequiresSameInputConnectionAndSession() {
        val connection = FakeEditorConnection()
        assertTrue(editor.commitScan(connection, 10L, "A", true))
        assertFalse(editor.undo(FakeEditorConnection(), 10L))
        assertEquals("A", connection.value())

        assertTrue(editor.commitScan(connection, 11L, "B", true))
        assertFalse(editor.undo(connection, 12L))
        assertEquals("AB", connection.value())
    }

    @Test fun unicodeAndRapidScansOnlyUndoTheLatestInsertion() {
        val connection = FakeEditorConnection("start-end", cursor = 5)
        assertTrue(editor.commitScan(connection, 1L, "📦漢", true))
        assertTrue(editor.commitScan(connection, 1L, "Z", true))
        assertEquals("start📦漢Z-end", connection.value())
        assertTrue(editor.undo(connection, 1L))
        assertEquals("start📦漢-end", connection.value())

        val unicode = FakeEditorConnection()
        assertTrue(editor.commitScan(unicode, 1L, "📦漢", true))
        assertTrue(editor.undo(unicode, 1L))
        assertEquals("", unicode.value())
    }

    @Test fun changingFieldsClearsPendingUndo() {
        val connection = FakeEditorConnection()
        assertTrue(editor.commitScan(connection, 1L, "A", true))
        editor.clearUndo()
        assertFalse(editor.canUndo(connection, 1L))
        assertFalse(editor.undo(connection, 1L))
        assertEquals("A", connection.value())
    }

    @Test fun deletionRejectionSafelyRejectsUndo() {
        val connection = FakeEditorConnection().apply { rejectDeletion = true }
        assertTrue(editor.commitScan(connection, 1L, "A", true))
        assertFalse(editor.undo(connection, 1L))
        assertEquals("A", connection.value())
    }

    @Test fun enterAndNextFieldUseSinglePolicySelectedAction() {
        val info = EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_NEXT }
        val connection = FakeEditorConnection().apply { rejectActions = true }
        assertFalse(editor.nextField(connection, info))
        assertEquals(listOf(EditorInfo.IME_ACTION_NEXT), connection.actions)
        assertTrue(connection.keyEvents.isEmpty())

        info.imeOptions = EditorInfo.IME_ACTION_SEARCH
        assertFalse(editor.enter(connection, info))
        assertEquals(listOf(EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_SEARCH), connection.actions)
        assertTrue(connection.keyEvents.isEmpty())
    }

    @Test fun commandPolicyCoversMultilineAndTabFallback() {
        val multiline = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertEquals(EnterCommand.InsertNewline, EditorCommandPolicy.enter(EditorInfo.IME_ACTION_UNSPECIFIED, multiline))
        assertEquals(EnterCommand.InsertNewline, EditorCommandPolicy.enter(
            EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            multiline,
        ))
        assertEquals(EnterCommand.PerformEditorAction(EditorInfo.IME_ACTION_DONE), EditorCommandPolicy.enter(
            EditorInfo.IME_ACTION_DONE,
            InputType.TYPE_CLASS_TEXT,
        ))
        assertEquals(EnterCommand.SendEnterKey, EditorCommandPolicy.enter(
            EditorInfo.IME_ACTION_UNSPECIFIED,
            InputType.TYPE_CLASS_NUMBER,
        ))
        assertEquals(NextFieldCommand.SEND_TAB_KEY, EditorCommandPolicy.nextField(0, InputType.TYPE_CLASS_TEXT))
        assertEquals(NextFieldCommand.UNSUPPORTED, EditorCommandPolicy.nextField(0, multiline))
        assertEquals(NextFieldCommand.PERFORM_EDITOR_ACTION,
            EditorCommandPolicy.nextField(EditorInfo.IME_ACTION_NEXT, multiline))
    }

    @Test fun enterAndTabFallbacksNeverCommitLiteralTab() {
        val singleLine = FakeEditorConnection()
        assertTrue(editor.nextField(singleLine, EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }))
        assertEquals(listOf(
            KeyEvent.KEYCODE_TAB to KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_TAB to KeyEvent.ACTION_UP,
        ), singleLine.keyEvents)
        assertTrue(singleLine.committedText.isEmpty())

        val multiline = FakeEditorConnection()
        val multilineInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        assertTrue(editor.enter(multiline, multilineInfo))
        assertEquals(listOf("\n"), multiline.committedText)
        assertFalse(editor.nextField(multiline, multilineInfo))
        assertTrue(multiline.keyEvents.isEmpty())
    }

    private fun nullReturningInputConnection(): InputConnection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader,
        arrayOf(InputConnection::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSurroundingText", "getExtractedText" -> null
            "toString" -> "NullInputConnection"
            "hashCode" -> 1
            "equals" -> false
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
    } as InputConnection

    private class FakeEditorConnection(
        initialText: String = "",
        cursor: Int = initialText.length,
        selectionStart: Int = cursor,
        selectionEnd: Int = cursor,
    ) : EditorConnection {
        override val identity: Any = Any()
        private val content = StringBuilder(initialText)
        private var start = selectionStart
        private var end = selectionEnd
        var supportsSnapshot = true
        var rejectDeletion = false
        var rejectActions = false
        val actions = mutableListOf<Int>()
        val keyEvents = mutableListOf<Pair<Int, Int>>()
        val committedText = mutableListOf<String>()

        override fun finishComposingText() = true

        override fun commitText(text: String, newCursorPosition: Int): Boolean {
            committedText += text
            content.replace(start, end, text)
            start += text.length
            end = start
            return true
        }

        override fun cursorSnapshot(beforeLength: Int, afterLength: Int): EditorCursorSnapshot? {
            if (!supportsSnapshot) return null
            if (start != end) return EditorCursorSnapshot(null, null, start, end, end)
            val beforeStart = (start - beforeLength).coerceAtLeast(0)
            val afterEnd = (end + afterLength).coerceAtMost(content.length)
            return EditorCursorSnapshot(
                content.substring(beforeStart, start),
                content.substring(end, afterEnd),
                0,
                0,
                end,
            )
        }

        override fun textBeforeCursor(length: Int): String = content.substring((start - length).coerceAtLeast(0), start)

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            if (rejectDeletion || start != end) return false
            val deleteStart = content.offsetByCodePoints(start, -beforeLength)
            val deleteEnd = content.offsetByCodePoints(start, afterLength)
            content.delete(deleteStart, deleteEnd)
            start = deleteStart
            end = deleteStart
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (rejectDeletion || start != end) return false
            content.delete(start - beforeLength, start + afterLength)
            start -= beforeLength
            end = start
            return true
        }

        override fun performEditorAction(action: Int): Boolean {
            actions += action
            return !rejectActions
        }

        override fun sendKeyEvent(keyCode: Int, action: Int): Boolean {
            keyEvents += keyCode to action
            return true
        }

        fun setCursor(position: Int) {
            start = position
            end = position
        }

        fun replaceAll(value: String) {
            content.replace(0, content.length, value)
            start = value.length
            end = start
        }

        fun value() = content.toString()
    }
}
