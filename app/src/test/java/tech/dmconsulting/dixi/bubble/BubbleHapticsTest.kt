package tech.dmconsulting.dixi.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleHapticsTest {
    @Test
    fun oneTapWhenTheDictationStarts() {
        listOf(BubbleState.Idle, BubbleState.Ready, BubbleState.Error).forEach {
            assertEquals(listOf(Haptic.Confirm), hapticsFor(it, BubbleState.Listening))
        }
    }

    @Test
    fun twoTapsWhenItStops() {
        assertEquals(listOf(Haptic.Confirm, Haptic.Confirm), hapticsFor(BubbleState.Listening, BubbleState.Processing))
    }

    @Test
    fun aRefusalWhenItIsCancelledOrFails() {
        assertEquals(listOf(Haptic.Reject), hapticsFor(BubbleState.Listening, BubbleState.Idle))
        assertEquals(listOf(Haptic.Reject), hapticsFor(BubbleState.Processing, BubbleState.Error))
        assertEquals(listOf(Haptic.Reject), hapticsFor(BubbleState.Listening, BubbleState.Error))
    }

    @Test
    fun nothingForTheTextReadyOrBackToRest() {
        assertEquals(emptyList<Haptic>(), hapticsFor(BubbleState.Processing, BubbleState.Ready))
        assertEquals(emptyList<Haptic>(), hapticsFor(BubbleState.Ready, BubbleState.Idle))
        assertEquals(emptyList<Haptic>(), hapticsFor(BubbleState.Error, BubbleState.Idle))
        assertEquals(emptyList<Haptic>(), hapticsFor(BubbleState.Idle, BubbleState.Idle))
    }
}
