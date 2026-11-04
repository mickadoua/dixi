package tech.dmconsulting.voxbulle.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import tech.dmconsulting.voxbulle.R

/** Debug build on the fake Voxtral: every screen shows the « Mode démo » label. */
val LocalDemoMode = staticCompositionLocalOf { false }

/**
 * Small, out of the way, but on every screen: testers know nothing is sent.
 * Each screen places it: in the title row of [VoxbulleTopBar], so it never
 * covers the bar's actions, or in a corner on screens without a top bar.
 */
@Composable
fun DemoModeLabel(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.demo_mode_description)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            stringResource(R.string.demo_mode),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
