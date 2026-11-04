package tech.dmconsulting.voxbulle.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every role listed in the charter (DMC-121, § 2), light and dark. */
class ColorTest {
    private fun charterRoles(s: ColorScheme) = mapOf(
        "primary" to s.primary, "onPrimary" to s.onPrimary,
        "primaryContainer" to s.primaryContainer, "onPrimaryContainer" to s.onPrimaryContainer,
        "secondary" to s.secondary, "onSecondary" to s.onSecondary,
        "tertiary" to s.tertiary, "onTertiary" to s.onTertiary,
        "surface" to s.surface, "background" to s.background,
        "onSurface" to s.onSurface, "onSurfaceVariant" to s.onSurfaceVariant,
        "surfaceContainer" to s.surfaceContainer, "outline" to s.outline,
        "error" to s.error, "onError" to s.onError,
    )

    private fun hex(vararg pairs: Pair<String, Long>) = pairs.associate { (k, v) -> k to Color(v) }

    @Test
    fun lightMatchesCharter() {
        val expected = hex(
            "primary" to 0xFF3A47C8, "onPrimary" to 0xFFFFFFFF,
            "primaryContainer" to 0xFFE0E1FF, "onPrimaryContainer" to 0xFF0B1058,
            "secondary" to 0xFF00696B, "onSecondary" to 0xFFFFFFFF,
            "tertiary" to 0xFFB3402A, "onTertiary" to 0xFFFFFFFF,
            "surface" to 0xFFFBF8FF, "background" to 0xFFFBF8FF,
            "onSurface" to 0xFF1B1B21, "onSurfaceVariant" to 0xFF46464F,
            "surfaceContainer" to 0xFFEFEDF4, "outline" to 0xFF777680,
            "error" to 0xFFBA1A1A, "onError" to 0xFFFFFFFF,
        )
        assertEquals(expected, charterRoles(VoxbulleLightColors))
    }

    @Test
    fun darkMatchesCharter() {
        val expected = hex(
            "primary" to 0xFFBDC2FF, "onPrimary" to 0xFF0E1780,
            "primaryContainer" to 0xFF2A34AE, "onPrimaryContainer" to 0xFFE0E1FF,
            "secondary" to 0xFF4DDAD8, "onSecondary" to 0xFF003737,
            "tertiary" to 0xFFFFB4A4, "onTertiary" to 0xFF5E1100,
            "surface" to 0xFF121318, "background" to 0xFF121318,
            "onSurface" to 0xFFE4E1E9, "onSurfaceVariant" to 0xFFC7C5D0,
            "surfaceContainer" to 0xFF1F1F25, "outline" to 0xFF918F9A,
            "error" to 0xFFFFB4AB, "onError" to 0xFF690005,
        )
        assertEquals(expected, charterRoles(VoxbulleDarkColors))
    }
}
