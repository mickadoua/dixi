package tech.dmconsulting.dixi.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

/** What the screens say about the active Mistral key. The key itself is never shown. */
enum class KeyStatus {
    /**
     * No key at all: dictations fail with « No key » ([tech.dmconsulting.dixi.transcription.VoxtralError.NoKey]).
     * Only debug builds fall back to the fake Voxtral (« Mode démo », DMC-766).
     */
    None,

    /** A key is active. Not proof that Mistral accepts it: « Test key » checks that. */
    Saved,

    /** The active key was refused by Mistral (HTTP 401 or 403) on a test or a dictation. */
    Rejected,
}

/** One saved key as the settings list it: its name and state, never the key (DMC-766). */
data class KeyEntry(val id: String, val name: String, val active: Boolean, val rejected: Boolean)

/**
 * Names and states of the saved keys, without the keys: the user names each key, one is
 * active (DMC-766). Pure, so both stores share the same rules. The file store writes it as
 * JSON next to the sealed keys.
 */
@Serializable
data class KeyRing(val keys: List<Stored> = emptyList(), val active: String? = null) {
    @Serializable
    data class Stored(val id: String, val name: String, val rejected: Boolean = false)

    val status: KeyStatus
        get() = when (keys.firstOrNull { it.id == active }?.rejected) {
            null -> KeyStatus.None
            true -> KeyStatus.Rejected
            false -> KeyStatus.Saved
        }

    val entries: List<KeyEntry>
        get() = keys.map { KeyEntry(it.id, it.name, active = it.id == active, rejected = it.rejected) }

    /** A new key, named [name], becomes the active one. */
    fun add(id: String, name: String): KeyRing = copy(keys = keys + Stored(id, name), active = id)

    /** [id] becomes active; an unknown id changes nothing. */
    fun activate(id: String): KeyRing = if (keys.any { it.id == id }) copy(active = id) else this

    /** Removes [id]. When it was the active key, the first one left takes over (or none). */
    fun remove(id: String): KeyRing {
        val left = keys.filterNot { it.id == id }
        return KeyRing(left, if (active == id || active == null) left.firstOrNull()?.id else active)
    }

    /** Mistral refused ([rejected]) or accepted the active key. No active key: unchanged. */
    fun markActive(rejected: Boolean): KeyRing = copy(keys = keys.map { if (it.id == active) it.copy(rejected = rejected) else it })

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/**
 * The Mistral API keys the user pasted (DMC-227, several since DMC-766). The app ships
 * without any key. Never log, print or put a key in an error message.
 */
interface MistralKeyStore {
    /** State of the active key. */
    val status: StateFlow<KeyStatus>

    /** Every saved key, in the order they were added. */
    val entries: StateFlow<List<KeyEntry>>

    /** The active key, or null. Also null when no saved key can be decrypted any more. */
    suspend fun key(): String?

    /** Saves [key], already checked by [MistralKey.normalize], under [name]. It becomes the active key. */
    suspend fun add(name: String, key: String)

    /** Makes the key [id] the one dictations use. */
    suspend fun activate(id: String)

    /** Deletes the key [id] from the phone. If it was active, the first key left takes over. */
    suspend fun delete(id: String)

    /** Mistral refused the active key. Kept until [markAccepted] or the key is deleted. */
    suspend fun markRejected()

    /** « Test key » found the active key valid: clears a refusal. */
    suspend fun markAccepted()
}

object MistralKey {
    /**
     * The pasted text as a key, or null when it cannot be one: blank, too short or too
     * long, or with a character that is not printable ASCII (spaces and line breaks
     * included). OkHttp would refuse such a value in the `Authorization` header.
     */
    fun normalize(input: String): String? {
        val key = input.trim()
        if (key.length !in MIN_LENGTH..MAX_LENGTH) return null
        return key.takeIf { k -> k.all { it in '!'..'~' } }
    }

    /** The typed name, trimmed and cut to [MAX_NAME_LENGTH], or [default] when blank. */
    fun name(input: String, default: String): String = input.trim().take(MAX_NAME_LENGTH).ifEmpty { default }

    const val MIN_LENGTH = 16
    const val MAX_LENGTH = 256
    const val MAX_NAME_LENGTH = 40
}

/** Keys lost when the process dies. Test fake and default of [tech.dmconsulting.dixi.AppContainer]. */
class InMemoryMistralKeyStore(initial: String? = null) : MistralKeyStore {
    private val lock = Any()
    private val secrets = mutableMapOf<String, String>()
    private var ring = KeyRing()
    private val state = MutableStateFlow(KeyStatus.None)
    private val list = MutableStateFlow<List<KeyEntry>>(emptyList())

    init {
        if (initial != null) {
            val id = KeyRing.newId()
            secrets[id] = initial
            publish(ring.add(id, "Key 1"))
        }
    }

    override val status: StateFlow<KeyStatus> = state
    override val entries: StateFlow<List<KeyEntry>> = list

    override suspend fun key(): String? = synchronized(lock) { ring.active?.let { secrets[it] } }

    override suspend fun add(name: String, key: String) = synchronized(lock) {
        val id = KeyRing.newId()
        secrets[id] = key
        publish(ring.add(id, name))
    }

    override suspend fun activate(id: String) = synchronized(lock) { publish(ring.activate(id)) }

    override suspend fun delete(id: String) = synchronized(lock) {
        secrets.remove(id)
        publish(ring.remove(id))
    }

    override suspend fun markRejected() = synchronized(lock) { publish(ring.markActive(rejected = true)) }

    override suspend fun markAccepted() = synchronized(lock) { publish(ring.markActive(rejected = false)) }

    private fun publish(next: KeyRing) {
        ring = next
        state.value = next.status
        list.value = next.entries
    }

    override fun toString(): String = "InMemoryMistralKeyStore(${ring.keys.size} keys)"
}
