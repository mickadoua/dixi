package tech.dmconsulting.voxbulle.transcription

import tech.dmconsulting.voxbulle.settings.DictationLanguage

/**
 * Local stand-in for the Voxtral cleanup: drops « euh », adds the capital and
 * the final period. Used in tests and whenever the build has no Mistral key.
 */
class FakeTextCleaner : TextCleaner {
    override suspend fun clean(rawText: String, language: DictationLanguage): VoxtralResult<String> {
        val words = rawText.split(Regex("\\s+")).filter { it.isNotEmpty() && it.lowercase() != "euh" }
        if (words.isEmpty()) return VoxtralResult.Failure(VoxtralError.NothingHeard)
        val text = words.joinToString(" ").replaceFirstChar { it.uppercase() }
        return VoxtralResult.Success(if (text.last() in ".!?") text else "$text.")
    }
}
