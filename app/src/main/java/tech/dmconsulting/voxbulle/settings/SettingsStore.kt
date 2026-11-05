package tech.dmconsulting.voxbulle.settings

import kotlinx.coroutines.flow.Flow

/** Dictation language. [Auto] lets Voxtral detect French or English. */
enum class DictationLanguage(val code: String?) {
    Auto(null),
    French("fr"),
    English("en"),
}

/** User settings, screen 10. Defaults are the first-launch values. */
data class AppSettings(
    val onboardingDone: Boolean = false,
    val bubbleEnabled: Boolean = false,
    val autoPasteEnabled: Boolean = false,
    val language: DictationLanguage = DictationLanguage.Auto,
    val historyEnabled: Boolean = true,
)

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)
}
