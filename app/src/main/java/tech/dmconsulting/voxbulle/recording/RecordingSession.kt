package tech.dmconsulting.voxbulle.recording

import java.io.File

/**
 * One dictation, from start to stop: drives [capture], watches the time limit
 * and sorts out recordings with nothing heard. Not thread-safe: call it from
 * one thread (the main thread in the app).
 */
class RecordingSession(
    private val capture: AudioCapture,
    clock: Clock,
    private val limits: RecordingLimits,
) {
    private val timer = RecordingTimer(clock, limits)
    private var file: File? = null
    private var maxAmplitude = 0

    val isRecording: Boolean get() = file != null

    /** Returns null when recording, or the error. On error no file is left behind. */
    fun start(file: File): RecordingError? {
        check(!isRecording) { "already recording" }
        return try {
            capture.start(file)
            this.file = file
            maxAmplitude = 0
            timer.start()
            null
        } catch (e: Exception) {
            capture.release()
            file.delete()
            RecordingError.StartFailed
        }
    }

    fun elapsedMs(): Long = timer.elapsedMs()

    /**
     * Voice level now, 0 to 32767, for the bubble halo. Reads the microphone
     * through the session so the peak kept for « nothing heard » sees it too.
     */
    fun level(): Int = if (isRecording) sampleAmplitude() else 0

    /** Call every 250 ms or so while recording. */
    fun tick(): TimerEvent? {
        if (!isRecording) return null
        sampleAmplitude()
        return timer.poll()
    }

    /**
     * Stops and returns the file to transcribe. A recording with nothing heard
     * is deleted here and gives [RecordingError.NothingHeard].
     */
    fun stop(): RecordingOutcome {
        val recorded = file ?: return RecordingOutcome.Failure(RecordingError.NothingHeard)
        sampleAmplitude()
        val elapsed = timer.elapsedMs()
        capture.stop()
        file = null
        // The file can be shorter than the wall clock: the microphone takes time to start.
        val duration = capture.durationMs(recorded).takeIf { it > 0 } ?: elapsed
        if (limits.isNothingHeard(duration, maxAmplitude)) {
            recorded.delete()
            return RecordingOutcome.Failure(RecordingError.NothingHeard)
        }
        return RecordingOutcome.Success(RecordingResult(recorded, duration, maxAmplitude))
    }

    /** Stops without a result (service destroyed) and deletes the file. */
    fun cancel() {
        val recorded = file ?: return
        capture.stop()
        file = null
        recorded.delete()
    }

    /** MediaRecorder resets its peak on each read: every read goes through here. */
    private fun sampleAmplitude(): Int {
        val sample = capture.sampleMaxAmplitude()
        maxAmplitude = maxOf(maxAmplitude, sample)
        return sample
    }
}
