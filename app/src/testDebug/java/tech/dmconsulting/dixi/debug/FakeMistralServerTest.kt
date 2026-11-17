package tech.dmconsulting.dixi.debug

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.dmconsulting.dixi.settings.DictationLanguage
import tech.dmconsulting.dixi.transcription.KeyCheck
import tech.dmconsulting.dixi.transcription.MistralHttp
import tech.dmconsulting.dixi.transcription.MistralKeyCheck
import tech.dmconsulting.dixi.transcription.VoxtralError
import tech.dmconsulting.dixi.transcription.VoxtralResult
import tech.dmconsulting.dixi.transcription.VoxtralTextCleaner
import tech.dmconsulting.dixi.transcription.VoxtralTranscriber
import java.nio.file.Files

/** The emulator proofs' fake Mistral answers the real clients as the scripts expect. */
class FakeMistralServerTest {
    private var mode: String? = "ok"
    private val server = FakeMistralServer(mode = { mode }, french = { false }).start()
    private val client = MistralHttp.newClient()
    private val audio = Files.createTempFile("fake", ".m4a").toFile().apply { writeText("audio") }

    @After
    fun tearDown() {
        server.close()
        audio.delete()
    }

    @Test
    fun keyTestRefusesThenAccepts() = runTest {
        val check = MistralKeyCheck(client, server.baseUrl)
        assertEquals(KeyCheck.Invalid, check.check("not-a-key"))
        assertEquals(KeyCheck.Valid, check.check(KEY))
    }

    @Test
    fun dictationGetsTheCannedTexts() = runTest {
        val raw = VoxtralTranscriber(KEY, client, server.baseUrl).transcribe(audio, DictationLanguage.Auto)
        assertEquals(VoxtralResult.Success(FakeMistralServer.RAW_EN), raw)
        val clean = VoxtralTextCleaner(KEY, client, server.baseUrl).clean(FakeMistralServer.RAW_EN, DictationLanguage.Auto)
        assertEquals(VoxtralResult.Success(FakeMistralServer.CLEAN_EN), clean)
    }

    @Test
    fun refusedModeRefusesDictationsButNotTheKeyTest() = runTest {
        mode = FakeMistralServer.MODE_REFUSED
        val raw = VoxtralTranscriber(KEY, client, server.baseUrl).transcribe(audio, DictationLanguage.Auto)
        assertEquals(VoxtralResult.Failure(VoxtralError.InvalidKey), raw)
        assertEquals(KeyCheck.Valid, MistralKeyCheck(client, server.baseUrl).check(KEY))
    }

    private companion object {
        const val KEY = FakeMistralServer.ACCEPTED_PREFIX + "proof-0000"
    }
}
