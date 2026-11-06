package tech.dmconsulting.voxbulle.dictation

import androidx.annotation.StringRes
import java.io.File
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import tech.dmconsulting.voxbulle.transcription.VoxtralError
import tech.dmconsulting.voxbulle.transcription.VoxtralResult

/** The microphone side of a dictation; every end comes back to [DictationController.onRecordingFinished]. */
interface DictationRecorder {
    /** Null if the recording is starting, or why it cannot. */
    fun start(): RecordingError?

    fun stop()

    /** Stops without a result and deletes the audio. */
    fun cancel()
}

/** The panel next to the bubble (mockups 09 and 10). */
sealed interface DictationPanel {
    data object None : DictationPanel

    /** « Je vous écoute », since [startedAtMs] on the elapsedMs clock. */
    data class Listening(val startedAtMs: Long) : DictationPanel

    /** « Je mets en texte… », after « Terminer ». */
    data object Processing : DictationPanel

    /** The final text, to correct, then « Valider » or « Refaire ». */
    data class Suggestion(val text: String) : DictationPanel
}

/** What the user sees and gets at the end of a dictation. */
interface DictationOutput {
    /** Puts [text] in the clipboard. [stoppedAtMs]: end of the recording, on the elapsedMs clock. */
    fun copy(text: String, stoppedAtMs: Long)

    /**
     * Auto-paste (DMC-141): pastes the clipboard into the focused field of the app below.
     * False when the option is off or the paste missed: the caller falls back to [notifyCopied].
     */
    suspend fun paste(): Boolean

    /** « Texte copié », with the length of the recording. */
    fun notifyCopied(recordingMs: Long)

    fun showError(@StringRes message: Int)

    fun showPanel(panel: DictationPanel)

    /** No microphone permission yet: explain, then ask Android (DMC-338). */
    fun askMicrophone()
}

/**
 * One dictation, from the bubble tap to the clipboard, or the focused field with auto-paste (brief § 3):
 * repos → écoute → traitement → suggestion → texte prêt (or error) → repos. The bubble
 * and the panel follow. « Terminer » is a tap on the bubble; « Annuler » deletes the audio.
 * The bubble stays in « traitement » while the suggestion waits: a tap does nothing.
 * One dictation at a time. The audio file is deleted after the transcription, success or not.
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

    /** The suggestion shown, with what [validate] needs. */
    private var pending: Pending? = null

    private class Pending(val stoppedAtMs: Long, val durationMs: Long)

    fun onTap() {
        when (bubble.tapAction()) {
            TapAction.Start -> start()
            TapAction.Stop -> recorder.stop()
            TapAction.None -> Unit
        }
    }

    private fun start() {
        val error = recorder.start()
        if (error == RecordingError.MicrophoneDenied) {
            // Not an error yet: the user was never asked, or can still say yes.
            output.askMicrophone()
            return
        }
        if (error != null) {
            fail(error.message)
            return
        }
        bubble.setState(BubbleState.Listening)
        output.showPanel(DictationPanel.Listening(elapsedMs()))
    }

    /** « Terminer »: same as a tap on the bubble while listening. */
    fun finish() {
        if (bubble.state == BubbleState.Listening) onTap()
    }

    /** « Annuler »: stops the recording, deletes the audio, back to rest. */
    fun cancel() {
        if (bubble.state != BubbleState.Listening) return
        recorder.cancel()
        output.showPanel(DictationPanel.None)
        bubble.setState(BubbleState.Idle)
    }

    /**
     * « Valider »: copies [text], the suggestion as the user corrected it, then pastes it if
     * auto-paste is on; « Texte copié » only when it was not pasted. A blank text does nothing.
     * The bubble stays in « traitement » during the paste (about 1 s at most).
     */
    fun validate(text: String) {
        if (text.isBlank()) return
        val done = pending ?: return
        pending = null
        output.showPanel(DictationPanel.None)
        output.copy(text, done.stoppedAtMs)
        // Undispatched: with auto-paste off, the end is immediate, as before DMC-141.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val pasted = try {
                output.paste()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            bubble.setState(BubbleState.Ready)
            if (!pasted) output.notifyCopied(done.durationMs)
        }
        val at = now()
        scope.launch {
            try {
                history.add(Dictation(text, at))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The text is copied; only the history entry is lost.
            }
        }
    }

    /** « Refaire »: drops the suggestion and listens again. */
    fun redo() {
        if (pending == null) return
        pending = null
        output.showPanel(DictationPanel.None)
        bubble.setState(BubbleState.Idle)
        start()
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
            RecordingOutcome.Cancelled -> {
                output.showPanel(DictationPanel.None)
                bubble.setState(BubbleState.Idle)
            }
            is RecordingOutcome.Success -> process(outcome)
        }
    }

    private fun process(outcome: RecordingOutcome.Success) {
        val stoppedAt = elapsedMs()
        val recording = outcome.result
        bubble.setState(BubbleState.Processing)
        output.showPanel(DictationPanel.Processing)
        scope.launch {
            val result = try {
                transcribe(recording.file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Settings or storage failed (not the network): the bubble must not crash.
                VoxtralResult.Failure(VoxtralError.ServiceUnavailable)
            } finally {
                recording.file.delete()
            }
            when (result) {
                is VoxtralResult.Failure -> fail(result.error.message)
                is VoxtralResult.Success -> {
                    pending = Pending(stoppedAt, recording.durationMs)
                    output.showPanel(DictationPanel.Suggestion(result.value.text))
                }
            }
        }
    }

    private fun fail(@StringRes message: Int) {
        output.showPanel(DictationPanel.None)
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
