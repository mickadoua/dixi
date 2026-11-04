package tech.dmconsulting.voxbulle.dictation

import java.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.bubble.BubbleController
import tech.dmconsulting.voxbulle.bubble.BubbleState
import tech.dmconsulting.voxbulle.history.Dictation
import tech.dmconsulting.voxbulle.history.InMemoryDictationHistory
import tech.dmconsulting.voxbulle.recording.RecordingError
import tech.dmconsulting.voxbulle.recording.RecordingOutcome
import tech.dmconsulting.voxbulle.recording.RecordingResult
import tech.dmconsulting.voxbulle.settings.InMemorySettingsStore
import tech.dmconsulting.voxbulle.transcription.FakeTextCleaner
import tech.dmconsulting.voxbulle.transcription.FakeTranscriber
import tech.dmconsulting.voxbulle.transcription.TranscriptionPipeline
import tech.dmconsulting.voxbulle.transcription.VoxtralError
import tech.dmconsulting.voxbulle.transcription.VoxtralResult

class DictationControllerTest {
    @get:Rule
    val cacheDir = TemporaryFolder()

    /** Starts when told to; [stop] ends the recording with [nextOutcome], like Recorder. */
    private inner class FakeRecorder : DictationRecorder {
        var startError: RecordingError? = null
        var nextOutcome: RecordingOutcome = RecordingOutcome.Cancelled
        var starts = 0
        var stops = 0

        override fun start(): RecordingError? {
            starts++
            return startError
        }

        override fun stop() {
            stops++
            controller.onRecordingFinished(nextOutcome)
        }
    }

    private inner class FakeOutput : DictationOutput {
        val texts = mutableListOf<String>()
        val stoppedAt = mutableListOf<Long>()
        val copiedAt = mutableListOf<Long>()
        val notified = mutableListOf<Long>()
        val errors = mutableListOf<Int>()

        override fun copy(text: String, stoppedAtMs: Long) {
            texts += text
            stoppedAt += stoppedAtMs
            copiedAt += scope.testScheduler.currentTime
        }

        override fun notifyCopied(recordingMs: Long) {
            notified += recordingMs
        }

        override fun showError(message: Int) {
            errors += message
        }
    }

    private val scope = TestScope()
    private val bubble = BubbleController { _, _ -> {} }
    private val states = mutableListOf<BubbleState>()
    private val recorder = FakeRecorder()
    private val history = InMemoryDictationHistory()
    private val output = FakeOutput()
    private var transcriber = FakeTranscriber()
    private val at = Instant.ofEpochSecond(1_000)
    private val controller = DictationController(
        bubble = bubble,
        recorder = recorder,
        transcribe = { TranscriptionPipeline(transcriber, FakeTextCleaner(), InMemorySettingsStore()).run(it) },
        history = history,
        output = output,
        scope = scope,
        elapsedMs = { scope.testScheduler.currentTime },
        now = { at },
    )

    init {
        bubble.onStateChanged = { states += it }
    }

    private fun recorded(): RecordingOutcome.Success {
        val file = cacheDir.newFile("dictation-1.m4a")
        return RecordingOutcome.Success(RecordingResult(file, durationMs = 4_000, maxAmplitude = 3_000))
    }

    @Test
    fun fullDictationCopiesTheCleanTextAndAddsItToTheHistory() {
        val outcome = recorded()
        recorder.nextOutcome = outcome
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()

        val text = "Bonjour ceci est un texte de test voxbulle."
        assertEquals(
            listOf(BubbleState.Listening, BubbleState.Processing, BubbleState.Ready),
            states,
        )
        assertEquals(listOf(text), output.texts)
        assertEquals(listOf(4_000L), output.notified)
        assertEquals(listOf(Dictation(text, at)), history.entries.value)
        assertFalse(outcome.result.file.exists())
        assertTrue(output.errors.isEmpty())
    }

    @Test
    fun withTheFakeServiceTheTextIsReadyWithinThreeSecondsOfTheStop() {
        recorder.nextOutcome = recorded()
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()

        val afterStop = output.copiedAt.single() - output.stoppedAt.single()
        assertEquals(FakeTranscriber.DEFAULT_DELAY_MS, afterStop)
        assertTrue(afterStop <= 3_000)
    }

    @Test
    fun aTapWhileProcessingIsIgnored() {
        recorder.nextOutcome = recorded()
        controller.onTap()
        controller.onTap()
        assertEquals(BubbleState.Processing, bubble.state)

        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()

        assertEquals(1, recorder.starts)
        assertEquals(1, recorder.stops)
        assertEquals(1, output.texts.size)
    }

    @Test
    fun aLateRecordingWhileProcessingIsDeletedNotTranscribed() {
        recorder.nextOutcome = recorded()
        controller.onTap()
        controller.onTap()
        val late = RecordingOutcome.Success(RecordingResult(cacheDir.newFile("dictation-2.m4a"), 4_000, 3_000))
        controller.onRecordingFinished(late)
        scope.advanceUntilIdle()

        assertFalse(late.result.file.exists())
        assertEquals(1, transcriber.received.size)
        assertEquals(1, output.texts.size)
    }

    @Test
    fun noNetworkShowsTheErrorCopiesNothingAndDeletesTheAudio() {
        transcriber = FakeTranscriber(VoxtralResult.Failure(VoxtralError.NoConnection))
        val outcome = recorded()
        recorder.nextOutcome = outcome
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()

        assertEquals(BubbleState.Error, bubble.state)
        assertEquals(listOf(R.string.error_no_connection), output.errors)
        assertTrue(output.texts.isEmpty())
        assertTrue(history.entries.value.isEmpty())
        assertFalse(outcome.result.file.exists())
    }

    @Test
    fun nothingHeardShowsTheErrorAndCopiesNothing() {
        recorder.nextOutcome = RecordingOutcome.Failure(RecordingError.NothingHeard)
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()

        assertEquals(listOf(BubbleState.Listening, BubbleState.Error), states)
        assertEquals(listOf(R.string.error_nothing_heard), output.errors)
        assertTrue(output.texts.isEmpty())
        assertTrue(transcriber.received.isEmpty())
    }

    @Test
    fun deniedMicrophoneShowsTheErrorWithoutListening() {
        recorder.startError = RecordingError.MicrophoneDenied
        controller.onTap()

        assertEquals(listOf(BubbleState.Error), states)
        assertEquals(listOf(R.string.error_mic_denied), output.errors)
    }

    @Test
    fun aTapOnTheErrorStartsANewDictation() {
        recorder.startError = RecordingError.MicrophoneDenied
        controller.onTap()
        recorder.startError = null
        controller.onTap()

        assertEquals(BubbleState.Listening, bubble.state)
        assertEquals(2, recorder.starts)
    }

    @Test
    fun stopBeforeTheMicrophoneOpensGoesBackToRestSilently() {
        controller.onTap()
        controller.onTap()

        assertEquals(listOf(BubbleState.Listening, BubbleState.Idle), states)
        assertTrue(output.errors.isEmpty())
    }

    @Test
    fun eachRecordingErrorHasItsMessage() {
        assertEquals(R.string.error_nothing_heard, RecordingError.NothingHeard.message)
        assertEquals(R.string.error_mic_denied, RecordingError.MicrophoneDenied.message)
        assertEquals(R.string.error_recording_failed, RecordingError.StartFailed.message)
    }

}
