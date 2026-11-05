package tech.dmconsulting.voxbulle.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What screen 10 says about the user's Mistral key. The key itself is never shown. */
enum class KeyStatus {
    /** No key: dictations run on the fake Voxtral (« Mode démo »). */
    None,

    /** A key is saved. Not proof that Mistral accepts it: « Tester la clé » checks that. */
    Saved,

    /** A key is saved but Mistral refused it (HTTP 401 or 403) on a test or a dictation. */
    Rejected,
}

/**
 * The Mistral API key the user pasted in screen 10 (DMC-227). The app ships without
 * any key. Never log, print or put [key] in an error message.
 */
interface MistralKeyStore {
    val status: StateFlow<KeyStatus>

    /** The saved key, or null. Also null when it can no longer be decrypted. */
    suspend fun key(): String?

    /** Saves [key], already checked by [MistralKey.normalize]. Clears a refusal. */
    suspend fun save(key: String)

    suspend fun delete()

    /** Mistral refused the saved key. Kept until the next [save], [delete] or [markAccepted]. */
    suspend fun markRejected()

    /** « Tester la clé » found the saved key valid: clears a refusal. */
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

    const val MIN_LENGTH = 16
    const val MAX_LENGTH = 256
}

/** Key lost when the process dies. Test fake and default of [tech.dmconsulting.voxbulle.AppContainer]. */
class InMemoryMistralKeyStore(initial: String? = null) : MistralKeyStore {
    @Volatile private var saved: String? = initial
    private val state = MutableStateFlow(if (initial == null) KeyStatus.None else KeyStatus.Saved)

    override val status: StateFlow<KeyStatus> = state

    override suspend fun key(): String? = saved

    override suspend fun save(key: String) {
        saved = key
        state.value = KeyStatus.Saved
    }

    override suspend fun delete() {
        saved = null
        state.value = KeyStatus.None
    }

    override suspend fun markRejected() {
        if (saved != null) state.value = KeyStatus.Rejected
    }

    override suspend fun markAccepted() {
        if (saved != null) state.value = KeyStatus.Saved
    }
}
