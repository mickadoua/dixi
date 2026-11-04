package tech.dmconsulting.voxbulle.transcription

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.dmconsulting.voxbulle.settings.DictationLanguage

class VoxtralTranscriberTest {
    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var audio: File
    private lateinit var transcriber: VoxtralTranscriber

    @Before
    fun setUp() {
        server.start()
        audio = folder.newFile("dictation.m4a").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        transcriber = VoxtralTranscriber(TEST_KEY, testClient(), server.url("/"))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun sendsAudioToTranscriptionRouteWithModelAndKey() = runTest {
        server.answer(200, """{"model":"voxtral-mini-transcribe-3-0","text":" bonjour euh Claire ","language":"fr"}""")

        val result = transcriber.transcribe(audio, DictationLanguage.Auto)

        assertEquals(VoxtralResult.Success("bonjour euh Claire"), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
        assertEquals("Bearer $TEST_KEY", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val form = request.body!!.utf8()
        assertTrue(formField("model", "voxtral-mini-transcribe-3-0").containsMatchIn(form))
        assertTrue(form.contains("""name="file"; filename="dictation.m4a""""))
        assertTrue(form.contains("Content-Type: audio/mp4"))
    }

    @Test
    fun autoLanguageLetsVoxtralDetect() = runTest {
        server.answer(200, """{"text":"hello"}""")
        transcriber.transcribe(audio, DictationLanguage.Auto)
        assertFalse(server.takeRequest().body!!.utf8().contains("""name="language""""))
    }

    @Test
    fun forcedLanguageIsSent() = runTest {
        server.answer(200, """{"text":"bonjour"}""")
        server.answer(200, """{"text":"hello"}""")
        transcriber.transcribe(audio, DictationLanguage.French)
        transcriber.transcribe(audio, DictationLanguage.English)
        assertTrue(formField("language", "fr").containsMatchIn(server.takeRequest().body!!.utf8()))
        assertTrue(formField("language", "en").containsMatchIn(server.takeRequest().body!!.utf8()))
    }

    @Test
    fun emptyTranscriptionMeansNothingHeard() = runTest {
        server.answer(200, """{"text":"   "}""")
        assertEquals(failure(VoxtralError.NothingHeard), transcriber.transcribe(audio, DictationLanguage.Auto))
    }

    @Test
    fun unreadableAnswersAreServiceErrors() = runTest {
        server.answer(200, "")
        server.answer(200, "<html>oops</html>")
        server.answer(200, """{"model":"voxtral-mini-transcribe-3-0"}""")
        repeat(3) {
            assertEquals(failure(VoxtralError.ServiceUnavailable), transcriber.transcribe(audio, DictationLanguage.Auto))
        }
    }

    @Test
    fun httpErrorsAreTyped() = runTest {
        val expected = mapOf(
            401 to VoxtralError.InvalidKey,
            403 to VoxtralError.InvalidKey,
            413 to VoxtralError.AudioTooLong,
            429 to VoxtralError.RateLimited,
            400 to VoxtralError.ServiceUnavailable,
            500 to VoxtralError.ServiceUnavailable,
            502 to VoxtralError.ServiceUnavailable,
            503 to VoxtralError.ServiceUnavailable,
            504 to VoxtralError.Timeout,
        )
        expected.forEach { (code, error) ->
            server.answer(code, """{"message":"error"}""")
            assertEquals("HTTP $code", failure(error), transcriber.transcribe(audio, DictationLanguage.Auto))
        }
    }

    @Test
    fun requestTimeoutStatusIsRetriedOnceThenTimeout() = runTest {
        // OkHttp retries a 408 by itself, once.
        server.answer(408)
        server.answer(408)
        assertEquals(failure(VoxtralError.Timeout), transcriber.transcribe(audio, DictationLanguage.Auto))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun slowAnswerIsTimeout() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"text":"late"}""").headersDelay(2, TimeUnit.SECONDS).build(),
        )
        assertEquals(failure(VoxtralError.Timeout), transcriber.transcribe(audio, DictationLanguage.Auto))
    }

    @Test
    fun unreachableServerIsNoConnection() = runTest {
        val url = server.url("/")
        server.close()
        val offline = VoxtralTranscriber(TEST_KEY, testClient(), url)
        assertEquals(failure(VoxtralError.NoConnection), offline.transcribe(audio, DictationLanguage.Auto))
    }

    @Test
    fun emptyOrMissingAudioIsNotSent() = runTest {
        val empty = folder.newFile("empty.m4a")
        assertEquals(failure(VoxtralError.NothingHeard), transcriber.transcribe(empty, DictationLanguage.Auto))
        assertEquals(failure(VoxtralError.NothingHeard), transcriber.transcribe(File(folder.root, "gone.m4a"), DictationLanguage.Auto))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun oversizedAudioIsNotSent() = runTest {
        val big = folder.newFile("big.m4a")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(VoxtralTranscriber.MAX_AUDIO_BYTES + 1) }
        assertEquals(failure(VoxtralError.AudioTooLong), transcriber.transcribe(big, DictationLanguage.Auto))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keyStaysOutOfResultsAndClient() = runTest {
        server.answer(401, """{"message":"Unauthorized"}""")
        val result = transcriber.transcribe(audio, DictationLanguage.Auto)
        assertFalse(result.toString().contains(TEST_KEY))
        assertFalse(transcriber.toString().contains(TEST_KEY))
        assertNull(server.takeRequest().url.queryParameter("key"))
    }

    private fun failure(error: VoxtralError) = VoxtralResult.Failure(error)

    /** Part header `name="…"`, blank line, then exactly [value] up to the next line break. */
    private fun formField(name: String, value: String) = Regex(
        """name="${Regex.escape(name)}"\r\n(?:[^\r\n]+\r\n)*\r\n${Regex.escape(value)}\r\n""",
    )
}
