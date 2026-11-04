package tech.dmconsulting.voxbulle.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecorderState { Idle, Starting, Recording, Warning }

/**
 * Starts and stops dictations. One per process (held by VoxbulleApp), main thread only.
 *
 * [start] launches [RecordingService], which goes to the foreground with the
 * microphone type, then calls back [onForeground] to open the microphone.
 * Every end of recording (tap, « Arrêter » in the notification, 5 min limit)
 * goes to [onFinished], with the file to transcribe then delete.
 */
class Recorder(private val context: Context) {

    var onFinished: ((RecordingOutcome) -> Unit)? = null

    private val _state = MutableStateFlow(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var session: RecordingSession? = null
    private var service: RecordingService? = null

    private val tick = object : Runnable {
        override fun run() {
            when (session?.tick()) {
                TimerEvent.Warning -> {
                    Log.i(TAG, "recording warning at ${session?.elapsedMs()} ms")
                    _state.value = RecorderState.Warning
                    service?.showWarning()
                }
                TimerEvent.LimitReached -> {
                    Log.i(TAG, "recording limit reached at ${session?.elapsedMs()} ms")
                    stop()
                    return
                }
                null -> Unit
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        deleteLeftoverAudio(context.cacheDir)
    }

    /** Returns null if the recording is starting, or why it cannot. */
    fun start(): RecordingError? {
        if (_state.value != RecorderState.Idle) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "recording refused: microphone permission denied")
            return RecordingError.MicrophoneDenied
        }
        _state.value = RecorderState.Starting
        return try {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "recording service start FAILED", e)
            _state.value = RecorderState.Idle
            RecordingError.StartFailed
        }
    }

    /** Stops the current recording, sends the outcome to [onFinished] and returns it. */
    fun stop(): RecordingOutcome {
        if (_state.value == RecorderState.Idle) return RecordingOutcome.Failure(RecordingError.NothingHeard)
        handler.removeCallbacks(tick)
        val outcome = session?.stop() ?: RecordingOutcome.Failure(RecordingError.NothingHeard)
        session = null
        finish(outcome)
        return outcome
    }

    /** Stops without a result and deletes the audio (the bubble is going away). */
    fun cancel() {
        if (_state.value == RecorderState.Idle) return
        handler.removeCallbacks(tick)
        session?.cancel()
        session = null
        finish(null)
    }

    /** Called by [RecordingService] once it is in the foreground, or [error] if it could not be. */
    internal fun onForeground(service: RecordingService, error: RecordingError? = null) {
        this.service = service
        if (_state.value != RecorderState.Starting) {
            // Stopped before the service was ready.
            finish(null)
            return
        }
        if (error != null) {
            finish(RecordingOutcome.Failure(error))
            return
        }
        val capture = MicrophoneRecorder(context)
        val newSession = RecordingSession(capture, { SystemClock.elapsedRealtime() }, RecordingLimits.current())
        val file = File(context.cacheDir, "$AUDIO_PREFIX${System.currentTimeMillis()}$AUDIO_SUFFIX")
        newSession.start(file)?.let {
            Log.e(TAG, "recording FAILED: $it")
            finish(RecordingOutcome.Failure(it))
            return
        }
        session = newSession
        _state.value = RecorderState.Recording
        Log.i(TAG, "recording started, limits=${RecordingLimits.current()}")
        handler.postDelayed(tick, TICK_MS)
        handler.postDelayed({ if (session === newSession) capture.logRecordingState() }, 1_500)
    }

    /** The service is going away without a stop (system kill): drop the audio. */
    internal fun onServiceDestroyed(destroyed: RecordingService) {
        if (destroyed !== service) return
        handler.removeCallbacks(tick)
        session?.cancel()
        session = null
        service = null
        _state.value = RecorderState.Idle
    }

    private fun finish(outcome: RecordingOutcome?) {
        service?.stopRecordingForeground()
        service = null
        _state.value = RecorderState.Idle
        if (outcome == null) return
        Log.i(TAG, "recording finished: $outcome")
        onFinished?.invoke(outcome)
    }

    companion object {
        private const val TAG = MicrophoneRecorder.TAG
        private const val TICK_MS = 250L
        const val AUDIO_PREFIX = "dictation-"
        const val AUDIO_SUFFIX = ".m4a"

        /** Audio left by a killed process is never kept: removed at the next start. */
        fun deleteLeftoverAudio(dir: File) {
            dir.listFiles { f -> f.name.startsWith(AUDIO_PREFIX) && f.name.endsWith(AUDIO_SUFFIX) }
                ?.forEach { it.delete() }
        }
    }
}
