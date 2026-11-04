package tech.dmconsulting.voxbulle.poc

import android.content.Context

/** One bubble tap: was the mic service in the foreground, file length, loudest sample. */
data class Trial(val number: Int, val foreground: Boolean, val durationMs: Long, val maxAmplitude: Int) {

    /** Pass rule of the real-phone test: service started, file >= 4 s, sound heard. */
    val passed: Boolean get() = foreground && durationMs >= 4_000 && maxAmplitude > 0

    fun encode(): String = "$number;$foreground;$durationMs;$maxAmplitude"

    fun label(): String =
        "Essai $number : service ${if (foreground) "OK" else "KO"}, " +
            "${formatDuration(durationMs)}, maxAmplitude $maxAmplitude → ${if (passed) "OK" else "ÉCHEC"}"

    companion object {
        fun decode(line: String): Trial? {
            val parts = line.split(';')
            if (parts.size != 4) return null
            return Trial(
                parts[0].toIntOrNull() ?: return null,
                parts[1].toBooleanStrictOrNull() ?: return null,
                parts[2].toLongOrNull() ?: return null,
                parts[3].toIntOrNull() ?: return null,
            )
        }
    }
}

/** Keeps the trials on the phone so they can be read without adb. */
object TrialStore {
    private const val PREFS = "trials"
    private const val KEY = "lines"

    fun all(context: Context): List<Trial> =
        prefs(context).getString(KEY, "").orEmpty().lines().mapNotNull(Trial::decode)

    fun add(context: Context, foreground: Boolean, durationMs: Long, maxAmplitude: Int): Trial {
        val trials = all(context)
        val trial = Trial(trials.size + 1, foreground, durationMs, maxAmplitude)
        prefs(context).edit()
            .putString(KEY, (trials + trial).joinToString("\n") { it.encode() })
            .apply()
        return trial
    }

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
