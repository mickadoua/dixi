package tech.dmconsulting.voxbulle.transcription

import java.io.File
import tech.dmconsulting.voxbulle.settings.DictationLanguage
import tech.dmconsulting.voxbulle.settings.MistralKeyStore

/**
 * Picks the service at each call (DMC-227): the real one with the user's saved key,
 * the fake one without a key. A refusal by Mistral marks the key as rejected, so
 * screen 10 says « Clé refusée ».
 */
class KeyedTranscriber(
    private val keys: MistralKeyStore,
    private val fake: Transcriber,
    private val real: (apiKey: String) -> Transcriber,
) : Transcriber {
    override suspend fun transcribe(audio: File, language: DictationLanguage): VoxtralResult<String> {
        val key = keys.key() ?: return fake.transcribe(audio, language)
        return real(key).transcribe(audio, language).also { keys.noteRefusal(it) }
    }
}

/** [KeyedTranscriber] for the cleanup step. */
class KeyedTextCleaner(
    private val keys: MistralKeyStore,
    private val fake: TextCleaner,
    private val real: (apiKey: String) -> TextCleaner,
) : TextCleaner {
    override suspend fun clean(rawText: String, language: DictationLanguage): VoxtralResult<String> {
        val key = keys.key() ?: return fake.clean(rawText, language)
        return real(key).clean(rawText, language).also { keys.noteRefusal(it) }
    }
}

private suspend fun MistralKeyStore.noteRefusal(result: VoxtralResult<*>) {
    if (result == VoxtralResult.Failure(VoxtralError.InvalidKey)) markRejected()
}
