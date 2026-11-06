package tech.dmconsulting.voxbulle.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** History lost when the process dies. Test fake; the app uses [DataStoreDictationHistory]. */
class InMemoryDictationHistory : DictationHistory {
    private val state = MutableStateFlow(emptyList<Dictation>())

    override val entries: StateFlow<List<Dictation>> = state

    override suspend fun add(dictation: Dictation) = state.update { (listOf(dictation) + it).take(DictationHistory.MAX_ENTRIES) }

    override suspend fun clear() {
        state.value = emptyList()
    }
}
