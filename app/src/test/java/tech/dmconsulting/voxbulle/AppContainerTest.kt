package tech.dmconsulting.voxbulle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.dmconsulting.voxbulle.dictation.UnlimitedQuota
import tech.dmconsulting.voxbulle.transcription.FakeTextCleaner
import tech.dmconsulting.voxbulle.transcription.FakeTranscriber
import tech.dmconsulting.voxbulle.transcription.VoxtralTextCleaner
import tech.dmconsulting.voxbulle.transcription.VoxtralTranscriber

class AppContainerTest {
    @Test
    fun withoutKeyProvidesFakeVoxtral() {
        val container = AppContainer(mistralApiKey = "")
        assertTrue(container.usesFakeVoxtral)
        assertTrue(container.transcriber is FakeTranscriber)
        assertTrue(container.textCleaner is FakeTextCleaner)
    }

    @Test
    fun blankKeyCountsAsNoKey() {
        assertTrue(AppContainer(mistralApiKey = "  ").transcriber is FakeTranscriber)
    }

    @Test
    fun withKeyProvidesVoxtralClients() {
        val container = AppContainer(mistralApiKey = "test-key")
        assertFalse(container.usesFakeVoxtral)
        assertTrue(container.transcriber is VoxtralTranscriber)
        assertTrue(container.textCleaner is VoxtralTextCleaner)
    }

    @Test
    fun quotaIsUnlimitedInV1() {
        assertSame(UnlimitedQuota, AppContainer(mistralApiKey = "").quota)
    }
}
