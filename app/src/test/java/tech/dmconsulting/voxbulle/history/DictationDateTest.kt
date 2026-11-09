package tech.dmconsulting.voxbulle.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** Labels and patterns as in res/values-fr and res/values. */
class DictationDateTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = LocalDateTime.of(2026, 11, 4, 10, 30).atZone(zone).toInstant()

    private val french = DictationDateFormat("Aujourd’hui", "Hier", "d MMM", "d MMM yyyy", "HH:mm", Locale.FRANCE)
    private val english = DictationDateFormat("Today", "Yesterday", "MMM d", "MMM d, yyyy", "h:mm a", Locale.US)

    private fun label(format: DictationDateFormat, year: Int, month: Int, day: Int, hour: Int, minute: Int) = formatDictationDate(
        createdAt = LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant(),
        now = now,
        zone = zone,
        format = format,
    )

    @Test
    fun today() = assertEquals("Aujourd’hui 09:12", label(french, 2026, 11, 4, 9, 12))

    @Test
    fun yesterdayEvenJustBeforeMidnight() = assertEquals("Hier 23:59", label(french, 2026, 11, 3, 23, 59))

    @Test
    fun olderThisYearHasNoYear() = assertEquals("2 nov. 17:40", label(french, 2026, 11, 2, 17, 40))

    @Test
    fun anotherYearShowsTheYear() = assertEquals("31 déc. 2025 08:05", label(french, 2025, 12, 31, 8, 5))

    @Test
    fun englishToday() = assertEquals("Today 9:12 AM", label(english, 2026, 11, 4, 9, 12))

    @Test
    fun englishYesterday() = assertEquals("Yesterday 11:59 PM", label(english, 2026, 11, 3, 23, 59))

    @Test
    fun englishOlderThisYear() = assertEquals("Nov 2 5:40 PM", label(english, 2026, 11, 2, 17, 40))

    @Test
    fun englishAnotherYear() = assertEquals("Dec 31, 2025 8:05 AM", label(english, 2025, 12, 31, 8, 5))
}
