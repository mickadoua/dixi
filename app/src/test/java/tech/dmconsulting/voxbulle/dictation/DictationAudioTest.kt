package tech.dmconsulting.voxbulle.dictation

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.dmconsulting.voxbulle.recording.FakeAudioCapture
import tech.dmconsulting.voxbulle.recording.FakeClock
import tech.dmconsulting.voxbulle.recording.RecordingLimits
import tech.dmconsulting.voxbulle.recording.RecordingOutcome
import tech.dmconsulting.voxbulle.recording.RecordingSession
import tech.dmconsulting.voxbulle.settings.DictationLanguage
import tech.dmconsulting.voxbulle.transcription.FakeTranscriber
import tech.dmconsulting.voxbulle.transcription.Transcriber
import tech.dmconsulting.voxbulle.transcription.VoxtralError
import tech.dmconsulting.voxbulle.transcription.VoxtralResult

class DictationAudioTest {
    @get:Rule
    val cacheDir = TemporaryFolder()

    private val clock = FakeClock()

    /** Records 3 s into cacheDir like the app does, then transcribes with [transcriber]. */
    private suspend fun dictate(n: Int, transcriber: Transcriber): VoxtralResult<String> {
        val session = RecordingSession(FakeAudioCapture(), clock, RecordingLimits.DEFAULT)
        session.start(File(cacheDir.root, "dictation-$n.m4a"))
        clock.now += 3_000
        val result = (session.stop() as RecordingOutcome.Success).result
        assertTrue(result.file.exists())
        return transcriber.transcribeThenDelete(result.file, DictationLanguage.Auto)
    }

    @Test
    fun cacheDirIsEmptyAfterThreeDictationsOneFailing() = runBlocking {
        val ok = FakeTranscriber()
        val offline = FakeTranscriber(result = VoxtralResult.Failure(VoxtralError.NoConnection))

        assertEquals(VoxtralResult.Success(FakeTranscriber.SAMPLE_RAW_TEXT), dictate(1, ok))
        assertEquals(VoxtralResult.Failure(VoxtralError.NoConnection), dictate(2, offline))
        assertEquals(VoxtralResult.Success(FakeTranscriber.SAMPLE_RAW_TEXT), dictate(3, ok))

        assertEquals(emptyList<File>(), cacheDir.root.listFiles()!!.toList())
        assertEquals(3, ok.received.size + offline.received.size)
    }

    @Test
    fun audioIsDeletedWhenTheTranscriberThrows() {
        val crashing = object : Transcriber {
            override suspend fun transcribe(audio: File, language: DictationLanguage): VoxtralResult<String> =
                throw IOException("socket closed")
        }
        val thrown = runCatching { runBlocking { dictate(1, crashing) } }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals(emptyList<File>(), cacheDir.root.listFiles()!!.toList())
    }
}
