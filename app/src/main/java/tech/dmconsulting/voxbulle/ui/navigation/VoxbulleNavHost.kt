package tech.dmconsulting.voxbulle.ui.navigation

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tech.dmconsulting.voxbulle.AppContainer
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.history.HistoryScreen
import tech.dmconsulting.voxbulle.onboarding.TrialPreview
import tech.dmconsulting.voxbulle.settings.SettingsScreen
import tech.dmconsulting.voxbulle.ui.theme.Dimens

/**
 * Screens without their ticket yet are empty placeholders.
 * [demoMode]: debug build on the fake Voxtral, shown by a « Mode démo » label.
 */
@Composable
fun VoxbulleNavHost(container: AppContainer, demoMode: Boolean, start: Screen = Screen.start) {
    val navController = rememberNavController()
    val back: () -> Unit = { navController.popBackStack() }
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = start.route) {
            Screen.entries.forEach { screen ->
                composable(screen.route) {
                    when (screen) {
                        Screen.History -> HistoryScreen(
                            history = container.history,
                            onBack = back,
                            onOpenSettings = { navController.navigate(Screen.Settings.route) },
                        )
                        Screen.Settings -> SettingsScreen(container.settings, container.history, onBack = back)
                        else -> PlaceholderScreen(screen, onOpen = { navController.navigate(it.route) }) {
                            // Real calls need a recording: only the fakes run from this preview.
                            if (screen == Screen.Trial && demoMode) TrialPreview(container.transcription)
                        }
                    }
                }
            }
        }
        if (demoMode) DemoModeLabel(Modifier.align(Alignment.TopEnd))
    }
}

/** Small, out of the way, but on every screen: testers know nothing is sent. */
@Composable
private fun DemoModeLabel(modifier: Modifier) {
    val description = stringResource(R.string.demo_mode_description)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .safeDrawingPadding()
            .padding(8.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            stringResource(R.string.demo_mode),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
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
    }
}
