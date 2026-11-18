package tech.dmconsulting.dixi.settings

import kotlinx.coroutines.flow.Flow

/** Dictation language. [Auto] lets Voxtral detect French or English. */
enum class DictationLanguage(val code: String?) {
    Auto(null),
    French("fr"),
    English("en"),
}

/**
 * Onboarding steps, in order (mockups DMC-764 § 3): the Mistral key before the bubble, then
 * the guided trial with the real bubble, then « Dixi est prêt ». The app reopens on the last
 * one shown until it is done. Saved by name: [Trial] is now the guided trial, after the bubble.
 */
enum class OnboardingStep {
    Welcome,
    MicPermission,
    MistralKey,
    BubbleIntro,
    Trial,
    BubbleEnabled,
}

/** User settings, screen 10. Defaults are the first-launch values. */
data class AppSettings(
    val onboardingDone: Boolean = false,
    val onboardingStep: OnboardingStep = OnboardingStep.Welcome,
    val bubbleEnabled: Boolean = false,
    val autoPasteEnabled: Boolean = false,
    val language: DictationLanguage = DictationLanguage.Auto,
    val historyEnabled: Boolean = true,
)

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)
}
