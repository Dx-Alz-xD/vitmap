package com.vitmap.app.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Grid3x3
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.vitmap.app.R
import com.vitmap.app.ui.theme.Accent
import com.vitmap.app.ui.theme.Accents

enum class ToolCategory(val title: String, val icon: ImageVector) {
    GAMES("Games", Icons.Rounded.SportsEsports),
    CAMERA("Camera & vision", Icons.Rounded.Videocam),
    DOCUMENTS("Documents & PDF", Icons.Rounded.Description),
    MEDIA("Media & files", Icons.Rounded.PermMedia),
    CREATIVE("Creative", Icons.Rounded.Brush),
    DEVELOPER("Developer", Icons.Rounded.Code),
    LANGUAGE("Language", Icons.Rounded.Translate),
    WEB("Internet", Icons.Rounded.Public),
    UTILITIES("Utilities", Icons.Rounded.Widgets),
}

enum class Connectivity(val label: String) {
    OFFLINE("Offline"),
    OFFLINE_AFTER_SETUP("Offline after setup"),
    ONLINE("Needs internet"),
}

/** Either a Material icon or a drawable resource (the chess knight). */
sealed interface ToolIcon {
    data class Vector(val icon: ImageVector) : ToolIcon
    data class Drawable(val res: Int) : ToolIcon
}

data class Tool(
    val id: String,
    val title: String,
    val tagline: String,
    val icon: ToolIcon,
    val category: ToolCategory,
    val accent: Accent,
    val connectivity: Connectivity,
    val keywords: List<String> = emptyList(),
) {
    fun matches(q: String): Boolean {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return true
        return title.lowercase().contains(s) || tagline.lowercase().contains(s) || keywords.any { it.contains(s) } ||
            category.title.lowercase().contains(s)
    }
}

/** Every tool in the app. Add a new entry here (and a branch in ToolHost) to ship a new tool. */
object Tools {
    val all: List<Tool> = listOf(
        Tool(
            "chess", "Chess", "Play bots from 100 to 3000 Elo, analyse every move, edit any position",
            ToolIcon.Drawable(R.drawable.piece_wn), ToolCategory.GAMES, Accents.Chess, Connectivity.OFFLINE,
            listOf("fen", "pgn", "engine", "bot", "analysis", "board"),
        ),
        Tool(
            "tictactoe", "Tic-Tac-Toe", "Classic 3×3 or any N×N board, against a friend or a bot",
            ToolIcon.Vector(Icons.Rounded.Grid3x3), ToolCategory.GAMES, Accents.TicTacToe, Connectivity.OFFLINE,
            listOf("noughts", "crosses", "gomoku", "xo"),
        ),
        Tool(
            "sudoku", "Sudoku", "Generated puzzles from easy to master, 4×4 to 16×16, with explained hints",
            ToolIcon.Vector(Icons.Rounded.Apps), ToolCategory.GAMES, Accents.Sudoku, Connectivity.OFFLINE,
            listOf("puzzle", "numbers", "logic", "daily", "grid"),
        ),
        Tool(
            "color", "Color Picker", "Point your camera to identify colours live, compare and pick from photos",
            ToolIcon.Vector(Icons.Rounded.Colorize), ToolCategory.CAMERA, Accents.ColorPicker, Connectivity.OFFLINE,
            listOf("hex", "rgb", "cmyk", "hsl", "oklch", "palette", "camera"),
        ),
        Tool(
            "measure", "Measure", "Measure real objects from photos using cards, coins and paper as references",
            ToolIcon.Vector(Icons.Rounded.Straighten), ToolCategory.CAMERA, Accents.Measure, Connectivity.OFFLINE,
            listOf("ruler", "length", "size", "distance", "area"),
        ),
        Tool(
            "scanner", "PDF Scanner", "Auto edge detection, auto or manual capture, filters, then edit and save as PDF",
            ToolIcon.Vector(Icons.Rounded.DocumentScanner), ToolCategory.DOCUMENTS, Accents.Scanner, Connectivity.OFFLINE,
            listOf("scan", "camera", "document", "pdf", "receipt", "crop", "sign", "ocr"),
        ),
        Tool(
            "pdftools", "PDF Tools", "Merge, split, edit, sign, protect, watermark and number pages",
            ToolIcon.Vector(Icons.Rounded.PictureAsPdf), ToolCategory.DOCUMENTS, Accents.PdfTools, Connectivity.OFFLINE,
            listOf("pdf", "merge", "combine", "split", "sign", "esign", "signature", "password", "annotate", "edit", "watermark"),
        ),
        Tool(
            "compress", "Compress", "Shrink photos, videos, audio, PDFs and documents to the exact size you need",
            ToolIcon.Vector(Icons.Rounded.Compress), ToolCategory.MEDIA, Accents.Compress, Connectivity.OFFLINE_AFTER_SETUP,
            listOf("shrink", "reduce", "size", "mb", "kb", "zip", "smaller", "upload limit", "email"),
        ),
        Tool(
            "translate", "Translator", "Text, photo, voice and document translation across 59 languages",
            ToolIcon.Vector(Icons.Rounded.Translate), ToolCategory.LANGUAGE, Accents.Translator, Connectivity.OFFLINE_AFTER_SETUP,
            listOf("language", "dictionary", "synonyms", "definition", "ocr", "speech"),
        ),
        Tool(
            "browser", "AllBrowser", "Tabs, incognito, downloads, bookmarks and a built-in element inspector",
            ToolIcon.Vector(Icons.Rounded.Language), ToolCategory.WEB, Accents.Browser, Connectivity.ONLINE,
            listOf("web", "internet", "inspect", "devtools", "html"),
        ),
        Tool(
            "clock", "World Clock", "Live clocks on a world map with day and night, sunrise times and a meeting planner",
            ToolIcon.Vector(Icons.Rounded.Schedule), ToolCategory.UTILITIES, Accents.Clock, Connectivity.OFFLINE,
            listOf("time", "timezone", "zone", "city", "map", "utc", "meeting", "sunrise"),
        ),
        Tool(
            "forex", "ForEx", "348 currencies: every fiat currency, local and historic ones, metals and crypto",
            ToolIcon.Vector(Icons.Rounded.CurrencyExchange), ToolCategory.UTILITIES, Accents.ForEx, Connectivity.OFFLINE_AFTER_SETUP,
            listOf("currency", "exchange", "money", "rate", "dollar", "euro", "rupee", "bitcoin", "gold"),
        ),
        Tool(
            "convert", "Conversion", "Nearly 800 units in 49 categories, from metres to smoots, plus your own",
            ToolIcon.Vector(Icons.Rounded.SwapHoriz), ToolCategory.UTILITIES, Accents.Convert, Connectivity.OFFLINE,
            listOf("units", "length", "weight", "temperature", "calculator", "metric", "imperial"),
        ),
    )

    fun byId(id: String): Tool? = all.firstOrNull { it.id == id }

    val placeholderIcon: ImageVector = Icons.Rounded.Extension
}
