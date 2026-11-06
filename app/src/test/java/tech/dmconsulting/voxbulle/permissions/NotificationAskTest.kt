package tech.dmconsulting.voxbulle.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAskTest {
    private val android13 = 33
    private val android12 = 32

    @Test
    fun microphoneAlreadyAllowedAsksForTheNotifications() {
        assertTrue(shouldAskNotifications(android13, micGranted = true, notificationsGranted = false, askedBefore = false))
    }

    @Test
    fun askedOnceIsNotAskedAgain() {
        assertFalse(shouldAskNotifications(android13, micGranted = true, notificationsGranted = false, askedBefore = true))
    }

    @Test
    fun allowedNotificationsAreNotAsked() {
        assertFalse(shouldAskNotifications(android13, micGranted = true, notificationsGranted = true, askedBefore = false))
    }

    @Test
    fun withoutTheMicrophoneTheMicrophoneFlowAsksBoth() {
        assertFalse(shouldAskNotifications(android13, micGranted = false, notificationsGranted = false, askedBefore = false))
    }

    @Test
    fun beforeAndroid13ThereIsNothingToAsk() {
        assertFalse(shouldAskNotifications(android12, micGranted = true, notificationsGranted = false, askedBefore = false))
    }
}
