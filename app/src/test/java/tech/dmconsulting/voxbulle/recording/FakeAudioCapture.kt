package tech.dmconsulting.voxbulle.recording

import java.io.File

/** Writes a small file instead of recording. Amplitude and duration are set by the test. */
class FakeAudioCapture(
    var amplitude: Int = 5_000,
    var fileDurationMs: Long = 0,
    private val failOnStart: Boolean = false,
) : AudioCapture {
    var started = false
    var released = false

    override fun start(file: File) {
        if (failOnStart) throw IllegalStateException("mic busy")
        file.writeBytes(ByteArray(64))
        started = true
    }

    override fun sampleMaxAmplitude(): Int = amplitude

    override fun stop() {
        started = false
    }

    override fun release() {
        released = true
    }

    override fun durationMs(file: File): Long = fileDurationMs
}

class FakeClock(var now: Long = 1_000) : Clock {
    override fun nowMs(): Long = now
}
