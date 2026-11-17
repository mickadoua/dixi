package tech.dmconsulting.dixi.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import tech.dmconsulting.dixi.AppContainer
import tech.dmconsulting.dixi.R
import tech.dmconsulting.dixi.bubble.BubbleService
import tech.dmconsulting.dixi.history.HistoryScreen
import tech.dmconsulting.dixi.home.HomeScreen
import tech.dmconsulting.dixi.offer.OfferScreen
import tech.dmconsulting.dixi.onboarding.BubbleEnabledScreen
import tech.dmconsulting.dixi.onboarding.BubbleIntroScreen
import tech.dmconsulting.dixi.onboarding.GuidedTrialScreen
import tech.dmconsulting.dixi.onboarding.MicPermissionScreen
import tech.dmconsulting.dixi.onboarding.MistralKeyScreen
import tech.dmconsulting.dixi.onboarding.WelcomeScreen
import tech.dmconsulting.dixi.onboarding.onboardingStep
import tech.dmconsulting.dixi.onboarding.previous
import tech.dmconsulting.dixi.onboarding.screen
import tech.dmconsulting.dixi.paste.AutoPasteDisclosureScreen
import tech.dmconsulting.dixi.paste.AutoPasteService
import tech.dmconsulting.dixi.recording.MicrophoneRecorder
import tech.dmconsulting.dixi.settings.OnboardingStep
import tech.dmconsulting.dixi.settings.SettingsScreen
import tech.dmconsulting.dixi.settings.rememberAutoPasteToggle
import tech.dmconsulting.dixi.settings.rememberBubbleToggle
import tech.dmconsulting.dixi.ui.components.LocalDemoMode
import tech.dmconsulting.dixi.ui.theme.Neon

/**
 * Home, History and Settings are tabs (neon mockups 05 and 06).
 * [demoMode]: no Mistral key, Voxtral is faked, shown by a « Mode démo » label
 * that each screen places ([LocalDemoMode]).
 * [openOnTop]: a screen opened once over [start], so « Retour » leads back to it
 * (a dictation notification opens screen 10 this way).
 *
 * Onboarding (mockups DMC-764 § 3): each move replaces the whole back stack, and the
 * back arrow leads to the step before, so a relaunch on any step behaves the same.
 * The step shown is saved; once « Terminer » or « Pas maintenant » is tapped the
 * app opens on [Screen.home].
 */
@Composable
fun DixiNavHost(container: AppContainer, demoMode: Boolean, start: Screen = Screen.start, openOnTop: Screen? = null) {
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
    var openedOnTop by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openOnTop) {
        if (openOnTop != null && !openedOnTop) {
            openedOnTop = true
            navController.navigate(openOnTop.route)
        }
    }
    val entry by navController.currentBackStackEntryAsState()
    val current = Screen.entries.firstOrNull { it.route == entry?.destination?.route } ?: start
    val bubble = rememberBubbleToggle(container.settings)
    val openDisclosure = if (Screen.autoPasteShown) ({ navController.navigate(Screen.AutoPasteDisclosure.route) }) else null
    val autoPaste = openDisclosure?.let { rememberAutoPasteToggle(container.settings, onDisclose = it) }
    val openOffer = if (Screen.offerShown) ({ navController.navigate(Screen.Offer.route) }) else null
    val openTab: (Screen) -> Unit = { tab -> navController.navigateToTab(tab) }
    CompositionLocalProvider(LocalDemoMode provides demoMode) {
        Scaffold(
            containerColor = Neon.Bg,
            bottomBar = { if (current.isTab) TabBar(current, navController) },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = start.route,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            ) {
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
                                    onGranted = { go(Screen.MistralKey) },
                                    // No microphone yet: the bubble asks again on its first tap.
                                    onLater = { go(Screen.MistralKey) },
                                    onBack = toPrevious,
                                )

                                OnboardingStep.MistralKey -> MistralKeyScreen(
                                    container.mistralKey,
                                    container.keyCheck,
                                    onContinue = { go(Screen.BubbleIntro) },
                                    onBack = toPrevious,
                                )

                                OnboardingStep.BubbleIntro -> BubbleIntroScreen(
                                    onEnabled = {
                                        scope.launch { container.settings.update { it.copy(bubbleEnabled = true) } }
                                        // The real bubble shows now, even if the user had hidden it before.
                                        BubbleService.show(context)
                                        go(Screen.Trial)
                                    },
                                    // No bubble, no trial: the home screen says « Bulle inactive ».
                                    onNotNow = finishOnboarding,
                                    onBack = toPrevious,
                                )

                                OnboardingStep.Trial -> GuidedTrialScreen(onDone = { go(Screen.BubbleEnabled) }, onBack = toPrevious)

                                OnboardingStep.BubbleEnabled -> BubbleEnabledScreen(
                                    onFinish = finishOnboarding,
                                    onAutoPaste = openDisclosure,
                                    onBack = toPrevious,
                                )
                            }
                            return@composable
                        }
                        when (screen) {
                            Screen.Home -> HomeScreen(
                                bubble = bubble,
                                history = container.history,
                                keys = container.mistralKey,
                                autoPaste = autoPaste,
                                onOpenSettings = { openTab(Screen.Settings) },
                                onOpenHistory = { openTab(Screen.History) },
                                onOpenOffer = openOffer,
                            )

                            Screen.History -> HistoryScreen(
                                history = container.history,
                                onBack = back,
                                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                            )

                            Screen.Settings -> SettingsScreen(
                                container.settings,
                                container.history,
                                bubble,
                                autoPaste,
                                container.mistralKey,
                                container.keyCheck,
                                onOpenOffer = openOffer,
                            )

                            Screen.Offer -> OfferScreen(onBack = back)

                            Screen.AutoPasteDisclosure -> AutoPasteDisclosureScreen(
                                onBack = back,
                                onAccept = {
                                    scope.launch { container.settings.update { it.copy(autoPasteEnabled = true) } }
                                    // Back to screen 5 or 10 first: it reads the service again on return.
                                    back()
                                    if (!AutoPasteService.isEnabled(context)) context.startActivity(AutoPasteService.settingsIntent())
                                },
                                onDecline = back,
                            )

                            // Onboarding screens: handled above.
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** Bottom tabs: hairline on top, orange for the current tab (text and icon, not color only: selected state). */
@Composable
private fun TabBar(current: Screen, navController: NavHostController) {
    Column {
        HorizontalDivider(color = Neon.Hairline)
        NavigationBar(containerColor = Neon.Bg, tonalElevation = 0.dp) {
            Screen.tabs.forEach { tab ->
                NavigationBarItem(
                    selected = tab == current,
                    onClick = { navController.navigateToTab(tab) },
                    icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                    label = { Text(stringResource(tab.title), style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Neon.OrangeText,
                        selectedTextColor = Neon.OrangeText,
                        indicatorColor = Neon.Surface2,
                        unselectedIconColor = Neon.TextMuted,
                        unselectedTextColor = Neon.TextMuted,
                    ),
                )
            }
        }
    }
}

/** Opens a bottom tab, as the tab bar does: one copy of each, state kept. */
private fun NavHostController.navigateToTab(tab: Screen) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@get:DrawableRes
private val Screen.icon: Int
    get() = when (this) {
        Screen.Home -> R.drawable.ic_home
        Screen.History -> R.drawable.ic_history
        else -> R.drawable.ic_settings
    }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
