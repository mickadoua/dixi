package tech.dmconsulting.dixi.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleWaveTest {
    @Test
    fun theWaveGoesFromTheDiscEdgeToFourteenDp() {
        assertEquals(0f, waveOffsetDp(0f), 0f)
        assertEquals(WAVE_MAX_DP, waveOffsetDp(1f), 0f)
        assertEquals("out of range is clamped", WAVE_MAX_DP, waveOffsetDp(3f), 0f)
        assertEquals(0f, waveOffsetDp(-1f), 0f)
    }

    @Test
    fun aQuietVoiceStillMovesIt() {
        // 4 % of full scale, a normal voice at arm's length: 20 % of the way.
        assertEquals(2.8f, waveOffsetDp(0.04f), 0.01f)
    }

    @Test
    fun smoothingReachesMostOfTheWayInAboutEightyMs() {
        assertEquals(0f, smoothWave(0f, 10f, 0), 0f)
        val after80 = smoothWave(0f, 10f, 80)
        assertTrue(after80 in 6f..7f)
        assertEquals(10f, smoothWave(0f, 10f, 2_000), 0.01f)
    }

    @Test
    fun silenceBreathesSlowlyAndFadesWhenTheVoiceRises() {
        assertEquals(0f, silencePulseDp(0, 0f), 0.001f)
        assertEquals(WAVE_PULSE_DP, silencePulseDp(WAVE_PULSE_MS / 2, 0f), 0.001f)
        assertEquals(0f, silencePulseDp(WAVE_PULSE_MS, 0f), 0.001f)
        assertEquals("a voice replaces the breath", 0f, silencePulseDp(WAVE_PULSE_MS / 2, WAVE_PULSE_DP), 0.001f)
    }
}
