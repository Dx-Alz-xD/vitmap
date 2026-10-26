package com.vitmap.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** A two-stop gradient used to give each tool its identity. */
data class Accent(val start: Color, val end: Color) {
    val brush: Brush get() = Brush.linearGradient(listOf(start, end))
    val mid: Color get() = Color((start.red + end.red) / 2, (start.green + end.green) / 2, (start.blue + end.blue) / 2)
}

object Accents {
    val Chess = Accent(Color(0xFF8B5CF6), Color(0xFF4338CA))
    val TicTacToe = Accent(Color(0xFFF43F5E), Color(0xFFF97316))
    val Browser = Accent(Color(0xFF38BDF8), Color(0xFF2563EB))
    val ColorPicker = Accent(Color(0xFFEC4899), Color(0xFFF59E0B))
    val Measure = Accent(Color(0xFF14B8A6), Color(0xFF16A34A))
    val Translator = Accent(Color(0xFF06B6D4), Color(0xFF7C3AED))
    val Convert = Accent(Color(0xFFF59E0B), Color(0xFFDC2626))
    val Brand = Accent(Color(0xFF6D4CFF), Color(0xFF00C2A8))
    val Scanner = Accent(Color(0xFF14B8A6), Color(0xFF3B82F6))
    val PdfTools = Accent(Color(0xFFEF4444), Color(0xFFDB2777))
    val Compress = Accent(Color(0xFF84CC16), Color(0xFF059669))
    val FileConvert = Accent(Color(0xFFF97316), Color(0xFFEAB308))
    val Downloader = Accent(Color(0xFFFF1744), Color(0xFFFF6D00))
    val ForEx = Accent(Color(0xFF16A34A), Color(0xFFCA8A04))
    val Clock = Accent(Color(0xFF6366F1), Color(0xFF0EA5E9))
    val Sudoku = Accent(Color(0xFF3B82F6), Color(0xFF8B5CF6))
    val Code = Accent(Color(0xFF22D3EE), Color(0xFF0F766E))
    val PhotoEditor = Accent(Color(0xFFD946EF), Color(0xFF6366F1))
}

val LightColors = lightColorScheme(
    primary = Color(0xFF5B3DF5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1B0063),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8F2EA),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFFE5484D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD8),
    onTertiaryContainer = Color(0xFF410006),
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFF8F7FC),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE5E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F1F9),
    surfaceContainer = Color(0xFFEDEBF4),
    surfaceContainerHigh = Color(0xFFE7E5EF),
    surfaceContainerHighest = Color(0xFFE1DFE9),
    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC8C5D0),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFC9BEFF),
    onPrimary = Color(0xFF2E0E9E),
    primaryContainer = Color(0xFF4629D4),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFF6FDBC9),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005047),
    onSecondaryContainer = Color(0xFFC8F2EA),
    tertiary = Color(0xFFFFB3AF),
    onTertiary = Color(0xFF68000F),
    tertiaryContainer = Color(0xFF93001C),
    onTertiaryContainer = Color(0xFFFFDAD8),
    background = Color(0xFF0E0D16),
    onBackground = Color(0xFFE5E1EA),
    surface = Color(0xFF0E0D16),
    onSurface = Color(0xFFE5E1EA),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    surfaceContainerLowest = Color(0xFF09080F),
    surfaceContainerLow = Color(0xFF16151F),
    surfaceContainer = Color(0xFF1B1A24),
    surfaceContainerHigh = Color(0xFF25242F),
    surfaceContainerHighest = Color(0xFF302F3A),
    outline = Color(0xFF928F9A),
    outlineVariant = Color(0xFF47464F),
)
