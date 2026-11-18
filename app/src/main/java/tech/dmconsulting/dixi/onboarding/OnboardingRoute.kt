package tech.dmconsulting.dixi.onboarding

import tech.dmconsulting.dixi.settings.AppSettings
import tech.dmconsulting.dixi.settings.OnboardingStep
import tech.dmconsulting.dixi.ui.navigation.Screen

/** Onboarding screen of each step (mockups DMC-764 § 3). */
val OnboardingStep.screen: Screen
    get() = when (this) {
        OnboardingStep.Welcome -> Screen.Welcome
        OnboardingStep.MicPermission -> Screen.MicPermission
        OnboardingStep.MistralKey -> Screen.MistralKey
        OnboardingStep.Trial -> Screen.Trial
        OnboardingStep.BubbleIntro -> Screen.BubbleIntro
        OnboardingStep.BubbleEnabled -> Screen.BubbleEnabled
    }

/** The step this screen belongs to, or null outside the onboarding. */
val Screen.onboardingStep: OnboardingStep?
    get() = OnboardingStep.entries.firstOrNull { it.screen == this }

/** Where the back arrow (and the system back) leads: the step before, or null on the first one. */
val OnboardingStep.previous: OnboardingStep?
    get() = OnboardingStep.entries.getOrNull(ordinal - 1)

/**
 * First screen at launch: the home screen once the onboarding is done, else the step left
 * last time. The key is needed for every step after its own: without one ([hasKey]), back to
 * the key step. The guided trial and « Dixi est prêt » need the bubble: if the user took the
 * overlay permission back since, back to the bubble step.
 */
fun startScreen(settings: AppSettings, overlayGranted: Boolean, hasKey: Boolean): Screen {
    val step = settings.onboardingStep
    return when {
        settings.onboardingDone -> Screen.home
        step > OnboardingStep.MistralKey && !hasKey -> Screen.MistralKey
        step > OnboardingStep.BubbleIntro && !overlayGranted -> Screen.BubbleIntro
        else -> step.screen
    }
}
