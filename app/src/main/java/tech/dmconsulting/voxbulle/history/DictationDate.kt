package tech.dmconsulting.voxbulle.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date line of a history entry (screen 9): « Aujourd’hui 09:12 », « Hier 17:40 »,
 * « 3 nov. 09:12 », and the year only when it is not the current one.
 */
fun formatDictationDate(
    createdAt: Instant,
    now: Instant,
    zone: ZoneId,
    today: String,
    yesterday: String,
    locale: Locale = Locale.FRANCE,
): String {
    val at = createdAt.atZone(zone)
    val day = at.toLocalDate()
    val currentDay = now.atZone(zone).toLocalDate()
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    val dayLabel = when {
        day == currentDay -> today
        day == currentDay.minusDays(1) -> yesterday
        day.year == currentDay.year -> at.format(DateTimeFormatter.ofPattern("d MMM", locale))
        else -> at.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }
    return "$dayLabel $time"
}
