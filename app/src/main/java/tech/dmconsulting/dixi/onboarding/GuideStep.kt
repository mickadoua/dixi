package tech.dmconsulting.dixi.onboarding

import tech.dmconsulting.dixi.bubble.BubbleState

/**
 * Guided trial with the real bubble (mockups DMC-764 § 3, step 5): one hint at a time,
 * each one done by the real gesture, never by a button.
 */
enum class GuideStep {
    /** a. « Tap the bubble to speak ». */
    Tap,

    /** b. « Speak, then tap again to finish », from the moment it listens. */
    Speak,

    /** c. and d. The text is in the field; « Drag the bubble to the edge to tuck it away ». */
    Dock,

    /** e. « Tap it to bring it back ». */
    Undock,

    /** All done: on to « Dixi est prêt ». */
    Done,
}

/** What the bubble just did, as the guided trial sees it. */
sealed interface GuideEvent {
    data class State(val state: BubbleState) : GuideEvent

    /** A dictation put its text in the field. */
    data object Text : GuideEvent

    data class Docked(val docked: Boolean) : GuideEvent
}

/**
 * The next step after [event]. A dictation that ends without text (cancelled, nothing heard,
 * no network) goes back to [GuideStep.Tap]: the user taps again to retry.
 */
fun GuideStep.next(event: GuideEvent): GuideStep = when (this) {
    GuideStep.Tap -> if (event == GuideEvent.State(BubbleState.Listening)) GuideStep.Speak else this

    GuideStep.Speak -> when (event) {
        GuideEvent.Text -> GuideStep.Dock
        GuideEvent.State(BubbleState.Idle), GuideEvent.State(BubbleState.Error) -> GuideStep.Tap
        else -> this
    }

    GuideStep.Dock -> if (event == GuideEvent.Docked(true)) GuideStep.Undock else this

    GuideStep.Undock -> if (event == GuideEvent.Docked(false)) GuideStep.Done else this

    GuideStep.Done -> this
}
