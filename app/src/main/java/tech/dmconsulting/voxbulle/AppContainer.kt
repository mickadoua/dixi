package tech.dmconsulting.voxbulle

import tech.dmconsulting.voxbulle.dictation.UnlimitedQuota
import tech.dmconsulting.voxbulle.dictation.UsageQuota
import tech.dmconsulting.voxbulle.history.DictationHistory
import tech.dmconsulting.voxbulle.history.InMemoryDictationHistory
import tech.dmconsulting.voxbulle.settings.InMemorySettingsStore
import tech.dmconsulting.voxbulle.settings.SettingsStore
import tech.dmconsulting.voxbulle.transcription.FakeTextCleaner
import tech.dmconsulting.voxbulle.transcription.FakeTranscriber
import tech.dmconsulting.voxbulle.transcription.MistralHttp
import tech.dmconsulting.voxbulle.transcription.TextCleaner
import tech.dmconsulting.voxbulle.transcription.Transcriber
import tech.dmconsulting.voxbulle.transcription.TranscriptionPipeline
import tech.dmconsulting.voxbulle.transcription.VoxtralTextCleaner
import tech.dmconsulting.voxbulle.transcription.VoxtralTranscriber

/**
 * Manual dependency injection: one instance per process, held by [VoxbulleApp].
 * The app passes the DataStore-backed [history] and [settings]; tests keep the in-memory ones.
 * Without a Mistral key (the default), Voxtral is faked and nothing leaves the phone.
 * Only when [fakesAllowed] (debug builds): elsewhere a missing key is an error, so a
 * release never runs on the fakes without saying so.
 */
class AppContainer(
    mistralApiKey: String,
    fakesAllowed: Boolean,
    val history: DictationHistory = InMemoryDictationHistory(),
    val settings: SettingsStore = InMemorySettingsStore(),
) {

    /** True without a key: the app shows « Mode démo » in debug builds. */
    val usesFakeVoxtral: Boolean = mistralApiKey.isBlank()

    init {
        check(fakesAllowed || !usesFakeVoxtral) { "No Mistral key: the fake Voxtral is for debug builds only." }
    }

    private val httpClient by lazy { MistralHttp.newClient() }

    val transcriber: Transcriber =
        if (usesFakeVoxtral) FakeTranscriber() else VoxtralTranscriber(mistralApiKey, httpClient)

    val textCleaner: TextCleaner =
        if (usesFakeVoxtral) FakeTextCleaner() else VoxtralTextCleaner(mistralApiKey, httpClient)

    /** Audio to raw and clean text, in the language from [settings]. */
    val transcription = TranscriptionPipeline(transcriber, textCleaner, settings)

    val quota: UsageQuota = UnlimitedQuota
}
