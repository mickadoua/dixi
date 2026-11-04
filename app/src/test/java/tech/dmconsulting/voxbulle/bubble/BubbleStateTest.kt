package tech.dmconsulting.voxbulle.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleStateTest {
    @Test
    fun listeningUsesTertiaryColor() {
        assertEquals(0xFFB3402A.toInt(), BubbleState.Listening.color)
    }
}
