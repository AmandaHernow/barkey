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
}
