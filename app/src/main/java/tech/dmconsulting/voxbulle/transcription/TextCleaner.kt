package tech.dmconsulting.voxbulle.transcription

import tech.dmconsulting.voxbulle.settings.DictationLanguage

/** Raw transcription to clean text: punctuation, capitals, hesitations removed. */
interface TextCleaner {
    suspend fun clean(rawText: String, language: DictationLanguage): VoxtralResult<String>
}
