package tech.dmconsulting.voxbulle.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DurationsTest {
    @Test
    fun formatsDurationWithFrenchComma() {
        assertEquals("5,0 s", formatDuration(5_012, Locale.FRANCE))
        assertEquals("0,0 s", formatDuration(0, Locale.FRANCE))
    }

    @Test
    fun formatsDurationWithEnglishPoint() {
        assertEquals("5.0 s", formatDuration(5_012, Locale.US))
        assertEquals("0.0 s", formatDuration(0, Locale.US))
    }
}
