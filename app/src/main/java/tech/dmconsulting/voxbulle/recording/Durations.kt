package tech.dmconsulting.voxbulle.recording

import java.util.Locale

/** "5.0 s" style duration, with the decimal separator of [locale] ("5,0 s" in French). */
fun formatDuration(millis: Long, locale: Locale): String = String.format(locale, "%.1f s", millis / 1000.0)
