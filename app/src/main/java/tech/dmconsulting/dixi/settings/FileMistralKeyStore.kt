package tech.dmconsulting.dixi.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/** Seals the key before it touches the disk. On the phone: [KeystoreKeyCipher]. */
interface KeyCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * The keys in [dir] (the app puts it in `noBackupFilesDir`, excluded from backups and device
 * transfer by `res/xml/data_extraction_rules.xml`): each key encrypted by [cipher] in
 * `<id>.bin`, and the [KeyRing] (names, active key, refusals: no key) in `keys.json`.
 * The plain active key is kept in memory once read, never written anywhere else.
 *
 * [legacyFile]: the single key of the versions before DMC-766. Moved into [dir] at the first
 * start, named [legacyName], so nobody has to paste their key again.
 */
class FileMistralKeyStore(
    private val dir: File,
    private val cipher: KeyCipher,
    private val legacyFile: File? = null,
    private val legacyName: String = "Key 1",
) : MistralKeyStore {
    private val index = File(dir, INDEX)
    private val lock = Mutex()

    @Volatile private var ring: KeyRing = KeyRing()

    /** The decrypted active key and its id, so a change of active key drops it. */
    @Volatile private var cached: Pair<String, String>? = null

    private val state = MutableStateFlow(KeyStatus.None)
    private val list = MutableStateFlow<List<KeyEntry>>(emptyList())

    init {
        // A small JSON file once per process (plus a rename the first time): fine on the main thread.
        publish(load())
    }

    override val status: StateFlow<KeyStatus> = state
    override val entries: StateFlow<List<KeyEntry>> = list

    override suspend fun key(): String? {
        val active = ring.active ?: return null
        cached?.let { (id, key) -> if (id == active) return key }
        return lock.withLock { withContext(Dispatchers.IO) { readActive() } }
    }

    override suspend fun add(name: String, key: String) = lock.withLock {
        val id = KeyRing.newId()
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val file = keyFile(id)
            try {
                writeAtomically(file, cipher.encrypt(key.toByteArray(Charsets.UTF_8)))
                save(ring.add(id, name))
            } catch (e: IOException) {
                file.delete()
                throw e
            }
        }
        cached = id to key
    }

    override suspend fun activate(id: String) = lock.withLock {
        withContext(Dispatchers.IO) { save(ring.activate(id)) }
    }

    override suspend fun delete(id: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            // The index first: a key file left alone is removed at the next start.
            save(ring.remove(id))
            keyFile(id).delete()
        }
        if (cached?.first == id) cached = null
    }

    override suspend fun markRejected() = lock.withLock {
        if (ring.active != null) withContext(Dispatchers.IO) { save(ring.markActive(rejected = true)) }
    }

    override suspend fun markAccepted() = lock.withLock {
        if (ring.active != null) withContext(Dispatchers.IO) { save(ring.markActive(rejected = false)) }
    }

    override fun toString(): String = "FileMistralKeyStore(${ring.keys.size} keys)"

    /**
     * The active key, decrypted. A key that cannot be decrypted (Keystore key gone, file
     * damaged) is useless: it is deleted and the next one takes over, so the screens never
     * show a key that cannot work.
     */
    private fun readActive(): String? {
        while (true) {
            val id = ring.active ?: return null
            cached?.let { (cachedId, key) -> if (cachedId == id) return key }
            val key = try {
                cipher.decrypt(keyFile(id).readBytes()).toString(Charsets.UTF_8)
            } catch (e: GeneralSecurityException) {
                null
            } catch (e: ProviderException) {
                null
            } catch (e: IOException) {
                null
            }
            if (key != null) {
                cached = id to key
                return key
            }
            keyFile(id).delete()
            save(ring.remove(id))
        }
    }

    private fun load(): KeyRing {
        val loaded = when {
            index.isFile -> try {
                Json.decodeFromString<KeyRing>(index.readText())
            } catch (e: SerializationException) {
                KeyRing()
            } catch (e: IllegalArgumentException) {
                KeyRing()
            } catch (e: IOException) {
                KeyRing()
            }

            legacyFile?.isFile == true -> migrate(legacyFile)

            else -> KeyRing()
        }
        // Keys whose file is gone are dropped, files no key points to are deleted.
        val ring = loaded.keys.filterNot { keyFile(it.id).isFile }.fold(loaded) { r, k -> r.remove(k.id) }
        val known = ring.keys.map { it.id + KEY_SUFFIX }.toSet()
        dir.listFiles()?.filter { it.name.endsWith(KEY_SUFFIX) && it.name !in known }?.forEach { it.delete() }
        if (ring != loaded) runCatching { write(ring) }
        return ring
    }

    private fun migrate(legacy: File): KeyRing {
        val marker = File(legacy.parentFile, legacy.name + ".rejected")
        val id = KeyRing.newId()
        dir.mkdirs()
        if (!legacy.renameTo(keyFile(id))) return KeyRing()
        val ring = KeyRing().add(id, legacyName).markActive(rejected = marker.isFile)
        return try {
            write(ring)
            marker.delete()
            ring
        } catch (e: IOException) {
            // Put it back: the next start tries again.
            keyFile(id).renameTo(legacy)
            KeyRing()
        }
    }

    private fun save(next: KeyRing) {
        write(next)
        publish(next)
    }

    private fun write(next: KeyRing) {
        dir.mkdirs()
        writeAtomically(index, Json.encodeToString(KeyRing.serializer(), next).toByteArray(Charsets.UTF_8))
    }

    private fun publish(next: KeyRing) {
        ring = next
        state.value = next.status
        list.value = next.entries
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not write ${file.name}")
        }
    }

    private fun keyFile(id: String) = File(dir, id + KEY_SUFFIX)

    private companion object {
        const val INDEX = "keys.json"
        const val KEY_SUFFIX = ".bin"
    }
}
