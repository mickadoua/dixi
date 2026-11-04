package tech.dmconsulting.voxbulle.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Charter § 3: Roboto (the system font, FontFamily.Default), sizes in sp,
// nothing below 14 sp.
private val Base = Typography()

private fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = lineHeight.sp, fontWeight = weight)

val VoxbulleTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = FontFamily.Default),
    displayMedium = Base.displayMedium.copy(fontFamily = FontFamily.Default),
    displaySmall = Base.displaySmall.copy(fontFamily = FontFamily.Default),
    // Welcome title.
    headlineLarge = style(32, 40),
    headlineMedium = style(28, 36),
    // Screen title (h2).
    headlineSmall = style(24, 32, FontWeight.Medium),
    // Top app bar.
    titleLarge = style(22, 28),
    // Tagline.
    titleMedium = style(18, 26),
    titleSmall = style(16, 24, FontWeight.Medium),
    // Body text.
    bodyLarge = style(16, 24),
    // Caption: metadata only, never essential information.
    bodyMedium = style(14, 20),
    bodySmall = style(14, 20),
    // Buttons.
    labelLarge = style(16, 24, FontWeight.Medium),
    labelMedium = style(14, 20, FontWeight.Medium),
    labelSmall = style(14, 20, FontWeight.Medium),
)
