package tech.dmconsulting.voxbulle.onboarding

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.dmconsulting.voxbulle.recording.Recorder
import tech.dmconsulting.voxbulle.recording.RecordingError
import tech.dmconsulting.voxbulle.recording.RecordingOutcome
import tech.dmconsulting.voxbulle.recording.message
import tech.dmconsulting.voxbulle.transcription.Transcript
import tech.dmconsulting.voxbulle.transcription.VoxtralResult
import java.io.File

/** Screen 3, from the big microphone button to the two text cards. */
sealed interface TrialState {
    data object Idle : TrialState

    /** Tapped: the microphone is opening or recording. A tap stops it. */
    data object Recording : TrialState

    data object Processing : TrialState

    data class Result(val transcript: Transcript) : TrialState

    data class Error(@StringRes val message: Int) : TrialState
}

/** What the trial needs from [Recorder]; a fake in tests. */
interface TrialRecorder {
    fun start(): RecordingError?

    fun stop()

    fun cancel()

    fun elapsedMs(): Long

    /** From now on, every end of recording goes to [onFinished], until [detach]. */
    fun attach(onFinished: (RecordingOutcome) -> Unit)

    fun detach()
}

/**
 * The in-app trial of screen 3: record, transcribe, show the result. Same
 * recorder as the bubble (5 min limit, foreground service), same transcription.
 * The audio file is deleted once transcribed, failed or abandoned.
 * Main thread only. [close] when the screen goes away.
 */
class TrialModel(
    private val recorder: TrialRecorder,
    private val transcribe: suspend (File) -> VoxtralResult<Transcript>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TrialState>(TrialState.Idle)
    val state: StateFlow<TrialState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        recorder.attach(::onFinished)
    }

    /** The big microphone button: starts, or stops a running recording. Ignored while transcribing. */
    fun onMicTap() {
        when (_state.value) {
            TrialState.Recording -> recorder.stop()

            TrialState.Processing -> Unit

            else -> {
                val error = recorder.start()
                _state.value = if (error == null) TrialState.Recording else TrialState.Error(error.message)
            }
        }
    }

    fun elapsedMs(): Long = if (_state.value == TrialState.Recording) recorder.elapsedMs() else 0

    /** The screen is gone: stop listening, drop any audio, give the recorder back. */
    fun close() {
        if (_state.value == TrialState.Recording) recorder.cancel()
        job?.cancel()
        recorder.detach()
    }

    private fun onFinished(outcome: RecordingOutcome) {
        when (outcome) {
            is RecordingOutcome.Success -> startTranscription(outcome.result.file)
            is RecordingOutcome.Failure -> _state.value = TrialState.Error(outcome.error.message)
            RecordingOutcome.Cancelled -> _state.value = TrialState.Idle
        }
    }

    private fun startTranscription(file: File) {
        _state.value = TrialState.Processing
        job = scope.launch {
            _state.value = when (val result = transcribe(file)) {
                is VoxtralResult.Success -> TrialState.Result(result.value)
                is VoxtralResult.Failure -> TrialState.Error(result.error.message)
            }
        }
        // Done, failed, or cancelled even before it ran: the audio goes in every case.
        job?.invokeOnCompletion { file.delete() }
    }
}

/**
 * [Recorder] for the trial. The bubble owns [Recorder.onFinished] while it runs:
 * the trial borrows it and gives it back on [detach].
 */
class RecorderTrialAdapter(private val recorder: Recorder) : TrialRecorder {
    private var previous: ((RecordingOutcome) -> Unit)? = null
    private var listener: ((RecordingOutcome) -> Unit)? = null

    override fun start(): RecordingError? = recorder.start()

    override fun stop() {
        recorder.stop()
    }

    override fun cancel() = recorder.cancel()

    override fun elapsedMs(): Long = recorder.elapsedMs()

    override fun attach(onFinished: (RecordingOutcome) -> Unit) {
        previous = recorder.onFinished
        listener = onFinished
        recorder.onFinished = onFinished
    }

    override fun detach() {
        // Someone else (the bubble) took it since: leave theirs.
        if (recorder.onFinished === listener) recorder.onFinished = previous
        previous = null
        listener = null
    }
}
