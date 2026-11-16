package tech.dmconsulting.dixi.paste

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import tech.dmconsulting.dixi.recording.MicrophoneRecorder

/**
 * Auto-paste (brief § 4 step 5, DMC-141), off by default. Its only job: right after a
 * dictation, paste the clipboard into the editable field that has the input focus.
 * It subscribes to no event (see `res/xml/auto_paste_service.xml`): it never reads,
 * stores nor logs any screen content, and does nothing between dictations.
 * Every miss (service off, no editable field, password field, paste refused) ends in the clipboard and
 * the « Texte copié » notification, as without the option.
 */
class AutoPasteService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "auto-paste service connected")
    }

    // No event type is subscribed: nothing comes here.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** One try on the focused field. Reads its state only (editable, focused, password, app), never its text. */
    private fun pasteOnce(): PasteTry {
        // Without events the node cache is never refreshed: ask the app itself.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) clearCache()
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return PasteTry.NoField
        if (!node.refresh() || !isPasteTarget(node.isEditable, node.isFocused, node.isPassword, node.packageName, packageName)) {
            return PasteTry.NoField
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) PasteTry.Pasted else PasteTry.Refused
    }

    private enum class PasteTry { Pasted, NoField, Refused }

    companion object {
        private const val TAG = MicrophoneRecorder.TAG

        /** The suggestion panel just closed: the app below needs a moment to get the focus back. */
        private const val FIRST_TRY_MS = 250L
        private const val RETRY_MS = 250L
        private const val TRIES = 4

        @Volatile
        private var instance: AutoPasteService? = null

        /**
         * Pastes the clipboard into the focused editable field, within about 1 s.
         * False if the service is off, there is no such field, or the app refused.
         */
        suspend fun paste(): Boolean {
            repeat(TRIES) { attempt ->
                delay(if (attempt == 0) FIRST_TRY_MS else RETRY_MS)
                val service = instance ?: return false.also { Log.i(TAG, "auto-paste: service off") }
                when (service.pasteOnce()) {
                    PasteTry.Pasted -> return true.also { Log.i(TAG, "auto-paste: pasted (try ${attempt + 1})") }
                    PasteTry.Refused -> return false.also { Log.i(TAG, "auto-paste: paste refused") }
                    PasteTry.NoField -> Unit
                }
            }
            Log.i(TAG, "auto-paste: no editable field")
            return false
        }

        /** Turned on in Android's accessibility settings (read from the system, not from the app). */
        fun isEnabled(context: Context): Boolean = isServiceListed(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            context.packageName,
            AutoPasteService::class.java.name,
        )

        /** Switching the option off in the app turns the service off too: nothing stays granted. */
        fun turnOff() {
            instance?.disableSelf()
        }

        /** Android's accessibility settings, where the user turns the service on. */
        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
