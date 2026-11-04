package tech.dmconsulting.voxbulle.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialTest {
    @Test
    fun passesOnlyWithServiceLengthAndSound() {
        assertTrue(Trial(1, true, 4_000, 1).passed)
        assertFalse(Trial(1, false, 5_000, 900).passed)
        assertFalse(Trial(1, true, 3_999, 900).passed)
        assertFalse(Trial(1, true, 5_000, 0).passed)
    }

    @Test
    fun roundTripsThroughStorage() {
        val trial = Trial(7, true, 4_870, 12_345)
        assertEquals(trial, Trial.decode(trial.encode()))
        assertNull(Trial.decode(""))
    }

    @Test
    fun labelShowsAllMeasures() {
        assertEquals(
            "Essai 3 : service OK, 4,9 s, maxAmplitude 0 → ÉCHEC",
            Trial(3, true, 4_870, 0).label(),
        )
    }
}
