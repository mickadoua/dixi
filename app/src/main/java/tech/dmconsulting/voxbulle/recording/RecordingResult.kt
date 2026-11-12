package tech.dmconsulting.voxbulle.recording

import androidx.annotation.StringRes
import tech.dmconsulting.voxbulle.R
import java.io.File

/** A finished recording. The caller sends [file] to the transcriber, then deletes it. */
data class RecordingResult(val file: File, val durationMs: Long, val maxAmplitude: Int)

/** Why a recording gave no audio to transcribe. Each one has its own message (mockups, screen 3). */
enum class RecordingError {
    /** RECORD_AUDIO not granted: screen 2 offers « Ouvrir les réglages ». */
    MicrophoneDenied,

    /** Too short or too quiet: « Je n'ai rien entendu. Réessayez. » */
    NothingHeard,

    /** The system refused the foreground service or the microphone did not start. */
    StartFailed,
}

/** What to tell the user, the same on screen 3 and from the bubble. */
@get:StringRes
val RecordingError.message: Int
    get() = when (this) {
        RecordingError.MicrophoneDenied -> R.string.error_mic_denied
        RecordingError.NothingHeard -> R.string.error_nothing_heard
        RecordingError.StartFailed -> R.string.error_recording_failed
    }

sealed interface RecordingOutcome {
    data class Success(val result: RecordingResult) : RecordingOutcome

    data class Failure(val error: RecordingError) : RecordingOutcome

    /** Stopped before the microphone was open, or nothing was recording: no message. */
    data object Cancelled : RecordingOutcome
}
