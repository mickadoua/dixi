package tech.dmconsulting.voxbulle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Voxbulle Material 3 theme: one dark neon theme, whatever the system setting. */
@Composable
fun VoxbulleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VoxbulleColors,
        typography = VoxbulleTypography,
        shapes = VoxbulleShapes,
        content = content,
    )
}
