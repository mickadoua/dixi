package tech.dmconsulting.dixi.bubble

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
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import tech.dmconsulting.dixi.DixiApp
import tech.dmconsulting.dixi.MainActivity
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.dictation.DictationController
import tech.dmconsulting.dixi.dictation.DictationOutput
import tech.dmconsulting.dixi.dictation.DictationRecorder
import tech.dmconsulting.dixi.history.copyToClipboard
import tech.dmconsulting.dixi.paste.AutoPasteService
import tech.dmconsulting.dixi.permissions.MicPermissionActivity
import tech.dmconsulting.dixi.recording.MicrophoneRecorder
import tech.dmconsulting.dixi.recording.Recorder
import tech.dmconsulting.dixi.recording.RecorderState
import tech.dmconsulting.dixi.recording.RecordingError
import tech.dmconsulting.dixi.recording.RecordingLimits
import tech.dmconsulting.dixi.recording.formatDuration
import tech.dmconsulting.dixi.transcription.VoxtralError
import tech.dmconsulting.dixi.transcription.VoxtralResult
import tech.dmconsulting.dixi.ui.navigation.Screen

/**
 * Shows the floating bubble. A tap starts a dictation, a second tap stops it, a long press
 * cancels it (5 min max, see [Recorder]); the text is copied and notified, or pasted with
 * auto-paste (see [DictationController]). Tucked at the edge, it stays half shown
 * ([BubbleOverlay]). Dropped on the hide zone, it hides until its notification (or [show])
 * brings it back.
 */
