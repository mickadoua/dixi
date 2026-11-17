package tech.dmconsulting.dixi.transcription

import androidx.annotation.StringRes
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.transcription.MistralHttp.auth

/**
 * Answer to « Test key », with its cause (texts DMC-765). [refused]: shown under
 * « Key refused »; no network and an unavailable service are not a refusal.
 * [rejectsKey]: Mistral does not take this key, the key status says so.
 */
enum class KeyCheck(@StringRes val message: Int, val refused: Boolean = false, val rejectsKey: Boolean = false) {
    Valid(R.string.key_check_valid_body),
    Invalid(R.string.key_cause_invalid, refused = true, rejectsKey = true),
    NoAccess(R.string.key_cause_no_access, refused = true, rejectsKey = true),
    RateLimited(R.string.key_cause_rate_limited, refused = true),
    NoConnection(R.string.key_cause_no_network),
    Unavailable(R.string.key_cause_unavailable),
    ;

    companion object {
        /** HTTP [status] to the answer; null when no answer came. */
        fun forStatus(status: Int?): KeyCheck = when (status) {
            null, 408 -> NoConnection
            in 200..299 -> Valid
            401 -> Invalid
            403 -> NoAccess
            429 -> RateLimited
            else -> Unavailable
        }
    }
}

/**
 * « Test key »: `GET /v1/models`, which needs a valid key but runs no model, so it
 * costs nothing. Only the status matters; the body is not read.
 */
class MistralKeyCheck(
    private val client: OkHttpClient = MistralHttp.newClient(),
    private val baseUrl: HttpUrl = MistralHttp.defaultBaseUrl,
) {
    suspend fun check(apiKey: String): KeyCheck {
        val request = Request.Builder().url(baseUrl.resolve(ROUTE)!!).auth(apiKey).get().build()
        return KeyCheck.forStatus(MistralHttp.status(client.newCall(request)))
    }

    companion object {
        const val ROUTE = "v1/models"
    }
}
