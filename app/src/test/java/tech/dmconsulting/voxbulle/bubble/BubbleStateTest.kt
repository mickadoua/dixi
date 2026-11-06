package tech.dmconsulting.voxbulle.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.dmconsulting.voxbulle.R

class BubbleStateTest {
    @Test
    fun colorsFollowTheNeonCharter() {
        assertEquals(
            listOf(R.color.bubble_disc, R.color.bubble_orange, R.color.bubble_disc, R.color.bubble_disc, R.color.bubble_disc),
            BubbleState.entries.map { it.fill },
        )
        assertEquals(
            listOf(
                R.color.bubble_orange,
                R.color.bubble_orange,
                R.color.bubble_processing_outline,
                R.color.bubble_cyan,
                R.color.bubble_magenta,
            ),
            BubbleState.entries.map { it.outline },
        )
        assertEquals(
            listOf(
                R.color.bubble_orange,
                R.color.bubble_on_orange,
                R.color.bubble_cyan,
                R.color.bubble_cyan,
                R.color.bubble_magenta,
            ),
            BubbleState.entries.map { it.content },
        )
        assertEquals(
            listOf(
                R.color.bubble_orange,
                R.color.bubble_orange,
                R.color.bubble_cyan,
                R.color.bubble_cyan,
                R.color.bubble_magenta,
            ),
            BubbleState.entries.map { it.glowColor },
        )
    }

    @Test
    fun glowStaysInsideTheBackdropMargin() {
        BubbleState.entries.flatMap { it.glow }.forEach {
            assertTrue(it.blurDp <= BubbleState.GLOW_MARGIN_DP)
            assertTrue(it.alpha in 0f..1f)
        }
    }

    @Test
    fun eachStateHasItsOwnDescription() {
        assertEquals(
            listOf(
                R.string.bubble_idle,
                R.string.bubble_listening,
                R.string.bubble_processing,
                R.string.bubble_ready,
                R.string.bubble_error,
            ),
            BubbleState.entries.map { it.description },
        )
    }

    @Test
    fun opacityIs92PercentAtRestOnly() {
        assertEquals(0.92f, BubbleState.Idle.opacity)
        BubbleState.entries.filter { it != BubbleState.Idle }.forEach { assertEquals(1f, it.opacity) }
    }
}
