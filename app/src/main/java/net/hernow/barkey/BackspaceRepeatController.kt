package net.hernow.barkey

/** Main-thread key repeat with explicit cancellation and platform-provided timing. */
internal class BackspaceRepeatController(
    private val scheduler: Scheduler,
    private val timeoutMillis: Long,
    private val intervalMillis: Long,
) {
    interface Scheduler {
        fun postDelayed(task: Runnable, delayMillis: Long)
        fun remove(task: Runnable)
    }

    private var repeatTask: Runnable? = null
    internal val isRepeating: Boolean get() = repeatTask != null

    /** Performs the initial tap immediately, then schedules repeats after the platform timeout. */
    fun press(repeat: () -> Boolean): Boolean {
        stop()
        val task = object : Runnable {
            override fun run() {
                if (repeatTask !== this) return
                val shouldRepeat = repeat()
                if (repeatTask !== this) return
                if (shouldRepeat) scheduler.postDelayed(this, intervalMillis) else repeatTask = null
            }
        }
        repeatTask = task
        if (!repeat()) {
            if (repeatTask === task) repeatTask = null
            return false
        }
        if (repeatTask !== task) return true
        scheduler.postDelayed(task, timeoutMillis)
        return true
    }

    fun stop() {
        repeatTask?.let(scheduler::remove)
        repeatTask = null
    }
}
