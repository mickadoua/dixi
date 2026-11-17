package tech.dmconsulting.dixi

import okhttp3.HttpUrl
import tech.dmconsulting.dixi.dictation.UnlimitedQuota
import tech.dmconsulting.dixi.dictation.UsageQuota
import tech.dmconsulting.dixi.history.DictationHistory
import tech.dmconsulting.dixi.history.InMemoryDictationHistory
import tech.dmconsulting.dixi.settings.InMemoryMistralKeyStore
import tech.dmconsulting.dixi.settings.InMemorySettingsStore
import tech.dmconsulting.dixi.settings.KeyStatus
import tech.dmconsulting.dixi.settings.MistralKeyStore
import tech.dmconsulting.dixi.settings.SettingsStore
import tech.dmconsulting.dixi.transcription.FakeTextCleaner
import tech.dmconsulting.dixi.transcription.FakeTranscriber
import tech.dmconsulting.dixi.transcription.KeyedTextCleaner
import tech.dmconsulting.dixi.transcription.KeyedTranscriber
import tech.dmconsulting.dixi.transcription.MistralHttp
import tech.dmconsulting.dixi.transcription.MistralKeyCheck
import tech.dmconsulting.dixi.transcription.TextCleaner
import tech.dmconsulting.dixi.transcription.Transcriber
import tech.dmconsulting.dixi.transcription.TranscriptionPipeline
import tech.dmconsulting.dixi.transcription.VoxtralResult
import tech.dmconsulting.dixi.transcription.VoxtralTextCleaner
import tech.dmconsulting.dixi.transcription.VoxtralTranscriber

/**
 * Manual dependency injection: one instance per process, held by [DixiApp].
 * The app passes the DataStore-backed [history] and [settings] and the encrypted
 * [mistralKey]; tests keep the in-memory ones.
 * The app ships without any Mistral key (DMC-227): each user pastes their own in
 * screen 10. Without one, Voxtral is faked (« Mode démo ») and nothing leaves the phone.
 */
class AppContainer(
    val mistralKey: MistralKeyStore = InMemoryMistralKeyStore(),
    val history: DictationHistory = InMemoryDictationHistory(),
    val settings: SettingsStore = InMemorySettingsStore(),
    mistralBaseUrl: HttpUrl = MistralHttp.debugBaseUrl ?: MistralHttp.defaultBaseUrl,
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
