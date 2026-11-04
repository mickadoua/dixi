package tech.dmconsulting.voxbulle.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Roles marked « charte » come from the Voxbulle charter, § 2 (DMC-121) and win.
// The others are Material 3 tones generated with material-color-utilities from
// the charter's colors: primary and neutrals from #3A47C8, secondary from
// #00696B, tertiary from #B3402A. No dynamic color (Material You) in v1.

val VoxbulleLightColors = lightColorScheme(
    primary = Color(0xFF3A47C8), // charte
    onPrimary = Color(0xFFFFFFFF), // charte
    primaryContainer = Color(0xFFE0E1FF), // charte
    onPrimaryContainer = Color(0xFF0B1058), // charte
    inversePrimary = Color(0xFFBDC2FF),
    secondary = Color(0xFF00696B), // charte
    onSecondary = Color(0xFFFFFFFF), // charte
    secondaryContainer = Color(0xFFA1F0F2),
    onSecondaryContainer = Color(0xFF002021),
    tertiary = Color(0xFFB3402A), // charte
    onTertiary = Color(0xFFFFFFFF), // charte
    tertiaryContainer = Color(0xFFFFDAD3),
    onTertiaryContainer = Color(0xFF3E0400),
    background = Color(0xFFFBF8FF), // charte
    onBackground = Color(0xFF1B1B21), // charte
    surface = Color(0xFFFBF8FF), // charte
    onSurface = Color(0xFF1B1B21), // charte
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F), // charte
    surfaceTint = Color(0xFF3A47C8),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2EFF7),
    error = Color(0xFFBA1A1A), // charte
    onError = Color(0xFFFFFFFF), // charte
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF777680), // charte
    outlineVariant = Color(0xFFC7C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceContainer = Color(0xFFEFEDF4), // charte
    surfaceContainerHigh = Color(0xFFEAE7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDBD9E0),
    primaryFixed = Color(0xFFE0E1FF),
    primaryFixedDim = Color(0xFFBDC2FF),
    onPrimaryFixed = Color(0xFF0B1058),
    onPrimaryFixedVariant = Color(0xFF2A34AE),
    secondaryFixed = Color(0xFFA1F0F2),
    secondaryFixedDim = Color(0xFF85D4D5),
    onSecondaryFixed = Color(0xFF002021),
    onSecondaryFixedVariant = Color(0xFF004F51),
    tertiaryFixed = Color(0xFFFFDAD3),
    tertiaryFixedDim = Color(0xFFFFB4A4),
    onTertiaryFixed = Color(0xFF3E0400),
    onTertiaryFixedVariant = Color(0xFF87200E),
)

val VoxbulleDarkColors = darkColorScheme(
    primary = Color(0xFFBDC2FF), // charte
    onPrimary = Color(0xFF0E1780), // charte
    primaryContainer = Color(0xFF2A34AE), // charte
    onPrimaryContainer = Color(0xFFE0E1FF), // charte
    inversePrimary = Color(0xFF3A47C8),
    secondary = Color(0xFF4DDAD8), // charte
    onSecondary = Color(0xFF003737), // charte
    secondaryContainer = Color(0xFF004F51),
    onSecondaryContainer = Color(0xFFA1F0F2),
    tertiary = Color(0xFFFFB4A4), // charte
    onTertiary = Color(0xFF5E1100), // charte
    tertiaryContainer = Color(0xFF87200E),
    onTertiaryContainer = Color(0xFFFFDAD3),
    background = Color(0xFF121318), // charte
    onBackground = Color(0xFFE4E1E9), // charte
    surface = Color(0xFF121318), // charte
    onSurface = Color(0xFFE4E1E9), // charte
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0), // charte
    surfaceTint = Color(0xFFBDC2FF),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
    error = Color(0xFFFFB4AB), // charte
    onError = Color(0xFF690005), // charte
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF918F9A), // charte
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39393F),
    surfaceContainer = Color(0xFF1F1F25), // charte
    surfaceContainerHigh = Color(0xFF29292F),
    surfaceContainerHighest = Color(0xFF34343A),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceDim = Color(0xFF121318),
    primaryFixed = Color(0xFFE0E1FF),
    primaryFixedDim = Color(0xFFBDC2FF),
    onPrimaryFixed = Color(0xFF0B1058),
    onPrimaryFixedVariant = Color(0xFF2A34AE),
    secondaryFixed = Color(0xFFA1F0F2),
    secondaryFixedDim = Color(0xFF85D4D5),
    onSecondaryFixed = Color(0xFF002021),
    onSecondaryFixedVariant = Color(0xFF004F51),
    tertiaryFixed = Color(0xFFFFDAD3),
    tertiaryFixedDim = Color(0xFFFFB4A4),
    onTertiaryFixed = Color(0xFF3E0400),
    onTertiaryFixedVariant = Color(0xFF87200E),
)
