package tech.dmconsulting.dixi.transcription

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import tech.dmconsulting.dixi.settings.DictationLanguage
import tech.dmconsulting.dixi.transcription.MistralHttp.auth
import java.io.File

/**
 * Mistral Voxtral speech-to-text: `POST /v1/audio/transcriptions`, multipart form
 * (`model`, `file`, optional `language`). Without a language, Voxtral detects it.
 */
class VoxtralTranscriber(
    private val apiKey: String,
    private val client: OkHttpClient = MistralHttp.newClient(),
    private val baseUrl: HttpUrl = MistralHttp.defaultBaseUrl,
) : Transcriber {

    override suspend fun transcribe(audio: File, language: DictationLanguage): VoxtralResult<String> {
        val size = audio.length() // 0 when the file is missing
        if (size == 0L) return VoxtralResult.Failure(VoxtralError.NothingHeard)
        if (size > MAX_AUDIO_BYTES) return VoxtralResult.Failure(VoxtralError.AudioTooLong)

        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("file", audio.name, audio.asRequestBody(AUDIO_TYPE))
            .apply { language.code?.let { addFormDataPart("language", it) } }
            .build()
        val request = Request.Builder()
            .url(baseUrl.resolve(ROUTE)!!)
            .auth(apiKey)
            .post(form)
            .build()

        return MistralHttp.execute(client.newCall(request)) { body ->
            val text = MistralHttp.json.decodeFromString<TranscriptionResponse>(body).text.trim()
            if (text.isEmpty()) VoxtralResult.Failure(VoxtralError.NothingHeard) else VoxtralResult.Success(text)
        }
    }

    @Serializable
    private data class TranscriptionResponse(val text: String)

    companion object {
        const val ROUTE = "v1/audio/transcriptions"

        /** Voxtral Mini Transcribe 3, pinned (not `-latest`) so a model change is a code change. */
        const val MODEL = "voxtral-mini-transcribe-3-0"

        /**
         * Far above a 5-minute AAC dictation (≈ 2.5 MB at 64 kbit/s): only a broken
         * recording gets here, and we refuse it before paying for the upload.
         */
        const val MAX_AUDIO_BYTES = 20L * 1024 * 1024

        /** MediaRecorder writes AAC in an MPEG-4 container (`.m4a`). */
        private val AUDIO_TYPE = "audio/mp4".toMediaType()
    }
}
