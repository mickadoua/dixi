package tech.dmconsulting.voxbulle.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    @Test
    fun asksOnAndroid13AndAboveWhileOff() {
        (33..37).forEach { sdk -> assertTrue(shouldAskNotifications(sdk, enabled = false, asked = false)) }
    }

    @Test
    fun neverAsksBelowAndroid13() {
        (26..32).forEach { sdk ->
            assertFalse(shouldAskNotifications(sdk, enabled = false, asked = false))
            assertFalse(shouldAskNotifications(sdk, enabled = true, asked = false))
        }
    }

    @Test
    fun asksOnlyOnce() {
        assertFalse(shouldAskNotifications(36, enabled = false, asked = true))
    }

    @Test
    fun neverAsksWhenAlreadyOn() {
        assertFalse(shouldAskNotifications(36, enabled = true, asked = false))
    }

    @Test
    fun offMessageWaitsForTheSystemWindow() {
        assertFalse(showNotificationsOff(36, enabled = false, asked = false))
    }

    @Test
    fun noOffMessageUnderTheOpenWindow() {
        assertFalse(showNotificationsOff(36, enabled = false, asked = true, windowOpen = true))
    }

    @Test
    fun offMessageAfterARefusal() {
        assertTrue(showNotificationsOff(36, enabled = false, asked = true))
    }

    @Test
    fun offMessageBelowAndroid13WhenTurnedOffInTheSettings() {
        assertTrue(showNotificationsOff(30, enabled = false, asked = false))
    }

    @Test
    fun noOffMessageWhenOn() {
        assertFalse(showNotificationsOff(36, enabled = true, asked = true))
        assertFalse(showNotificationsOff(30, enabled = true, asked = false))
    }
}
