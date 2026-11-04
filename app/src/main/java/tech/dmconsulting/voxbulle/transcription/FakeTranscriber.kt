package tech.dmconsulting.voxbulle.transcription

import java.io.File
import kotlinx.coroutines.delay
import tech.dmconsulting.voxbulle.settings.DictationLanguage

/**
 * Answers [result] after [delayMs] without any network call. Used in tests and
 * whenever the build has no Mistral key.
 */
class FakeTranscriber(
    private val result: VoxtralResult<String> = VoxtralResult.Success(SAMPLE_RAW_TEXT),
    private val delayMs: Long = 0,
) : Transcriber {
    /** Audio files received, in order: lets tests check what was sent. */
    val received = mutableListOf<File>()

    override suspend fun transcribe(audio: File, language: DictationLanguage): VoxtralResult<String> {
        received += audio
        delay(delayMs)
        return result
    }

    companion object {
        /** Fictitious sample, never shipped as real content. */
        const val SAMPLE_RAW_TEXT = "bonjour euh ceci est un texte de test voxbulle"
    }
}
