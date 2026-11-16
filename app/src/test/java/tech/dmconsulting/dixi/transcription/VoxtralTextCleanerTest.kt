package tech.dmconsulting.dixi.transcription

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.dmconsulting.dixi.settings.DictationLanguage
import tech.dmconsulting.dixi.settings.InMemorySettingsStore
import java.io.File
import java.util.concurrent.TimeUnit

class VoxtralTextCleanerTest {
    private val server = MockWebServer()
    private lateinit var cleaner: VoxtralTextCleaner

    @Before
    fun setUp() {
        server.start()
        cleaner = VoxtralTextCleaner(TEST_KEY, testClient(), server.url("/"))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun sendsChatRequestWithModelPromptAndKey() = runTest {
        server.answer(200, chatAnswer("Bonjour Claire,\\n\\nJe voulais confirmer le rendez-vous de jeudi à 14 h."))

        val result = cleaner.clean(RAW, DictationLanguage.Auto)

        assertEquals(VoxtralResult.Success("Bonjour Claire,\n\nJe voulais confirmer le rendez-vous de jeudi à 14 h."), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals("Bearer $TEST_KEY", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val body = sentJson(request.body!!.utf8())
        assertEquals("mistral-small-2603", body["model"]!!.jsonPrimitive.content)
        assertEquals(0.1, body["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.int)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(VoxtralTextCleaner.SYSTEM_PROMPT, messages[0]["content"]!!.jsonPrimitive.content)
        assertEquals(RAW, messages[1]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun promptKeepsMeaningAndLanguage() {
        val prompt = VoxtralTextCleaner.SYSTEM_PROMPT
        assertTrue(prompt.contains("Ne change pas le sens"))
        assertTrue(prompt.contains("Ne traduis jamais"))
        assertTrue(prompt.contains("ponctuation"))
        assertTrue(prompt.contains("paragraphes"))
        assertTrue(prompt.contains("euh"))
    }

    @Test
    fun forcedLanguageIsToldToTheModel() = runTest {
        server.answer(200, chatAnswer("Bonjour."))
        server.answer(200, chatAnswer("Hello."))
        cleaner.clean("bonjour", DictationLanguage.French)
        cleaner.clean("hello", DictationLanguage.English)
        assertTrue(systemPromptSent().endsWith("Le texte est en français."))
        assertTrue(systemPromptSent().endsWith("Le texte est en anglais : réponds en anglais."))
    }

    @Test
    fun readsChunkedContent() = runTest {
        server.answer(
            200,
            """{"choices":[{"message":{"role":"assistant","content":[{"type":"text","text":"Bonjour, "},{"type":"text","text":"Claire."}]}}]}""",
        )
        assertEquals(VoxtralResult.Success("Bonjour, Claire."), cleaner.clean("bonjour claire", DictationLanguage.Auto))
    }

    @Test
    fun blankTextIsNotSent() = runTest {
        assertEquals(VoxtralResult.Failure(VoxtralError.NothingHeard), cleaner.clean("  ", DictationLanguage.Auto))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun emptyOrUnreadableAnswersAreServiceErrors() = runTest {
        listOf(
            chatAnswer("  "),
            """{"choices":[]}""",
            """{"choices":[{"message":{"role":"assistant","content":null}}]}""",
            """{"id":"x"}""",
            "",
            "not json",
        ).forEach { answer ->
            server.answer(200, answer)
            assertEquals(answer, VoxtralResult.Failure(VoxtralError.ServiceUnavailable), cleaner.clean(RAW, DictationLanguage.Auto))
        }
    }

    @Test
    fun cutAnswersAreServiceErrors() = runTest {
        listOf("length", "model_length", "error").forEach { reason ->
            server.answer(200, chatAnswer("Bonjour Claire, je voulais", finishReason = reason))
            assertEquals(reason, VoxtralResult.Failure(VoxtralError.ServiceUnavailable), cleaner.clean(RAW, DictationLanguage.Auto))
        }
    }

    @Test
    fun cutAnswerKeepsTheRawTextInThePipeline() = runTest {
        server.answer(200, chatAnswer("Bonjour Claire, je voulais", finishReason = "length"))
        val pipeline = TranscriptionPipeline(FakeTranscriber(VoxtralResult.Success(RAW)), cleaner, InMemorySettingsStore())

        val transcript = (pipeline.run(File("dictation.m4a")) as VoxtralResult.Success).value

        assertEquals(Transcript(RAW, cleaned = null, cleanupError = VoxtralError.ServiceUnavailable), transcript)
        assertEquals(RAW, transcript.text)
    }

    @Test
    fun httpErrorsAreTyped() = runTest {
        mapOf(
            401 to VoxtralError.InvalidKey,
            429 to VoxtralError.RateLimited,
            500 to VoxtralError.ServiceUnavailable,
            503 to VoxtralError.ServiceUnavailable,
        ).forEach { (code, error) ->
            server.answer(code)
            assertEquals("HTTP $code", VoxtralResult.Failure(error), cleaner.clean(RAW, DictationLanguage.Auto))
        }
    }

    @Test
    fun slowAnswerIsTimeout() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(chatAnswer("Tard.")).headersDelay(2, TimeUnit.SECONDS).build())
        assertEquals(VoxtralResult.Failure(VoxtralError.Timeout), cleaner.clean(RAW, DictationLanguage.Auto))
    }

    @Test
    fun unreachableServerIsNoConnection() = runTest {
        val url = server.url("/")
        server.close()
        val offline = VoxtralTextCleaner(TEST_KEY, testClient(), url)
        assertEquals(VoxtralResult.Failure(VoxtralError.NoConnection), offline.clean(RAW, DictationLanguage.Auto))
    }

    @Test
    fun keyIsOnlyInTheAuthHeader() = runTest {
        server.answer(200, chatAnswer("Bonjour."))
        val result = cleaner.clean("bonjour", DictationLanguage.Auto)
        val request = server.takeRequest()
        assertFalse(request.body!!.utf8().contains(TEST_KEY))
        assertFalse(request.url.toString().contains(TEST_KEY))
        assertFalse(result.toString().contains(TEST_KEY))
        assertFalse(cleaner.toString().contains(TEST_KEY))
    }

    private fun systemPromptSent(): String = sentJson(server.takeRequest().body!!.utf8())["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content

    private fun sentJson(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun chatAnswer(content: String, finishReason: String = "stop") = """{"id":"c1","model":"mistral-small-2603","choices":[{"index":0,"message":{"role":"assistant","content":"$content"},"finish_reason":"$finishReason"}]}"""

    private companion object {
        /** Fictitious dictation, like the mockups' sample. */
        const val RAW = "euh bonjour Claire donc je voulais euh confirmer le rendez-vous de jeudi quatorze heures"
    }
}
