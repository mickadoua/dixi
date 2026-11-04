package tech.dmconsulting.voxbulle

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.dmconsulting.voxbulle.dictation.UnlimitedQuota
import tech.dmconsulting.voxbulle.transcription.FakeTextCleaner
import tech.dmconsulting.voxbulle.transcription.FakeTranscriber
import tech.dmconsulting.voxbulle.transcription.VoxtralResult
import tech.dmconsulting.voxbulle.transcription.VoxtralTextCleaner
import tech.dmconsulting.voxbulle.transcription.VoxtralTranscriber

class AppContainerTest {
    @Test
    fun withoutKeyProvidesFakeVoxtral() {
        val container = AppContainer(mistralApiKey = "", fakesAllowed = true)
        assertTrue(container.usesFakeVoxtral)
        assertTrue(container.transcriber is FakeTranscriber)
        assertTrue(container.textCleaner is FakeTextCleaner)
    }

    @Test
    fun blankKeyCountsAsNoKey() {
        assertTrue(AppContainer(mistralApiKey = "  ", fakesAllowed = true).transcriber is FakeTranscriber)
    }

    @Test
    fun withKeyProvidesVoxtralClients() {
        val container = AppContainer(mistralApiKey = "test-key", fakesAllowed = false)
        assertFalse(container.usesFakeVoxtral)
        assertTrue(container.transcriber is VoxtralTranscriber)
        assertTrue(container.textCleaner is VoxtralTextCleaner)
    }

    @Test
    fun withoutKeyOutsideDebugFakesAreRefused() {
        listOf("", "  ").forEach { key ->
            val error = assertThrows(IllegalStateException::class.java) { AppContainer(key, fakesAllowed = false) }
            assertTrue(error.message!!.contains("No Mistral key"))
        }
    }

    @Test
    fun withoutKeyTheWholeTranscriptionRunsOnFakes() = runTest {
        val result = AppContainer(mistralApiKey = "", fakesAllowed = true).transcription.run(File("demo.m4a"))
        assertTrue(result is VoxtralResult.Success)
    }

    @Test
    fun quotaIsUnlimitedInV1() {
        assertSame(UnlimitedQuota, AppContainer(mistralApiKey = "", fakesAllowed = true).quota)
    }
}
