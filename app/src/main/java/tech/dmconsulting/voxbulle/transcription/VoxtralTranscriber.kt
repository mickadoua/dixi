package tech.dmconsulting.voxbulle.transcription

import java.io.File
import tech.dmconsulting.voxbulle.settings.DictationLanguage

/** Mistral Voxtral speech-to-text client. Placeholder: the call itself comes with DMC-137. */
class VoxtralTranscriber(private val apiKey: String) : Transcriber {
    override suspend fun transcribe(audio: File, language: DictationLanguage): VoxtralResult<String> =
        TODO("Voxtral client: DMC-137")
}
