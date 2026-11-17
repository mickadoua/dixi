package tech.dmconsulting.dixi.transcription

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class MistralKeyCheckTest {
    private val server = MockWebServer()
    private lateinit var check: MistralKeyCheck

    @Before
    fun setUp() {
        server.start()
        check = MistralKeyCheck(testClient(), server.url("/"))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun validKeyOnModelsRoute() = runTest {
        server.answer(200, """{"object":"list","data":[]}""")

        assertEquals(KeyCheck.Valid, check.check(TEST_KEY))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer $TEST_KEY", request.headers["Authorization"])
    }

    @Test
    fun eachRefusalHasItsCause() = runTest {
        // Léa's table (DMC-765): 401 invalid, 403 no access, 429 limited.
        listOf(401 to KeyCheck.Invalid, 403 to KeyCheck.NoAccess, 429 to KeyCheck.RateLimited).forEach { (code, expected) ->
            server.answer(code, """{"message":"Unauthorized"}""")
            val result = check.check(TEST_KEY)
            assertEquals("HTTP $code", expected, result)
            assertTrue("HTTP $code is shown under « Key refused »", result.refused)
        }
    }

    @Test
    fun onlyInvalidOrNoAccessMarksTheKeyRejected() {
        assertEquals(setOf(KeyCheck.Invalid, KeyCheck.NoAccess), KeyCheck.entries.filter { it.rejectsKey }.toSet())
    }

    @Test
    fun serviceTroubleIsNotARefusal() = runTest {
        listOf(500, 503).forEach { code ->
            server.answer(code)
            val result = check.check(TEST_KEY)
            assertEquals("HTTP $code", KeyCheck.Unavailable, result)
            assertFalse(result.refused)
        }
    }

    @Test
    fun statusesWithoutAnAnswer() {
        assertEquals(KeyCheck.NoConnection, KeyCheck.forStatus(null))
        assertEquals(KeyCheck.NoConnection, KeyCheck.forStatus(408))
        assertEquals(KeyCheck.Valid, KeyCheck.forStatus(200))
        assertEquals(KeyCheck.Unavailable, KeyCheck.forStatus(404))
    }

    @Test
    fun noNetwork() = runTest {
        val url = server.url("/")
        server.close()
        assertEquals(KeyCheck.NoConnection, MistralKeyCheck(testClient(), url).check(TEST_KEY))
    }

    @Test
    fun noAnswerInTime() = runTest {
        server.enqueue(MockResponse.Builder().code(200).headersDelay(2, TimeUnit.SECONDS).build())
        assertEquals(KeyCheck.NoConnection, check.check(TEST_KEY))
    }

    @Test
    fun okHttpNeverPrintsTheAuthorizationValue() {
        // MistralKey.normalize keeps such a key out; if one got through, OkHttp's error must not show it.
        val error = assertThrows(IllegalArgumentException::class.java) {
            Request.Builder().header("Authorization", "Bearer ${TEST_KEY}\u00e9")
        }
        assertFalse(error.toString().contains(TEST_KEY))
    }
}
