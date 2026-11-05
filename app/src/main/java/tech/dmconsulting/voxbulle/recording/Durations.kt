package tech.dmconsulting.voxbulle.recording

import java.util.Locale

/** "5,0 s" style duration, French decimal comma. */
fun formatDuration(millis: Long): String =
    String.format(Locale.FRANCE, "%.1f s", millis / 1000.0)
