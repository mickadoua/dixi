package tech.dmconsulting.dixi.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every role set by the neon charter (DMC-213, `charte-neon`). */
class ColorTest {
    private fun hex(vararg pairs: Pair<String, Long>) = pairs.associate { (k, v) -> k to Color(v) }

    @Test
    fun matchesNeonCharter() {
        val s = DixiColors
        val actual = mapOf(
            "primary" to s.primary, "onPrimary" to s.onPrimary,
            "secondary" to s.secondary, "tertiary" to s.tertiary,
            "background" to s.background, "surface" to s.surface,
            "onSurface" to s.onSurface, "onSurfaceVariant" to s.onSurfaceVariant,
            "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
            "outline" to s.outline, "outlineVariant" to s.outlineVariant,
        )
        val expected = hex(
            "primary" to 0xFFFF7A1A, "onPrimary" to 0xFF0A0A0C,
            "secondary" to 0xFF2BE4FF, "tertiary" to 0xFFFF3D9A,
            "background" to 0xFF050507, "surface" to 0xFF050507,
            "onSurface" to 0xFFF5F5F7, "onSurfaceVariant" to 0xFFA9A9B8,
            "surfaceContainer" to 0xFF0E0E14, "surfaceContainerHigh" to 0xFF171720,
            "outline" to 0xFF6E6E7E, "outlineVariant" to 0xFF262631,
        )
        assertEquals(expected, actual)
    }

    /** WCAG contrast of the text pairs the charter measures (AA: 4.5:1 text, 3:1 non-text). */
    @Test
    fun textPairsMeetAA() {
        fun lum(c: Color) = listOf(c.red, c.green, c.blue)
            .map { if (it <= 0.03928f) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            .let { (r, g, b) -> 0.2126 * r + 0.7152 * g + 0.0722 * b }
        fun ratio(a: Color, b: Color) = (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)
        val text = listOf(
            Neon.Text to Neon.Bg,
            Neon.TextMuted to Neon.Surface,
            Neon.OrangeText to Neon.Bg,
            Neon.OrangeText to Neon.Surface,
            Neon.OnOrange to Neon.Orange,
            Neon.Cyan to Neon.Bg,
            Neon.Magenta to Neon.Surface,
        )
        text.forEach { (fg, bg) -> assert(ratio(fg, bg) >= 4.5) { "$fg on $bg: ${ratio(fg, bg)}" } }
        assert(ratio(Neon.Outline, Neon.Bg) >= 3.0)
    }
}
