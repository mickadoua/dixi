package tech.dmconsulting.dixi.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.dmconsulting.dixi.bubble.BubbleState

class GuideStepTest {
    private fun GuideStep.after(vararg events: GuideEvent) = events.fold(this) { step, event -> step.next(event) }

    @Test
    fun eachStepIsDoneByTheRealGesture() {
        val end = GuideStep.Tap.after(
            GuideEvent.State(BubbleState.Listening),
            GuideEvent.State(BubbleState.Processing),
            GuideEvent.Text,
            GuideEvent.State(BubbleState.Ready),
            GuideEvent.State(BubbleState.Idle),
            GuideEvent.Docked(true),
            GuideEvent.Docked(false),
        )
        assertEquals(GuideStep.Done, end)
    }

    @Test
    fun listeningMovesToSpeak() {
        assertEquals(GuideStep.Speak, GuideStep.Tap.next(GuideEvent.State(BubbleState.Listening)))
        assertEquals(GuideStep.Tap, GuideStep.Tap.next(GuideEvent.State(BubbleState.Idle)))
    }

    @Test
    fun aDictationWithoutTextGoesBackToTap() {
        assertEquals(GuideStep.Tap, GuideStep.Speak.next(GuideEvent.State(BubbleState.Error)))
        assertEquals("cancelled by a long press", GuideStep.Tap, GuideStep.Speak.next(GuideEvent.State(BubbleState.Idle)))
        assertEquals(GuideStep.Speak, GuideStep.Speak.next(GuideEvent.State(BubbleState.Processing)))
    }

    @Test
    fun tuckingBeforeTheTextDoesNotSkipAStep() {
        assertEquals(GuideStep.Tap, GuideStep.Tap.next(GuideEvent.Docked(true)))
        assertEquals(GuideStep.Speak, GuideStep.Speak.next(GuideEvent.Docked(true)))
        assertEquals(GuideStep.Dock, GuideStep.Dock.next(GuideEvent.Docked(false)))
    }

    @Test
    fun doneStaysDone() {
        GuideEvent.State(BubbleState.Listening).let { assertEquals(GuideStep.Done, GuideStep.Done.next(it)) }
        assertEquals(GuideStep.Done, GuideStep.Done.next(GuideEvent.Docked(true)))
    }
}
