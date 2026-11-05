package tech.dmconsulting.voxbulle.transcription

import java.io.File
import tech.dmconsulting.voxbulle.settings.DictationLanguage

/** Speech to raw text (Voxtral). The caller owns [audio] and deletes it afterwards. */
interface Transcriber {
    suspend fun transcribe(audio: File, language: DictationLanguage): VoxtralResult<String>
}
