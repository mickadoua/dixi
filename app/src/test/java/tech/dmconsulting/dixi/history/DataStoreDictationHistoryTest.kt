package tech.dmconsulting.dixi.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant

class DataStoreDictationHistoryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun dictation(i: Int) = Dictation("texte $i", Instant.ofEpochMilli(1_762_000_000_000L + i))

    /** Runs [block] on a fresh store over [file], then stops it like a killed process would. */
    private fun <T> withHistory(file: File, block: suspend (DataStoreDictationHistory) -> T): T = runBlocking {
        val job = Job()
        try {
            block(DataStoreDictationHistory.create(file, CoroutineScope(Dispatchers.IO + job)))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun twentyFirstEntryDropsTheOldest() {
        val file = folder.newFile("history.json").apply { delete() }
        val entries = withHistory(file) { history ->
            (1..21).forEach { history.add(dictation(it)) }
            history.entries.first()
        }
        assertEquals(DictationHistory.MAX_ENTRIES, entries.size)
        assertEquals(dictation(21), entries.first())
        assertEquals(dictation(2), entries.last())
    }

    @Test
    fun clearEmptiesTheHistory() {
        val file = folder.newFile("history.json").apply { delete() }
        val entries = withHistory(file) { history ->
            history.add(dictation(1))
            history.add(dictation(2))
            history.clear()
            history.entries.first()
        }
        assertTrue(entries.isEmpty())
        assertTrue(withHistory(file) { it.entries.first() }.isEmpty())
    }

    @Test
    fun entriesSurviveARestart() {
        val file = folder.newFile("history.json").apply { delete() }
        withHistory(file) { history -> (1..3).forEach { history.add(dictation(it)) } }

        val reloaded = withHistory(file) { it.entries.first() }

        assertEquals(listOf(dictation(3), dictation(2), dictation(1)), reloaded)
    }

    @Test
    fun keepsTextAndDateOnly() {
        val file = folder.newFile("history.json").apply { delete() }
        withHistory(file) { it.add(Dictation("Bonjour « Claire », à 14 h\nmerci", Instant.ofEpochMilli(42))) }
        assertEquals("""[{"text":"Bonjour « Claire », à 14 h\nmerci","createdAtMillis":42}]""", file.readText())
    }

    @Test
    fun unreadableFileStartsEmpty() {
        val file = folder.newFile("history.json").apply { writeText("pas du json") }
        assertTrue(withHistory(file) { it.entries.first() }.isEmpty())
    }

    @Test
    fun missingFileStartsEmpty() {
        val file = folder.newFile("history.json").apply { delete() }
        assertTrue(withHistory(file) { it.entries.first() }.isEmpty())
    }

    @Test
    fun zeroByteFileReadsAsEmptyHistory() {
        val read = runBlocking { DictationListSerializer.readFrom(ByteArrayInputStream(ByteArray(0))) }
        assertTrue(read.isEmpty())
    }

    @Test
    fun zeroByteFileStartsEmpty() {
        val file = folder.newFile("history.json")
        assertEquals(0L, file.length())
        assertTrue(withHistory(file) { it.entries.first() }.isEmpty())
    }
}
