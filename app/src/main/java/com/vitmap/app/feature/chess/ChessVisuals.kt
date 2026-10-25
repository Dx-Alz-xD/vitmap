package com.vitmap.app.feature.chess

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.vitmap.app.R
import com.vitmap.core.chess.MoveQuality
import com.vitmap.core.chess.Piece

data class BoardTheme(val name: String, val light: Color, val dark: Color, val highlight: Color)

val BoardThemes = listOf(
    BoardTheme("Vitmap", Color(0xFFE9E3F7), Color(0xFF8C73CF), Color(0x99FFD54F)),
    BoardTheme("Tournament", Color(0xFFEEEED2), Color(0xFF769656), Color(0x99F6F669)),
    BoardTheme("Walnut", Color(0xFFF0D9B5), Color(0xFFB58863), Color(0x99CDD26A)),
    BoardTheme("Glacier", Color(0xFFDEE3E6), Color(0xFF8CA2AD), Color(0x998FD6FF)),
    BoardTheme("Coral", Color(0xFFFCE4D6), Color(0xFFE07A5F), Color(0x99FFE08A)),
    BoardTheme("Midnight", Color(0xFF7D8796), Color(0xFF3B4252), Color(0x9988C0D0)),
    BoardTheme("Mint", Color(0xFFE8F5EC), Color(0xFF5FA382), Color(0x99FFF59D)),
)

fun pieceDrawable(piece: Int): Int {
    val white = Piece.color(piece) == Piece.WHITE
    return when (Piece.type(piece)) {
        Piece.PAWN -> if (white) R.drawable.piece_wp else R.drawable.piece_bp
        Piece.KNIGHT -> if (white) R.drawable.piece_wn else R.drawable.piece_bn
        Piece.BISHOP -> if (white) R.drawable.piece_wb else R.drawable.piece_bb
        Piece.ROOK -> if (white) R.drawable.piece_wr else R.drawable.piece_br
        Piece.QUEEN -> if (white) R.drawable.piece_wq else R.drawable.piece_bq
        else -> if (white) R.drawable.piece_wk else R.drawable.piece_bk
    }
}

val MoveQuality.color: Color
    get() = when (this) {
        MoveQuality.BRILLIANT -> Color(0xFF1BACA6)
        MoveQuality.GREAT -> Color(0xFF5C8BB0)
        MoveQuality.BEST -> Color(0xFF81B64C)
        MoveQuality.EXCELLENT -> Color(0xFF96BC4B)
        MoveQuality.GOOD -> Color(0xFF77915F)
        MoveQuality.BOOK -> Color(0xFFA88865)
        MoveQuality.FORCED -> Color(0xFF8C9AA6)
        MoveQuality.INACCURACY -> Color(0xFFF0C02F)
        MoveQuality.MISTAKE -> Color(0xFFE58F2A)
        MoveQuality.BLUNDER -> Color(0xFFCA3431)
    }

/** Either an icon or a short glyph for the quality badge. */
val MoveQuality.icon: ImageVector?
    get() = when (this) {
        MoveQuality.BEST -> Icons.Rounded.Star
        MoveQuality.EXCELLENT -> Icons.Rounded.ThumbUp
        MoveQuality.GOOD -> Icons.Rounded.Check
        MoveQuality.BOOK -> Icons.Rounded.Book
        MoveQuality.FORCED -> Icons.Rounded.Lock
        else -> null
    }

val MoveQuality.glyph: String
    get() = when (this) {
        MoveQuality.BRILLIANT -> "!!"
        MoveQuality.GREAT -> "!"
        MoveQuality.INACCURACY -> "?!"
        MoveQuality.MISTAKE -> "?"
        MoveQuality.BLUNDER -> "??"
        else -> ""
    }

val MoveQuality.describe: String
    get() = when (this) {
        MoveQuality.BRILLIANT -> "A brilliant sacrifice"
        MoveQuality.GREAT -> "The only good move"
        MoveQuality.BEST -> "The engine's top choice"
        MoveQuality.EXCELLENT -> "Almost as good as the best move"
        MoveQuality.GOOD -> "A solid move"
        MoveQuality.BOOK -> "A known opening move"
        MoveQuality.FORCED -> "The only legal move"
        MoveQuality.INACCURACY -> "A slightly weaker move"
        MoveQuality.MISTAKE -> "A clear mistake"
        MoveQuality.BLUNDER -> "A serious blunder"
    }
