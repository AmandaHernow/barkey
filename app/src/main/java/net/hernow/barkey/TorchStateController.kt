package net.hernow.barkey

internal data class TorchRequest(val generation: Long, val enabled: Boolean)

/** Main-thread state for asynchronous torch requests; failures leave the confirmed state intact. */
internal class TorchStateController {
    private var generation = 0L
    var isEnabled: Boolean = false
        private set
    var isPending: Boolean = false
        private set

    fun beginToggle(hasFlashUnit: Boolean): TorchRequest? {
        if (!hasFlashUnit || isPending) return null
        isPending = true
        generation += 1
        return TorchRequest(generation, enabled = !isEnabled)
    }

    fun complete(request: TorchRequest, succeeded: Boolean): Boolean {
        if (request.generation != generation || !isPending) return false
        isPending = false
        if (succeeded) isEnabled = request.enabled
        return true
    }

    fun reset() {
        generation += 1
        isPending = false
        isEnabled = false
    }
}
