package tech.dmconsulting.dixi.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class VoxtralErrorTest {
    @Test
    fun everyErrorHasItsOwnMessage() {
        val messages = VoxtralError.entries.map { it.message }
        assertEquals(messages.size, messages.toSet().size)
    }

    @Test
    fun statusMapping() {
        assertEquals(VoxtralError.InvalidKey, MistralHttp.errorFor(401))
        assertEquals(VoxtralError.InvalidKey, MistralHttp.errorFor(403))
        assertEquals(VoxtralError.Timeout, MistralHttp.errorFor(408))
        assertEquals(VoxtralError.Timeout, MistralHttp.errorFor(504))
        assertEquals(VoxtralError.AudioTooLong, MistralHttp.errorFor(413))
        assertEquals(VoxtralError.RateLimited, MistralHttp.errorFor(429))
        listOf(400, 404, 422, 500, 502, 503).forEach {
            assertEquals("HTTP $it", VoxtralError.ServiceUnavailable, MistralHttp.errorFor(it))
        }
    }
}
