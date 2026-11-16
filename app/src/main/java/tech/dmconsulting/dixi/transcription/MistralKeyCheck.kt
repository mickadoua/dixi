package tech.dmconsulting.dixi.transcription

import androidx.annotation.StringRes
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.transcription.MistralHttp.auth

/** Answer to « Tester la clé », with the message screen 10 shows. */
enum class KeyCheck(@StringRes val message: Int) {
    Valid(R.string.key_check_valid),
    Rejected(R.string.key_check_rejected),
    NoConnection(R.string.key_check_no_connection),
    Unavailable(R.string.key_check_unavailable),
}

/**
 * « Tester la clé » (screen 10): `GET /v1/models`, which needs a valid key but runs no
 * model, so it costs nothing. Only the status matters; the body is not read.
 */
class MistralKeyCheck(
    private val client: OkHttpClient = MistralHttp.newClient(),
    private val baseUrl: HttpUrl = MistralHttp.defaultBaseUrl,
) {
    suspend fun check(apiKey: String): KeyCheck {
        val request = Request.Builder().url(baseUrl.resolve(ROUTE)!!).auth(apiKey).get().build()
        val result = MistralHttp.execute(client.newCall(request)) { VoxtralResult.Success(Unit) }
        return when (result) {
            is VoxtralResult.Success -> KeyCheck.Valid

            is VoxtralResult.Failure -> when (result.error) {
                VoxtralError.InvalidKey -> KeyCheck.Rejected
                VoxtralError.NoConnection, VoxtralError.Timeout -> KeyCheck.NoConnection
                else -> KeyCheck.Unavailable
            }
        }
    }

    companion object {
        const val ROUTE = "v1/models"
    }
}
