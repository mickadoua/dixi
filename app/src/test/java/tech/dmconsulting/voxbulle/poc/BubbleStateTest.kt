package tech.dmconsulting.voxbulle.poc

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleStateTest {
    @Test
    fun formatsDurationWithFrenchComma() {
        assertEquals("5,0 s", formatDuration(5_012))
        assertEquals("0,0 s", formatDuration(0))
    }

    @Test
    fun listeningUsesTertiaryColor() {
        assertEquals(0xFFB3402A.toInt(), BubbleState.Listening.color)
    }
}
