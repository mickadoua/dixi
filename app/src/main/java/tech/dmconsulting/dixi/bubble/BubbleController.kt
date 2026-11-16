package tech.dmconsulting.dixi.bubble

/** What a tap on the bubble asks for, in the current state. */
enum class TapAction { Start, Stop, None }

/** Runs [block] after [delayMs]; the returned function cancels it. */
fun interface Scheduler {
    fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
}

/**
 * Bubble state machine. The dictation chain calls [setState]; the bubble view
 * follows [onStateChanged]. « Texte prêt » goes back to « repos » after 1.5 s,
 * an error after 3.5 s (the time its message shows).
 */
class BubbleController(private val scheduler: Scheduler) {

    var state: BubbleState = BubbleState.Idle
        private set

    var onStateChanged: (BubbleState) -> Unit = {}

    /** Voice level, 0 to 1, while listening: the halo follows it. */
    var onVoiceLevel: (Float) -> Unit = {}

    /** How long « Texte prêt » and an error stay. Only the debug proof hook changes them. */
    var readyToIdleMs: Long = READY_TO_IDLE_MS
    var errorToIdleMs: Long = ERROR_TO_IDLE_MS

    private var cancelIdleTimeout: (() -> Unit)? = null

    fun setState(newState: BubbleState) {
        cancelIdleTimeout?.invoke()
        cancelIdleTimeout = null
        state = newState
        onStateChanged(newState)
        val toIdleMs = when (newState) {
            BubbleState.Ready -> readyToIdleMs
            BubbleState.Error -> errorToIdleMs
            else -> return
        }
        cancelIdleTimeout = scheduler.schedule(toIdleMs) {
            cancelIdleTimeout = null
            setState(BubbleState.Idle)
        }
    }

    fun setVoiceLevel(level: Float) = onVoiceLevel(level.coerceIn(0f, 1f))

    /** Tap = start or stop (mockups, screen 6). Nothing while the text is processed. */
    fun tapAction(): TapAction = when (state) {
        BubbleState.Idle, BubbleState.Ready, BubbleState.Error -> TapAction.Start
        BubbleState.Listening -> TapAction.Stop
        BubbleState.Processing -> TapAction.None
    }

    /** The hide zone is offered only when no dictation is running. */
    val canHide: Boolean get() = tapAction() == TapAction.Start

    /**
     * Calls [hide] only when [canHide]. Hiding stops the service: during a
     * dictation that would cut the microphone and lose the text.
     */
    fun hideIfAllowed(hide: () -> Unit): Boolean {
        if (!canHide) return false
        hide()
        return true
    }

    companion object {
        const val READY_TO_IDLE_MS = 1_500L

        /** Like a long Toast, which shows the error message. */
        const val ERROR_TO_IDLE_MS = 3_500L
    }
}
