package tech.dmconsulting.voxbulle.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import tech.dmconsulting.voxbulle.settings.DictationLanguage
import tech.dmconsulting.voxbulle.transcription.MistralHttp
import tech.dmconsulting.voxbulle.transcription.VoxtralError
import tech.dmconsulting.voxbulle.transcription.VoxtralResult
import tech.dmconsulting.voxbulle.transcription.VoxtralTextCleaner
import tech.dmconsulting.voxbulle.transcription.VoxtralTranscriber
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs the real Mistral clients against canned answers from a loopback server, so the
 * minified debug APK proves it still encodes the requests and parses the answers
 * (kotlinx.serialization and OkHttp under R8, DMC-161). Fake key, nothing leaves 127.0.0.1.
 */
object MistralProbe {
    const val PASS = "PROBE PASS"
    const val FAIL = "PROBE FAIL"

    /** One line per check (« OK … » or « KO … »), then [PASS] or [FAIL]. */
    suspend fun run(workDir: File): List<String> = LoopbackServer().use { server ->
        val client = MistralHttp.newClient()
        val transcriber = VoxtralTranscriber(PROBE_KEY, client, server.baseUrl)
        val cleaner = VoxtralTextCleaner(PROBE_KEY, client, server.baseUrl)
        val audio = File(workDir, "probe.m4a").apply { writeText("probe audio") }
        val lines = mutableListOf<String>()
        fun expect(name: String, ok: Boolean, detail: Any?) {
            lines += if (ok) "OK $name" else "KO $name: $detail"
        }

        try {
            var request = server.answerNext(TRANSCRIPTION_ANSWER)
            val transcript = transcriber.transcribe(audio, DictationLanguage.French)
            expect("transcription parsed", transcript == VoxtralResult.Success("bonjour euh Claire"), transcript)
            val sent = request.get(5, TimeUnit.SECONDS)
            expect(
                "transcription request",
                sent.path == "/v1/audio/transcriptions" && VoxtralTranscriber.MODEL in sent.body && "probe audio" in sent.body,
                sent.path,
            )

            request = server.answerNext(chatAnswer("\"Bonjour Claire.\"", "stop"))
            val cleaned = cleaner.clean("bonjour euh Claire", DictationLanguage.Auto)
            expect("cleanup parsed", cleaned == VoxtralResult.Success("Bonjour Claire."), cleaned)
            val chat = request.get(5, TimeUnit.SECONDS)
            val body = runCatching { Json.parseToJsonElement(chat.body).jsonObject }.getOrNull()
            expect(
                "cleanup request encoded",
                chat.path == "/v1/chat/completions" &&
                    body?.get("model")?.jsonPrimitive?.content == VoxtralTextCleaner.MODEL &&
                    body["max_tokens"]?.jsonPrimitive?.int == VoxtralTextCleaner.MAX_TOKENS &&
                    body["messages"]?.jsonArray?.size == 2,
                chat.body.take(80),
            )

            server.answerNext(chatAnswer("""[{"type":"text","text":"Bonjour, "},{"type":"text","text":"Claire."}]""", "stop"))
            val chunked = cleaner.clean("bonjour Claire", DictationLanguage.Auto)
            expect("chunked cleanup parsed", chunked == VoxtralResult.Success("Bonjour, Claire."), chunked)

            server.answerNext(chatAnswer("\"Bonjour Claire, je\"", "length"))
            val cut = cleaner.clean("bonjour euh Claire", DictationLanguage.Auto)
            expect("cut cleanup refused", cut == VoxtralResult.Failure(VoxtralError.ServiceUnavailable), cut)
        } finally {
            audio.delete()
        }
        lines + if (lines.all { it.startsWith("OK") }) PASS else FAIL
    }

    private fun chatAnswer(content: String, finishReason: String) = """{"id":"probe","object":"chat.completion","model":"${VoxtralTextCleaner.MODEL}","choices":[{"index":0,""" +
        """"message":{"role":"assistant","content":$content},"finish_reason":"$finishReason"}],""" +
        """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

    /** Shape of a Mistral transcription answer, with fields the client ignores. */
    private const val TRANSCRIPTION_ANSWER =
        """{"model":"voxtral-mini-transcribe-3-0","text":" bonjour euh Claire ","language":"fr","segments":[],""" +
            """"usage":{"prompt_audio_seconds":2,"prompt_tokens":1,"total_tokens":2}}"""

    /** Never a real key: the server is ours. */
    private const val PROBE_KEY = "probe-key-not-real"
}

/** What the loopback server received. */
private class SentRequest(val path: String, val body: String)

/** Answers one HTTP/1.1 request at a time on 127.0.0.1 with a canned 200 JSON body. */
private class LoopbackServer : Closeable {
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val baseUrl: HttpUrl = "http://127.0.0.1:${socket.localPort}/".toHttpUrl()

    /** Serves the next request with [answer]; completes with what was sent. */
    fun answerNext(answer: String): CompletableFuture<SentRequest> {
        val sent = CompletableFuture<SentRequest>()
        thread(name = "probe-server", isDaemon = true) {
            runCatching {
                socket.accept().use { connection ->
                    // ISO-8859-1: one char per byte, so Content-Length counts chars.
                    val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.ISO_8859_1))
                    val path = reader.readLine().split(" ")[1]
                    var length = 0
                    while (true) {
                        val header = reader.readLine()
                        if (header.isNullOrEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val chars = CharArray(length)
                    var read = 0
                    while (read < length) read += reader.read(chars, read, length - read).also { check(it >= 0) }
                    val body = String(String(chars).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                    val bytes = answer.toByteArray(Charsets.UTF_8)
                    connection.getOutputStream().apply {
                        write(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.ISO_8859_1),
                        )
                        write(bytes)
                        flush()
                    }
                    sent.complete(SentRequest(path, body))
                }
            }.onFailure { sent.completeExceptionally(it) }
        }
        return sent
    }

    override fun close() = socket.close()
}
