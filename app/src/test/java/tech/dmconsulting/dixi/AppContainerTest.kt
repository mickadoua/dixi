package tech.dmconsulting.dixi

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.dmconsulting.dixi.dictation.UnlimitedQuota
import tech.dmconsulting.dixi.history.InMemoryDictationHistory
import tech.dmconsulting.dixi.settings.DictationLanguage
import tech.dmconsulting.dixi.settings.InMemoryMistralKeyStore
import tech.dmconsulting.dixi.settings.InMemorySettingsStore
import tech.dmconsulting.dixi.settings.KeyStatus
import tech.dmconsulting.dixi.transcription.FakeTranscriber
import tech.dmconsulting.dixi.transcription.TEST_KEY
import tech.dmconsulting.dixi.transcription.Transcript
import tech.dmconsulting.dixi.transcription.VoxtralError
import tech.dmconsulting.dixi.transcription.VoxtralResult
import tech.dmconsulting.dixi.transcription.answer
import java.io.File

class AppContainerTest {
    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var audio: File

    @Before
    fun setUp() {
        server.start()
        audio = folder.newFile("dictation.m4a").apply { writeBytes(ByteArray(64) { it.toByte() }) }
    }

    @After
    fun tearDown() = server.close()

    private fun container(key: String? = null, demoMode: Boolean = false) = AppContainer(mistralKey = InMemoryMistralKeyStore(key), mistralBaseUrl = server.url("/"), demoMode = demoMode)

    @Test
    fun withoutKeyADictationSaysNoKeyAndNothingIsSent() = runTest {
        val container = container()
        assertFalse(container.usesFakeVoxtral)

        val result = container.transcription.run(audio)

        assertEquals(VoxtralResult.Failure(VoxtralError.NoKey), result)
        assertEquals(R.string.error_no_key, VoxtralError.NoKey.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun inDemoModeWithoutKeyTheFakesAnswerAndNothingIsSent() = runTest {
        val container = container(demoMode = true)
        assertTrue(container.usesFakeVoxtral)

        val result = container.transcription.run(audio)

        assertTrue(result is VoxtralResult.Success)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun inDemoModeAKeyStillGoesToMistral() = runTest {
        val container = container(TEST_KEY, demoMode = true)
        assertFalse(container.usesFakeVoxtral)
        server.answer(200, """{"text":"bonjour"}""")

        assertEquals(VoxtralResult.Success("bonjour"), container.transcriber.transcribe(audio, DictationLanguage.Auto))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun demoModeOnlyInDebugBuilds() {
        assertEquals(BuildConfig.DEBUG, BuildConfig.DEMO_MODE)
    }

    @Test
    fun withKeyTheRealServiceGetsTheUsersKey() = runTest {
        val container = container(TEST_KEY)
        assertFalse(container.usesFakeVoxtral)
        server.answer(200, """{"text":"bonjour euh Claire"}""")
        server.answer(200, """{"choices":[{"message":{"content":"Bonjour Claire."},"finish_reason":"stop"}]}""")

        val result = container.transcription.run(audio)

        assertEquals(VoxtralResult.Success(Transcript("bonjour euh Claire", "Bonjour Claire.")), result)
        assertEquals("Bearer $TEST_KEY", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer $TEST_KEY", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun theServiceFollowsTheActiveKeyAtRunTime() = runTest {
        val container = container()
        val noKey = VoxtralResult.Failure(VoxtralError.NoKey)
        assertEquals(noKey, container.transcriber.transcribe(audio, DictationLanguage.Auto))
        assertEquals(0, server.requestCount)

        container.mistralKey.add("Perso", TEST_KEY)
        container.mistralKey.add("Pro", OTHER_KEY)
        server.answer(200, """{"text":"bonjour"}""")
        assertEquals(VoxtralResult.Success("bonjour"), container.transcriber.transcribe(audio, DictationLanguage.Auto))
        assertEquals("Bearer $OTHER_KEY", server.takeRequest().headers["Authorization"])

        val perso = container.mistralKey.entries.value.first { it.name == "Perso" }
        container.mistralKey.activate(perso.id)
        server.answer(200, """{"text":"bonjour"}""")
        container.transcriber.transcribe(audio, DictationLanguage.Auto)
        assertEquals("Bearer $TEST_KEY", server.takeRequest().headers["Authorization"])

        container.mistralKey.entries.value.forEach { container.mistralKey.delete(it.id) }
        assertEquals(noKey, container.transcriber.transcribe(audio, DictationLanguage.Auto))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRefusedKeyIsMarkedAndTheErrorSaysWhereToCheck() = runTest {
        val container = container(TEST_KEY)
        server.answer(401, """{"message":"Unauthorized"}""")

        val result = container.transcription.run(audio)

        assertEquals(VoxtralResult.Failure(VoxtralError.InvalidKey), result)
        assertEquals(KeyStatus.Rejected, container.mistralKey.status.value)
        assertEquals(R.string.error_invalid_key, VoxtralError.InvalidKey.message)
        assertFalse(result.toString().contains(TEST_KEY))
        // Still the real service: the key is there, only refused.
        assertFalse(container.usesFakeVoxtral)
    }

    @Test
    fun aRefusalAtCleanupKeepsTheRawText() = runTest {
        val container = container(TEST_KEY)
        server.answer(200, """{"text":"bonjour euh Claire"}""")
        server.answer(403)

        val result = container.transcription.run(audio)

        assertEquals(
            VoxtralResult.Success(Transcript("bonjour euh Claire", cleaned = null, cleanupError = VoxtralError.InvalidKey)),
            result,
        )
        assertEquals(KeyStatus.Rejected, container.mistralKey.status.value)
    }

    @Test
    fun keyCheckUsesTheSameService() = runTest {
        server.answer(200, "{}")
        container().keyCheck.check(TEST_KEY)
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
    }

    @Test
    fun defaultsHaveNoKeyAndNoDemo() = runTest {
        val container = AppContainer()
        assertFalse(container.usesFakeVoxtral)
        assertNull(container.mistralKey.key())
        assertTrue(FakeTranscriber().transcribe(audio, DictationLanguage.Auto) is VoxtralResult.Success)
    }

    @Test
    fun quotaIsUnlimitedInV1() {
        assertSame(UnlimitedQuota, AppContainer().quota)
    }

    @Test
    fun usesTheStoresItIsGiven() {
        val history = InMemoryDictationHistory()
        val settings = InMemorySettingsStore()
        val keys = InMemoryMistralKeyStore()
        val container = AppContainer(mistralKey = keys, history = history, settings = settings)
        assertSame(history, container.history)
        assertSame(settings, container.settings)
        assertSame(keys, container.mistralKey)
    }

    @Test
    fun buildConfigHoldsNoKey() {
        val fields = BuildConfig::class.java.declaredFields.map { it.name }
        assertTrue(fields.toString(), fields.none { it.contains("KEY", ignoreCase = true) || it.contains("MISTRAL", ignoreCase = true) })
    }

    private companion object {
        const val OTHER_KEY = "test-key-not-real-other"
    }
}
