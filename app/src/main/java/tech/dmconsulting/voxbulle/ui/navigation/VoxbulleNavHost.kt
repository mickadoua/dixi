package tech.dmconsulting.voxbulle.ui.navigation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tech.dmconsulting.voxbulle.AppContainer
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.history.HistoryScreen
import tech.dmconsulting.voxbulle.home.HomeScreen
import tech.dmconsulting.voxbulle.offer.OfferScreen
import tech.dmconsulting.voxbulle.onboarding.TrialPreview
import tech.dmconsulting.voxbulle.onboarding.WelcomeScreen
import tech.dmconsulting.voxbulle.settings.SettingsScreen
import tech.dmconsulting.voxbulle.settings.rememberBubbleToggle
import tech.dmconsulting.voxbulle.ui.components.DemoModeLabel
import tech.dmconsulting.voxbulle.ui.components.LocalDemoMode
import tech.dmconsulting.voxbulle.ui.theme.Dimens
import tech.dmconsulting.voxbulle.ui.theme.Neon

/**
 * Home, History and Settings are tabs (neon mockups 05 and 06). Screens without
 * their ticket yet are empty placeholders.
 * [demoMode]: debug build on the fake Voxtral, shown by a « Mode démo » label
 * that each screen places ([LocalDemoMode]).
 */
@Composable
fun VoxbulleNavHost(container: AppContainer, demoMode: Boolean, start: Screen = Screen.start) {
    val navController = rememberNavController()
    val back: () -> Unit = { navController.popBackStack() }
    val entry by navController.currentBackStackEntryAsState()
    val current = Screen.entries.firstOrNull { it.route == entry?.destination?.route } ?: start
    val bubble = rememberBubbleToggle(container.settings)
    val openOffer = { navController.navigate(Screen.Offer.route) }
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
                        when (screen) {
                            Screen.Home -> HomeScreen(bubble, container.history, onOpenOffer = openOffer)
                            Screen.History -> HistoryScreen(
                                history = container.history,
                                onBack = back,
                                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                            )
                            Screen.Settings -> SettingsScreen(container.settings, container.history, bubble, onOpenOffer = openOffer)
                            Screen.Offer -> OfferScreen(onBack = back)
                            Screen.Welcome -> WelcomeScreen(onStart = { navController.navigate(Screen.MicPermission.route) })
                            else -> PlaceholderScreen(screen, onOpen = { navController.navigate(it.route) }) {
                                // Real calls need a recording: only the fakes run from this preview.
                                if (screen == Screen.Trial && demoMode) TrialPreview(container.transcription)
                            }
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
                    onClick = {
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
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

@get:DrawableRes
private val Screen.icon: Int
    get() = when (this) {
        Screen.Home -> R.drawable.ic_home
        Screen.History -> R.drawable.ic_history
        else -> R.drawable.ic_settings
    }

@Composable
private fun PlaceholderScreen(screen: Screen, onOpen: (Screen) -> Unit, extra: @Composable () -> Unit) {
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
            extra()
        }
        // No top bar here: the label sits in the corner, clear of the heading's start.
        if (LocalDemoMode.current) {
            Box(Modifier.safeDrawingPadding().padding(8.dp), contentAlignment = Alignment.TopEnd) {
                DemoModeLabel()
            }
        }
    }
}