class BubbleService :
    Service(),
    DictationOutput {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val recorder: Recorder get() = (application as DixiApp).recorder
    private val controller = BubbleController { delayMs, block ->
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMs)
        return@BubbleController { handler.removeCallbacks(runnable) }
    }
    private val dictation by lazy {
        val app = application as DixiApp
        DictationController(
            bubble = controller,
            recorder = object : DictationRecorder {
                override fun start(): RecordingError? = recorder.start()
                override fun stop() {
                    recorder.stop()
                }

                override fun cancel() = recorder.cancel()
            },
            transcribe = { audio ->
                // Read before the call: the keyed transcriber picks the fake without a key.
                demoDictation = app.container.usesFakeVoxtral
                app.container.transcription.run(audio).also {
                    // A Failure prints only its VoxtralError: never the key.
                    when (it) {
                        VoxtralResult.Failure(VoxtralError.InvalidKey) -> notifyKey(R.string.notif_key_refused_title, R.string.notif_key_refused_body)
                        VoxtralResult.Failure(VoxtralError.NoKey) -> notifyKey(R.string.notif_no_key_title, R.string.notif_no_key_body)
                        else -> Unit
                    }
                }
            },
            history = app.container.history,
            output = this,
            scope = scope,
            elapsedMs = SystemClock::elapsedRealtime,
        )
    }

    /** The last dictation went to the fakes: no key saved (DMC-227). */
    private var demoDictation = false
    private lateinit var store: BubblePositionStore
    private lateinit var overlay: BubbleOverlay
    private var debugReceiver: BroadcastReceiver? = null
    private var listeningSinceMs = 0L
    private val levelRunnable = object : Runnable {
        override fun run() {
            // Only a real recording moves the wave: the debug proof sets its own levels.
            if (recorder.state.value != RecorderState.Idle) controller.setVoiceLevel(recorder.voiceLevel() / 32_767f)
            val elapsed = SystemClock.elapsedRealtime() - listeningSinceMs
            overlay.setListening(elapsed, nearLimit = elapsed >= RecordingLimits.current().maxMs - NEAR_LIMIT_MS)
            handler.postDelayed(this, LEVEL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        recorder.onFinished = dictation::onRecordingFinished
        store = BubblePositionStore(this)
        overlay = BubbleOverlay(this, controller, store, onTap = ::onBubbleTap, onHide = ::hide, onCancel = { dictation.cancel() })
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
            NotificationManagerCompat.from(this).cancel(NOTIF_HIDDEN)
        }
        if (store.hidden) {
            Log.i(TAG, "bubble is hidden: not shown")
            stopSelf()
        } else {
            try {
                overlay.show()
                BubbleVisibility.set(true)
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
        BubbleVisibility.set(false)
        super.onDestroy()
    }

    private fun hide() {
        Log.i(TAG, "bubble hidden")
        overlay.remove()
        BubbleVisibility.set(false)
        store.hidden = true
        // Hiding cuts the bubble; tucking it at the edge is the way to keep it half shown (DMC-768).
        notifyHidden()
        stopSelf()
    }

    private fun onBubbleTap() {
        Log.i(TAG, "bubble tapped in state ${controller.state}")
        dictation.onTap()
    }

    private fun onStateChanged(state: BubbleState) {
        Log.i(TAG, "bubble state $state")
        overlay.render(state)
        BubbleEvents.setState(state)
        handler.removeCallbacks(levelRunnable)
        if (state == BubbleState.Listening) {
            listeningSinceMs = SystemClock.elapsedRealtime()
            handler.post(levelRunnable)
        } else {
            controller.setVoiceLevel(0f)
        }
    }

    override fun copy(text: String, stoppedAtMs: Long) {
        copyToClipboard(this, text)
        BubbleEvents.copied(text)
        Log.i(TAG, "clipboard set ${SystemClock.elapsedRealtime() - stoppedAtMs} ms after stop demo=$demoDictation")
    }

    /** Auto-paste (DMC-141): only if the user turned it on in screen 10 (after screen 8). */
    override suspend fun paste(): Boolean {
        overlay.toastPasted = false
        val on = (application as DixiApp).container.settings.settings.first().autoPasteEnabled
        val pasted = on && AutoPasteService.paste()
        overlay.toastPasted = pasted
        debugPastedReadyMs?.let { controller.readyToIdleMs = if (pasted) it else BubbleController.READY_TO_IDLE_MS }
        return pasted
    }

    override fun showError(message: Int) {
        Log.i(TAG, "dictation error: ${getString(message)}")
        overlay.errorMessage = message
        BubbleEvents.failed(message)
    }

    override fun openKeySettings() {
        Log.i(TAG, "key refused: opening the key settings")
        // Allowed from the background: the app shows an overlay window.
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    override fun askMicrophone() {
        Log.i(TAG, "microphone permission missing: asking")
        MicPermissionActivity.start(this)
    }

    override fun askNotifications(): Boolean = MicPermissionActivity.askNotificationsIfNeeded(this)

    /**
     * « Texte copié », gone after 6 s. A tap or « Voir le texte » opens the history (screen 7).
     * In « Mode démo » the fake Voxtral wrote the text: a tap opens screen 10 to add a key (DMC-227).
     */
    override fun notifyCopied(recordingMs: Long) {
        val history = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, Screen.History.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hide = PendingIntent.getService(
            this,
            1,
            Intent(this, BubbleService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_COPIED)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_copied_title))
            .setSubText(formatDuration(recordingMs, resources.configuration.locales[0]))
            .addAction(0, getString(R.string.notif_copied_show_text), history)
            .addAction(0, getString(R.string.bubble_action_hide), hide)
            .setAutoCancel(true)
            .apply {
                if (demoDictation) {
                    val body = getString(R.string.notif_demo_body)
                    setContentText(body)
                    setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    setContentIntent(openSettingsIntent())
                    setTimeoutAfter(debugCopiedTimeoutMs ?: DEMO_TIMEOUT_MS)
                } else {
                    setContentText(getString(R.string.notif_copied_body))
                    setContentIntent(history)
                    setTimeoutAfter(debugCopiedTimeoutMs ?: COPIED_TIMEOUT_MS)
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

    /** Mistral refused the active key, or there is none: say so, tapping opens screen 10. */
    private fun notifyKey(@StringRes title: Int, @StringRes body: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_COPIED)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(title))
            .setContentText(getString(body))
            .setContentIntent(openSettingsIntent())
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_COPIED, notification)
            Log.i(TAG, "key notification posted")
        } catch (e: SecurityException) {
            Log.e(TAG, "key notification FAILED", e)
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
            this,
            0,
            Intent(this, BubbleService::class.java).setAction(ACTION_SHOW),
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
                if (intent.hasExtra("level")) {
                    // Debug proof of the wave: a voice level from 0 to 1 while listening.
                    overlay.setVoiceLevel(intent.getFloatExtra("level", 0f))
                    return
                }
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
        /** Set only by the debug build (DebugRecordingReceiver): the emulator opens the shade too slowly for 6 s. */
        @Volatile
        var debugCopiedTimeoutMs: Long? = null

        /** Set only by the debug build (DebugRecordingReceiver): « Texte collé » shows 1.5 s, too short for an emulator screenshot. */
        @Volatile
        var debugPastedReadyMs: Long? = null

        /** Shows the bubble again, even if the user hid it. */
        const val ACTION_SHOW = "tech.dmconsulting.dixi.action.SHOW_BUBBLE"
        private const val ACTION_HIDE = "tech.dmconsulting.dixi.action.HIDE_BUBBLE"

        fun show(context: Context) {
            context.startService(Intent(context, BubbleService::class.java).setAction(ACTION_SHOW))
        }

        /**
         * The home screen's « Dicter ici » button: same as a tap on the bubble
         * (start or stop). Needs the overlay permission, like the bubble itself.
         */
        const val ACTION_DICTATE = "tech.dmconsulting.dixi.action.DICTATE"

        fun dictate(context: Context) {
            context.startService(Intent(context, BubbleService::class.java).setAction(ACTION_DICTATE))
        }

        private const val ACTION_DEBUG_STATE = "tech.dmconsulting.dixi.debug.SET_BUBBLE_STATE"
        private const val TAG = MicrophoneRecorder.TAG
        private const val CHANNEL_BUBBLE = "bubble"
        private const val NOTIF_HIDDEN = 3
        private const val LEVEL_MS = 100L

        /** The wave turns magenta 10 s before the time limit (mockups DMC-764 § 2). */
        private const val NEAR_LIMIT_MS = 10_000L
        private const val CHANNEL_COPIED = "copied"
        private const val NOTIF_COPIED = 2
        private const val COPIED_TIMEOUT_MS = 6_000L
        private const val DEMO_TIMEOUT_MS = 15_000L
        private const val REQUEST_OPEN_SETTINGS = 1
    }
}
