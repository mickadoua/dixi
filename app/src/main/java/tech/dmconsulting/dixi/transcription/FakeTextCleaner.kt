package tech.dmconsulting.dixi.transcription

import kotlinx.coroutines.delay
import tech.dmconsulting.dixi.settings.DictationLanguage

/**
 * Local stand-in for the Voxtral cleanup: drops « euh » (« um », « uh » in English),
 * adds the capital and the final period. Used in tests and whenever the build has no
 * Mistral key.
 */
class FakeTextCleaner(private val delayMs: Long = 0) : TextCleaner {
    override suspend fun clean(rawText: String, language: DictationLanguage): VoxtralResult<String> {
        delay(delayMs)
        val words = rawText.split(Regex("\\s+")).filter { it.isNotEmpty() && it.lowercase() !in FILLERS }
        if (words.isEmpty()) return VoxtralResult.Failure(VoxtralError.NothingHeard)
        val text = words.joinToString(" ").replaceFirstChar { it.uppercase() }
        return VoxtralResult.Success(if (text.last() in ".!?") text else "$text.")
    }

    private companion object {
        val FILLERS = setOf("euh", "um", "uh")
    }
}
