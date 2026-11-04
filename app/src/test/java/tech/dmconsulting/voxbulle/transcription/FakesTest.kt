package tech.dmconsulting.voxbulle.transcription

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.dmconsulting.voxbulle.settings.DictationLanguage

class FakesTest {
    @Test
    fun fakeTranscriberAnswersConfiguredResult() = runBlocking {
        val audio = File("dictation.m4a")
        val fake = FakeTranscriber(result = VoxtralResult.Failure(VoxtralError.NoConnection))
        assertEquals(VoxtralResult.Failure(VoxtralError.NoConnection), fake.transcribe(audio, DictationLanguage.Auto))
        assertEquals(listOf(audio), fake.received)
    }

    @Test
    fun fakeTranscriberDefaultsToSampleText() = runBlocking {
        val result = FakeTranscriber().transcribe(File("a.m4a"), DictationLanguage.French)
        assertEquals(VoxtralResult.Success(FakeTranscriber.SAMPLE_RAW_TEXT), result)
    }

    @Test
    fun fakeCleanerDropsHesitationsAndPunctuates() = runBlocking {
        val result = FakeTextCleaner().clean(FakeTranscriber.SAMPLE_RAW_TEXT, DictationLanguage.Auto)
        assertEquals(VoxtralResult.Success("Bonjour ceci est un texte de test voxbulle."), result)
    }

    @Test
    fun fakeCleanerReportsSilence() = runBlocking {
        assertEquals(
            VoxtralResult.Failure(VoxtralError.NothingHeard),
            FakeTextCleaner().clean(" euh  ", DictationLanguage.Auto),
        )
    }
}
