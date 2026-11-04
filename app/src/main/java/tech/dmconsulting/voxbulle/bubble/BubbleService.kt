package tech.dmconsulting.voxbulle.bubble

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import tech.dmconsulting.voxbulle.MainActivity
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.VoxbulleApp
import tech.dmconsulting.voxbulle.dictation.DictationController
import tech.dmconsulting.voxbulle.dictation.DictationOutput
import tech.dmconsulting.voxbulle.dictation.DictationRecorder
import tech.dmconsulting.voxbulle.history.copyToClipboard
import tech.dmconsulting.voxbulle.recording.MicrophoneRecorder
import tech.dmconsulting.voxbulle.recording.Recorder
import tech.dmconsulting.voxbulle.recording.RecordingError
import tech.dmconsulting.voxbulle.recording.formatDuration
import tech.dmconsulting.voxbulle.ui.navigation.Screen

/**
 * Shows the floating bubble. A tap starts a dictation, a second tap stops it
 * (5 min max, see [Recorder]); the text is copied and notified (see [DictationController]).
 * Dropped on the hide zone, the bubble hides until its notification (or [show]) brings it back.
 */
class BubbleService : Service(), DictationOutput {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val recorder: Recorder get() = (application as VoxbulleApp).recorder
    private val controller = BubbleController { delayMs, block ->
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMs)
        return@BubbleController { handler.removeCallbacks(runnable) }
    }
    private val dictation by lazy {
        val app = application as VoxbulleApp
        DictationController(
            bubble = controller,
            recorder = object : DictationRecorder {
                override fun start(): RecordingError? = recorder.start()
                override fun stop() {
                    recorder.stop()
                }
            },
            transcribe = app.container.transcription::run,
            history = app.container.history,
            output = this,
            scope = scope,
            elapsedMs = SystemClock::elapsedRealtime,
        )
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
        recorder.onFinished = dictation::onRecordingFinished
        store = BubblePositionStore(this)
        overlay = BubbleOverlay(this, controller, store, onTap = ::onBubbleTap, onHide = ::hide)
        controller.onStateChanged = ::onStateChanged
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
        if (intent?.action == ACTION_HIDE) {
            // « Masquer la bulle » in the « Texte copié » notification.
            NotificationManagerCompat.from(this).cancel(NOTIF_COPIED)
            if (!controller.hideIfAllowed(::hide)) Log.i(TAG, "hide refused: dictation running")
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
        dictation.onTap()
    }

    private fun onStateChanged(state: BubbleState) {
        Log.i(TAG, "bubble state $state")
        overlay.render(state)
        handler.removeCallbacks(levelRunnable)
        if (state == BubbleState.Listening) {
            handler.post(levelRunnable)
        } else {
            controller.setVoiceLevel(0f)
        }
    }

    override fun copy(text: String, stoppedAtMs: Long) {
        copyToClipboard(this, text)
        Log.i(TAG, "clipboard set ${SystemClock.elapsedRealtime() - stoppedAtMs} ms after stop")
    }

    override fun showError(message: Int) {
        Log.i(TAG, "dictation error: ${getString(message)}")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** « Texte copié », gone after 6 s. A tap or « Voir le texte » opens the history (screen 7). */
    override fun notifyCopied(recordingMs: Long) {
        val history = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, Screen.History.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hide = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_COPIED)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_copied_title))
            .setContentText(getString(R.string.notif_copied_body))
            .setSubText(formatDuration(recordingMs))
            .setContentIntent(history)
            .addAction(0, getString(R.string.notif_copied_show_text), history)
            .addAction(0, getString(R.string.bubble_action_hide), hide)
            .setTimeoutAfter(COPIED_TIMEOUT_MS)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_COPIED, notification)
            Log.i(TAG, "copied notification posted")
        } catch (e: SecurityException) {
            Log.e(TAG, "copied notification FAILED", e)
        }
    }

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
                controller.errorToIdleMs = intent.getLongExtra("readyMs", BubbleController.ERROR_TO_IDLE_MS)
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
        private const val ACTION_HIDE = "tech.dmconsulting.voxbulle.action.HIDE_BUBBLE"

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
        private const val COPIED_TIMEOUT_MS = 6_000L
    }
}
