package net.hernow.barkey

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
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

    @Test fun scanIsUndoableWhenFinishComposingReturnsFalseButInsertionVerifies() {
        val connection = FakeEditorConnection().apply { finishComposingResult = false }
        assertTrue(editor.commitScan(connection, 1L, "123456789", true))
        assertEquals("123456789", connection.value())
        assertTrue(editor.canUndo(connection, 1L))
        assertTrue(editor.undo(connection, 1L))
        assertEquals("", connection.value())
    }

    @Test fun spaceInsertionCanBeSafelyUndoneToPreviousCursor() {
        val connection = FakeEditorConnection("left-right", cursor = 4)
        assertTrue(editor.insertSpace(connection, 3L, recordUndo = true))
        assertEquals("left -right", connection.value())
        assertTrue(editor.canUndo(connection, 3L))
        assertTrue(editor.undo(connection, 3L))
        assertEquals("left-right", connection.value())
        assertEquals(4, connection.cursor())
        assertFalse(editor.canUndo(connection, 3L))
    }

    @Test fun explicitlyInsertedMultilineNewlineDoesNotCreateUndo() {
        val connection = FakeEditorConnection("ab", cursor = 1)
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED
        }
        assertTrue(editor.enter(connection, info))
        assertEquals("a\nb", connection.value())
        assertFalse(editor.canUndo(connection, 8L))
    }

    @Test fun undoEligibilityRechecksContextAndSelectionCallback() {
        val unchanged = FakeEditorConnection()
        assertTrue(editor.insertSpace(unchanged, 4L, recordUndo = true))
        editor.onSelectionChanged(unchanged, 4L, 1, 1)
        assertTrue(editor.canUndo(unchanged, 4L))

        val edited = FakeEditorConnection()
        assertTrue(editor.insertText(edited, 5L, " ", recordUndo = true))
        edited.replaceAll("external")
        assertFalse(editor.canUndo(edited, 5L))

        val selected = FakeEditorConnection()
        assertTrue(editor.insertText(selected, 5L, "X", recordUndo = true))
        editor.onSelectionChanged(selected, 5L, 0, 0)
        assertFalse(editor.canUndo(selected, 5L))
        assertEquals("X", selected.value())
    }

    @Test fun emptyFieldBackspaceDoesNotDelete() {
        val connection = FakeEditorConnection()
        assertEquals(BackspaceResult.NO_OP, editor.backspace(connection))
        assertEquals("", connection.value())
    }

    @Test fun backspaceDeletesSelectionAndWholeUnicodeCodePoint() {
        val selected = FakeEditorConnection("abcdef", selectionStart = 2, selectionEnd = 4)
        assertEquals(BackspaceResult.DELETED, editor.backspace(selected))
        assertEquals("abef", selected.value())
        assertTrue(selected.keyEvents.isEmpty())

        val unicode = FakeEditorConnection("A📦")
        assertEquals(BackspaceResult.DELETED, editor.backspace(unicode))
        assertEquals("A", unicode.value())
    }

    @Test fun backspaceDeletionInvalidatesScanUndo() {
        val connection = FakeEditorConnection()
        assertTrue(editor.commitScan(connection, 1L, "CODE", true))
        assertTrue(editor.canUndo(connection, 1L))
        assertEquals(BackspaceResult.DELETED, editor.backspace(connection))
        assertEquals("COD", connection.value())
        assertFalse(editor.canUndo(connection, 1L))
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

    @Test fun api31SurroundingTextFallsBackToBoundedBeforeAndAfterQueries() {
        val connection = beforeAfterOnlyInputConnection("leftCODE", "-right")
        val snapshot = AndroidEditorConnection(connection, 31).cursorSnapshot(16, 16)
        assertEquals("leftCODE", snapshot?.textBeforeCursor)
        assertEquals("-right", snapshot?.textAfterCursor)
        assertNull(snapshot?.absoluteCursorPosition)
        assertFalse(snapshot?.selectionIsKnown ?: true)
    }

    @Test fun unsupportedOrThrowingSnapshotMethodsStillUseAvailableFallbacks() {
        val connection = beforeAfterOnlyInputConnection("before", "after", throwSnapshotQueries = true)
        val snapshot = AndroidEditorConnection(connection, 31).cursorSnapshot(16, 16)
        assertEquals("before", snapshot?.textBeforeCursor)
        assertEquals("after", snapshot?.textAfterCursor)
    }

    @Test fun scanUndoWorksWithOnlyBeforeAndAfterCursorFallback() {
        val connection = beforeAfterEditorConnection("left-right", cursor = 4, selectedText = "")
        val editorConnection = AndroidEditorConnection(connection, 31)
        assertTrue(editor.commitScan(editorConnection, 7L, "123456789", true))
        assertTrue(editor.canUndo(editorConnection, 7L))
        assertTrue(editor.undo(editorConnection, 7L))
        assertEquals("left-right", connection.toString())
    }

    @Test fun fallbackWithUnknownSelectionCannotCreateOrExecuteUndo() {
        val connection = beforeAfterEditorConnection("left-right", cursor = 4, selectedText = null)
        val editorConnection = AndroidEditorConnection(connection, 31)
        assertTrue(editor.commitScan(editorConnection, 7L, "123456789", true))
        assertFalse(editor.canUndo(editorConnection, 7L))
        assertFalse(editor.undo(editorConnection, 7L))
        assertEquals("left123456789-right", connection.toString())
    }

    @Test fun backspaceUsesEditorKeyEventWhenSelectionIsUnknown() {
        val connection = FakeEditorConnection("left-right", cursor = 4).apply { selectionStateKnown = false }
        val result = editor.backspace(connection)
        assertEquals(BackspaceResult.KEY_EVENT_CONTEXT_CHANGED, result)
        assertEquals("lef-right", connection.value())
        assertEquals(
            listOf(
                android.view.KeyEvent.KEYCODE_DEL to android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_DEL to android.view.KeyEvent.ACTION_UP,
            ),
            connection.keyEvents,
        )
        assertEquals(0, connection.deleteOperationCalls)
    }

    @Test fun unknownSelectionBackspaceKeyEventPreservesUnicodeCodePointDeletion() {
        val connection = FakeEditorConnection("A📦").apply { selectionStateKnown = false }
        val result = editor.backspace(connection)
        assertEquals(BackspaceResult.KEY_EVENT_CONTEXT_CHANGED, result)
        assertEquals("A", connection.value())
    }

    @Test fun unavailableSnapshotStillAllowsOneEditorHandledBackspaceTap() {
        val connection = FakeEditorConnection("abc").apply { supportsSnapshot = false }
        val result = editor.backspace(connection)
        assertEquals(BackspaceResult.KEY_EVENT_SENT, result)
        assertEquals("ab", connection.value())
    }

    @Test fun refusedBackspaceKeyEventIsNotReportedAsDeletion() {
        val connection = FakeEditorConnection("abc").apply {
            selectionStateKnown = false
            acceptKeyEvents = false
        }
        val result = editor.backspace(connection)
        assertEquals(BackspaceResult.FAILED, result)
        assertEquals("abc", connection.value())
    }

    @Test fun keyEventFallbackLetsEditorDeleteUnknownSelectionWithoutDeletingAdjacentText() {
        val connection = FakeEditorConnection("abcdef", selectionStart = 2, selectionEnd = 4).apply {
            selectionStateKnown = false
        }
        val result = editor.backspace(connection)
        assertEquals(BackspaceResult.KEY_EVENT_SENT, result)
        assertEquals("abef", connection.value())
        assertEquals(0, connection.deleteOperationCalls)
    }

    @Test fun fallbackCanConfirmCollapsedSelectionFromExtractedTextWhenSelectedTextIsNull() {
        val connection = beforeAfterEditorConnection(
            "left-right",
            cursor = 4,
            selectedText = null,
            extractedSelectionAvailable = true,
        )
        val editorConnection = AndroidEditorConnection(connection, 31)
        assertTrue(editor.commitScan(editorConnection, 7L, "123456789", true))
        assertTrue(editor.canUndo(editorConnection, 7L))
        assertTrue(editor.undo(editorConnection, 7L))
        assertEquals("left-right", connection.toString())
    }

    @Test fun temporarilyUnavailableUndoSnapshotDoesNotDiscardRecord() {
        val connection = FakeEditorConnection()
        assertTrue(editor.commitScan(connection, 1L, "123456789", true))
        connection.supportsSnapshot = false
        assertFalse(editor.canUndo(connection, 1L))
        assertEquals("123456789", connection.value())
        connection.supportsSnapshot = true
        assertTrue(editor.canUndo(connection, 1L))
        assertTrue(editor.undo(connection, 1L))
        assertEquals("", connection.value())
    }

    @Test fun delayedAndDuplicateSelectionCallbacksAreCheckedAgainstLiveEditor() {
        val connection = FakeEditorConnection("before-after", cursor = 6)
        assertTrue(editor.commitScan(connection, 1L, "123456789", true))
        editor.onSelectionChanged(connection, 1L, 0, 0)
        editor.onSelectionChanged(connection, 1L, connection.cursor(), connection.cursor())
        assertTrue(editor.canUndo(connection, 1L))
        connection.setCursor(2)
        editor.onSelectionChanged(connection, 1L, 2, 2)
        assertFalse(editor.canUndo(connection, 1L))
        assertEquals("before123456789-after", connection.value())
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

    @Test fun commandPolicyAllowsOnlyExplicitNextNavigation() {
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
        assertEquals(NextFieldCommand.UNSUPPORTED, EditorCommandPolicy.nextField(null))
        assertEquals(NextFieldCommand.UNSUPPORTED, EditorCommandPolicy.nextField(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED
        }))
        assertEquals(NextFieldCommand.PERFORM_EDITOR_ACTION,
            EditorCommandPolicy.nextField(EditorInfo().apply {
                inputType = multiline
                imeOptions = EditorInfo.IME_ACTION_NEXT
            }))
        assertEquals(NextFieldCommand.PERFORM_EDITOR_ACTION,
            EditorCommandPolicy.nextField(EditorInfo().apply {
                inputType = multiline
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NAVIGATE_NEXT
            }))
        listOf(
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NONE,
            EditorInfo.IME_ACTION_UNSPECIFIED,
        ).forEach { action ->
            assertEquals(NextFieldCommand.UNSUPPORTED,
                EditorCommandPolicy.nextField(EditorInfo().apply { imeOptions = action }))
        }
        assertEquals(NextFieldCommand.UNSUPPORTED,
            EditorCommandPolicy.nextField(EditorInfo().apply {
                inputType = multiline
                imeOptions = EditorInfo.IME_ACTION_DONE
            }))
    }

    @Test fun tabUsesOnlyTheAdvertisedNextActionAndNeverInjectsAKey() {
        val next = FakeEditorConnection()
        val nextInfo = EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_NEXT }
        assertTrue(editor.nextField(next, nextInfo))
        assertEquals(listOf(EditorInfo.IME_ACTION_NEXT), next.actions)
        assertTrue(next.keyEvents.isEmpty())
        assertTrue(next.committedText.isEmpty())

        val multiline = FakeEditorConnection()
        val multilineInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        assertTrue(editor.enter(multiline, multilineInfo))
        assertEquals(listOf("\n"), multiline.committedText)
        assertFalse(editor.nextField(multiline, multilineInfo))
        assertTrue(multiline.keyEvents.isEmpty())
    }

    @Test fun unsupportedTabIsHarmlessAndPreservesUndo() {
        listOf(
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NONE,
            EditorInfo.IME_ACTION_UNSPECIFIED,
        ).forEach { action ->
            val connection = FakeEditorConnection()
            assertTrue(editor.commitScan(connection, 1L, "SAFE", recordUndo = true))
            val info = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = action
            }
            val committedBefore = connection.committedText.toList()
            val actionsBefore = connection.actions.toList()
            val keyEventsBefore = connection.keyEvents.toList()
            assertFalse(editor.nextField(connection, info))
            assertEquals(committedBefore, connection.committedText)
            assertEquals(actionsBefore, connection.actions)
            assertEquals(keyEventsBefore, connection.keyEvents)
            assertTrue(editor.canUndo(connection, 1L))
        }
        assertFalse(editor.nextField(FakeEditorConnection(), null))
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

    private fun beforeAfterOnlyInputConnection(
        before: String,
        after: String,
        throwSnapshotQueries: Boolean = false,
    ): InputConnection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader,
        arrayOf(InputConnection::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSurroundingText", "getExtractedText" -> if (throwSnapshotQueries) {
                throw IllegalStateException("snapshot unavailable")
            } else null
            "getSelectedText" -> null
            "getTextBeforeCursor" -> before
            "getTextAfterCursor" -> after
            "toString" -> "BeforeAfterInputConnection"
            "hashCode" -> 2
            "equals" -> false
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
    } as InputConnection

    private fun beforeAfterEditorConnection(
        initial: String,
        cursor: Int,
        selectedText: String?,
        extractedSelectionAvailable: Boolean = false,
    ): InputConnection {
        val content = StringBuilder(initial)
        var start = cursor
        var end = cursor
        return Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getSurroundingText" -> null
                "getExtractedText" -> if (extractedSelectionAvailable) {
                    ExtractedText().apply {
                        text = "x".repeat(9_000)
                        selectionStart = start
                        selectionEnd = end
                    }
                } else null
                "getSelectedText" -> selectedText
                "getTextBeforeCursor" -> content.substring((start - (args!![0] as Int)).coerceAtLeast(0), start)
                "getTextAfterCursor" -> content.substring(end, (end + (args!![0] as Int)).coerceAtMost(content.length))
                "finishComposingText" -> true
                "commitText" -> {
                    val value = args!![0].toString()
                    content.replace(start, end, value)
                    start += value.length
                    end = start
                    true
                }
                "deleteSurroundingTextInCodePoints" -> {
                    val beforeCount = args!![0] as Int
                    val deleteStart = content.offsetByCodePoints(start, -beforeCount)
                    content.delete(deleteStart, start)
                    start = deleteStart
                    end = start
                    true
                }
                "deleteSurroundingText" -> {
                    val deleteStart = start - (args!![0] as Int)
                    content.delete(deleteStart, start)
                    start = deleteStart
                    end = start
                    true
                }
                "toString" -> content.toString()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        } as InputConnection
    }

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
        var finishComposingResult = true
        var selectionStateKnown = true
        var acceptKeyEvents = true
        var rejectDeletion = false
        var rejectActions = false
        val actions = mutableListOf<Int>()
        val keyEvents = mutableListOf<Pair<Int, Int>>()
        val committedText = mutableListOf<String>()
        var deleteOperationCalls = 0

        override fun finishComposingText() = finishComposingResult

        override fun commitText(text: String, newCursorPosition: Int): Boolean {
            committedText += text
            content.replace(start, end, text)
            start += text.length
            end = start
            return true
        }

        override fun cursorSnapshot(beforeLength: Int, afterLength: Int): EditorCursorSnapshot? {
            if (!supportsSnapshot) return null
            if (start != end && selectionStateKnown) return EditorCursorSnapshot(null, null, start, end, end)
            val beforeStart = (start - beforeLength).coerceAtLeast(0)
            val afterEnd = (end + afterLength).coerceAtMost(content.length)
            return EditorCursorSnapshot(
                content.substring(beforeStart, start),
                content.substring(end, afterEnd),
                0,
                0,
                if (selectionStateKnown) end else null,
                selectionIsKnown = selectionStateKnown,
            )
        }

        override fun textBeforeCursor(length: Int): String = content.substring((start - length).coerceAtLeast(0), start)

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            deleteOperationCalls++
            if (rejectDeletion || start != end) return false
            val deleteStart = content.offsetByCodePoints(start, -beforeLength)
            val deleteEnd = content.offsetByCodePoints(start, afterLength)
            content.delete(deleteStart, deleteEnd)
            start = deleteStart
            end = deleteStart
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deleteOperationCalls++
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
            if (!acceptKeyEvents) return false
            if (keyCode == android.view.KeyEvent.KEYCODE_DEL && action == android.view.KeyEvent.ACTION_DOWN) {
                if (start != end) {
                    content.delete(start, end)
                    end = start
                } else if (start > 0) {
                    val deleteStart = content.offsetByCodePoints(start, -1)
                    content.delete(deleteStart, start)
                    start = deleteStart
                    end = start
                }
            }
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
        fun cursor() = end
    }
}
