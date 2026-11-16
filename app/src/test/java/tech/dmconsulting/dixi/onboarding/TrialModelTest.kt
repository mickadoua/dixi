package tech.dmconsulting.dixi.onboarding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.recording.RecordingError
import tech.dmconsulting.dixi.recording.RecordingOutcome
import tech.dmconsulting.dixi.recording.RecordingResult
import tech.dmconsulting.dixi.transcription.Transcript
import tech.dmconsulting.dixi.transcription.VoxtralError
import tech.dmconsulting.dixi.transcription.VoxtralResult
import java.io.File

private class FakeTrialRecorder(var startError: RecordingError? = null) : TrialRecorder {
    var listener: ((RecordingOutcome) -> Unit)? = null
    var starts = 0
    var stops = 0
    var cancels = 0
    var elapsed = 0L

    override fun start(): RecordingError? {
        starts++
        return startError
    }

    override fun stop() {
        stops++
    }

    override fun cancel() {
        cancels++
    }

    override fun elapsedMs(): Long = elapsed

    override fun attach(onFinished: (RecordingOutcome) -> Unit) {
        listener = onFinished
    }

    override fun detach() {
        listener = null
    }

    fun finish(outcome: RecordingOutcome) = listener!!.invoke(outcome)
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrialModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val transcript = Transcript(raw = "brut", cleaned = "Propre.")

    private fun audio(): File = folder.newFile("dictation-1.m4a").apply { writeBytes(ByteArray(16)) }

    private fun success(file: File) = RecordingOutcome.Success(RecordingResult(file, 2_000, 5_000))

    private fun TestScope.model(
        recorder: TrialRecorder,
        transcribe: suspend (File) -> VoxtralResult<Transcript> = { VoxtralResult.Success(transcript) },
    ) = TrialModel(recorder, transcribe, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun tapRecordsTapAgainStopsThenShowsTheTwoTexts() = runTest {
        val recorder = FakeTrialRecorder()
        val model = model(recorder)
        assertEquals(TrialState.Idle, model.state.value)

        model.onMicTap()
        assertEquals(TrialState.Recording, model.state.value)
        assertEquals(1, recorder.starts)

        model.onMicTap()
        assertEquals(1, recorder.stops)

        val file = audio()
        recorder.finish(success(file))
        assertEquals(TrialState.Result(transcript), model.state.value)
        assertFalse("audio must be deleted after the transcription", file.exists())
    }

    @Test
    fun processingShowsWhileTranscribing() = runTest {
        val recorder = FakeTrialRecorder()
        val answer = CompletableDeferred<VoxtralResult<Transcript>>()
        val model = model(recorder) { answer.await() }
        model.onMicTap()
        val file = audio()
        recorder.finish(success(file))
        assertEquals(TrialState.Processing, model.state.value)

        model.onMicTap()
        assertEquals("no new recording while transcribing", 1, recorder.starts)

        answer.complete(VoxtralResult.Success(transcript))
        assertEquals(TrialState.Result(transcript), model.state.value)
        assertFalse(file.exists())
    }

    @Test
    fun microphoneRefusedShowsItsMessage() = runTest {
        val model = model(FakeTrialRecorder(startError = RecordingError.MicrophoneDenied))
        model.onMicTap()
        assertEquals(TrialState.Error(R.string.error_mic_denied), model.state.value)
    }

    @Test
    fun nothingHeardShowsItsMessageAndCanRetry() = runTest {
        val recorder = FakeTrialRecorder()
        val model = model(recorder)
        model.onMicTap()
        recorder.finish(RecordingOutcome.Failure(RecordingError.NothingHeard))
        assertEquals(TrialState.Error(R.string.error_nothing_heard), model.state.value)

        model.onMicTap()
        assertEquals(TrialState.Recording, model.state.value)
        assertEquals(2, recorder.starts)
    }

    @Test
    fun transcriptionErrorShowsItsMessageAndDeletesTheAudio() = runTest {
        val recorder = FakeTrialRecorder()
        val model = model(recorder) { VoxtralResult.Failure(VoxtralError.NoConnection) }
        model.onMicTap()
        val file = audio()
        recorder.finish(success(file))
        assertEquals(TrialState.Error(R.string.error_no_connection), model.state.value)
        assertFalse(file.exists())
    }

    @Test
    fun stoppedBeforeTheMicrophoneOpenedGoesBackToIdle() = runTest {
        val recorder = FakeTrialRecorder()
        val model = model(recorder)
        model.onMicTap()
        recorder.finish(RecordingOutcome.Cancelled)
        assertEquals(TrialState.Idle, model.state.value)
    }

    @Test
    fun counterReadsTheRecorderOnlyWhileRecording() = runTest {
        val recorder = FakeTrialRecorder().apply { elapsed = 12_345 }
        val model = model(recorder)
        assertEquals(0, model.elapsedMs())
        model.onMicTap()
        assertEquals(12_345, model.elapsedMs())
    }

    @Test
    fun closeWhileRecordingCancelsAndGivesTheRecorderBack() = runTest {
        val recorder = FakeTrialRecorder()
        val model = model(recorder)
        model.onMicTap()
        model.close()
        assertEquals(1, recorder.cancels)
        assertNull(recorder.listener)
    }

    @Test
    fun closeWhileTranscribingStillDeletesTheAudio() = runTest {
        val recorder = FakeTrialRecorder()
        val model = model(recorder) { CompletableDeferred<VoxtralResult<Transcript>>().await() }
        model.onMicTap()
        val file = audio()
        recorder.finish(success(file))
        assertTrue(file.exists())

        model.close()
        assertFalse(file.exists())
        assertEquals("nothing to cancel on the recorder", 0, recorder.cancels)
    }
}
