package tech.dmconsulting.dixi.transcription

import tech.dmconsulting.dixi.settings.DictationLanguage
import java.io.File

/** Speech to raw text (Voxtral). The caller owns [audio] and deletes it afterwards. */
interface Transcriber {
    suspend fun transcribe(audio: File, language: DictationLanguage): VoxtralResult<String>
}
