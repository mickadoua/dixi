package tech.dmconsulting.voxbulle.transcription

import kotlinx.coroutines.delay
import tech.dmconsulting.voxbulle.settings.DictationLanguage

/**
 * Local stand-in for the Voxtral cleanup: drops « euh », adds the capital and
 * the final period. Used in tests and whenever the build has no Mistral key.
 */
class FakeTextCleaner(private val delayMs: Long = 0) : TextCleaner {
    override suspend fun clean(rawText: String, language: DictationLanguage): VoxtralResult<String> {
        delay(delayMs)
        val words = rawText.split(Regex("\\s+")).filter { it.isNotEmpty() && it.lowercase() != "euh" }
        if (words.isEmpty()) return VoxtralResult.Failure(VoxtralError.NothingHeard)
        val text = words.joinToString(" ").replaceFirstChar { it.uppercase() }
        return VoxtralResult.Success(if (text.last() in ".!?") text else "$text.")
    }
}
