package tech.dmconsulting.voxbulle.transcription

import kotlinx.coroutines.delay
import tech.dmconsulting.voxbulle.settings.DictationLanguage
import java.io.File

/**
 * Answers [result] after [delayMs] without any network call. Used in tests and
 * whenever the build has no Mistral key (« Mode démo »). [result] is read at each
 * dictation, so the demo text follows the phone language (res `demo_raw_text`).
 */
class FakeTranscriber(
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val result: () -> VoxtralResult<String> = { VoxtralResult.Success(SAMPLE_RAW_TEXT) },
) : Transcriber {
    constructor(result: VoxtralResult<String>, delayMs: Long = DEFAULT_DELAY_MS) : this(delayMs, { result })

    /** Audio files received, in order: lets tests check what was sent. */
    val received = mutableListOf<File>()

    override suspend fun transcribe(audio: File, language: DictationLanguage): VoxtralResult<String> {
        received += audio
        delay(delayMs)
        return result()
    }

    companion object {
        /** Roughly what a short real dictation takes, so the « Traitement » state shows. */
        const val DEFAULT_DELAY_MS = 1_500L

        /** Fictitious sample, never shipped as real content. The app reads `demo_raw_text` instead. */
        const val SAMPLE_RAW_TEXT = "bonjour euh ceci est un texte de test dixi"
    }
}
