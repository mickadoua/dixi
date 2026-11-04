package tech.dmconsulting.voxbulle.bubble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import tech.dmconsulting.voxbulle.transcription.VoxtralResult

/**
 * Shows the floating bubble. A tap starts a dictation, a second tap stops it
 * (5 min max, see [Recorder]); the text is copied and notified.
 */
class BubbleService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var bubble: BubbleView? = null
    private val scope = MainScope()
    private val recorder: Recorder get() = (application as VoxbulleApp).recorder
    private var state = BubbleState.Idle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        recorder.onFinished = ::onRecordingFinished
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
        scope.cancel()
        recorder.onFinished = null
        recorder.cancel()
        bubble?.let { windowManager.removeView(it) }
        super.onDestroy()
    }

    private fun onBubbleTap() {
        Log.i(TAG, "bubble tapped in state $state")
        when (state) {
            BubbleState.Idle -> startRecording()
            BubbleState.Listening -> recorder.stop()
            else -> Unit
        }
    }

    private fun startRecording() {
        val error = recorder.start()
        if (error != null) {
            showError(error)
            return
        }
        render(BubbleState.Listening)
    }

    /** Any end of recording: tap, « Arrêter » in the notification, 5 min limit. */
    private fun onRecordingFinished(outcome: RecordingOutcome) {
        when (outcome) {
            is RecordingOutcome.Failure -> {
                showError(outcome.error)
                render(BubbleState.Idle)
            }
            is RecordingOutcome.Success -> transcribe(outcome.result)
        }
    }

    private fun transcribe(result: RecordingResult) {
        render(BubbleState.Processing)
        Log.i(TAG, "recorded file duration=${formatDuration(result.durationMs)} (${result.durationMs} ms) maxAmplitude=${result.maxAmplitude}")
        scope.launch {
            val container = (application as VoxbulleApp).container
            val text = container.transcriber.transcribeThenDelete(result.file, DictationLanguage.Auto)
            Log.i(TAG, "audio deleted=${!result.file.exists()}")
            if (text !is VoxtralResult.Success) {
                Log.w(TAG, "transcription FAILED: $text")
                render(BubbleState.Idle)
                return@launch
            }
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Voxbulle", text.value))
            Log.i(TAG, "clipboard set")
            notifyCopied(result.durationMs)
            render(BubbleState.Ready)
            handler.postDelayed({ render(BubbleState.Idle) }, 1_500)
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
        const val TAG = MicrophoneRecorder.TAG
        const val CHANNEL_COPIED = "copied"
        const val NOTIF_COPIED = 2
    }
}
