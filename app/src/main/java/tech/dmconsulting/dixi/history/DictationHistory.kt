package tech.dmconsulting.dixi.history

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** One finished dictation. Text only: the audio is never kept. */
data class Dictation(val text: String, val createdAt: Instant)

/** The last dictations, newest first, at most [MAX_ENTRIES] (screen 9). */
interface DictationHistory {
    val entries: Flow<List<Dictation>>

    suspend fun add(dictation: Dictation)

    suspend fun clear()

    companion object {
        const val MAX_ENTRIES = 20
    }
}
