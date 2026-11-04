package tech.dmconsulting.voxbulle.bubble

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.dmconsulting.voxbulle.R

class BubbleStateTest {
    @Test
    fun colorsFollowTheCharter() {
        assertEquals(R.color.bubble_primary, BubbleState.Idle.background)
        assertEquals(R.color.bubble_tertiary, BubbleState.Listening.background)
        assertEquals(R.color.bubble_primary, BubbleState.Processing.background)
        assertEquals(R.color.bubble_secondary, BubbleState.Ready.background)
        assertEquals(R.color.bubble_error, BubbleState.Error.background)
    }

    @Test
    fun eachStateHasItsOwnDescription() {
        assertEquals(
            listOf(
                R.string.bubble_idle, R.string.bubble_listening, R.string.bubble_processing, R.string.bubble_ready,
                R.string.bubble_error,
            ),
            BubbleState.entries.map { it.description },
        )
    }

    @Test
    fun opacityIs90PercentAtRestOnly() {
        assertEquals(0.9f, BubbleState.Idle.opacity)
        BubbleState.entries.filter { it != BubbleState.Idle }.forEach { assertEquals(1f, it.opacity) }
    }
}
