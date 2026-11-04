package tech.dmconsulting.voxbulle.dictation

import androidx.annotation.StringRes
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.bubble.BubbleController
import tech.dmconsulting.voxbulle.bubble.BubbleState
import tech.dmconsulting.voxbulle.bubble.TapAction
import tech.dmconsulting.voxbulle.history.Dictation
import tech.dmconsulting.voxbulle.history.DictationHistory
import tech.dmconsulting.voxbulle.recording.RecordingError
import tech.dmconsulting.voxbulle.recording.RecordingOutcome
import tech.dmconsulting.voxbulle.transcription.Transcript
import tech.dmconsulting.voxbulle.transcription.VoxtralResult

/** The microphone side of a dictation; every end comes back to [DictationController.onRecordingFinished]. */
interface DictationRecorder {
    /** Null if the recording is starting, or why it cannot. */
    fun start(): RecordingError?

    fun stop()
}

/** What the user sees and gets at the end of a dictation. */
interface DictationOutput {
    /** Puts [text] in the clipboard. [stoppedAtMs]: end of the recording, on the elapsedMs clock. */
    fun copy(text: String, stoppedAtMs: Long)

    /** « Texte copié », with the length of the recording. */
    fun notifyCopied(recordingMs: Long)

    fun showError(@StringRes message: Int)
}

/**
 * One dictation, from the bubble tap to the clipboard (brief § 3):
 * repos → écoute → traitement → texte prêt (or error) → repos. The bubble follows.
 * One dictation at a time: a tap while the text is processed does nothing.
 * The audio file is deleted after the transcription, success or not.
 */
class DictationController(
    private val bubble: BubbleController,
    private val recorder: DictationRecorder,
    private val transcribe: suspend (File) -> VoxtralResult<Transcript>,
    private val history: DictationHistory,
    private val output: DictationOutput,
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long,
    private val now: () -> Instant = Instant::now,
) {

    fun onTap() {
        when (bubble.tapAction()) {
            TapAction.Start -> start()
            TapAction.Stop -> recorder.stop()
            TapAction.None -> Unit
        }
    }

    private fun start() {
        val error = recorder.start()
        if (error != null) {
            fail(error.message)
            return
        }
        bubble.setState(BubbleState.Listening)
    }

    /** Any end of recording: tap, « Arrêter » in the notification, 5 min limit. */
    fun onRecordingFinished(outcome: RecordingOutcome) {
        if (bubble.state != BubbleState.Listening) {
            // Not ours to transcribe: a dictation is already processed.
            if (outcome is RecordingOutcome.Success) outcome.result.file.delete()
            return
        }
        when (outcome) {
            is RecordingOutcome.Failure -> fail(outcome.error.message)
            RecordingOutcome.Cancelled -> bubble.setState(BubbleState.Idle)
            is RecordingOutcome.Success -> process(outcome)
        }
    }

    private fun process(outcome: RecordingOutcome.Success) {
        val stoppedAt = elapsedMs()
        val recording = outcome.result
        bubble.setState(BubbleState.Processing)
        scope.launch {
            val result = try {
                transcribe(recording.file)
            } finally {
                recording.file.delete()
            }
            when (result) {
                is VoxtralResult.Failure -> fail(result.error.message)
                is VoxtralResult.Success -> {
                    val text = result.value.text
                    output.copy(text, stoppedAt)
                    bubble.setState(BubbleState.Ready)
                    output.notifyCopied(recording.durationMs)
                    history.add(Dictation(text, now()))
                }
            }
        }
    }

    private fun fail(@StringRes message: Int) {
        output.showError(message)
        bubble.setState(BubbleState.Error)
    }
}

@get:StringRes
val RecordingError.message: Int
    get() = when (this) {
        RecordingError.NothingHeard -> R.string.error_nothing_heard
        RecordingError.MicrophoneDenied -> R.string.error_mic_denied
        RecordingError.StartFailed -> R.string.error_recording_failed
    }
