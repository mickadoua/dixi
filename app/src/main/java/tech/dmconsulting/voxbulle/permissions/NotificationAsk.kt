package tech.dmconsulting.voxbulle.permissions

import android.content.Context
import android.os.Build
import androidx.core.content.edit

/**
 * The notifications are asked once, at the first dictation (DMC-372). With the microphone
 * already allowed (an update, `pm grant`), the microphone flow never runs: this asks alone.
 * Refused once: never asked again, only « Texte copié » is lost.
 */
fun shouldAskNotifications(sdk: Int, micGranted: Boolean, notificationsGranted: Boolean, askedBefore: Boolean) = sdk >= Build.VERSION_CODES.TIRAMISU && micGranted && !notificationsGranted && !askedBefore

/** Whether Android's notification request was already shown, kept across updates. */
class NotificationAskStore(context: Context) {

    private val prefs = context.getSharedPreferences("permissions", Context.MODE_PRIVATE)

    var asked: Boolean
        get() = prefs.getBoolean(KEY_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_ASKED, value) }

    private companion object {
        const val KEY_ASKED = "notifications_asked"
    }
}
