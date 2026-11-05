package tech.dmconsulting.voxbulle.recording

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One recording of the real-phone test (DMC-133, DMC-139): was the microphone
 * service in the foreground, length of the file, loudest sample.
 */
data class Trial(val number: Int, val foreground: Boolean, val durationMs: Long, val maxAmplitude: Int) {

    /** Pass rule of the test: service started, file >= 4 s, sound heard. */
    val passed: Boolean get() = foreground && durationMs >= 4_000 && maxAmplitude > 0

    fun encode(): String = "$number;$foreground;$durationMs;$maxAmplitude"

    fun label(): String =
        "Essai $number : ${if (foreground) "service OK" else "service KO"} · " +
            "${formatDuration(durationMs)} · max $maxAmplitude · ${if (passed) "réussi" else "échec"}"

    companion object {
        fun decode(line: String): Trial? {
            val parts = line.split(';')
            if (parts.size != 4) return null
            return Trial(
                number = parts[0].toIntOrNull() ?: return null,
                foreground = parts[1].toBooleanStrictOrNull() ?: return null,
                durationMs = parts[2].toLongOrNull() ?: return null,
                maxAmplitude = parts[3].toIntOrNull() ?: return null,
            )
        }
    }
}

/** Trials kept in SharedPreferences, shown on the home screen of debug builds. Main thread only. */
object TrialLog {
    private const val PREFS = "trials"
    private const val KEY = "lines"

    private val _trials = MutableStateFlow(emptyList<Trial>())
    val trials: StateFlow<List<Trial>> = _trials.asStateFlow()

    fun load(context: Context) {
        val text = prefs(context).getString(KEY, "").orEmpty()
        _trials.value = text.lines().mapNotNull(Trial::decode)
    }

    fun add(context: Context, foreground: Boolean, durationMs: Long, maxAmplitude: Int): Trial {
        load(context)
        val trial = Trial(_trials.value.size + 1, foreground, durationMs, maxAmplitude)
        save(context, _trials.value + trial)
        return trial
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, list: List<Trial>) {
        prefs(context).edit().putString(KEY, list.joinToString("\n") { it.encode() }).apply()
        _trials.value = list
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
