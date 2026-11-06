package tech.dmconsulting.voxbulle.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.bubble.BubbleService
import tech.dmconsulting.voxbulle.ui.theme.VoxbulleTheme

/**
 * Asks for the microphone, and the notifications on Android 13+, the first time
 * the user dictates (DMC-338). Opened by the bubble, over the app in use: an
 * explanation, then Android's requests. Allowed: the dictation starts. Refused a
 * second time (Android will not ask again): a way to the app's settings.
 * Microphone already allowed: only the notification request, then the dictation (DMC-372).
 */
class MicPermissionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoxbulleTheme {
                MicPermissionFlow(
                    notificationsOnly = intent.getBooleanExtra(EXTRA_NOTIFICATIONS_ONLY, false),
                    askNotifications = { needsNotificationPermission() },
                    onAskNotifications = { NotificationAskStore(this).asked = true },
                    canAskMicAgain = { shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) },
                    openSettings = { startActivity(appSettingsIntent()) },
                    onDone = ::done,
                )
            }
        }
    }

    private fun done(micGranted: Boolean) {
        // Same as a tap on the bubble: the user wanted to dictate.
        if (micGranted) BubbleService.dictate(this)
        finish()
    }

    private fun needsNotificationPermission() = shouldAskNotificationsAfterMic(
        sdk = Build.VERSION.SDK_INT,
        notificationsGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.POST_NOTIFICATIONS),
        askedBefore = NotificationAskStore(this).asked,
    )

    private fun appSettingsIntent() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        private const val EXTRA_NOTIFICATIONS_ONLY = "notifications_only"

        fun start(context: Context, notificationsOnly: Boolean = false) {
            context.startActivity(
                Intent(context, MicPermissionActivity::class.java)
                    .putExtra(EXTRA_NOTIFICATIONS_ONLY, notificationsOnly)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** The first dictation with the microphone already allowed asks the notifications once. */
        fun askNotificationsIfNeeded(context: Context): Boolean {
            val store = NotificationAskStore(context)
            val ask = shouldAskNotifications(
                sdk = Build.VERSION.SDK_INT,
                micGranted = context.granted(Manifest.permission.RECORD_AUDIO),
                notificationsGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.granted(Manifest.permission.POST_NOTIFICATIONS),
                askedBefore = store.asked,
            )
            if (ask) start(context, notificationsOnly = true)
            return ask
        }
    }
}

private fun Context.granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private enum class Step { Explain, Asking, Settings }

@Composable
private fun MicPermissionFlow(
    notificationsOnly: Boolean,
    askNotifications: () -> Boolean,
    onAskNotifications: () -> Unit,
    canAskMicAgain: () -> Boolean,
    openSettings: () -> Unit,
    onDone: (micGranted: Boolean) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(if (notificationsOnly) Step.Asking else Step.Explain) }
    var micGranted by rememberSaveable { mutableStateOf(false) }

    fun afterMic() {
        when {
            micGranted -> onDone(true)

            // Second refusal, or « Ne plus demander »: only the settings can allow it now.
            !canAskMicAgain() -> step = Step.Settings

            else -> onDone(false)
        }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Refused notifications do not stop the dictation: only « Texte copié » is lost.
        if (notificationsOnly) onDone(true) else afterMic()
    }
    fun launchNotifications() {
        onAskNotifications()
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (notificationsOnly) {
        // Once, not again after a rotation: the request is already on screen.
        var launched by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!launched) {
                launched = true
                launchNotifications()
            }
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && askNotifications()) {
            launchNotifications()
        } else {
            afterMic()
        }
    }

    when (step) {
        Step.Explain -> PermissionDialog(
            title = R.string.mic_ask_title,
            body = R.string.mic_ask_body,
            confirm = R.string.mic_ask_continue,
            onConfirm = {
                step = Step.Asking
                mic.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDismiss = { onDone(false) },
        )

        Step.Asking -> Unit

        Step.Settings -> PermissionDialog(
            title = R.string.mic_settings_title,
            body = R.string.mic_settings_body,
            confirm = R.string.mic_settings_open,
            onConfirm = {
                openSettings()
                onDone(false)
            },
            onDismiss = { onDone(false) },
        )
    }
}

@Composable
private fun PermissionDialog(title: Int, body: Int, confirm: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_later)) } },
    )
}
