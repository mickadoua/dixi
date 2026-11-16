package tech.dmconsulting.dixi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import tech.dmconsulting.dixi.R

// Neon charter § Typographie: Space Grotesk 700 for titles, figures and the
// brand name; Inter 400/500/600 for text. Both OFL, embedded (res/font).
val SpaceGrotesk = FontFamily(Font(R.font.space_grotesk_bold, FontWeight.Bold))
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val Base = Typography()

private fun title(size: Int, lineHeight: Int) = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = lineHeight.sp)

private fun text(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = lineHeight.sp)

val DixiTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold),
    displayMedium = Base.displayMedium.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold),
    displaySmall = Base.displaySmall.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold),
    // Welcome title, brand name.
    headlineLarge = title(32, 40),
    headlineMedium = title(28, 36),
    // Screen title.
    headlineSmall = title(24, 32),
    // Card and section titles.
    titleLarge = title(20, 28),
    // Tagline.
    titleMedium = text(18, 26, FontWeight.Medium),
    titleSmall = text(16, 24, FontWeight.SemiBold),
    // Body text.
    bodyLarge = text(16, 24),
    // Caption: metadata only, never essential information.
    bodyMedium = text(14, 20),
    bodySmall = text(14, 20),
    // Buttons.
    labelLarge = text(16, 24, FontWeight.SemiBold),
    labelMedium = text(14, 20, FontWeight.Medium),
    // Section label: 12 sp caps, 1.5 sp spacing. Decoration only, never essential information.
    labelSmall = text(12, 16, FontWeight.SemiBold).copy(letterSpacing = 1.5.sp),
)
