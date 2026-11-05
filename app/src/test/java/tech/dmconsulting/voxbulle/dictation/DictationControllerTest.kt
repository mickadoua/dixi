package tech.dmconsulting.voxbulle.dictation

import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
import java.io.IOException
import tech.dmconsulting.voxbulle.history.Dictation
import tech.dmconsulting.voxbulle.history.DictationHistory
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
        var cancels = 0

        override fun start(): RecordingError? {
            starts++
            return startError
        }

        override fun stop() {
            stops++
            controller.onRecordingFinished(nextOutcome)
        }

        override fun cancel() {
            cancels++
            (nextOutcome as? RecordingOutcome.Success)?.result?.file?.delete()
        }
    }

    private inner class FakeOutput : DictationOutput {
        val texts = mutableListOf<String>()
        val notified = mutableListOf<Long>()
        val errors = mutableListOf<Int>()
        val panels = mutableListOf<DictationPanel>()
        var suggestedAt = -1L
        var pastes = 0
        var pasteMs = 0L
        var pasteResult: () -> Boolean = { false }

        override suspend fun paste(): Boolean {
            pastes++
            if (pasteMs > 0) delay(pasteMs)
            return pasteResult()
        }

        override fun copy(text: String, stoppedAtMs: Long) {
            texts += text
        }

        override fun notifyCopied(recordingMs: Long) {
            notified += recordingMs
        }

        override fun showError(message: Int) {
            errors += message
        }

        override fun showPanel(panel: DictationPanel) {
            panels += panel
            if (panel is DictationPanel.Suggestion) suggestedAt = scope.testScheduler.currentTime
        }
    }

    private val scope = TestScope()
    private val bubble = BubbleController { _, _ -> {} }
    private val states = mutableListOf<BubbleState>()
    private val recorder = FakeRecorder()
    private val history = InMemoryDictationHistory()
    private val output = FakeOutput()
    private var transcriber = FakeTranscriber()
    private var transcribeThrows: Exception? = null
    private var historyThrows: Exception? = null
    private val at = Instant.ofEpochSecond(1_000)
    private val controller = DictationController(
        bubble = bubble,
        recorder = recorder,
        transcribe = {
            transcribeThrows?.let { e -> throw e }
            TranscriptionPipeline(transcriber, FakeTextCleaner(), InMemorySettingsStore()).run(it)
        },
        history = object : DictationHistory by history {
            override suspend fun add(dictation: Dictation) {
                historyThrows?.let { e -> throw e }
                history.add(dictation)
            }
        },
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
        assertEquals(DictationPanel.Suggestion(text), output.panels.last())
        assertTrue("nothing copied before « Valider »", output.texts.isEmpty())
        controller.validate(text)
        scope.advanceUntilIdle()

        assertEquals(DictationPanel.None, output.panels.last())
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

        val afterStop = output.suggestedAt
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
        assertEquals(1, transcriber.received.size)
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
        assertTrue(output.panels.last() is DictationPanel.Suggestion)
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
    fun theListeningPanelShowsWithTheRecording() {
        controller.onTap()

        assertEquals(listOf(DictationPanel.Listening(0)), output.panels)
    }

    @Test
    fun terminerIsATapOnTheBubble() {
        recorder.nextOutcome = recorded()
        controller.onTap()
        controller.finish()

        assertEquals(1, recorder.stops)
        assertEquals(BubbleState.Processing, bubble.state)
        assertEquals(DictationPanel.Processing, output.panels.last())
    }

    @Test
    fun annulerDeletesTheAudioAndGoesBackToRest() {
        val outcome = recorded()
        recorder.nextOutcome = outcome
        controller.onTap()
        controller.cancel()
        scope.advanceUntilIdle()

        assertEquals(1, recorder.cancels)
        assertEquals(0, recorder.stops)
        assertFalse(outcome.result.file.exists())
        assertEquals(listOf(BubbleState.Listening, BubbleState.Idle), states)
        assertEquals(DictationPanel.None, output.panels.last())
        assertTrue(transcriber.received.isEmpty())
        assertTrue(output.texts.isEmpty())
    }

    @Test
    fun validerCopiesTheCorrectedText() {
        recorder.nextOutcome = recorded()
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()
        controller.validate("Bonjour, à jeudi.")
        scope.advanceUntilIdle()

        assertEquals(listOf("Bonjour, à jeudi."), output.texts)
        assertEquals(listOf(Dictation("Bonjour, à jeudi.", at)), history.entries.value)
        assertEquals(BubbleState.Ready, bubble.state)

        controller.validate("again")
        assertEquals("a second « Valider » does nothing", 1, output.texts.size)
    }

    @Test
    fun validerIgnoresABlankText() {
        recorder.nextOutcome = recorded()
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()
        val suggestion = output.panels.last()
        controller.validate("  \n ")
        scope.advanceUntilIdle()

        assertTrue(output.texts.isEmpty())
        assertTrue(history.entries.value.isEmpty())
        assertEquals("the suggestion stays open", suggestion, output.panels.last())

        controller.validate("Bonjour.")
        scope.advanceUntilIdle()
        assertEquals(listOf("Bonjour."), output.texts)
    }

    @Test
    fun refaireDropsTheSuggestionAndListensAgain() {
        recorder.nextOutcome = recorded()
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()
        controller.redo()

        assertEquals(2, recorder.starts)
        assertEquals(BubbleState.Listening, bubble.state)
        assertTrue(output.texts.isEmpty())
        assertTrue(history.entries.value.isEmpty())
        assertTrue(output.panels.last() is DictationPanel.Listening)
    }

    @Test
    fun aTapWhileTheSuggestionWaitsDoesNothing() {
        recorder.nextOutcome = recorded()
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()
        controller.onTap()

        assertEquals(1, recorder.starts)
        assertTrue(output.panels.last() is DictationPanel.Suggestion)
    }

    @Test
    fun anErrorClosesThePanel() {
        recorder.nextOutcome = RecordingOutcome.Failure(RecordingError.NothingHeard)
        controller.onTap()
        controller.onTap()

        assertEquals(DictationPanel.None, output.panels.last())
    }

    @Test
    fun aTranscriptionThatThrowsShowsAnErrorDeletesTheAudioAndGoesBackToRest() {
        transcribeThrows = IOException("settings unreadable")
        val outcome = recorded()
        recorder.nextOutcome = outcome
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()

        assertEquals(listOf(BubbleState.Listening, BubbleState.Processing, BubbleState.Error), states)
        assertEquals(listOf(R.string.error_service_unavailable), output.errors)
        assertEquals(DictationPanel.None, output.panels.last())
        assertTrue(output.texts.isEmpty())
        assertFalse(outcome.result.file.exists())

        // The error goes back to rest on its own; a tap starts a new dictation.
        recorder.nextOutcome = RecordingOutcome.Cancelled
        controller.onTap()
        assertEquals(BubbleState.Listening, bubble.state)
    }

    @Test
    fun aHistoryThatThrowsKeepsTheCopiedText() {
        historyThrows = IOException("disk full")
        recorder.nextOutcome = recorded()
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()
        controller.validate("Bonjour")
        scope.advanceUntilIdle()

        assertEquals(BubbleState.Ready, bubble.state)
        assertEquals(listOf("Bonjour"), output.texts)
        assertEquals(1, output.notified.size)
        assertTrue(output.errors.isEmpty())
    }

    @Test
    fun eachRecordingErrorHasItsMessage() {
        assertEquals(R.string.error_nothing_heard, RecordingError.NothingHeard.message)
        assertEquals(R.string.error_mic_denied, RecordingError.MicrophoneDenied.message)
        assertEquals(R.string.error_recording_failed, RecordingError.StartFailed.message)
    }


    private fun suggestion(): String {
        recorder.nextOutcome = recorded()
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()
        return (output.panels.last() as DictationPanel.Suggestion).text
    }

    @Test
    fun autoPasteOffEndsRightAway() {
        val text = suggestion()
        controller.validate(text)

        assertEquals(1, output.pastes)
        assertEquals(BubbleState.Ready, states.last())
        assertEquals(listOf(4_000L), output.notified)
    }

    @Test
    fun pastedTextIsCopiedTooButNotNotified() {
        output.pasteResult = { true }
        val text = suggestion()
        controller.validate(text)
        scope.advanceUntilIdle()

        assertEquals("copied first: the paste takes it from the clipboard", listOf(text), output.texts)
        assertEquals(BubbleState.Ready, states.last())
        assertTrue("« Texte copié » only when not pasted", output.notified.isEmpty())
        assertEquals(listOf(text), history.entries.value.map { it.text })
    }

    @Test
    fun bubbleStaysProcessingWhileItPastes() {
        output.pasteMs = 1_000
        output.pasteResult = { true }
        val text = suggestion()
        controller.validate(text)
        scope.runCurrent()

        assertEquals(BubbleState.Processing, states.last())
        assertEquals(DictationPanel.None, output.panels.last())
        controller.onTap()
        assertEquals("a tap does nothing during the paste", 1, recorder.starts)

        scope.advanceUntilIdle()
        assertEquals(BubbleState.Ready, states.last())
        assertTrue(output.notified.isEmpty())
    }

    @Test
    fun missedPasteFallsBackToTheCopiedNotification() {
        output.pasteMs = 1_000
        output.pasteResult = { false }
        val text = suggestion()
        controller.validate(text)
        scope.runCurrent()
        assertTrue("not before the paste is tried", output.notified.isEmpty())

        scope.advanceUntilIdle()
        assertEquals(listOf(text), output.texts)
        assertEquals(BubbleState.Ready, states.last())
        assertEquals(listOf(4_000L), output.notified)
    }

    @Test
    fun failingPasteFallsBackToTheCopiedNotification() {
        output.pasteResult = { throw IllegalStateException("settings unreadable") }
        val text = suggestion()
        controller.validate(text)
        scope.advanceUntilIdle()

        assertEquals(BubbleState.Ready, states.last())
        assertEquals(listOf(4_000L), output.notified)
    }
}
