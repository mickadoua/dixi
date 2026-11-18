package tech.dmconsulting.dixi.transcription

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.dmconsulting.dixi.settings.DictationLanguage
import tech.dmconsulting.dixi.settings.InMemoryMistralKeyStore
import tech.dmconsulting.dixi.settings.KeyStatus
import tech.dmconsulting.dixi.settings.MistralKeyStore
import java.io.File
import java.io.IOException

class KeyedTranscriberTest {
    private val refused = VoxtralResult.Failure(VoxtralError.InvalidKey)

    @Test
    fun aRefusalMarksTheActiveKey() = runTest {
        val keys = InMemoryMistralKeyStore(KEY)
        val transcriber = KeyedTranscriber(keys, fake = null) { FakeTranscriber(result = refused, delayMs = 0) }

        assertEquals(refused, transcriber.transcribe(File("a.m4a"), DictationLanguage.Auto))
        assertEquals(KeyStatus.Rejected, keys.status.value)
    }

    @Test
    fun aDiskErrorWhileMarkingTheRefusalDoesNotCrashTheDictation() = runTest {
        val keys = BrokenDisk(InMemoryMistralKeyStore(KEY))
        val transcriber = KeyedTranscriber(keys, fake = null) { FakeTranscriber(result = refused, delayMs = 0) }
        val cleaner = KeyedTextCleaner(keys, fake = null) {
            object : TextCleaner {
                override suspend fun clean(rawText: String, language: DictationLanguage): VoxtralResult<String> = refused
            }
        }

        assertEquals(refused, transcriber.transcribe(File("a.m4a"), DictationLanguage.Auto))
        assertEquals(refused, cleaner.clean("bonjour", DictationLanguage.Auto))
    }

    /** Writing to the disk fails, like a full storage. */
    private class BrokenDisk(inner: MistralKeyStore) : MistralKeyStore by inner {
        override suspend fun markRejected() = throw IOException("No space left on device")
    }

    private companion object {
        const val KEY = "test-key-not-real-1"
    }
}
