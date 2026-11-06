package tech.dmconsulting.voxbulle.transcription

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What the two Mistral clients share: base URL, HTTP client, auth header and the
 * mapping of failures to [VoxtralError]. Routes and models checked in the Mistral
 * docs on 04/11/2026 (DMC-137).
 *
 * The key only goes into the `Authorization` header. Nothing here logs: no
 * logging interceptor, no request or response printed.
 */
object MistralHttp {
    /** Mistral's EU-hosted API (brief § 5). */
    val defaultBaseUrl: HttpUrl = "https://api.mistral.ai/".toHttpUrl()

    val json = Json { ignoreUnknownKeys = true }

    /** One client for the app: transcription can take a while for a 5-minute dictation. */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun Request.Builder.auth(apiKey: String): Request.Builder = header("Authorization", "Bearer $apiKey")

    /** Error for a non-2xx status. */
    fun errorFor(status: Int): VoxtralError = when (status) {
        401, 403 -> VoxtralError.InvalidKey
        408, 504 -> VoxtralError.Timeout
        413 -> VoxtralError.AudioTooLong
        429 -> VoxtralError.RateLimited
        else -> VoxtralError.ServiceUnavailable
    }

    /**
     * Runs [call] and turns its response into a result with [parse]. Network,
     * HTTP and parsing failures all come back as [VoxtralResult.Failure].
     */
    suspend fun <T> execute(call: Call, parse: (String) -> VoxtralResult<T>): VoxtralResult<T> {
        val response = try {
            call.await()
        } catch (e: InterruptedIOException) {
            // OkHttp signals connect, read and call timeouts this way.
            return VoxtralResult.Failure(VoxtralError.Timeout)
        } catch (e: IOException) {
            return VoxtralResult.Failure(VoxtralError.NoConnection)
        }
        return response.use {
            if (!it.isSuccessful) return VoxtralResult.Failure(errorFor(it.code))
            val body = try {
                it.body.string()
            } catch (e: InterruptedIOException) {
                return VoxtralResult.Failure(VoxtralError.Timeout)
            } catch (e: IOException) {
                return VoxtralResult.Failure(VoxtralError.NoConnection)
            }
            try {
                parse(body)
            } catch (e: SerializationException) {
                VoxtralResult.Failure(VoxtralError.ServiceUnavailable)
            } catch (e: IllegalArgumentException) {
                VoxtralResult.Failure(VoxtralError.ServiceUnavailable)
            }
        }
    }

    /** Suspends until [this] call answers; cancelling the coroutine cancels the call. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, value, _ ->
                value.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        })
    }
}
