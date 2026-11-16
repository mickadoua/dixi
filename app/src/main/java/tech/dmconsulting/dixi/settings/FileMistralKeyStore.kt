package tech.dmconsulting.dixi.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * The key, encrypted by [cipher], in [file] (the app puts it in `noBackupFilesDir`,
 * excluded from backups and device transfer by `res/xml/data_extraction_rules.xml`).
 * A refusal by Mistral is an empty marker file next to it. The plain key is kept in
 * memory once read, never written anywhere else.
 */
class FileMistralKeyStore(
    private val file: File,
    private val cipher: KeyCipher,
) : MistralKeyStore {
    private val rejectedMarker = File(file.parentFile, file.name + ".rejected")
    private val lock = Mutex()

    @Volatile private var cached: String? = null

    // Two file checks, once per process: cheap enough for the main thread.
    private val state = MutableStateFlow(
        when {
            !file.isFile -> KeyStatus.None
            rejectedMarker.isFile -> KeyStatus.Rejected
            else -> KeyStatus.Saved
        },
    )

    override val status: StateFlow<KeyStatus> = state

    override suspend fun key(): String? = cached ?: lock.withLock {
        cached ?: withContext(Dispatchers.IO) { readKey() }?.also { cached = it }
    }

    override suspend fun save(key: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(cipher.encrypt(key.toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Could not write the key file")
            }
            rejectedMarker.delete()
        }
        cached = key
        state.value = KeyStatus.Saved
    }

    override suspend fun delete() = lock.withLock {
        withContext(Dispatchers.IO) {
            file.delete()
            rejectedMarker.delete()
        }
        cached = null
        state.value = KeyStatus.None
    }

    override suspend fun markRejected() = lock.withLock {
        if (state.value == KeyStatus.None) return@withLock
        withContext(Dispatchers.IO) { rejectedMarker.createNewFile() }
        state.value = KeyStatus.Rejected
    }

    override suspend fun markAccepted() = lock.withLock {
        if (state.value == KeyStatus.None) return@withLock
        withContext(Dispatchers.IO) { rejectedMarker.delete() }
        state.value = KeyStatus.Saved
    }

    /**
     * Null without a file, or when it cannot be decrypted (Keystore key gone, file
     * damaged): the file is then useless and is deleted, so the screen says « Aucune clé ».
     */
    private fun readKey(): String? {
        if (!file.isFile) return null
        return try {
            cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            forget()
        } catch (e: ProviderException) {
            forget()
        } catch (e: IOException) {
            forget()
        }
    }

    private fun forget(): String? {
        file.delete()
        rejectedMarker.delete()
        state.value = KeyStatus.None
        return null
    }
}
