package tech.dmconsulting.dixi.dictation

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.bubble.BubbleController
import tech.dmconsulting.dixi.bubble.BubbleState
import tech.dmconsulting.dixi.history.Dictation
import tech.dmconsulting.dixi.history.DictationHistory
import tech.dmconsulting.dixi.history.InMemoryDictationHistory
import tech.dmconsulting.dixi.recording.RecordingError
import tech.dmconsulting.dixi.recording.RecordingOutcome
import tech.dmconsulting.dixi.recording.RecordingResult
import tech.dmconsulting.dixi.settings.InMemorySettingsStore
import tech.dmconsulting.dixi.transcription.FakeTextCleaner
import tech.dmconsulting.dixi.transcription.FakeTranscriber
import tech.dmconsulting.dixi.transcription.TranscriptionPipeline
import tech.dmconsulting.dixi.transcription.VoxtralError
import tech.dmconsulting.dixi.transcription.VoxtralResult
import java.io.IOException
import java.time.Instant

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
        var copiedAt = -1L
        var keySettingsOpened = 0
        var pastes = 0
        var pasteMs = 0L
        var pasteResult: () -> Boolean = { false }

        override suspend fun paste(): Boolean {
            pastes++
            if (pasteMs > 0) delay(pasteMs)
            return pasteResult()
        }
        var micAsks = 0

        override fun copy(text: String, stoppedAtMs: Long) {
            texts += text
            copiedAt = scope.testScheduler.currentTime
        }

        override fun notifyCopied(recordingMs: Long) {
            notified += recordingMs
        }

        override fun showError(message: Int) {
            errors += message
        }

        override fun openKeySettings() {
            keySettingsOpened++
        }

        override fun askMicrophone() {
            micAsks++
        }

        var notificationAsk = false
        var notificationAsks = 0

        override fun askNotifications(): Boolean {
            if (notificationAsk) notificationAsks++
            return notificationAsk
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

    private val cleanText = "Bonjour ceci est un texte de test dixi."

    private fun dictate() {
        recorder.nextOutcome = recorded()
        controller.onTap()
        controller.onTap()
    }

    @Test
    fun fullDictationCopiesTheCleanTextRightAwayAndAddsItToTheHistory() {
        val outcome = recorded()
        recorder.nextOutcome = outcome
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()

        // No suggestion to confirm any more (DMC-768): the text leaves as soon as it is ready.
        assertEquals(listOf(BubbleState.Listening, BubbleState.Processing, BubbleState.Ready), states)
        assertEquals(listOf(cleanText), output.texts)
        assertEquals(listOf(4_000L), output.notified)
        assertEquals(listOf(Dictation(cleanText, at)), history.entries.value)
        assertFalse(outcome.result.file.exists())
        assertTrue(output.errors.isEmpty())
    }

    @Test
    fun withTheFakeServiceTheTextIsCopiedWithinThreeSecondsOfTheStop() {
        dictate()
        scope.advanceUntilIdle()

        assertEquals(FakeTranscriber.DEFAULT_DELAY_MS, output.copiedAt)
        assertTrue(output.copiedAt <= 3_000)
    }

    @Test
    fun aTapWhileProcessingIsIgnored() {
        dictate()
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
        dictate()
        val late = RecordingOutcome.Success(RecordingResult(cacheDir.newFile("dictation-2.m4a"), 4_000, 3_000))
        controller.onRecordingFinished(late)
        scope.advanceUntilIdle()

        assertFalse(late.result.file.exists())
        assertEquals(1, transcriber.received.size)
        assertEquals(1, output.texts.size)
    }

    @Test
    fun noNetworkShowsItsShortCauseCopiesNothingAndDeletesTheAudio() {
        transcriber = FakeTranscriber(VoxtralResult.Failure(VoxtralError.NoConnection))
        val outcome = recorded()
        recorder.nextOutcome = outcome
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()

        assertEquals(BubbleState.Error, bubble.state)
        assertEquals(listOf(R.string.bubble_error_no_network), output.errors)
        assertTrue(output.texts.isEmpty())
        assertTrue(history.entries.value.isEmpty())
        assertFalse(outcome.result.file.exists())
    }

    @Test
    fun nothingHeardShowsItsShortCauseAndCopiesNothing() {
        recorder.nextOutcome = RecordingOutcome.Failure(RecordingError.NothingHeard)
        controller.onTap()
        controller.onTap()
        scope.advanceUntilIdle()

        assertEquals(listOf(BubbleState.Listening, BubbleState.Error), states)
        assertEquals(listOf(R.string.bubble_error_nothing_heard), output.errors)
        assertTrue(output.texts.isEmpty())
        assertTrue(transcriber.received.isEmpty())
    }

    @Test
    fun aBlankTranscriptionIsNothingHeard() {
        transcriber = FakeTranscriber(VoxtralResult.Success("   "))
        dictate()
        scope.advanceUntilIdle()

        assertEquals(BubbleState.Error, bubble.state)
        assertTrue(output.texts.isEmpty())
        assertTrue(history.entries.value.isEmpty())
    }

    @Test
    fun aTapOnARefusedKeyOpensTheKeySettingsInsteadOfANewDictation() {
        transcriber = FakeTranscriber(VoxtralResult.Failure(VoxtralError.InvalidKey))
        dictate()
        scope.advanceUntilIdle()
        assertEquals(listOf(R.string.bubble_error_key_refused), output.errors)

        controller.onTap()

        assertEquals(1, output.keySettingsOpened)
        assertEquals(1, recorder.starts)
        // Once opened, the next tap dictates again.
        recorder.nextOutcome = RecordingOutcome.Cancelled
        controller.onTap()
        assertEquals(2, recorder.starts)
    }

    @Test
    fun withoutKeyTheBubbleSaysSoAndATapOpensTheKeySettings() {
        transcriber = FakeTranscriber(VoxtralResult.Failure(VoxtralError.NoKey))
        dictate()
        scope.advanceUntilIdle()
        assertEquals(listOf(R.string.bubble_error_no_key), output.errors)

        controller.onTap()

        assertEquals(1, output.keySettingsOpened)
        assertEquals(1, recorder.starts)
    }

    @Test
    fun aTapOnAnotherErrorStartsANewDictation() {
        transcriber = FakeTranscriber(VoxtralResult.Failure(VoxtralError.NoConnection))
        dictate()
        scope.advanceUntilIdle()

        controller.onTap()

        assertEquals(0, output.keySettingsOpened)
        assertEquals(BubbleState.Listening, bubble.state)
    }

    @Test
    fun deniedMicrophoneAsksForItWithoutListeningOrError() {
        recorder.startError = RecordingError.MicrophoneDenied
        controller.onTap()

        assertEquals(1, output.micAsks)
        assertEquals(emptyList<BubbleState>(), states)
        assertEquals(emptyList<Int>(), output.errors)
    }

    @Test
    fun notificationsToAskWaitWithoutListening() {
        output.notificationAsk = true
        controller.onTap()

        assertEquals(1, output.notificationAsks)
        assertEquals(0, recorder.starts)
        assertEquals(emptyList<BubbleState>(), states)
        assertEquals(emptyList<Int>(), output.errors)
    }

    @Test
    fun theTapAfterTheNotificationRequestStartsTheDictation() {
        output.notificationAsk = true
        controller.onTap()
        output.notificationAsk = false
        controller.onTap()

        assertEquals(BubbleState.Listening, bubble.state)
        assertEquals(1, recorder.starts)
    }

    @Test
    fun aTapOnceTheMicrophoneIsAllowedStartsTheDictation() {
        recorder.startError = RecordingError.MicrophoneDenied
        controller.onTap()
        recorder.startError = null
        controller.onTap()

        assertEquals(BubbleState.Listening, bubble.state)
        assertEquals(2, recorder.starts)
    }

    @Test
    fun aTapOnTheErrorStartsANewDictation() {
        recorder.startError = RecordingError.StartFailed
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
    fun cancelDeletesTheAudioAndGoesBackToRest() {
        val outcome = recorded()
        recorder.nextOutcome = outcome
        controller.onTap()
        controller.cancel()
        scope.advanceUntilIdle()

        assertEquals(1, recorder.cancels)
        assertEquals(0, recorder.stops)
        assertFalse(outcome.result.file.exists())
        assertEquals(listOf(BubbleState.Listening, BubbleState.Idle), states)
        assertTrue(transcriber.received.isEmpty())
        assertTrue(output.texts.isEmpty())
    }

    @Test
    fun cancelOutsideListeningDoesNothing() {
        dictate()
        controller.cancel()
        scope.advanceUntilIdle()

        assertEquals(0, recorder.cancels)
        assertEquals(listOf(cleanText), output.texts)
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
        dictate()
        scope.advanceUntilIdle()

        assertEquals(BubbleState.Ready, bubble.state)
        assertEquals(listOf(cleanText), output.texts)
        assertEquals(1, output.notified.size)
        assertTrue(output.errors.isEmpty())
    }

    @Test
    fun eachRecordingErrorHasItsMessage() {
        assertEquals(R.string.error_nothing_heard, RecordingError.NothingHeard.message)
        assertEquals(R.string.error_mic_denied, RecordingError.MicrophoneDenied.message)
        assertEquals(R.string.error_recording_failed, RecordingError.StartFailed.message)
    }

    @Test
    fun theCommonErrorsHaveAShortCauseNextToTheBubble() {
        assertEquals(R.string.bubble_error_key_refused, VoxtralError.InvalidKey.bubbleMessage)
        assertEquals(R.string.bubble_error_no_key, VoxtralError.NoKey.bubbleMessage)
        assertEquals(R.string.bubble_error_no_network, VoxtralError.NoConnection.bubbleMessage)
        assertEquals(R.string.bubble_error_nothing_heard, VoxtralError.NothingHeard.bubbleMessage)
        assertEquals(R.string.error_rate_limited, VoxtralError.RateLimited.bubbleMessage)
        assertEquals(R.string.bubble_error_nothing_heard, RecordingError.NothingHeard.bubbleMessage)
        assertEquals(R.string.error_recording_failed, RecordingError.StartFailed.bubbleMessage)
    }

    @Test
    fun autoPasteOffEndsRightAway() {
        dictate()
        scope.advanceTimeBy(FakeTranscriber.DEFAULT_DELAY_MS + 1)

        assertEquals(1, output.pastes)
        assertEquals(BubbleState.Ready, states.last())
        assertEquals(listOf(4_000L), output.notified)
    }

    @Test
    fun pastedTextIsCopiedTooButNotNotified() {
        output.pasteResult = { true }
        dictate()
        scope.advanceUntilIdle()

        assertEquals("copied first: the paste takes it from the clipboard", listOf(cleanText), output.texts)
        assertEquals(BubbleState.Ready, states.last())
        assertTrue("« Texte copié » only when not pasted", output.notified.isEmpty())
        assertEquals(listOf(cleanText), history.entries.value.map { it.text })
    }

    @Test
    fun bubbleStaysProcessingWhileItPastes() {
        output.pasteMs = 1_000
        output.pasteResult = { true }
        dictate()
        scope.advanceTimeBy(FakeTranscriber.DEFAULT_DELAY_MS + 1)

        assertEquals(1, output.pastes)
        assertEquals(BubbleState.Processing, states.last())
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
        dictate()
        scope.advanceTimeBy(FakeTranscriber.DEFAULT_DELAY_MS + 1)
        assertTrue("not before the paste is tried", output.notified.isEmpty())

        scope.advanceUntilIdle()
        assertEquals(listOf(cleanText), output.texts)
        assertEquals(BubbleState.Ready, states.last())
        assertEquals(listOf(4_000L), output.notified)
    }

    @Test
    fun failingPasteFallsBackToTheCopiedNotification() {
        output.pasteResult = { throw IllegalStateException("settings unreadable") }
        dictate()
        scope.advanceUntilIdle()

        assertEquals(BubbleState.Ready, states.last())
        assertEquals(listOf(4_000L), output.notified)
    }
}
