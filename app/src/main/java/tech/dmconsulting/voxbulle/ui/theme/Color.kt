package tech.dmconsulting.voxbulle.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** Neon charter colors (DMC-213, `charte-neon`, § Couleurs). Dark only. */
object Neon {
    val Bg = Color(0xFF050507)
    val Surface = Color(0xFF0E0E14)
    val Surface2 = Color(0xFF171720)

    /** Separators, decoration only. */
    val Hairline = Color(0xFF262631)

    /** Switch outlines, secondary button: 4.07:1 on bg. */
    val Outline = Color(0xFF6E6E7E)
    val Text = Color(0xFFF5F5F7)
    val TextMuted = Color(0xFFA9A9B8)

    /** Accent: filled buttons, outlines, active switch. */
    val Orange = Color(0xFFFF7A1A)

    /** Orange text: 9.0:1 on bg, 8.5:1 on a card. */
    val OrangeText = Color(0xFFFF8F3D)
    val OnOrange = Color(0xFF0A0A0C)

    /** System states: processing, « Texte copié », active. */
    val Cyan = Color(0xFF2BE4FF)

    /** Paid offer and hiding only. */
    val Magenta = Color(0xFFFF3D9A)
}

// Roles marked « charte » come from `charte-neon` and win. Error keeps the
// Material 3 dark tones (the charter does not set it). No dynamic color.
val VoxbulleColors = darkColorScheme(
    primary = Neon.Orange, // charte
    onPrimary = Neon.OnOrange, // charte
    primaryContainer = Neon.Surface2,
    onPrimaryContainer = Neon.OrangeText,
    inversePrimary = Neon.Orange,
    secondary = Neon.Cyan, // charte
    onSecondary = Neon.OnOrange,
    secondaryContainer = Neon.Surface2,
    onSecondaryContainer = Neon.Cyan,
    tertiary = Neon.Magenta, // charte
    onTertiary = Neon.OnOrange,
    tertiaryContainer = Neon.Surface2,
    onTertiaryContainer = Neon.Magenta,
    background = Neon.Bg, // charte
    onBackground = Neon.Text, // charte
    surface = Neon.Bg, // charte
    onSurface = Neon.Text, // charte
    surfaceVariant = Neon.Surface2,
    onSurfaceVariant = Neon.TextMuted, // charte
    surfaceTint = Color.Transparent,
    inverseSurface = Neon.Text,
    inverseOnSurface = Neon.Bg,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Neon.Outline, // charte
    outlineVariant = Neon.Hairline, // charte
    scrim = Color(0xFF000000),
    surfaceBright = Neon.Surface2,
    surfaceContainerLowest = Neon.Bg,
    surfaceContainerLow = Neon.Surface,
    surfaceContainer = Neon.Surface, // charte: cards
    surfaceContainerHigh = Neon.Surface2, // charte: toasts, raised items
    surfaceContainerHighest = Neon.Surface2,
    surfaceDim = Neon.Bg,
)
