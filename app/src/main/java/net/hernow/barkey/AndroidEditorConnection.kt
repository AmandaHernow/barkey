package net.hernow.barkey

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Adapts the current Android editor without retaining its text outside bounded Undo context. */
internal class AndroidEditorConnection(
    private val inputConnection: InputConnection,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
) : EditorConnection {
    override val identity: Any
        get() = inputConnection

    override fun finishComposingText(): Boolean = inputConnection.finishComposingText()

    override fun commitText(text: String, newCursorPosition: Int): Boolean =
        inputConnection.commitText(text, newCursorPosition)

    override fun cursorSnapshot(beforeLength: Int, afterLength: Int): EditorCursorSnapshot? {
        if (beforeLength < 0 || afterLength < 0) return null
        val surrounding = if (apiLevel >= Build.VERSION_CODES.S) {
            attemptSnapshot { surroundingTextSnapshot(beforeLength, afterLength) }
        } else null
        return surrounding
            ?: attemptSnapshot { extractedTextSnapshot(beforeLength, afterLength) }
            ?: attemptSnapshot { beforeAfterSnapshot(beforeLength, afterLength) }
    }

    override fun textBeforeCursor(length: Int): String? = try {
        inputConnection.getTextBeforeCursor(length, 0)?.toString()
    } catch (_: RuntimeException) {
        null
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        inputConnection.deleteSurroundingTextInCodePoints(beforeLength, afterLength)

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        inputConnection.deleteSurroundingText(beforeLength, afterLength)

    override fun performEditorAction(action: Int): Boolean = inputConnection.performEditorAction(action)

    override fun sendKeyEvent(keyCode: Int, action: Int): Boolean {
        val eventTime = SystemClock.uptimeMillis()
        return inputConnection.sendKeyEvent(KeyEvent(eventTime, eventTime, action, keyCode, 0))
    }

    private fun surroundingTextSnapshot(beforeLength: Int, afterLength: Int): EditorCursorSnapshot? {
        val surrounding = inputConnection.getSurroundingText(beforeLength, afterLength, 0) ?: return null
        val start = surrounding.selectionStart
        val end = surrounding.selectionEnd
        if (start < 0 || end < 0) return null
        val absoluteCursor = if (surrounding.offset >= 0) surrounding.offset + end else null

        // The platform includes the selected range in SurroundingText. Do not copy selected text.
        if (start != end) return EditorCursorSnapshot(null, null, start, end, absoluteCursor)

        val surroundingChars = surrounding.text
        if (start > surroundingChars.length) return null
        val text = surroundingChars.toString()
        return EditorCursorSnapshot(
            textBeforeCursor = text.substring(0, start),
            textAfterCursor = text.substring(end),
            selectionStart = start,
            selectionEnd = end,
            absoluteCursorPosition = absoluteCursor,
        )
    }

    @Suppress("DEPRECATION")
    private fun extractedTextSnapshot(beforeLength: Int, afterLength: Int): EditorCursorSnapshot? {
        val request = ExtractedTextRequest().apply {
            token = 0
            hintMaxChars = (beforeLength + afterLength).coerceAtLeast(1)
            hintMaxLines = 0
        }
        val extracted = inputConnection.getExtractedText(request, 0) ?: return null
        val text = extracted.text ?: return null
        val start = extracted.selectionStart
        val end = extracted.selectionEnd
        if (start < 0 || end < 0 || start > text.length || end > text.length) return null
        val absoluteCursor = if (extracted.startOffset >= 0) extracted.startOffset + end else null

        // Selection offsets are sufficient to reject Undo or delete a selection. Never copy the
        // selected contents into the temporary keyboard state.
        if (start != end) return EditorCursorSnapshot(null, null, start, end, absoluteCursor)

        // Older editors may return the whole field despite the extraction hint. Bound what we
        // inspect; a large or partial response still provides no trustworthy Undo context.
        if (text.length > MAX_LEGACY_CONTEXT_CHARS || extracted.partialStartOffset != -1 ||
            extracted.startOffset != 0) return null
        return EditorCursorSnapshot(
            textBeforeCursor = text.subSequence((start - beforeLength).coerceAtLeast(0), start).toString(),
            textAfterCursor = text.subSequence(end, (end + afterLength).coerceAtMost(text.length)).toString(),
            selectionStart = start,
            selectionEnd = end,
            absoluteCursorPosition = absoluteCursor,
        )
    }

    private fun beforeAfterSnapshot(beforeLength: Int, afterLength: Int): EditorCursorSnapshot? {
        val before = inputConnection.getTextBeforeCursor(beforeLength, 0)?.toString() ?: return null
        val after = inputConnection.getTextAfterCursor(afterLength, 0)?.toString() ?: return null
        if (before.length > beforeLength || after.length > afterLength) return null
        // A null selected-text result can mean either no selection or an unavailable query.
        // Use extracted selection offsets as a second source; otherwise keep the snapshot unknown.
        val selected = try {
            inputConnection.getSelectedText(0)
        } catch (_: RuntimeException) {
            null
        }
        val extractedSelection = if (selected == null) {
            try {
                extractedSelectionCollapsed()
            } catch (_: RuntimeException) {
                null
            }
        } else null
        val selectionCollapsed = selected?.let { it.isEmpty() } ?: extractedSelection
        if (selectionCollapsed == false) return EditorCursorSnapshot(null, null, 0, 1, null)
        return EditorCursorSnapshot(
            textBeforeCursor = before,
            textAfterCursor = after,
            selectionStart = 0,
            selectionEnd = 0,
            absoluteCursorPosition = null,
            selectionIsKnown = selectionCollapsed != null,
        )
    }

    @Suppress("DEPRECATION")
    private fun extractedSelectionCollapsed(): Boolean? {
        val request = ExtractedTextRequest().apply {
            token = 0
            hintMaxChars = SELECTION_HINT_CHARS
            hintMaxLines = 0
        }
        val extracted = inputConnection.getExtractedText(request, 0) ?: return null
        val textLength = extracted.text?.length ?: return null
        val start = extracted.selectionStart
        val end = extracted.selectionEnd
        if (start < 0 || end < 0 || start > textLength || end > textLength) return null
        return start == end
    }

    private inline fun attemptSnapshot(query: () -> EditorCursorSnapshot?): EditorCursorSnapshot? = try {
        query()
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        const val MAX_LEGACY_CONTEXT_CHARS = 8_192
        const val SELECTION_HINT_CHARS = 64
    }
}
