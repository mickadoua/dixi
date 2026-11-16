package tech.dmconsulting.dixi.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingTimerTest {
    private val clock = FakeClock()
    private val timer = RecordingTimer(clock, RecordingLimits.DEFAULT).apply { start() }

    @Test
    fun nothingBefore4min30() {
        clock.now += 269_999
        assertNull(timer.poll())
        assertEquals(269_999, timer.elapsedMs())
    }

    @Test
    fun warnsOnceAt4min30() {
        clock.now += 270_000
        assertEquals(TimerEvent.Warning, timer.poll())
        clock.now += 10_000
        assertNull(timer.poll())
    }

    @Test
    fun stopsAt5min() {
        clock.now += 270_000
        timer.poll()
        clock.now += 29_999
        assertNull(timer.poll())
        clock.now += 1
        assertEquals(TimerEvent.LimitReached, timer.poll())
    }

    @Test
    fun restartResetsTheWarning() {
        clock.now += 280_000
        assertEquals(TimerEvent.Warning, timer.poll())
        timer.start()
        clock.now += 270_000
        assertEquals(TimerEvent.Warning, timer.poll())
    }
}
