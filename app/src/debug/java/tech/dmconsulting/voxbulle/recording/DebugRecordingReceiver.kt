package tech.dmconsulting.voxbulle.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import tech.dmconsulting.voxbulle.bubble.BubbleService

/**
 * Debug builds only: shortens the recording limits to test them in seconds,
 * and can keep the « Texte copié » notification longer (copied_timeout_ms).
 * Kept in memory until the process ends. No extra resets to the defaults.
 *
 *   adb shell am broadcast -n tech.dmconsulting.dixi/tech.dmconsulting.voxbulle.recording.DebugRecordingReceiver \
 *       --el max_ms 10000 --el warn_ms 7000 --ei min_amplitude 0 --el copied_timeout_ms 60000
 */
class DebugRecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        RecordingLimits.debugOverride = if (extras == null || extras.isEmpty) {
            null
        } else {
            val d = RecordingLimits.DEFAULT
            RecordingLimits(
                warnAtMs = intent.getLongExtra("warn_ms", d.warnAtMs),
                maxMs = intent.getLongExtra("max_ms", d.maxMs),
                minDurationMs = intent.getLongExtra("min_duration_ms", d.minDurationMs),
                minAmplitude = intent.getIntExtra("min_amplitude", d.minAmplitude),
            )
        }
        BubbleService.debugCopiedTimeoutMs = extras?.getLong("copied_timeout_ms")?.takeIf { it > 0 }
        Log.i(
            MicrophoneRecorder.TAG,
            "debug limits=${RecordingLimits.current()} copiedTimeoutMs=${BubbleService.debugCopiedTimeoutMs}",
        )
    }
}
