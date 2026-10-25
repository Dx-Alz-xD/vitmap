package com.vitmap.app.feature.chess

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.theme.MonoStyle
import com.vitmap.core.chess.Piece
import com.vitmap.core.chess.Position

private const val TOOL_MOVE = -1
private const val TOOL_ERASE = -2

/** Free-form position editor: place, move and remove pieces, set side to move and castling. */
@Composable
fun BoardEditor(
    initialFen: String,
    theme: BoardTheme,
    onClose: () -> Unit,
    onUse: (fen: String, mode: ChessMode) -> Unit,
) {
    val start = remember(initialFen) { runCatching { Position.fromFen(initialFen) }.getOrElse { Position.start() } }
    var board by remember { mutableStateOf(start.board.copyOf()) }
    var whiteToMove by remember { mutableStateOf(start.side == Piece.WHITE) }
    var castling by remember { mutableIntStateOf(start.castling) }
    var tool by remember { mutableIntStateOf(Piece.WP) }
    var flipped by remember { mutableStateOf(false) }
    var fenField by remember { mutableStateOf(initialFen) }
    var fenError by remember { mutableStateOf<String?>(null) }

    fun currentFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = board[r * 8 + f]
                if (p == 0) empty++ else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(Piece.toChar(p))
                }
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        // Only keep castling rights the placement allows.
        var c = castling
        if (board[4] != Piece.WK) c = c and 3.inv()
        if (board[7] != Piece.WR) c = c and 1.inv()
        if (board[0] != Piece.WR) c = c and 2.inv()
        if (board[60] != Piece.BK) c = c and 12.inv()
        if (board[63] != Piece.BR) c = c and 4.inv()
        if (board[56] != Piece.BR) c = c and 8.inv()
        val cs = buildString {
            if (c and 1 != 0) append('K')
            if (c and 2 != 0) append('Q')
            if (c and 4 != 0) append('k')
            if (c and 8 != 0) append('q')
        }
        sb.append(cs.ifEmpty { "-" }).append(" - 0 1")
        return sb.toString()
    }

    fun changed() {
        fenField = currentFen()
        fenError = null
    }

    val fen = currentFen()
    val problem = remember(fen) { Position.validate(fen) }

    ToolScaffold(
        title = "Edit position",
        onBack = onClose,
        actions = {
            IconButton(onClick = { flipped = !flipped }) { Icon(Icons.Rounded.SwapVert, "Flip") }
            IconButton(onClick = {
                board = IntArray(64)
                castling = 0
                changed()
            }) { Icon(Icons.Rounded.DeleteSweep, "Clear board") }
            IconButton(onClick = {
                val p = Position.start()
                board = p.board.copyOf()
                castling = 15
                whiteToMove = true
                changed()
            }) { Icon(Icons.Rounded.RestartAlt, "Start position") }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChessBoard(
                board = board,
                flipped = flipped,
                theme = theme,
                animate = false,
                onTap = { sq ->
                    when (tool) {
                        TOOL_MOVE -> {}
                        TOOL_ERASE -> board = board.copyOf().also { it[sq] = 0 }
                        else -> {
                            val b = board.copyOf()
                            // Kings are unique: placing one moves it.
                            if (Piece.type(tool) == Piece.KING) for (i in 0 until 64) if (b[i] == tool) b[i] = 0
                            b[sq] = if (b[sq] == tool) 0 else tool
                            board = b
                        }
                    }
                    changed()
                },
                onDragStart = { sq -> board[sq] != 0 },
                onDrop = { from, to ->
                    if (from != to) {
                        val b = board.copyOf()
                        b[to] = b[from]
                        b[from] = 0
                        board = b
                        changed()
                    }
                },
            )

            // Palette
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (color in listOf(Piece.WHITE, Piece.BLACK)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in listOf(Piece.KING, Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT, Piece.PAWN)) {
                            val p = Piece.make(color, t)
                            PaletteCell(selected = tool == p, modifier = Modifier.weight(1f), onClick = { tool = p }) {
                                Image(painterResource(pieceDrawable(p)), null, Modifier.size(36.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolCell(Icons.Rounded.PanTool, "Move", tool == TOOL_MOVE, Modifier.weight(1f)) { tool = TOOL_MOVE }
                    ToolCell(Icons.Rounded.Clear, "Erase", tool == TOOL_ERASE, Modifier.weight(1f)) { tool = TOOL_ERASE }
                }
            }

            Text("Side to move", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(whiteToMove, { whiteToMove = true; changed() }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("White") }
                SegmentedButton(!whiteToMove, { whiteToMove = false; changed() }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Black") }
            }

            Text("Castling rights", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((bit, label) in listOf(1 to "White O-O", 2 to "White O-O-O", 4 to "Black O-O", 8 to "Black O-O-O")) {
                    FilterChip(
                        selected = castling and bit != 0,
                        onClick = { castling = castling xor bit; changed() },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            OutlinedTextField(
                value = fenField,
                onValueChange = { fenField = it; fenError = null },
                label = { Text("FEN") },
                textStyle = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        val err = Position.validate(fenField)
                        if (err != null) fenError = err else {
                            val p = Position.fromFen(fenField)
                            board = p.board.copyOf()
                            whiteToMove = p.side == Piece.WHITE
                            castling = p.castling
                            fenError = null
                        }
                    }) { Text("Apply") }
                },
                isError = fenError != null,
            )
            AnimatedVisibility(fenError != null || problem != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(fenError ?: problem ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onUse(fen, ChessMode.ANALYSIS) }, enabled = problem == null, modifier = Modifier.weight(1f)) { Text("Analyse") }
                FilledTonalButton(onClick = { onUse(fen, ChessMode.PVP) }, enabled = problem == null, modifier = Modifier.weight(1f)) { Text("2 players") }
            }
            Button(onClick = { onUse(fen, ChessMode.VS_BOT) }, enabled = problem == null, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Rounded.Check, null)
                Spacer(Modifier.size(8.dp))
                Text("Play vs bot from here")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PaletteCell(selected: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, label = "cell")
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "cellBorder")
    Box(
        modifier
            .aspectRatio(1f)
            .bouncyClickable(shape = RoundedCornerShape(14.dp), onClick = onClick)
            .background(bg)
            .border(2.dp, border, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ToolCell(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, label = "tool")
    Row(
        modifier
            .height(44.dp)
            .bouncyClickable(shape = RoundedCornerShape(14.dp), onClick = onClick)
            .background(bg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
