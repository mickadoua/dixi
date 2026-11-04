package tech.dmconsulting.voxbulle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Voxbulle Material 3 theme. Follows the system light/dark setting. */
@Composable
fun VoxbulleTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) VoxbulleDarkColors else VoxbulleLightColors,
        typography = VoxbulleTypography,
        shapes = VoxbulleShapes,
        content = content,
    )
}
