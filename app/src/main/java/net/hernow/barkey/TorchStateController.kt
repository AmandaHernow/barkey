package net.hernow.barkey

/** Identifies one async torch request so an old reply cannot change newer state. */
internal data class TorchRequest(val generation: Long, val enabled: Boolean)

/** Main-thread state for asynchronous torch requests; failures leave the confirmed state intact. */
internal class TorchStateController {
    private var generation = 0L
    var isEnabled: Boolean = false
        private set
    var isPending: Boolean = false
        private set

    /** Starts a toggle only when flash is available and no request is already pending. */
    fun beginToggle(hasFlashUnit: Boolean): TorchRequest? {
        if (!hasFlashUnit || isPending) return null
        isPending = true
        generation += 1
        return TorchRequest(generation, enabled = !isEnabled)
    }

    /** Applies a reply only if it belongs to the latest request. */
    fun complete(request: TorchRequest, succeeded: Boolean): Boolean {
        if (request.generation != generation || !isPending) return false
        isPending = false
        if (succeeded) isEnabled = request.enabled
        return true
    }

    /** Clears pending and confirmed state when the camera closes. */
    fun reset() {
        generation += 1
        isPending = false
        isEnabled = false
    }
}
