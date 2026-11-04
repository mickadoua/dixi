package tech.dmconsulting.voxbulle.poc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import java.io.File

/**
 * Shows the floating bubble. A tap records 5 s of microphone audio in a
 * microphone foreground service, then copies a fixed text and notifies.
 */
class BubbleService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var bubble: BubbleView? = null
    private var recorder: MediaRecorder? = null
    private var state = BubbleState.Idle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (56 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = margin
            y = (300 * resources.displayMetrics.density).toInt()
        }
        val view = BubbleView(this).apply {
            setOnClickListener { onBubbleTap() }
        }
        windowManager.addView(view, params)
        bubble = view
        render(BubbleState.Idle)
        view.post {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            Log.i(TAG, "bubble shown at x=${loc[0] + view.width / 2} y=${loc[1] + view.height / 2}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recorder?.release()
        bubble?.let { windowManager.removeView(it) }
        super.onDestroy()
    }

    private fun onBubbleTap() {
        if (state != BubbleState.Idle) return
        Log.i(TAG, "bubble tapped")
        val notification = NotificationCompat.Builder(this, CHANNEL_DICTATION)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_recording))
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_RECORDING,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
            Log.i(TAG, "startForeground(microphone) OK")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(microphone) FAILED", e)
            return
        }
        val file = File(cacheDir, "dictation.m4a")
        try {
            recorder = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "recording FAILED", e)
            stopFg()
            return
        }
        Log.i(TAG, "recording started")
        render(BubbleState.Listening)
        handler.postDelayed({ logRecordingState() }, 1_500)
        handler.postDelayed({ finishRecording(file) }, RECORD_MS)
    }

    private fun finishRecording(file: File) {
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            Log.e(TAG, "recorder stop FAILED", e)
        }
        recorder?.release()
        recorder = null
        stopFg()
        render(BubbleState.Processing)

        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: RuntimeException) {
            Log.e(TAG, "duration read FAILED", e)
            0L
        } finally {
            retriever.release()
        }
        Log.i(TAG, "recorded file duration=${formatDuration(durationMs)} ($durationMs ms) size=${file.length()} B")

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Voxbulle", getString(R.string.fixed_text)))
        Log.i(TAG, "clipboard set")
        notifyCopied(durationMs)
        render(BubbleState.Ready)
        handler.postDelayed({ render(BubbleState.Idle) }, 1_500)
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

    /** Android may silence a background app's mic without any error: check it. */
    private fun logRecordingState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val configs = audio.activeRecordingConfigurations
        if (configs.isEmpty()) Log.w(TAG, "no active recording configuration")
        configs.forEach { Log.i(TAG, "recording active, clientSilenced=${it.isClientSilenced}") }
        Log.i(TAG, "maxAmplitude=${recorder?.maxAmplitude}")
    }

    private fun stopFg() = ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    private fun render(newState: BubbleState) {
        state = newState
        bubble?.apply {
            color = newState.color
            contentDescription = getString(newState.description)
            alpha = if (newState == BubbleState.Idle) 0.9f else 1f
            invalidate()
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DICTATION, getString(R.string.channel_dictation), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COPIED, getString(R.string.channel_copied), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private class BubbleView(service: Service) : View(service) {
        var color: Int = BubbleState.Idle.color
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icon = service.getDrawable(R.drawable.ic_mic)!!

        init {
            elevation = 6 * resources.displayMetrics.density
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = color
            val r = width / 2f
            canvas.drawCircle(r, r, r, paint)
            val inset = (width * 0.2f).toInt()
            icon.setBounds(inset, inset, width - inset, height - inset)
            icon.draw(canvas)
        }
    }

    private companion object {
        const val TAG = "VoxbulleProof"
        const val CHANNEL_DICTATION = "dictation"
        const val CHANNEL_COPIED = "copied"
        const val NOTIF_RECORDING = 1
        const val NOTIF_COPIED = 2
        const val RECORD_MS = 5_000L
    }
}
