package tech.dmconsulting.voxbulle.transcription

import tech.dmconsulting.voxbulle.settings.DictationLanguage
import java.io.File

/** Speech to raw text (Voxtral). The caller owns [audio] and deletes it afterwards. */
interface Transcriber {
    suspend fun transcribe(audio: File, language: DictationLanguage): VoxtralResult<String>
}
