package tech.dmconsulting.voxbulle.dictation

import java.io.File
import tech.dmconsulting.voxbulle.settings.DictationLanguage
import tech.dmconsulting.voxbulle.transcription.Transcriber
import tech.dmconsulting.voxbulle.transcription.VoxtralResult

/** Sends [audio] to the transcriber, then deletes it whatever happens: audio is never kept. */
suspend fun Transcriber.transcribeThenDelete(audio: File, language: DictationLanguage): VoxtralResult<String> =
    try {
        transcribe(audio, language)
    } finally {
        audio.delete()
    }
