package tech.dmconsulting.voxbulle

import tech.dmconsulting.voxbulle.dictation.UnlimitedQuota
import tech.dmconsulting.voxbulle.dictation.UsageQuota
import tech.dmconsulting.voxbulle.history.DictationHistory
import tech.dmconsulting.voxbulle.history.InMemoryDictationHistory
import tech.dmconsulting.voxbulle.settings.InMemorySettingsStore
import tech.dmconsulting.voxbulle.settings.SettingsStore
import tech.dmconsulting.voxbulle.transcription.FakeTextCleaner
import tech.dmconsulting.voxbulle.transcription.FakeTranscriber
import tech.dmconsulting.voxbulle.transcription.TextCleaner
import tech.dmconsulting.voxbulle.transcription.Transcriber
import tech.dmconsulting.voxbulle.transcription.VoxtralTextCleaner
import tech.dmconsulting.voxbulle.transcription.VoxtralTranscriber

/**
 * Manual dependency injection: one instance per process, held by [VoxbulleApp].
 * Without a Mistral key (the default), Voxtral is faked and nothing leaves the phone.
 */
class AppContainer(mistralApiKey: String) {

    val usesFakeVoxtral: Boolean = mistralApiKey.isBlank()

    val transcriber: Transcriber =
        if (usesFakeVoxtral) FakeTranscriber() else VoxtralTranscriber(mistralApiKey)

    val textCleaner: TextCleaner =
        if (usesFakeVoxtral) FakeTextCleaner() else VoxtralTextCleaner(mistralApiKey)

    // In memory until DataStore lands (DMC-138).
    val history: DictationHistory = InMemoryDictationHistory()

    val settings: SettingsStore = InMemorySettingsStore()

    val quota: UsageQuota = UnlimitedQuota
}
