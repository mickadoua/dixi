package tech.dmconsulting.voxbulle.bubble

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tech.dmconsulting.voxbulle.MainActivity
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.VoxbulleApp
import tech.dmconsulting.voxbulle.dictation.transcribeThenDelete
import tech.dmconsulting.voxbulle.recording.MicrophoneRecorder
import tech.dmconsulting.voxbulle.recording.Recorder
import tech.dmconsulting.voxbulle.recording.RecordingError
import tech.dmconsulting.voxbulle.recording.RecordingOutcome
import tech.dmconsulting.voxbulle.recording.RecordingResult
import tech.dmconsulting.voxbulle.recording.formatDuration
import tech.dmconsulting.voxbulle.settings.DictationLanguage
import tech.dmconsulting.voxbulle.transcription.VoxtralError
import tech.dmconsulting.voxbulle.transcription.VoxtralResult

/**
 * Shows the floating bubble. A tap starts a dictation, a second tap stops it
 * (5 min max, see [Recorder]); the text is copied and notified. Dropped on the
 * hide zone, the bubble hides until its notification (or [show]) brings it back.
 */
class BubbleService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val recorder: Recorder get() = (application as VoxbulleApp).recorder
    private val controller = BubbleController { delayMs, block ->
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMs)
        return@BubbleController { handler.removeCallbacks(runnable) }
    }
    private lateinit var store: BubblePositionStore
    private lateinit var overlay: BubbleOverlay
    private var debugReceiver: BroadcastReceiver? = null
    private val levelRunnable = object : Runnable {
        override fun run() {
            controller.setVoiceLevel(recorder.voiceLevel() / 32_767f)
            handler.postDelayed(this, LEVEL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        recorder.onFinished = ::onRecordingFinished
        store = BubblePositionStore(this)
        overlay = BubbleOverlay(this, controller, store, onTap = ::onBubbleTap, onHide = ::hide)
        controller.onStateChanged = overlay::render
        controller.onVoiceLevel = overlay::setVoiceLevel
        registerDebugReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The user can take the "display over other apps" permission back at any time.
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission missing: bubble not shown")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOW) {
            store.hidden = false
            overlay.removeEdge()
            NotificationManagerCompat.from(this).cancel(NOTIF_HIDDEN)
        }
        if (store.hidden) {
            Log.i(TAG, "bubble is hidden: not shown")
            if (store.hideToEdge) showEdge() else stopSelf()
        } else {
            try {
                overlay.show()
                if (intent?.action == ACTION_DICTATE) onBubbleTap()
            } catch (e: RuntimeException) {
                Log.e(TAG, "bubble show FAILED", e)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay.onConfigurationChanged()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        debugReceiver?.let { unregisterReceiver(it) }
        recorder.onFinished = null
        recorder.cancel()
        overlay.remove()
        super.onDestroy()
    }

    private fun hide() {
        Log.i(TAG, "bubble hidden")
        overlay.remove()
        store.hidden = true
        if (store.hideToEdge) {
            showEdge()
        } else {
            notifyHidden()
            stopSelf()
        }
    }

    /** Edge mode: the tab stays at the side; a tap on it shows the bubble again. */
    private fun showEdge() {
        try {
            overlay.showEdge(onShowBubble = ::unhide)
        } catch (e: RuntimeException) {
            Log.e(TAG, "bubble edge FAILED: notification instead", e)
            notifyHidden()
            stopSelf()
        }
    }

    private fun unhide() {
        Log.i(TAG, "bubble shown from the edge")
        store.hidden = false
        overlay.removeEdge()
        try {
            overlay.show()
        } catch (e: RuntimeException) {
            Log.e(TAG, "bubble show FAILED", e)
            stopSelf()
        }
    }

    private fun onBubbleTap() {
        Log.i(TAG, "bubble tapped in state ${controller.state}")
        when (controller.tapAction()) {
            TapAction.Start -> startRecording()
            TapAction.Stop -> recorder.stop()
            TapAction.None -> Unit
        }
    }

    private fun startRecording() {
        val error = recorder.start()
        if (error != null) {
            showError(error)
            return
        }
        controller.setState(BubbleState.Listening)
        handler.post(levelRunnable)
    }

    /** Any end of recording: tap, « Arrêter » in the notification, 5 min limit. */
    private fun onRecordingFinished(outcome: RecordingOutcome) {
        handler.removeCallbacks(levelRunnable)
        controller.setVoiceLevel(0f)
        when (outcome) {
            is RecordingOutcome.Failure -> {
                showError(outcome.error)
                controller.setState(BubbleState.Idle)
            }
            is RecordingOutcome.Success -> transcribe(outcome.result)
            RecordingOutcome.Cancelled -> controller.setState(BubbleState.Idle)
        }
    }

    private fun transcribe(result: RecordingResult) {
        controller.setState(BubbleState.Processing)
        Log.i(TAG, "recorded file duration=${formatDuration(result.durationMs)} (${result.durationMs} ms) maxAmplitude=${result.maxAmplitude}")
        scope.launch {
            val container = (application as VoxbulleApp).container
            // Read before the call: the keyed transcriber picks the fake without a key.
            val demo = container.usesFakeVoxtral
            val text = container.transcriber.transcribeThenDelete(result.file, DictationLanguage.Auto)
            Log.i(TAG, "audio deleted=${!result.file.exists()}")
            if (text !is VoxtralResult.Success) {
                // A Failure prints only its VoxtralError: never the key.
                Log.w(TAG, "transcription FAILED: $text")
                if (text == VoxtralResult.Failure(VoxtralError.InvalidKey)) notifyKeyRefused()
                controller.setState(BubbleState.Idle)
                return@launch
            }
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Voxbulle", text.value))
            Log.i(TAG, "clipboard set demo=$demo")
            notifyCopied(result.durationMs, demo)
            controller.setState(BubbleState.Ready)
        }
    }

    private fun showError(error: RecordingError) {
        val message = when (error) {
            RecordingError.NothingHeard -> R.string.error_nothing_heard
            RecordingError.MicrophoneDenied -> R.string.error_mic_denied
            RecordingError.StartFailed -> R.string.error_recording_failed
        }
        Log.i(TAG, "recording error $error")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** [demo]: the fake Voxtral wrote the text; tapping opens screen 10 to add a key (DMC-227). */
    private fun notifyCopied(durationMs: Long, demo: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL_COPIED)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_copied_title))
            .setSubText(formatDuration(durationMs))
            .setAutoCancel(true)
            .apply {
                if (demo) {
                    val body = getString(R.string.notif_demo_body)
                    setContentText(body)
                    setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    setContentIntent(openSettingsIntent())
                    setTimeoutAfter(15_000)
                } else {
                    setContentText(getString(R.string.notif_copied_body))
                    setTimeoutAfter(6_000)
                }
            }
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_COPIED, notification)
            Log.i(TAG, "copied notification posted")
        } catch (e: SecurityException) {
            Log.e(TAG, "copied notification FAILED", e)
        }
    }

    /** Mistral refused the saved key: say so, tapping opens screen 10. */
    private fun notifyKeyRefused() {
        val notification = NotificationCompat.Builder(this, CHANNEL_COPIED)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_key_refused_title))
            .setContentText(getString(R.string.notif_key_refused_body))
            .setContentIntent(openSettingsIntent())
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_COPIED, notification)
            Log.i(TAG, "key refused notification posted")
        } catch (e: SecurityException) {
            Log.e(TAG, "key refused notification FAILED", e)
        }
    }

    private fun openSettingsIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_SETTINGS,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // The visible result of the tap is the bubble itself, over the current app:
    // opening an activity here would hide that app.
    @SuppressLint("LaunchActivityFromNotification")
    private fun notifyHidden() {
        val show = PendingIntent.getService(
            this, 0, Intent(this, BubbleService::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_BUBBLE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_bubble_hidden_title))
            .setContentText(getString(R.string.notif_bubble_hidden_body))
            .setContentIntent(show)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_HIDDEN, notification)
            Log.i(TAG, "hidden notification posted")
        } catch (e: SecurityException) {
            Log.e(TAG, "hidden notification FAILED", e)
        }
    }

    /** Debug builds only: lets the emulator proof set each state over another app. */
    private fun registerDebugReceiver() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getStringExtra("state")?.let { name -> BubbleState.entries.find { it.name == name } }
                    ?: return
                Log.i(TAG, "debug state $state")
                // A screenshot takes more than 1.5 s on the NAS emulator.
                controller.readyToIdleMs = intent.getLongExtra("readyMs", BubbleController.READY_TO_IDLE_MS)
                controller.setState(state)
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION_DEBUG_STATE), ContextCompat.RECEIVER_EXPORTED)
        debugReceiver = receiver
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_BUBBLE, getString(R.string.channel_bubble), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COPIED, getString(R.string.channel_copied), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        /** Shows the bubble again, even if the user hid it. */
        const val ACTION_SHOW = "tech.dmconsulting.voxbulle.action.SHOW_BUBBLE"

        fun show(context: Context) {
            context.startService(Intent(context, BubbleService::class.java).setAction(ACTION_SHOW))
        }

        /**
         * The main screen's « Touchez, parlez » button: same as a tap on the bubble
         * (start or stop). Needs the overlay permission, like the bubble itself.
         */
        const val ACTION_DICTATE = "tech.dmconsulting.voxbulle.action.DICTATE"

        fun dictate(context: Context) {
            context.startService(Intent(context, BubbleService::class.java).setAction(ACTION_DICTATE))
        }

        private const val ACTION_DEBUG_STATE = "tech.dmconsulting.voxbulle.debug.SET_BUBBLE_STATE"
        private const val TAG = MicrophoneRecorder.TAG
        private const val CHANNEL_BUBBLE = "bubble"
        private const val NOTIF_HIDDEN = 3
        private const val LEVEL_MS = 100L
        private const val CHANNEL_COPIED = "copied"
        private const val NOTIF_COPIED = 2
        private const val REQUEST_OPEN_SETTINGS = 1
    }
}
