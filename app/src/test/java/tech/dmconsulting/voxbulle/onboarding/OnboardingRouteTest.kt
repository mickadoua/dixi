package tech.dmconsulting.voxbulle.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.dmconsulting.voxbulle.settings.AppSettings
import tech.dmconsulting.voxbulle.settings.OnboardingStep
import tech.dmconsulting.voxbulle.ui.navigation.Screen

class OnboardingRouteTest {
    @Test
    fun firstLaunchOpensOnWelcome() {
        assertEquals(Screen.Welcome, startScreen(AppSettings(), overlayGranted = false))
    }

    @Test
    fun resumesOnTheStepLeft() {
        OnboardingStep.entries.forEach { step ->
            assertEquals(step.screen, startScreen(AppSettings(onboardingStep = step), overlayGranted = true))
        }
    }

    @Test
    fun neverReplaysOnceDone() {
        OnboardingStep.entries.forEach { step ->
            val settings = AppSettings(onboardingDone = true, onboardingStep = step)
            assertEquals(Screen.home, startScreen(settings, overlayGranted = false))
            assertEquals(Screen.home, startScreen(settings, overlayGranted = true))
        }
    }

    @Test
    fun bubbleReadyWithoutOverlayPermissionGoesBackToScreen4() {
        val settings = AppSettings(onboardingStep = OnboardingStep.BubbleEnabled)
        assertEquals(Screen.BubbleIntro, startScreen(settings, overlayGranted = false))
    }

    @Test
    fun stepsMatchScreens1To5InOrder() {
        assertEquals(listOf(1, 2, 3, 4, 5), OnboardingStep.entries.map { it.screen.number })
        OnboardingStep.entries.forEach { assertEquals(it, it.screen.onboardingStep) }
        assertNull(Screen.History.onboardingStep)
    }

    @Test
    fun backLeadsToTheStepBefore() {
        assertNull(OnboardingStep.Welcome.previous)
        assertEquals(OnboardingStep.Welcome, OnboardingStep.MicPermission.previous)
        assertEquals(OnboardingStep.MicPermission, OnboardingStep.Trial.previous)
        assertEquals(OnboardingStep.Trial, OnboardingStep.BubbleIntro.previous)
        assertEquals(OnboardingStep.BubbleIntro, OnboardingStep.BubbleEnabled.previous)
    }

    @Test
    fun noOverlayPermissionBeforeTheTrial() {
        // The journey reaches screen 4 (overlay permission) only from screen 3 or from « Plus tard ».
        val toBubbleIntro = Screen.entries.filter { Screen.BubbleIntro in it.links }
        assertEquals(listOf(Screen.MicPermission, Screen.Trial), toBubbleIntro)
    }
}
