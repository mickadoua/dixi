package tech.dmconsulting.dixi.history

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InMemoryDictationHistoryTest {
    private fun dictation(i: Int) = Dictation("texte $i", Instant.ofEpochSecond(i.toLong()))

    @Test
    fun keepsTheTwentyNewestFirst() = runBlocking {
        val history = InMemoryDictationHistory()
        (1..25).forEach { history.add(dictation(it)) }
        val entries = history.entries.value
        assertEquals(DictationHistory.MAX_ENTRIES, entries.size)
        assertEquals(dictation(25), entries.first())
        assertEquals(dictation(6), entries.last())
    }

    @Test
    fun clearEmptiesTheHistory() = runBlocking {
        val history = InMemoryDictationHistory()
        history.add(dictation(1))
        history.clear()
        assertTrue(history.entries.value.isEmpty())
    }
}
