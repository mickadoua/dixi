package tech.dmconsulting.voxbulle.recording

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingSessionTest {
    @get:Rule
    val cacheDir = TemporaryFolder()

    private val clock = FakeClock()
    private val capture = FakeAudioCapture()
    private val session = RecordingSession(capture, clock, RecordingLimits.DEFAULT)

    private fun file() = File(cacheDir.root, "dictation-1.m4a")

    @Test
    fun manualStopGivesFileDurationAndAmplitude() {
        assertNull(session.start(file()))
        clock.now += 3_000
        capture.amplitude = 1_200
        session.tick()
        capture.amplitude = 800
        capture.fileDurationMs = 2_700

        val outcome = session.stop() as RecordingOutcome.Success
        assertEquals(RecordingResult(file(), 2_700, 1_200), outcome.result)
        assertTrue(file().exists())
        assertFalse(session.isRecording)
    }

    @Test
    fun limitAt5minStillGivesTheFile() {
        session.start(file())
        var event: TimerEvent? = null
        while (event != TimerEvent.LimitReached) {
            clock.now += 250
            event = session.tick() ?: event
        }
        assertEquals(300_000, session.elapsedMs())
        val outcome = session.stop() as RecordingOutcome.Success
        assertEquals(300_000, outcome.result.durationMs)
        assertTrue(outcome.result.file.exists())
    }

    @Test
    fun warningComesBeforeLimit() {
        session.start(file())
        val events = mutableListOf<TimerEvent>()
        repeat(300_000 / 250) {
            clock.now += 250
            session.tick()?.let(events::add)
        }
        assertEquals(listOf(TimerEvent.Warning, TimerEvent.LimitReached), events)
    }

    @Test
    fun underHalfSecondIsNothingHeardAndDeleted() {
        session.start(file())
        clock.now += 499
        assertEquals(RecordingOutcome.Failure(RecordingError.NothingHeard), session.stop())
        assertFalse(file().exists())
    }

    @Test
    fun quietRecordingIsNothingHeardAndDeleted() {
        capture.amplitude = RecordingLimits.DEFAULT.minAmplitude - 1
        session.start(file())
        clock.now += 4_000
        session.tick()
        assertEquals(RecordingOutcome.Failure(RecordingError.NothingHeard), session.stop())
        assertFalse(file().exists())
        // The trial log still sees what was recorded.
        assertEquals(4_000, session.lastDurationMs)
        assertEquals(RecordingLimits.DEFAULT.minAmplitude - 1, session.lastMaxAmplitude)
    }

    @Test
    fun amplitudeAtThresholdIsHeard() {
        capture.amplitude = RecordingLimits.DEFAULT.minAmplitude
        session.start(file())
        clock.now += 1_000
        assertTrue(session.stop() is RecordingOutcome.Success)
    }

    @Test
    fun peakReadByTheBubbleLevelStillCounts() {
        session.start(file())
        clock.now += 2_000
        capture.amplitude = 1_200
        assertEquals(1_200, session.level())
        capture.amplitude = 0
        session.tick()

        val outcome = session.stop() as RecordingOutcome.Success
        assertEquals(1_200, outcome.result.maxAmplitude)
    }

    @Test
    fun levelIsZeroWhenNotRecording() {
        assertEquals(0, session.level())
    }

    @Test
    fun microphoneThatFailsGivesStartFailedAndNoFile() {
        val failing = RecordingSession(FakeAudioCapture(failOnStart = true), clock, RecordingLimits.DEFAULT)
        assertEquals(RecordingError.StartFailed, failing.start(file()))
        assertFalse(failing.isRecording)
        assertFalse(file().exists())
    }

    @Test
    fun cancelDeletesTheFile() {
        session.start(file())
        clock.now += 2_000
        session.cancel()
        assertFalse(file().exists())
        assertFalse(session.isRecording)
    }

    @Test
    fun leftoverAudioIsDeletedButNotOtherFiles() {
        File(cacheDir.root, "dictation-42.m4a").writeText("x")
        val other = File(cacheDir.root, "image.png").apply { writeText("x") }
        Recorder.deleteLeftoverAudio(cacheDir.root)
        assertEquals(listOf(other), cacheDir.root.listFiles()!!.toList())
    }
}
