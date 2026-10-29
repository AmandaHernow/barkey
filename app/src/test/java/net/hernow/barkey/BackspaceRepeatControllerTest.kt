package net.hernow.barkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackspaceRepeatControllerTest {
    @Test fun shortTapDeletesOnceAndHeldPressRepeatsAtConfiguredInterval() {
        val scheduler = FakeScheduler()
        val controller = BackspaceRepeatController(scheduler, timeoutMillis = 500, intervalMillis = 50)
        var deletions = 0
        assertTrue(controller.press { deletions++; true })
        assertEquals(1, deletions)
        controller.stop() // release before timeout
        scheduler.runAll()
        assertEquals(1, deletions)

        assertTrue(controller.press { deletions++; true })
        assertEquals(2, deletions)

        scheduler.runNext()
        assertEquals(3, deletions)
        assertEquals(listOf(500L, 50L), scheduler.delays.takeLast(2))
        scheduler.runNext()
        assertEquals(4, deletions)

        controller.stop()
        scheduler.runAll()
        assertEquals(4, deletions)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test fun failedDeletionStopsAndDoesNotScheduleAgain() {
        val scheduler = FakeScheduler()
        val controller = BackspaceRepeatController(scheduler, 500, 50)
        assertFalse(controller.press { false })
        assertFalse(controller.isRepeating)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test fun reachingStartOfFieldStopsRepeatingWithoutBoundaryRetries() {
        val scheduler = FakeScheduler()
        val controller = BackspaceRepeatController(scheduler, 500, 50)
        val connection = MutableTextConnection("ABC")
        val editor = InputConnectionEditor()
        var deletions = 0

        assertTrue(controller.press {
            when (editor.backspace(connection)) {
                BackspaceResult.DELETED -> { deletions++; true }
                BackspaceResult.NO_OP, BackspaceResult.FAILED -> false
            }
        })
        while (scheduler.tasks.isNotEmpty()) scheduler.runNext()

        assertEquals("", connection.value())
        assertEquals(3, deletions)
        assertFalse(controller.isRepeating)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test fun stopDuringDeletionCallbackCannotRescheduleCurrentTask() {
        val scheduler = FakeScheduler()
        val controller = BackspaceRepeatController(scheduler, 500, 50)
        var deletions = 0
        controller.press {
            deletions++
            if (deletions == 2) controller.stop()
            true
        }

        scheduler.runNext()

        assertEquals(2, deletions)
        assertFalse(controller.isRepeating)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test fun stoppedQueuedCallbackCannotRepeat() {
        val scheduler = FakeScheduler()
        val controller = BackspaceRepeatController(scheduler, 500, 50)
        var deletions = 0
        controller.press { deletions++; true }
        val queuedTask = scheduler.tasks.single()

        controller.stop()
        queuedTask.run() // Simulate a callback already dequeued when cancellation happened.

        assertEquals(1, deletions)
        assertFalse(controller.isRepeating)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test fun replacedRepeatTaskDoesNotRescheduleTheOldTask() {
        val scheduler = FakeScheduler()
        val controller = BackspaceRepeatController(scheduler, 500, 50)
        var deletions = 0
        controller.press {
            deletions++
            if (deletions == 2) controller.press { true }
            true
        }

        scheduler.runNext()

        assertEquals(2, deletions)
        assertEquals(1, scheduler.tasks.size)
        assertEquals(listOf(500L, 500L), scheduler.delays)
    }

    private class FakeScheduler : BackspaceRepeatController.Scheduler {
        val tasks = mutableListOf<Runnable>()
        val delays = mutableListOf<Long>()

        override fun postDelayed(task: Runnable, delayMillis: Long) {
            tasks += task
            delays += delayMillis
        }

        override fun remove(task: Runnable) {
            tasks.removeAll { it === task }
        }

        fun runNext() {
            tasks.removeAt(0).run()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }

    private class MutableTextConnection(initialText: String) : EditorConnection {
        override val identity: Any = Any()
        private val text = StringBuilder(initialText)
        private var cursor = initialText.length

        override fun finishComposingText() = true
        override fun commitText(text: String, newCursorPosition: Int): Boolean = false
        override fun cursorSnapshot(beforeLength: Int, afterLength: Int): EditorCursorSnapshot = EditorCursorSnapshot(
            textBeforeCursor = text.substring((cursor - beforeLength).coerceAtLeast(0), cursor),
            textAfterCursor = text.substring(cursor, (cursor + afterLength).coerceAtMost(text.length)),
            selectionStart = 0,
            selectionEnd = 0,
            absoluteCursorPosition = cursor,
        )
        override fun textBeforeCursor(length: Int): String = text.substring((cursor - length).coerceAtLeast(0), cursor)
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            val start = text.offsetByCodePoints(cursor, -beforeLength)
            text.delete(start, cursor)
            cursor = start
            return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            text.delete(cursor - beforeLength, cursor)
            cursor -= beforeLength
            return true
        }
        override fun performEditorAction(action: Int) = false
        override fun sendKeyEvent(keyCode: Int, action: Int) = false
        fun value() = text.toString()
    }
}
