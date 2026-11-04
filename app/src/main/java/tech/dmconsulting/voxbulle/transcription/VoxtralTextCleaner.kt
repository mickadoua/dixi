package tech.dmconsulting.voxbulle.transcription

import tech.dmconsulting.voxbulle.settings.DictationLanguage

/** Mistral cleanup client. Placeholder: the call itself comes with DMC-137. */
class VoxtralTextCleaner(private val apiKey: String) : TextCleaner {
    override suspend fun clean(rawText: String, language: DictationLanguage): VoxtralResult<String> =
        TODO("Mistral cleanup client: DMC-137")
}
