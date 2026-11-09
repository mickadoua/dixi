package tech.dmconsulting.voxbulle

import okhttp3.HttpUrl
import tech.dmconsulting.voxbulle.dictation.UnlimitedQuota
import tech.dmconsulting.voxbulle.dictation.UsageQuota
import tech.dmconsulting.voxbulle.history.DictationHistory
import tech.dmconsulting.voxbulle.history.InMemoryDictationHistory
import tech.dmconsulting.voxbulle.settings.InMemoryMistralKeyStore
import tech.dmconsulting.voxbulle.settings.InMemorySettingsStore
import tech.dmconsulting.voxbulle.settings.KeyStatus
import tech.dmconsulting.voxbulle.settings.MistralKeyStore
import tech.dmconsulting.voxbulle.settings.SettingsStore
import tech.dmconsulting.voxbulle.transcription.FakeTextCleaner
import tech.dmconsulting.voxbulle.transcription.FakeTranscriber
import tech.dmconsulting.voxbulle.transcription.KeyedTextCleaner
import tech.dmconsulting.voxbulle.transcription.KeyedTranscriber
import tech.dmconsulting.voxbulle.transcription.MistralHttp
import tech.dmconsulting.voxbulle.transcription.MistralKeyCheck
import tech.dmconsulting.voxbulle.transcription.TextCleaner
import tech.dmconsulting.voxbulle.transcription.Transcriber
import tech.dmconsulting.voxbulle.transcription.TranscriptionPipeline
import tech.dmconsulting.voxbulle.transcription.VoxtralResult
import tech.dmconsulting.voxbulle.transcription.VoxtralTextCleaner
import tech.dmconsulting.voxbulle.transcription.VoxtralTranscriber

/**
 * Manual dependency injection: one instance per process, held by [VoxbulleApp].
 * The app passes the DataStore-backed [history] and [settings] and the encrypted
 * [mistralKey]; tests keep the in-memory ones.
 * The app ships without any Mistral key (DMC-227): each user pastes their own in
 * screen 10. Without one, Voxtral is faked (« Mode démo ») and nothing leaves the phone.
 */
class AppContainer(
    val mistralKey: MistralKeyStore = InMemoryMistralKeyStore(),
    val history: DictationHistory = InMemoryDictationHistory(),
    val settings: SettingsStore = InMemorySettingsStore(),
    mistralBaseUrl: HttpUrl = MistralHttp.defaultBaseUrl,
    /** Raw text of a demo dictation, in the phone language. */
    demoRawText: () -> String = { FakeTranscriber.SAMPLE_RAW_TEXT },
) {

    /** True without a saved key: dictations use the fakes and screens show « Mode démo ». */
    val usesFakeVoxtral: Boolean get() = mistralKey.status.value == KeyStatus.None

    private val httpClient by lazy { MistralHttp.newClient() }

    private val demoTranscriber = FakeTranscriber { VoxtralResult.Success(demoRawText()) }

    val transcriber: Transcriber = KeyedTranscriber(mistralKey, demoTranscriber) { key ->
        VoxtralTranscriber(key, httpClient, mistralBaseUrl)
    }

    val textCleaner: TextCleaner = KeyedTextCleaner(mistralKey, FakeTextCleaner()) { key ->
        VoxtralTextCleaner(key, httpClient, mistralBaseUrl)
    }

    /** Audio to raw and clean text, in the language from [settings]. */
    val transcription = TranscriptionPipeline(transcriber, textCleaner, settings)

    /** « Tester la clé » in screen 10. */
    val keyCheck by lazy { MistralKeyCheck(httpClient, mistralBaseUrl) }

    val quota: UsageQuota = UnlimitedQuota
}
