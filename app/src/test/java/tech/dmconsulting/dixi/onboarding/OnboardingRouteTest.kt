package tech.dmconsulting.dixi.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.dmconsulting.dixi.settings.AppSettings
import tech.dmconsulting.dixi.settings.OnboardingStep
import tech.dmconsulting.dixi.ui.navigation.Screen

class OnboardingRouteTest {
    @Test
    fun firstLaunchOpensOnWelcome() {
        assertEquals(Screen.Welcome, startScreen(AppSettings(), overlayGranted = false, hasKey = false))
    }

    @Test
    fun resumesOnTheStepLeft() {
        OnboardingStep.entries.forEach { step ->
            assertEquals(step.screen, startScreen(AppSettings(onboardingStep = step), overlayGranted = true, hasKey = true))
        }
    }

    @Test
    fun neverReplaysOnceDone() {
        OnboardingStep.entries.forEach { step ->
            val settings = AppSettings(onboardingDone = true, onboardingStep = step)
            assertEquals(Screen.home, startScreen(settings, overlayGranted = false, hasKey = false))
            assertEquals(Screen.home, startScreen(settings, overlayGranted = true, hasKey = true))
        }
    }

    @Test
    fun trialOrReadyWithoutOverlayPermissionGoesBackToTheBubbleStep() {
        listOf(OnboardingStep.Trial, OnboardingStep.BubbleEnabled).forEach { step ->
            assertEquals(Screen.BubbleIntro, startScreen(AppSettings(onboardingStep = step), overlayGranted = false, hasKey = true))
        }
    }

    @Test
    fun everyStepAfterTheKeyNeedsTheKey() {
        listOf(OnboardingStep.BubbleIntro, OnboardingStep.Trial, OnboardingStep.BubbleEnabled).forEach { step ->
            assertEquals(Screen.MistralKey, startScreen(AppSettings(onboardingStep = step), overlayGranted = true, hasKey = false))
        }
        assertEquals(Screen.MicPermission, startScreen(AppSettings(onboardingStep = OnboardingStep.MicPermission), overlayGranted = false, hasKey = false))
    }

    @Test
    fun keyBeforeTheBubbleThenTheGuidedTrial() {
        assertEquals(
            listOf(Screen.Welcome, Screen.MicPermission, Screen.MistralKey, Screen.BubbleIntro, Screen.Trial, Screen.BubbleEnabled),
            OnboardingStep.entries.map { it.screen },
        )
        OnboardingStep.entries.forEach { assertEquals(it, it.screen.onboardingStep) }
        assertNull(Screen.History.onboardingStep)
    }

    @Test
    fun backLeadsToTheStepBefore() {
        assertNull(OnboardingStep.Welcome.previous)
        OnboardingStep.entries.drop(1).forEach { assertEquals(OnboardingStep.entries[it.ordinal - 1], it.previous) }
    }

    @Test
    fun theGuidedTrialComesOnlyAfterTheOverlayPermission() {
        assertEquals(listOf(Screen.BubbleIntro), Screen.entries.filter { Screen.Trial in it.links })
        assertEquals(listOf(Screen.MicPermission), Screen.entries.filter { Screen.MistralKey in it.links })
    }
}
