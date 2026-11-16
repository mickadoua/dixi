package tech.dmconsulting.dixi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Dixi Material 3 theme: one dark neon theme, whatever the system setting. */
@Composable
fun DixiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DixiColors,
        typography = DixiTypography,
        shapes = DixiShapes,
        content = content,
    )
}
