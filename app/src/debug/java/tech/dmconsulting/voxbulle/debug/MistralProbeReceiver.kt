package tech.dmconsulting.voxbulle.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** Runs [MistralProbe] in the installed APK and logs its report under [TAG]. Debug builds only. */
class MistralProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val workDir = context.cacheDir
        thread(name = "mistral-probe") {
            try {
                runBlocking { MistralProbe.run(workDir) }.forEach { Log.i(TAG, it) }
            } catch (e: Throwable) {
                Log.e(TAG, "${MistralProbe.FAIL}: crash", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "VoxbulleProbe"
    }
}
