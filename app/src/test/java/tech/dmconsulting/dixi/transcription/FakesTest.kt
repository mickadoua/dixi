package tech.dmconsulting.dixi.transcription

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.dmconsulting.dixi.settings.DictationLanguage
import java.io.File

class FakesTest {
    @Test
    fun fakeTranscriberAnswersConfiguredResult() = runTest {
        val audio = File("dictation.m4a")
        val fake = FakeTranscriber(result = VoxtralResult.Failure(VoxtralError.NoConnection))
        assertEquals(VoxtralResult.Failure(VoxtralError.NoConnection), fake.transcribe(audio, DictationLanguage.Auto))
        assertEquals(listOf(audio), fake.received)
    }

    @Test
    fun fakeTranscriberDefaultsToSampleText() = runTest {
        val result = FakeTranscriber().transcribe(File("a.m4a"), DictationLanguage.French)
        assertEquals(VoxtralResult.Success(FakeTranscriber.SAMPLE_RAW_TEXT), result)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // currentTime: virtual clock of runTest
    @Test
    fun fakeTranscriberWaitsLikeTheRealService() = runTest {
        FakeTranscriber().transcribe(File("a.m4a"), DictationLanguage.Auto)
        assertEquals(1_500L, currentTime)
        FakeTranscriber(delayMs = 200).transcribe(File("a.m4a"), DictationLanguage.Auto)
        assertEquals(1_700L, currentTime)
    }

    @Test
    fun fakeCleanerDropsHesitationsAndPunctuates() = runTest {
        val result = FakeTextCleaner().clean(FakeTranscriber.SAMPLE_RAW_TEXT, DictationLanguage.Auto)
        assertEquals(VoxtralResult.Success("Bonjour ceci est un texte de test dixi."), result)
    }

    @Test
    fun fakeCleanerDropsEnglishHesitations() = runTest {
        val result = FakeTextCleaner().clean("hello um this is a dixi test text", DictationLanguage.Auto)
        assertEquals(VoxtralResult.Success("Hello this is a dixi test text."), result)
    }

    @Test
    fun fakeTranscriberReadsItsTextAtEachDictation() = runTest {
        var text = "bonjour"
        val fake = FakeTranscriber(delayMs = 0) { VoxtralResult.Success(text) }
        assertEquals(VoxtralResult.Success("bonjour"), fake.transcribe(File("a.m4a"), DictationLanguage.Auto))
        text = "hello"
        assertEquals(VoxtralResult.Success("hello"), fake.transcribe(File("a.m4a"), DictationLanguage.Auto))
    }

    @Test
    fun fakeCleanerReportsSilence() = runTest {
        assertEquals(
            VoxtralResult.Failure(VoxtralError.NothingHeard),
            FakeTextCleaner().clean(" euh  ", DictationLanguage.Auto),
        )
    }
}
