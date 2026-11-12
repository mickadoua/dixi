package tech.dmconsulting.voxbulle.onboarding

import tech.dmconsulting.voxbulle.settings.AppSettings
import tech.dmconsulting.voxbulle.settings.OnboardingStep
import tech.dmconsulting.voxbulle.ui.navigation.Screen

/** Onboarding screen of each step (mockups, screens 1 to 5). */
val OnboardingStep.screen: Screen
    get() = when (this) {
        OnboardingStep.Welcome -> Screen.Welcome
        OnboardingStep.MicPermission -> Screen.MicPermission
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
 * First screen at launch: the home screen once the onboarding is done, else the
 * step left last time. Screen 5 says « La bulle est prête »: if the user took the
 * overlay permission back since, they go back to screen 4.
 */
fun startScreen(settings: AppSettings, overlayGranted: Boolean): Screen = when {
    settings.onboardingDone -> Screen.home
    settings.onboardingStep == OnboardingStep.BubbleEnabled && !overlayGranted -> Screen.BubbleIntro
    else -> settings.onboardingStep.screen
}
