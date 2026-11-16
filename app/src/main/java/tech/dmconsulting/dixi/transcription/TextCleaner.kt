package tech.dmconsulting.dixi.transcription

import tech.dmconsulting.dixi.settings.DictationLanguage

/** Raw transcription to clean text: punctuation, capitals, hesitations removed. */
interface TextCleaner {
    suspend fun clean(rawText: String, language: DictationLanguage): VoxtralResult<String>
}
