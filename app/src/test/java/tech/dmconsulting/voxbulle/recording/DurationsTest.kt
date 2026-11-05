package tech.dmconsulting.voxbulle.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationsTest {
    @Test
    fun formatsDurationWithFrenchComma() {
        assertEquals("5,0 s", formatDuration(5_012))
        assertEquals("0,0 s", formatDuration(0))
    }
}
