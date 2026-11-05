package tech.dmconsulting.voxbulle.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialTest {
    @Test
    fun passesWithServiceFourSecondsAndSound() {
        assertTrue(Trial(1, foreground = true, durationMs = 4_000, maxAmplitude = 1).passed)
    }

    @Test
    fun failsWithoutServiceOrShortFileOrSilence() {
        assertFalse(Trial(1, foreground = false, durationMs = 5_000, maxAmplitude = 900).passed)
        assertFalse(Trial(2, foreground = true, durationMs = 3_999, maxAmplitude = 900).passed)
        assertFalse(Trial(3, foreground = true, durationMs = 5_000, maxAmplitude = 0).passed)
    }

    @Test
    fun encodeThenDecodeGivesTheSameTrial() {
        val trial = Trial(7, foreground = true, durationMs = 5_120, maxAmplitude = 8_431)
        assertEquals(trial, Trial.decode(trial.encode()))
        assertNull(Trial.decode(""))
        assertNull(Trial.decode("1;yes;5000;10"))
    }

    @Test
    fun labelShowsWhatMickaReports() {
        assertEquals(
            "Essai 3 : service OK · 5,1 s · max 8431 · réussi",
            Trial(3, foreground = true, durationMs = 5_120, maxAmplitude = 8_431).label(),
        )
    }
}
