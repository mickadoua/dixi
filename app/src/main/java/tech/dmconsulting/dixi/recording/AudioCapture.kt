package tech.dmconsulting.dixi.recording

import java.io.File

/** The microphone, behind an interface so [RecordingSession] runs in unit tests. */
interface AudioCapture {
    /** Starts recording into [file]. Throws if the microphone cannot start. */
    fun start(file: File)

    /** Highest amplitude (0 to 32767) since the previous call. */
    fun sampleMaxAmplitude(): Int

    fun stop()

    fun release()

    /** Real length of the recorded [file] in ms, 0 if unknown. */
    fun durationMs(file: File): Long
}
