package tech.dmconsulting.voxbulle.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.VoxbulleApp

/**
 * Foreground service of type microphone that holds a dictation (Android 14+
 * needs it to record while another app is in front). Shows the recording
 * notification of mockup screen 7, with an « Arrêter » action.
 */
class RecordingService : Service() {

    private val recorder: Recorder get() = (application as VoxbulleApp).recorder

    /** Start time shown by the notification chronometer; kept when the text changes. */
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop action from notification")
            recorder.stop()
            // Stale notification: nothing was recording, so nothing stopped this service.
            if (recorder.state.value == RecorderState.Idle) stopSelf(startId)
            return START_NOT_STICKY
        }
        val token = intent?.getIntExtra(EXTRA_TOKEN, NO_TOKEN) ?: NO_TOKEN
        createChannel(this)
        startedAt = System.currentTimeMillis()
        try {
            MicrophoneForeground.start(this, NOTIF_RECORDING, notification(warning = false))
            Log.i(TAG, "startForeground(microphone) OK")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(microphone) FAILED", e)
            recorder.onForeground(this, token, RecordingError.StartFailed)
            return START_NOT_STICKY
        }
        recorder.onForeground(this, token)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recorder.onServiceDestroyed(this)
        super.onDestroy()
    }

    /** 30 s left: same notification, new text. */
    fun showWarning() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_RECORDING, notification(warning = true))
        } catch (e: SecurityException) {
            Log.e(TAG, "warning notification FAILED", e)
        }
    }

    fun stopRecordingForeground() {
        MicrophoneForeground.stop(this)
        stopSelf()
    }

    private fun notification(warning: Boolean): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_DICTATION)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_recording))
            .setContentText(getString(if (warning) R.string.notif_recording_warning else R.string.notif_recording_body))
            .setWhen(startedAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stop, getString(R.string.notif_recording_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = MicrophoneRecorder.TAG
        const val CHANNEL_DICTATION = "dictation"
        const val NOTIF_RECORDING = 1
        const val EXTRA_TOKEN = "tech.dmconsulting.voxbulle.extra.START_TOKEN"
        private const val NO_TOKEN = -1
        private const val ACTION_STOP = "tech.dmconsulting.voxbulle.action.STOP_RECORDING"

        /** « Dictée » channel, low importance: no sound, no pop-up (mockups, screen 7). */
        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DICTATION,
                    context.getString(R.string.channel_dictation),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
