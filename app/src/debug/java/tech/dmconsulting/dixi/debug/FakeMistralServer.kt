package tech.dmconsulting.dixi.debug

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Debug builds only: a fake Mistral on 127.0.0.1 for the emulator proofs (DMC-775), so the
 * onboarding can show « Key refused » then « Key valid », and the guided trial can put a text
 * in the field, without a real key. Nothing leaves the phone.
 *
 * - `GET /v1/models` (« Test key »): 200 for a key starting with [ACCEPTED_PREFIX], else 401.
 * - Transcription and cleanup: canned answers, or 401 when [mode] says [MODE_REFUSED]
 *   (the key was revoked after it was saved: « Key refused » next to the bubble).
 */
class FakeMistralServer(
    /** Read on each request, so a script can switch it while the app runs. */
    private val mode: () -> String?,
    private val french: () -> Boolean,
) : Closeable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val baseUrl: HttpUrl = "http://127.0.0.1:${socket.localPort}/".toHttpUrl()

    fun start(): FakeMistralServer {
        thread(name = "fake-mistral", isDaemon = true) {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "fake-mistral-call", isDaemon = true) { connection.use(::serve) }
            }
        }
        return this
    }

    private fun serve(connection: Socket) {
        // ISO-8859-1: one char per byte, so Content-Length counts chars.
        val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.ISO_8859_1))
        val (method, path) = reader.readLine().orEmpty().split(" ").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        var length = 0
        var authorization = ""
        while (true) {
            val header = reader.readLine()
            if (header.isNullOrEmpty()) break
            val name = header.substringBefore(':').trim()
            val value = header.substringAfter(':').trim()
            if (name.equals("Content-Length", ignoreCase = true)) length = value.toInt()
            if (name.equals("Authorization", ignoreCase = true)) authorization = value
        }
        // Read the body (audio or chat request) so the client sees a clean exchange.
        val chars = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(chars, read, length - read)
            if (n < 0) break
            read += n
        }
        val (status, body) = answer(method, path, authorization.removePrefix("Bearer "), mode(), french())
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Unauthorized"
        connection.getOutputStream().apply {
            write(
                "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1),
            )
            write(bytes)
            flush()
        }
    }

    override fun close() = socket.close()

    companion object {
        /** A key the fake accepts. Never a real key: the server is ours. */
        const val ACCEPTED_PREFIX = "dixi-fake-"

        /** [mode] value: dictations get 401, as with a key revoked after it was saved. */
        const val MODE_REFUSED = "401"

        const val RAW_EN = "hello um this is a dixi test text"
        const val CLEAN_EN = "Hello, this is a Dixi test text."
        const val RAW_FR = "bonjour euh ceci est un texte de test dixi"
        const val CLEAN_FR = "Bonjour, ceci est un texte de test Dixi."

        private const val REFUSED = """{"message":"Unauthorized","request_id":"fake"}"""

        /** Status and JSON body for one request. */
        fun answer(method: String, path: String, key: String, mode: String?, french: Boolean): Pair<Int, String> {
            val route = path.substringBefore('?')
            return when {
                method == "GET" && route == "/v1/models" ->
                    if (key.startsWith(ACCEPTED_PREFIX)) 200 to """{"object":"list","data":[]}""" else 401 to REFUSED

                !key.startsWith(ACCEPTED_PREFIX) || mode?.trim() == MODE_REFUSED -> 401 to REFUSED

                method == "POST" && route == "/v1/audio/transcriptions" -> 200 to transcription(if (french) RAW_FR else RAW_EN, if (french) "fr" else "en")

                method == "POST" && route == "/v1/chat/completions" -> 200 to chat(if (french) CLEAN_FR else CLEAN_EN)

                else -> 404 to """{"message":"Not found"}"""
            }
        }

        private fun transcription(text: String, language: String) = """{"model":"voxtral-mini-transcribe-3-0","text":"$text","language":"$language","segments":[],""" +
            """"usage":{"prompt_audio_seconds":2,"prompt_tokens":1,"total_tokens":2}}"""

        private fun chat(text: String) = """{"id":"fake","object":"chat.completion","model":"fake","choices":[{"index":0,""" +
            """"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}],""" +
            """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
    }
}
