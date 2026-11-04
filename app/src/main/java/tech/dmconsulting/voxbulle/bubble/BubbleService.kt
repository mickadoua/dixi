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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.recording.MicrophoneForeground
import tech.dmconsulting.voxbulle.recording.MicrophoneRecorder
import tech.dmconsulting.voxbulle.recording.formatDuration

/**
 * Shows the floating bubble. A tap records up to 5 s of microphone audio in a
 * microphone foreground service (a second tap stops it), then copies a fixed
 * text and notifies. Dropped on the hide zone, the bubble hides until its
 * notification (or [show]) brings it back.
 */
class BubbleService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val recorder = MicrophoneRecorder(this)
    private val controller = BubbleController { delayMs, block ->
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMs)
        return@BubbleController { handler.removeCallbacks(runnable) }
    }
    private lateinit var store: BubblePositionStore
    private lateinit var overlay: BubbleOverlay
    private var debugReceiver: BroadcastReceiver? = null
    private val finishRunnable = Runnable { finishRecording() }
    private val levelRunnable = object : Runnable {
        override fun run() {
            controller.setVoiceLevel(recorder.amplitude() / 32_767f)
            handler.postDelayed(this, LEVEL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
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
            NotificationManagerCompat.from(this).cancel(NOTIF_HIDDEN)
        }
        if (store.hidden) {
            Log.i(TAG, "bubble is hidden: not shown")
            stopSelf()
        } else {
            try {
                overlay.show()
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
        debugReceiver?.let { unregisterReceiver(it) }
        if (controller.state == BubbleState.Listening) MicrophoneForeground.stop(this)
        recorder.release()
        overlay.remove()
        super.onDestroy()
    }

    private fun hide() {
        Log.i(TAG, "bubble hidden")
        overlay.remove()
        store.hidden = true
        notifyHidden()
        stopSelf()
    }

    private fun onBubbleTap() {
        Log.i(TAG, "bubble tapped in state ${controller.state}")
        when (controller.tapAction()) {
            TapAction.Start -> startRecording()
            TapAction.Stop -> {
                handler.removeCallbacks(finishRunnable)
                finishRecording()
            }
            TapAction.None -> Unit
        }
    }

    private fun startRecording() {
        val notification = NotificationCompat.Builder(this, CHANNEL_DICTATION)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_recording))
            .setOngoing(true)
            .build()
        try {
            MicrophoneForeground.start(this, NOTIF_RECORDING, notification)
            Log.i(TAG, "startForeground(microphone) OK")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(microphone) FAILED", e)
            return
        }
        try {
            recorder.start(recordingFile())
        } catch (e: Exception) {
            Log.e(TAG, "recording FAILED", e)
            recorder.release()
            MicrophoneForeground.stop(this)
            return
        }
        Log.i(TAG, "recording started")
        controller.setState(BubbleState.Listening)
        handler.post(levelRunnable)
        handler.postDelayed({ recorder.logRecordingState() }, 1_500)
        handler.postDelayed(finishRunnable, RECORD_MS)
    }

    private fun recordingFile() = File(cacheDir, "dictation.m4a")

    private fun finishRecording() {
        handler.removeCallbacks(levelRunnable)
        controller.setVoiceLevel(0f)
        recorder.stop()
        MicrophoneForeground.stop(this)
        controller.setState(BubbleState.Processing)
        val file = recordingFile()

        val durationMs = MicrophoneRecorder.durationMs(file)
        Log.i(TAG, "recorded file duration=${formatDuration(durationMs)} ($durationMs ms) size=${file.length()} B")

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Voxbulle", getString(R.string.fixed_text)))
        Log.i(TAG, "clipboard set")
        notifyCopied(durationMs)
        controller.setState(BubbleState.Ready)
    }

    private fun notifyCopied(durationMs: Long) {
        val notification = NotificationCompat.Builder(this, CHANNEL_COPIED)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_copied_title))
            .setContentText(getString(R.string.notif_copied_body))
            .setSubText(formatDuration(durationMs))
            .setTimeoutAfter(6_000)
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
            NotificationChannel(CHANNEL_DICTATION, getString(R.string.channel_dictation), NotificationManager.IMPORTANCE_LOW),
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

        private const val ACTION_DEBUG_STATE = "tech.dmconsulting.voxbulle.debug.SET_BUBBLE_STATE"
        private const val TAG = MicrophoneRecorder.TAG
        private const val CHANNEL_BUBBLE = "bubble"
        private const val NOTIF_HIDDEN = 3
        private const val LEVEL_MS = 100L
        private const val CHANNEL_DICTATION = "dictation"
        private const val CHANNEL_COPIED = "copied"
        private const val NOTIF_RECORDING = 1
        private const val NOTIF_COPIED = 2
        private const val RECORD_MS = 5_000L
    }
}
