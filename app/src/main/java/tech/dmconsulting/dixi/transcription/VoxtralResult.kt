package tech.dmconsulting.dixi.transcription

import androidx.annotation.StringRes
import tech.dmconsulting.dixi.R

/** Outcome of a Voxtral call. Failures are values, not exceptions, so every caller handles them. */
sealed interface VoxtralResult<out T> {
    data class Success<T>(val value: T) : VoxtralResult<T>

    data class Failure(val error: VoxtralError) : VoxtralResult<Nothing>
}

/**
 * What went wrong, each with a message that says what to do (charter § 7, screen 3).
 * The HTTP status that leads to each value is in [MistralHttp.errorFor].
 */
enum class VoxtralError(@StringRes val message: Int) {
    /** No network, DNS or connection failure. */
    NoConnection(R.string.error_no_connection),

    /** Connection open but no answer in time (also HTTP 408 and 504). */
    Timeout(R.string.error_timeout),

    /** Only silence in the audio, or an empty transcription. */
    NothingHeard(R.string.error_nothing_heard),

    /** Audio over the size limit, checked before sending, or HTTP 413. */
    AudioTooLong(R.string.error_audio_too_long),

    /** The user's key, refused by Mistral (HTTP 401 or 403): to check in screen 10. */
    InvalidKey(R.string.error_invalid_key),

    /** Too many requests (HTTP 429): rate limit or spending cap reached. */
    RateLimited(R.string.error_rate_limited),

    /** Any other server error (5xx, unexpected 4xx) or an unreadable answer. */
    ServiceUnavailable(R.string.error_service_unavailable),
}
