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
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.onboarding.TrialPreview
import tech.dmconsulting.voxbulle.transcription.TranscriptionPipeline
import tech.dmconsulting.voxbulle.ui.theme.Dimens

/**
 * Every screen is an empty placeholder until its ticket replaces it here.
 * [demoMode]: debug build on the fake Voxtral, shown by a « Mode démo » label.
 */
@Composable
fun VoxbulleNavHost(pipeline: TranscriptionPipeline, demoMode: Boolean) {
    val navController = rememberNavController()
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = Screen.start.route) {
            Screen.entries.forEach { screen ->
                composable(screen.route) {
                    PlaceholderScreen(screen, onOpen = { navController.navigate(it.route) }) {
                        // Real calls need a recording: only the fakes run from this preview.
                        if (screen == Screen.Trial && demoMode) TrialPreview(pipeline)
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
