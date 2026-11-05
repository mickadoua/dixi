package tech.dmconsulting.voxbulle.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.dmconsulting.voxbulle.recording.Recorder.Companion.foregroundDecision
import tech.dmconsulting.voxbulle.recording.Recorder.ForegroundDecision

class ForegroundDecisionTest {

    @Test
    fun currentStartOpensTheMicrophone() {
        assertEquals(ForegroundDecision.Open, foregroundDecision(1, 1, RecorderState.Starting))
    }

    @Test
    fun stoppedBeforeTheServiceWasReadyStopsTheService() {
        assertEquals(ForegroundDecision.StopService, foregroundDecision(1, 1, RecorderState.Idle))
        assertEquals(ForegroundDecision.StopService, foregroundDecision(1, 2, RecorderState.Idle))
    }

    /** Tap to start, tap to stop while Starting, tap to start again, then both service commands arrive. */
    @Test
    fun staleCommandDoesNotStopTheNewerRecording() {
        assertEquals(ForegroundDecision.Ignore, foregroundDecision(1, 2, RecorderState.Starting))
        assertEquals(ForegroundDecision.Open, foregroundDecision(2, 2, RecorderState.Starting))
        assertEquals(ForegroundDecision.Ignore, foregroundDecision(1, 2, RecorderState.Recording))
        assertEquals(ForegroundDecision.Ignore, foregroundDecision(1, 2, RecorderState.Warning))
    }

    @Test
    fun repeatedCommandWhileRecordingIsIgnored() {
        assertEquals(ForegroundDecision.Ignore, foregroundDecision(2, 2, RecorderState.Recording))
    }
}
