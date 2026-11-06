package tech.dmconsulting.voxbulle.transcription

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Fake key for tests: never a real one, so it may appear in assertions. */
const val TEST_KEY = "test-key-not-real"

/** Short timeouts so the timeout tests stay fast. */
fun testClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(300, TimeUnit.MILLISECONDS)
    .build()

fun MockWebServer.answer(code: Int, body: String = "") = enqueue(MockResponse.Builder().code(code).body(body).build())
