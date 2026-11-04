package tech.dmconsulting.voxbulle.ui.navigation

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tech.dmconsulting.voxbulle.R
import tech.dmconsulting.voxbulle.ui.theme.Dimens

/** Every screen is an empty placeholder until its ticket replaces it here. */
@Composable
fun VoxbulleNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Screen.start.route) {
        Screen.entries.forEach { screen ->
            composable(screen.route) {
                PlaceholderScreen(screen, onOpen = { navController.navigate(it.route) })
            }
        }
    }
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
    }
}
