package tech.dmconsulting.dixi.home

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.dmconsulting.dixi.settings.BubbleToggle

class BubbleCardStateTest {
    private fun toggle(shown: Boolean?, permitted: Boolean) = BubbleToggle(shown, permitted) {}

    @Test
    fun theCardSaysIfTheBubbleIsReady() {
        assertEquals(BubbleCardState.Active, bubbleCardState(toggle(shown = true, permitted = true)))
        assertEquals(BubbleCardState.Inactive, bubbleCardState(toggle(shown = false, permitted = true)))
        assertEquals("still loading", BubbleCardState.Inactive, bubbleCardState(toggle(shown = null, permitted = true)))
    }

    @Test
    fun aMissingPermissionComesFirst() {
        assertEquals(BubbleCardState.PermissionMissing, bubbleCardState(toggle(shown = false, permitted = false)))
    }
}
