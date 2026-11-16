package tech.dmconsulting.dixi.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import tech.dmconsulting.dixi.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Day labels and java.time patterns of the phone language (res/values*, history_date_*). */
data class DictationDateFormat(
    val today: String,
    val yesterday: String,
    val dayMonth: String,
    val dayMonthYear: String,
    val time: String,
    val locale: Locale,
)

/**
 * Date line of a history entry (screen 9): « Today 9:12 AM », « Yesterday 5:40 PM »,
 * « Nov 3 9:12 AM » in English, « Aujourd’hui 09:12 », « 3 nov. 09:12 » in French,
 * and the year only when it is not the current one.
 */
fun formatDictationDate(createdAt: Instant, now: Instant, zone: ZoneId, format: DictationDateFormat): String {
    val at = createdAt.atZone(zone)
    val day = at.toLocalDate()
    val currentDay = now.atZone(zone).toLocalDate()
    val time = at.format(DateTimeFormatter.ofPattern(format.time, format.locale))
    val dayLabel = when {
        day == currentDay -> format.today
        day == currentDay.minusDays(1) -> format.yesterday
        day.year == currentDay.year -> at.format(DateTimeFormatter.ofPattern(format.dayMonth, format.locale))
        else -> at.format(DateTimeFormatter.ofPattern(format.dayMonthYear, format.locale))
    }
    return "$dayLabel $time"
}

/** [formatDictationDate] for now, in the phone's time zone and language. */
@Composable
fun dictationDateLabel(createdAt: Instant): String = formatDictationDate(
    createdAt = createdAt,
    now = Instant.now(),
    zone = ZoneId.systemDefault(),
    format = DictationDateFormat(
        today = stringResource(R.string.history_today),
        yesterday = stringResource(R.string.history_yesterday),
        dayMonth = stringResource(R.string.history_date_day_month),
        dayMonthYear = stringResource(R.string.history_date_day_month_year),
        time = stringResource(R.string.history_date_time),
        locale = LocalConfiguration.current.locales[0],
    ),
)
