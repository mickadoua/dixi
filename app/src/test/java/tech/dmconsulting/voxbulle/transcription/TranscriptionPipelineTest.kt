package tech.dmconsulting.voxbulle.transcription

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.dmconsulting.voxbulle.settings.AppSettings
import tech.dmconsulting.voxbulle.settings.DictationLanguage
import tech.dmconsulting.voxbulle.settings.InMemorySettingsStore
import java.io.File

class TranscriptionPipelineTest {
    private val audio = File("dictation.m4a")

    @Test
    fun transcribesThenCleans() = runTest {
        val pipeline = TranscriptionPipeline(FakeTranscriber(), FakeTextCleaner(), InMemorySettingsStore())
        assertEquals(
            VoxtralResult.Success(
                Transcript(FakeTranscriber.SAMPLE_RAW_TEXT, "Bonjour ceci est un texte de test voxbulle."),
            ),
            pipeline.run(audio),
        )
    }

    @Test
    fun languageComesFromSettings() = runTest {
        val transcriber = RecordingTranscriber()
        val cleaner = RecordingCleaner()
        val settings = InMemorySettingsStore()
        val pipeline = TranscriptionPipeline(transcriber, cleaner, settings)

        pipeline.run(audio)
        settings.update { it.copy(language = DictationLanguage.English) }
        pipeline.run(audio)

        assertEquals(listOf(DictationLanguage.Auto, DictationLanguage.English), transcriber.languages)
        assertEquals(listOf(DictationLanguage.Auto, DictationLanguage.English), cleaner.languages)
    }

    @Test
    fun transcriptionFailureStopsBeforeCleanup() = runTest {
        val cleaner = RecordingCleaner()
        val pipeline = TranscriptionPipeline(
            FakeTranscriber(VoxtralResult.Failure(VoxtralError.RateLimited)),
            cleaner,
            InMemorySettingsStore(AppSettings(language = DictationLanguage.French)),
        )
        assertEquals(VoxtralResult.Failure(VoxtralError.RateLimited), pipeline.run(audio))
        assertEquals(emptyList<DictationLanguage>(), cleaner.languages)
    }

    @Test
    fun failedCleanupKeepsTheRawText() = runTest {
        val pipeline = TranscriptionPipeline(
            FakeTranscriber(),
            RecordingCleaner(VoxtralResult.Failure(VoxtralError.ServiceUnavailable)),
            InMemorySettingsStore(),
        )
        val transcript = (pipeline.run(audio) as VoxtralResult.Success).value
        assertEquals(null, transcript.cleaned)
        assertEquals(VoxtralError.ServiceUnavailable, transcript.cleanupError)
        assertEquals(FakeTranscriber.SAMPLE_RAW_TEXT, transcript.text)
    }

    @Test
    fun onlyHesitationsIsNothingHeard() = runTest {
        val pipeline = TranscriptionPipeline(
            FakeTranscriber(VoxtralResult.Success("euh euh")),
            FakeTextCleaner(),
            InMemorySettingsStore(),
        )
        assertEquals(VoxtralResult.Failure(VoxtralError.NothingHeard), pipeline.run(audio))
    }

    private class RecordingTranscriber : Transcriber {
        val languages = mutableListOf<DictationLanguage>()

        override suspend fun transcribe(audio: File, language: DictationLanguage): VoxtralResult<String> {
            languages += language
            return VoxtralResult.Success("hello")
        }
    }

    private class RecordingCleaner(
        private val result: VoxtralResult<String> = VoxtralResult.Success("Hello."),
    ) : TextCleaner {
        val languages = mutableListOf<DictationLanguage>()

        override suspend fun clean(rawText: String, language: DictationLanguage): VoxtralResult<String> {
            languages += language
            return result
        }
    }
}
