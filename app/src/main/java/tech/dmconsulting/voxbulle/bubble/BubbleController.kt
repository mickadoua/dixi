package tech.dmconsulting.voxbulle.bubble

/** What a tap on the bubble asks for, in the current state. */
enum class TapAction { Start, Stop, None }

/** Runs [block] after [delayMs]; the returned function cancels it. */
fun interface Scheduler {
    fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
}

/**
 * Bubble state machine. The dictation chain calls [setState]; the bubble view
 * follows [onStateChanged]. « Texte prêt » goes back to « repos » after 1.5 s.
 */
class BubbleController(private val scheduler: Scheduler) {

    var state: BubbleState = BubbleState.Idle
        private set

    var onStateChanged: (BubbleState) -> Unit = {}

    /** Voice level, 0 to 1, while listening: the halo follows it. */
    var onVoiceLevel: (Float) -> Unit = {}

    private var cancelReadyTimeout: (() -> Unit)? = null

    fun setState(newState: BubbleState) {
        cancelReadyTimeout?.invoke()
        cancelReadyTimeout = null
        state = newState
        onStateChanged(newState)
        if (newState == BubbleState.Ready) {
            cancelReadyTimeout = scheduler.schedule(READY_TO_IDLE_MS) {
                cancelReadyTimeout = null
                setState(BubbleState.Idle)
            }
        }
    }

    fun setVoiceLevel(level: Float) = onVoiceLevel(level.coerceIn(0f, 1f))

    /** Tap = start or stop (mockups, screen 6). Nothing while the text is processed. */
    fun tapAction(): TapAction = when (state) {
        BubbleState.Idle, BubbleState.Ready -> TapAction.Start
        BubbleState.Listening -> TapAction.Stop
        BubbleState.Processing -> TapAction.None
    }

    /** The hide zone is offered only when no dictation is running. */
    val canHide: Boolean get() = state == BubbleState.Idle || state == BubbleState.Ready

    companion object {
        const val READY_TO_IDLE_MS = 1_500L
    }
}
