package tech.dmconsulting.voxbulle.transcription

import kotlinx.coroutines.flow.first
import tech.dmconsulting.voxbulle.settings.SettingsStore
import java.io.File

/**
 * A dictation's two texts: [raw] from Voxtral (screen 3, « brut » card) and
 * [cleaned] by Mistral (« Texte Voxbulle » card). [cleaned] is null when the
 * cleanup failed, with the reason in [cleanupError]: the words are not lost.
 */
data class Transcript(
    val raw: String,
    val cleaned: String?,
    val cleanupError: VoxtralError? = null,
) {
    /** What to copy or paste: the clean text, else the raw one. */
    val text: String get() = cleaned ?: raw
}

/**
 * Audio file to [Transcript]: transcription, then cleanup, in the language set
 * in [settings] (auto by default). The caller owns [audio] and deletes it.
 */
class TranscriptionPipeline(
    private val transcriber: Transcriber,
    private val cleaner: TextCleaner,
    private val settings: SettingsStore,
) {
    suspend fun run(audio: File): VoxtralResult<Transcript> {
        val language = settings.settings.first().language
        val raw = when (val result = transcriber.transcribe(audio, language)) {
            is VoxtralResult.Failure -> return result
            is VoxtralResult.Success -> result.value
        }
        return when (val result = cleaner.clean(raw, language)) {
            is VoxtralResult.Success -> VoxtralResult.Success(Transcript(raw, result.value))

            // Nothing worth copying. FakeTextCleaner says so for a dictation of only « euh »;
            // VoxtralTextCleaner only for a blank text, which VoxtralTranscriber never returns.
            is VoxtralResult.Failure if result.error == VoxtralError.NothingHeard -> result

            is VoxtralResult.Failure -> VoxtralResult.Success(Transcript(raw, cleaned = null, cleanupError = result.error))
        }
    }
}
