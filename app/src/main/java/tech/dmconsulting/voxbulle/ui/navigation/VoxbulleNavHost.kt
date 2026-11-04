package tech.dmconsulting.voxbulle.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.dmconsulting.voxbulle.AppContainer
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.bubble.BubbleService
import tech.dmconsulting.voxbulle.history.HistoryScreen
import tech.dmconsulting.voxbulle.onboarding.BubbleEnabledScreen
import tech.dmconsulting.voxbulle.onboarding.BubbleIntroScreen
import tech.dmconsulting.voxbulle.onboarding.MicPermissionScreen
import tech.dmconsulting.voxbulle.onboarding.TrialScreen
import tech.dmconsulting.voxbulle.onboarding.WelcomeScreen
import tech.dmconsulting.voxbulle.onboarding.onboardingStep
import tech.dmconsulting.voxbulle.onboarding.previous
import tech.dmconsulting.voxbulle.onboarding.screen
import tech.dmconsulting.voxbulle.recording.MicrophoneRecorder
import tech.dmconsulting.voxbulle.settings.OnboardingStep
import tech.dmconsulting.voxbulle.settings.SettingsScreen
import tech.dmconsulting.voxbulle.ui.components.DemoModeLabel
import tech.dmconsulting.voxbulle.ui.components.LocalDemoMode
import tech.dmconsulting.voxbulle.ui.theme.Dimens

/**
 * Screens without their ticket yet are empty placeholders.
 * [demoMode]: debug build on the fake Voxtral, shown by a « Mode démo » label
 * that each screen places ([LocalDemoMode]).
 *
 * Onboarding (screens 1 to 5): each move replaces the whole back stack, and the
 * back arrow leads to the step before, so a relaunch on any step behaves the same.
 * The step shown is saved; once « Terminer » or « Pas maintenant » is tapped the
 * app opens on [Screen.home].
 */
@Composable
fun VoxbulleNavHost(container: AppContainer, demoMode: Boolean, start: Screen = Screen.start) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val back: () -> Unit = {
        // Last screen: leave the app rather than show an empty NavHost.
        if (navController.previousBackStackEntry != null) navController.popBackStack() else context.findActivity()?.finish()
    }
    val go: (Screen) -> Unit = { target ->
        navController.navigate(target.route) { popUpTo(navController.graph.id) { inclusive = true } }
    }
    val finishOnboarding: () -> Unit = {
        scope.launch {
            container.settings.update { it.copy(onboardingDone = true) }
            Log.i(MicrophoneRecorder.TAG, "onboarding done")
            go(Screen.home)
        }
    }
    CompositionLocalProvider(LocalDemoMode provides demoMode) {
        NavHost(navController = navController, startDestination = start.route) {
            Screen.entries.forEach { screen ->
                composable(screen.route) {
                    val step = screen.onboardingStep
                    if (step != null) {
                        LaunchedEffect(step) {
                            Log.i(MicrophoneRecorder.TAG, "onboarding step $step")
                            container.settings.update { it.copy(onboardingStep = step) }
                        }
                        val previous = step.previous
                        val toPrevious = { previous?.let { go(it.screen) } ?: Unit }
                        if (previous != null) BackHandler(onBack = toPrevious)
                        when (step) {
                            OnboardingStep.Welcome -> WelcomeScreen(onStart = { go(Screen.MicPermission) })
                            OnboardingStep.MicPermission -> MicPermissionScreen(
                                onGranted = { go(Screen.Trial) },
                                // No microphone, no trial: on to the bubble, which asks again on its first tap.
                                onLater = { go(Screen.BubbleIntro) },
                                onBack = toPrevious,
                            )
                            OnboardingStep.Trial -> TrialScreen(onContinue = { go(Screen.BubbleIntro) }, onBack = toPrevious)
                            OnboardingStep.BubbleIntro -> BubbleIntroScreen(
                                onEnabled = {
                                    scope.launch { container.settings.update { it.copy(bubbleEnabled = true) } }
                                    // Shown even if the user had hidden it before.
                                    BubbleService.show(context)
                                    go(Screen.BubbleEnabled)
                                },
                                onNotNow = finishOnboarding,
                                onBack = toPrevious,
                            )
                            OnboardingStep.BubbleEnabled -> BubbleEnabledScreen(
                                notificationsAsked = { container.settings.settings.first().notificationsAsked },
                                onNotificationsAsked = {
                                    scope.launch { container.settings.update { it.copy(notificationsAsked = true) } }
                                },
                                onFinish = finishOnboarding,
                                onAutoPaste = { navController.navigate(Screen.AutoPasteDisclosure.route) },
                                onBack = toPrevious,
                            )
                        }
                        return@composable
                    }
                    when (screen) {
                        Screen.History -> HistoryScreen(
                            history = container.history,
                            onBack = back,
                            onOpenSettings = { navController.navigate(Screen.Settings.route) },
                        )
                        Screen.Settings -> SettingsScreen(container.settings, container.history, onBack = back)
                        else -> PlaceholderScreen(screen, onOpen = { navController.navigate(it.route) })
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PlaceholderScreen(screen: Screen, onOpen: (Screen) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(Dimens.ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(Dimens.VerticalSpacing),
        ) {
            Text(
                stringResource(R.string.screen_heading, screen.number, stringResource(screen.title)),
                style = MaterialTheme.typography.headlineSmall,
            )
            screen.links.forEach { target ->
                OutlinedButton(
                    onClick = { onOpen(target) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.ButtonHeight),
                ) {
                    Text(stringResource(R.string.screen_heading, target.number, stringResource(target.title)))
                }
            }
        }
        // No top bar here: the label sits in the corner, clear of the heading's start.
        if (LocalDemoMode.current) {
            Box(Modifier.safeDrawingPadding().padding(8.dp), contentAlignment = Alignment.TopEnd) {
                DemoModeLabel()
            }
        }
    }
}
