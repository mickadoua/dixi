package tech.dmconsulting.voxbulle.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Runs [MistralProbe] in the installed APK and logs its report under [TAG]. Debug and probe
 * builds only. Each line starts with the `run` extra, so scripts/mistral-probe.sh reads only
 * the lines of its own run (the emulator clock is not reliable enough to filter by time).
 */
class MistralProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val workDir = context.cacheDir
        val run = intent.getStringExtra("run")?.let { "$it " }.orEmpty()
        thread(name = "mistral-probe") {
            try {
                runBlocking { MistralProbe.run(workDir) }.forEach { Log.i(TAG, run + it) }
            } catch (e: Throwable) {
                Log.e(TAG, "$run${MistralProbe.FAIL}: crash", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "VoxbulleProbe"
    }
}
