package com.vitmap.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vitmap.app.R

private fun outfit(weight: Int) = Font(
    R.font.outfit,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun mono(weight: Int) = Font(
    R.font.jetbrains_mono,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Outfit = FontFamily(outfit(300), outfit(400), outfit(500), outfit(600), outfit(700), outfit(800))
val Mono = FontFamily(mono(400), mono(500), mono(700))

private val base = Typography()

val VitmapTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontFamily = Outfit, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = Outfit, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = Outfit, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = Outfit, fontWeight = FontWeight.Medium),
)

val MonoStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium)
