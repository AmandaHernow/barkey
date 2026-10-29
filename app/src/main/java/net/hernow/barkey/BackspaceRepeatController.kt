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
        if (!repeat()) return false
        val task = object : Runnable {
            override fun run() {
                if (repeat()) scheduler.postDelayed(this, intervalMillis) else repeatTask = null
            }
        }
        repeatTask = task
        scheduler.postDelayed(task, timeoutMillis)
        return true
    }

    fun stop() {
        repeatTask?.let(scheduler::remove)
        repeatTask = null
    }
}
