package tech.dmconsulting.voxbulle.onboarding

/** Android 13 (API 33): first version that asks before showing an app's notifications. */
const val NOTIFICATIONS_PERMISSION_SDK = 33

/**
 * Screen 5 shows Android's notification window once, on Android 13+, while
 * notifications are still off. Below 13 they are on unless turned off in the settings.
 */
fun shouldAskNotifications(sdkInt: Int, enabled: Boolean, asked: Boolean): Boolean =
    sdkInt >= NOTIFICATIONS_PERMISSION_SDK && !enabled && !asked

/**
 * « Notifications désactivées » replaces « Une notification vous le confirme »
 * when notifications are off and there is nothing left to ask. Never under
 * Android's window while it waits for an answer ([windowOpen]).
 */
fun showNotificationsOff(sdkInt: Int, enabled: Boolean, asked: Boolean, windowOpen: Boolean = false): Boolean =
    !windowOpen && !enabled && !shouldAskNotifications(sdkInt, enabled, asked)
