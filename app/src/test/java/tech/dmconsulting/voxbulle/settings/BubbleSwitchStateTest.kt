package tech.dmconsulting.voxbulle.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.dmconsulting.voxbulle.bubble.BubbleVisibility

class BubbleSwitchStateTest {
    @After
    fun reset() = BubbleVisibility.set(false)

    @Test
    fun bubbleShownDuringOnboardingMakesHomeSayActive() {
        // Saved setting still off: the « Touchez, parlez » button started the bubble (DMC-433).
        BubbleVisibility.set(true)
        assertEquals(true, bubbleSwitchState(settingsLoaded = true, BubbleVisibility.visible.value, canShowBubble = true))
    }

    @Test
    fun bubbleHiddenFromItsMenuMakesHomeSayOff() {
        BubbleVisibility.set(true)
        BubbleVisibility.set(false)
        assertEquals(false, bubbleSwitchState(settingsLoaded = true, BubbleVisibility.visible.value, canShowBubble = true))
    }

    @Test
    fun overlayPermissionTakenBackMakesHomeSayOff() {
        assertEquals(false, bubbleSwitchState(settingsLoaded = true, bubbleVisible = true, canShowBubble = false))
    }

    @Test
    fun unknownWhileTheSettingsLoad() {
        assertNull(bubbleSwitchState(settingsLoaded = false, bubbleVisible = true, canShowBubble = true))
    }
}
