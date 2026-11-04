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

    /** Token of the latest [start]; a service command with an older one is stale. */
    private var startToken = 0

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
        startToken++
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).putExtra(RecordingService.EXTRA_TOKEN, startToken),
            )
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "recording service start FAILED", e)
            _state.value = RecorderState.Idle
            RecordingError.StartFailed
        }
    }

    /**
     * Stops the current recording, sends the outcome to [onFinished] and returns it.
     * Before the microphone is open, or when nothing records, the outcome is [RecordingOutcome.Cancelled]:
     * the caller goes back to idle without a message.
     */
    fun stop(): RecordingOutcome {
        if (_state.value == RecorderState.Idle) {
            onFinished?.invoke(RecordingOutcome.Cancelled)
            return RecordingOutcome.Cancelled
        }
        handler.removeCallbacks(tick)
        val outcome = session?.stop() ?: RecordingOutcome.Cancelled
        session = null
        finish(outcome)
        return outcome
    }

    /** Time recorded so far, 0 when the microphone is not open. For the counter on screen 3. */
    fun elapsedMs(): Long = session?.elapsedMs() ?: 0

    /** Voice level now, 0 to 32767; 0 when the microphone is not open. */
    fun voiceLevel(): Int = session?.level() ?: 0

    /** Stops without a result and deletes the audio (the bubble is going away). */
    fun cancel() {
        if (_state.value == RecorderState.Idle) return
        handler.removeCallbacks(tick)
        session?.cancel()
        session = null
        finish(null)
    }

    /** Called by [RecordingService] once it is in the foreground, or [error] if it could not be. */
    internal fun onForeground(service: RecordingService, token: Int, error: RecordingError? = null) {
        when (foregroundDecision(token, startToken, _state.value)) {
            ForegroundDecision.Ignore -> {
                // Command of an older start; a newer start owns this service now.
                Log.i(TAG, "stale service command ignored (token $token, current $startToken)")
                return
            }
            ForegroundDecision.StopService -> {
                // Stopped before the service was ready.
                service.stopRecordingForeground()
                return
            }
            ForegroundDecision.Open -> Unit
        }
        this.service = service
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

    /** The service is going away without a stop (system kill): drop the audio and tell the caller. */
    internal fun onServiceDestroyed(destroyed: RecordingService) {
        if (destroyed !== service) return
        Log.w(TAG, "recording service destroyed while recording")
        handler.removeCallbacks(tick)
        session?.cancel()
        session = null
        service = null
        _state.value = RecorderState.Idle
        onFinished?.invoke(RecordingOutcome.Failure(RecordingError.StartFailed))
    }

    private fun finish(outcome: RecordingOutcome?) {
        service?.stopRecordingForeground()
        service = null
        _state.value = RecorderState.Idle
        if (outcome == null) return
        Log.i(TAG, "recording finished: $outcome")
        onFinished?.invoke(outcome)
    }

    internal enum class ForegroundDecision { Open, StopService, Ignore }

    companion object {
        /**
         * What to do when the service reaches the foreground for start [token].
         * A stale token while a newer start runs is ignored, so it cannot stop that recording.
         */
        internal fun foregroundDecision(token: Int, currentToken: Int, state: RecorderState): ForegroundDecision =
            when {
                state == RecorderState.Idle -> ForegroundDecision.StopService
                token != currentToken -> ForegroundDecision.Ignore
                state == RecorderState.Starting -> ForegroundDecision.Open
                else -> ForegroundDecision.Ignore
            }

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
