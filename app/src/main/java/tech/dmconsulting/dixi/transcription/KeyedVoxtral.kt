package tech.dmconsulting.dixi.transcription

import tech.dmconsulting.dixi.settings.DictationLanguage
import tech.dmconsulting.dixi.settings.MistralKeyStore
import java.io.File
import java.io.IOException

/**
 * Picks the service at each call (DMC-227): the real one with the user's active key.
 * Without a key: [fake] in debug builds (« Mode démo »), else [VoxtralError.NoKey] (DMC-766).
 * A refusal by Mistral marks the key as rejected, so screen 10 says « Clé refusée ».
 */
class KeyedTranscriber(
    private val keys: MistralKeyStore,
    private val fake: Transcriber?,
    private val real: (apiKey: String) -> Transcriber,
) : Transcriber {
    override suspend fun transcribe(audio: File, language: DictationLanguage): VoxtralResult<String> {
        val key = keys.key() ?: return fake?.transcribe(audio, language) ?: NO_KEY
        return real(key).transcribe(audio, language).also { keys.noteRefusal(it) }
    }
}

/** [KeyedTranscriber] for the cleanup step. */
class KeyedTextCleaner(
    private val keys: MistralKeyStore,
    private val fake: TextCleaner?,
    private val real: (apiKey: String) -> TextCleaner,
) : TextCleaner {
    override suspend fun clean(rawText: String, language: DictationLanguage): VoxtralResult<String> {
        val key = keys.key() ?: return fake?.clean(rawText, language) ?: NO_KEY
        return real(key).clean(rawText, language).also { keys.noteRefusal(it) }
    }
}

private val NO_KEY = VoxtralResult.Failure(VoxtralError.NoKey)

/** A disk error only loses the « Clé refusée » mark: the dictation still gets its result. */
private suspend fun MistralKeyStore.noteRefusal(result: VoxtralResult<*>) {
    if (result != VoxtralResult.Failure(VoxtralError.InvalidKey)) return
    try {
        markRejected()
    } catch (e: IOException) {
        // Marked again at the next refusal.
    }
}
