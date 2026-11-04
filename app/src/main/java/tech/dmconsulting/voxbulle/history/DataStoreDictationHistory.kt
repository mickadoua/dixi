package tech.dmconsulting.voxbulle.history

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** History kept in a JSON file on the phone, so it survives the app being stopped. Text only. */
class DataStoreDictationHistory(private val store: DataStore<List<Dictation>>) : DictationHistory {

    override val entries: Flow<List<Dictation>> = store.data

    override suspend fun add(dictation: Dictation) {
        store.updateData { (listOf(dictation) + it).take(DictationHistory.MAX_ENTRIES) }
    }

    override suspend fun clear() {
        store.updateData { emptyList() }
    }

    companion object {
        /** One instance per [file] and per process: DataStore forbids two stores on the same file. */
        fun create(
            file: File,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ) = DataStoreDictationHistory(
            DataStoreFactory.create(
                serializer = DictationListSerializer,
                // An unreadable file must not lock the user out of the app: start again empty.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}

@Serializable
private data class StoredDictation(val text: String, val createdAtMillis: Long)

internal object DictationListSerializer : Serializer<List<Dictation>> {
    private val format = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(StoredDictation.serializer())

    override val defaultValue: List<Dictation> = emptyList()

    override suspend fun readFrom(input: InputStream): List<Dictation> {
        val bytes = input.readBytes()
        // A 0-byte file (write cut short) is an empty history, not a corrupt one.
        if (bytes.isEmpty()) return defaultValue
        return try {
            format.decodeFromString(listSerializer, bytes.decodeToString())
                .map { Dictation(it.text, Instant.ofEpochMilli(it.createdAtMillis)) }
        } catch (e: SerializationException) {
            throw CorruptionException("Unreadable dictation history", e)
        }
    }

    override suspend fun writeTo(t: List<Dictation>, output: OutputStream) {
        val stored = t.map { StoredDictation(it.text, it.createdAt.toEpochMilli()) }
        output.write(format.encodeToString(listSerializer, stored).encodeToByteArray())
    }
}
