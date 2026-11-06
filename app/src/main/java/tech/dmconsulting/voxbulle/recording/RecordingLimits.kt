package tech.dmconsulting.voxbulle.recording

/**
 * Time limit and silence rule of a dictation (brief § 5, mockups screen 3).
 *
 * [minAmplitude] is compared with MediaRecorder.getMaxAmplitude (0 to 32767).
 * 250 is about -42 dBFS: a quiet room stays below, speech at arm's length is
 * well above. To check on a real phone and adjust if needed.
 */
data class RecordingLimits(
    val warnAtMs: Long = 270_000,
    val maxMs: Long = 300_000,
    val minDurationMs: Long = 500,
    val minAmplitude: Int = 250,
) {
    fun isNothingHeard(durationMs: Long, maxAmplitude: Int): Boolean = durationMs < minDurationMs || maxAmplitude < minAmplitude

    companion object {
        val DEFAULT = RecordingLimits()

        /** Set only by the debug build (DebugRecordingReceiver), to test the limit in seconds. */
        @Volatile
        var debugOverride: RecordingLimits? = null

        fun current(): RecordingLimits = debugOverride ?: DEFAULT
    }
}
