package tech.dmconsulting.voxbulle.history

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DictationDateTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = LocalDateTime.of(2026, 11, 4, 10, 30).atZone(zone).toInstant()

    private fun label(year: Int, month: Int, day: Int, hour: Int, minute: Int) = formatDictationDate(
        createdAt = LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant(),
        now = now,
        zone = zone,
        today = "Aujourd’hui",
        yesterday = "Hier",
    )

    @Test
    fun today() = assertEquals("Aujourd’hui 09:12", label(2026, 11, 4, 9, 12))

    @Test
    fun yesterdayEvenJustBeforeMidnight() = assertEquals("Hier 23:59", label(2026, 11, 3, 23, 59))

    @Test
    fun olderThisYearHasNoYear() = assertEquals("2 nov. 17:40", label(2026, 11, 2, 17, 40))

    @Test
    fun anotherYearShowsTheYear() = assertEquals("31 déc. 2025 08:05", label(2025, 12, 31, 8, 5))
}
