package tech.dmconsulting.dixi.dictation

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.bubble.BubbleController
import tech.dmconsulting.dixi.bubble.BubbleState
import tech.dmconsulting.dixi.bubble.TapAction
import tech.dmconsulting.dixi.history.Dictation
import tech.dmconsulting.dixi.history.DictationHistory
import tech.dmconsulting.dixi.recording.RecordingError
import tech.dmconsulting.dixi.recording.RecordingOutcome
import tech.dmconsulting.dixi.transcription.Transcript
import tech.dmconsulting.dixi.transcription.VoxtralError
import tech.dmconsulting.dixi.transcription.VoxtralResult
import java.io.File
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/** The microphone side of a dictation; every end comes back to [DictationController.onRecordingFinished]. */
interface DictationRecorder {
    /** Null if the recording is starting, or why it cannot. */
    fun start(): RecordingError?

    fun stop()

    /** Stops without a result and deletes the audio. */
    fun cancel()
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

    /** The short cause next to the bubble (« Clé refusée », « Pas de réseau »…). */
    fun showError(@StringRes message: Int)

    /** A tap on a « key refused » error: the app opens on the key settings (mockups DMC-764 § 2). */
    fun openKeySettings()

    /** No microphone permission yet: explain, then ask Android (DMC-338). */
    fun askMicrophone()

    /**
     * Microphone allowed but notifications never asked (Android 13+): asks once (DMC-372).
     * True when the request opened: it starts the dictation itself once answered.
     */
    fun askNotifications(): Boolean
}

/**
 * One dictation, from the bubble tap to the clipboard, or the focused field with auto-paste (brief § 3):
 * repos → écoute → traitement → texte prêt (or error) → repos. Everything shows on the bubble
 * (mockups DMC-764 § 2): first tap starts, second tap stops, a long press cancels and deletes
 * the audio. The text is copied (and pasted) as soon as it is ready: no suggestion to confirm.
 * A tap on an error starts again, or opens the key settings when Mistral refused the key.
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

    /** The error shown is a refused key: a tap opens its settings instead of a new dictation. */
    private var keyRefused = false

    fun onTap() {
        when (bubble.tapAction()) {
            TapAction.Start -> if (bubble.state == BubbleState.Error && keyRefused) {
                keyRefused = false
                output.openKeySettings()
            } else {
                start()
            }

            TapAction.Stop -> recorder.stop()

            TapAction.None -> Unit
        }
    }

    private fun start() {
        if (output.askNotifications()) return
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
    }

    /** Long press while listening: stops the recording, deletes the audio, back to rest. */
    fun cancel() {
        if (bubble.state != BubbleState.Listening) return
        recorder.cancel()
        bubble.setState(BubbleState.Idle)
    }

    /** Any end of recording: tap, « Arrêter » in the notification, 5 min limit. */
    fun onRecordingFinished(outcome: RecordingOutcome) {
        if (bubble.state != BubbleState.Listening) {
            // Not ours to transcribe: a dictation is already processed.
            if (outcome is RecordingOutcome.Success) outcome.result.file.delete()
            return
        }
        when (outcome) {
            is RecordingOutcome.Failure -> fail(outcome.error.bubbleMessage)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Settings or storage failed (not the network): the bubble must not crash.
                VoxtralResult.Failure(VoxtralError.ServiceUnavailable)
            } finally {
                recording.file.delete()
            }
            when (result) {
                is VoxtralResult.Failure -> fail(result.error.bubbleMessage, keyRefused = result.error.opensKeySettings)

                is VoxtralResult.Success -> {
                    val text = result.value.text
                    if (text.isBlank()) fail(R.string.bubble_error_nothing_heard) else deliver(text, stoppedAt, recording.durationMs)
                }
            }
        }
    }

    /**
     * Copies [text], then pastes it if auto-paste is on; « Texte copié » only when it was not
     * pasted. The bubble stays in « traitement » during the paste (about 1 s at most).
     */
    private fun deliver(text: String, stoppedAtMs: Long, durationMs: Long) {
        output.copy(text, stoppedAtMs)
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
            if (!pasted) output.notifyCopied(durationMs)
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

    private fun fail(@StringRes message: Int, keyRefused: Boolean = false) {
        this.keyRefused = keyRefused
        output.showError(message)
        bubble.setState(BubbleState.Error)
    }
}

/** Short cause next to the bubble for the common errors (mockups DMC-764 § 2), else the full message. */
@get:StringRes
val VoxtralError.bubbleMessage: Int
    get() = when (this) {
        VoxtralError.InvalidKey -> R.string.bubble_error_key_refused
        VoxtralError.NoKey -> R.string.bubble_error_no_key
        VoxtralError.NoConnection -> R.string.bubble_error_no_network
        VoxtralError.NothingHeard -> R.string.bubble_error_nothing_heard
        else -> message
    }

/** Refused or missing key: a tap on the bubble in error opens the key settings. */
val VoxtralError.opensKeySettings: Boolean
    get() = this == VoxtralError.InvalidKey || this == VoxtralError.NoKey

@get:StringRes
val RecordingError.bubbleMessage: Int
    get() = if (this == RecordingError.NothingHeard) R.string.bubble_error_nothing_heard else message

@get:StringRes
val RecordingError.message: Int
    get() = when (this) {
        RecordingError.NothingHeard -> R.string.error_nothing_heard
        RecordingError.MicrophoneDenied -> R.string.error_mic_denied
        RecordingError.StartFailed -> R.string.error_recording_failed
    }
