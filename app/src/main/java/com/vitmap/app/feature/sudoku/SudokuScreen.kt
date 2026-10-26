package com.vitmap.app.feature.sudoku

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.ui.components.ConfettiOverlay
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.theme.Accents
import com.vitmap.core.sudoku.Difficulty
import com.vitmap.core.sudoku.Variant
import com.vitmap.core.sudoku.bitOf
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

fun digitLabel(d: Int): String = if (d <= 9) d.toString() else ('A' + (d - 10)).toString()

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun SudokuScreen(onBack: () -> Unit) {
    val vm: SudokuViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    var newGame by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.won) { if (s.won) confetti++ }
    val accent = Accents.Sudoku

    ToolScaffold(
        title = "Sudoku",
        subtitle = if (s.generating) "Generating a puzzle…" else buildString {
            if (s.daily != null) append("Daily · ")
            append(s.difficulty.label)
            append(" · ${s.n}×${s.n}")
            if (s.geometry.variant == Variant.DIAGONAL) append(" · diagonal")
        },
        accent = accent,
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.setPaused(!s.paused) }, enabled = !s.generating && !s.won) {
                Icon(if (s.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (s.paused) "Resume" else "Pause")
            }
            IconButton(onClick = { stats = true }) { Icon(Icons.Rounded.BarChart, "Statistics") }
            IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Settings, "Settings") }
            IconButton(onClick = { newGame = true }) { Icon(Icons.Rounded.AddCircleOutline, "New game") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val limit = if (s.prefs.mistakeLimit) "/3" else ""
                    Text("Mistakes ${s.mistakes}$limit", style = MaterialTheme.typography.labelLarge, color = if (s.mistakes > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("Hints ${s.hintsUsed}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(s.elapsed), style = MaterialTheme.typography.titleMedium)
                }
                Box(Modifier.fillMaxWidth().widthIn(max = 560.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
                    Board(s, onTap = vm::select, modifier = Modifier.fillMaxSize())
                    if (s.generating) CircularProgressIndicator(color = accent.start)
                    if (s.paused && !s.generating) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).bouncyClickable(pressedScale = 1f) { vm.setPaused(false) }, contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(56.dp), tint = accent.start)
                                Text("Paused · tap to continue", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                AnimatedVisibility(s.hint != null, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
                    val h = s.hint
                    if (h != null) HintCard(h, onApply = vm::applyHint, onDismiss = vm::dismissHint)
                }
                Spacer(Modifier.height(8.dp))
                Controls(s, vm)
                Spacer(Modifier.height(8.dp))
                NumberPad(s, onDigit = vm::input)
            }
            ConfettiOverlay(confetti, Modifier.fillMaxSize())
            if (s.won) EndCard(
                title = "Solved!",
                body = "${s.difficulty.label} in ${formatTime(s.elapsed)}" + (s.prefs.stats["${s.n}-${s.geometry.variant.name}-${s.difficulty.name}"]?.let { st -> " · best ${formatTime(st.bestMs)} · streak ${st.streak}" } ?: ""),
                primary = "New game" to { newGame = true },
                secondary = null,
            )
            if (s.lost) EndCard(
                title = "Three mistakes",
                body = "That's the limit for this game. You can keep going without it counting toward your streak.",
                primary = "Keep playing" to vm::continueAfterLoss,
                secondary = "New game" to { newGame = true },
            )
        }
    }

    if (newGame) NewGameSheet(s, onDismiss = { newGame = false }, onDaily = { vm.daily(); newGame = false }) { n, v, d ->
        vm.newGame(n, v, d)
        newGame = false
    }
    if (settings) SettingsSheet(s.prefs, onDismiss = { settings = false }, onChange = vm::setPref)
    if (stats) StatsSheet(s, onDismiss = { stats = false })
}

@Composable
private fun Board(s: SudokuState, onTap: (Int) -> Unit, modifier: Modifier) {
    val n = s.n
    val g = s.geometry
    val measurer = rememberTextMeasurer()
    val accent = Accents.Sudoku.start
    val scheme = MaterialTheme.colorScheme
    val lastPlaced = remember { Animatable(1f) }
    var popCell by remember { mutableIntStateOf(-1) }
    val flash = remember { Animatable(0f) }
    val wave = remember { Animatable(0f) }
    var prevValues by remember { mutableStateOf(s.values) }
    LaunchedEffect(s.values) {
        val changed = s.values.indices.firstOrNull { it < prevValues.size && s.values[it] != prevValues[it] && s.values[it] != 0 }
        prevValues = s.values
        if (changed != null) {
            popCell = changed
            lastPlaced.snapTo(0.4f)
            lastPlaced.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
        }
    }
    LaunchedEffect(s.flash.first) {
        if (s.flash.first == 0) return@LaunchedEffect
        flash.snapTo(1f)
        flash.animateTo(0f, tween(700))
    }
    LaunchedEffect(s.won) {
        if (s.won) {
            wave.snapTo(0f)
            wave.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
        }
    }
    val sel = s.selected
    val selValue = if (sel >= 0) s.valueAt(sel) else 0
    val hintFocus = s.hint?.focus?.toSet().orEmpty()
    val elimCells = s.hint?.eliminations?.map { it.first }?.toSet().orEmpty()
    val noteCols = ceil(sqrt(n.toDouble())).toInt()
    val noteRows = ceil(n.toDouble() / noteCols).toInt()
    val givenStyle = TextStyle(fontWeight = FontWeight.SemiBold, color = scheme.onSurface)

    Canvas(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerLowest)
            .pointerInput(n) {
                detectTapGestures { p ->
                    val cs = size.width / n.toFloat()
                    val c = (p.x / cs).toInt().coerceIn(0, n - 1)
                    val r = (p.y / cs).toInt().coerceIn(0, n - 1)
                    onTap(r * n + c)
                }
            },
    ) {
        val cs = size.width / n
        val selRow = if (sel >= 0) g.row(sel) else -1
        val selCol = if (sel >= 0) g.col(sel) else -1
        val selBox = if (sel >= 0) g.box(sel) else -1
        val flashSet = s.flash.second.toSet()
        for (i in 0 until g.cells) {
            val r = g.row(i)
            val c = g.col(i)
            val topLeft = Offset(c * cs, r * cs)
            val v = s.valueAt(i)
            var bg = Color.Transparent
            if (g.variant == Variant.DIAGONAL && (r == c || r + c == n - 1)) bg = accent.copy(alpha = 0.05f)
            if (sel >= 0 && (r == selRow || c == selCol || g.box(i) == selBox)) bg = accent.copy(alpha = 0.09f)
            if (s.prefs.highlightSame && selValue != 0 && v == selValue) bg = accent.copy(alpha = 0.24f)
            if (i in hintFocus) bg = Color(0xFFFACC15).copy(alpha = 0.32f)
            if (i in elimCells) bg = Color(0xFFF97316).copy(alpha = 0.22f)
            if (i == sel) bg = accent.copy(alpha = 0.38f)
            if (s.isWrong(i) && s.prefs.showMistakes) bg = lerp(bg, scheme.error.copy(alpha = 0.22f), 0.8f)
            if (bg != Color.Transparent) drawRect(bg, topLeft, Size(cs, cs))
            if (i in flashSet && flash.value > 0f) drawRect(accent.copy(alpha = 0.35f * flash.value), topLeft, Size(cs, cs))
            if (wave.value > 0f) {
                val d = hypot(r - (n - 1) / 2f, c - (n - 1) / 2f) / (n * 0.75f)
                val t = ((wave.value * 1.6f - d) * 3f).coerceIn(0f, 1f)
                val a = (1 - kotlin.math.abs(t * 2 - 1)) * 0.6f
                if (a > 0f) drawRect(Accents.Sudoku.end.copy(alpha = a), topLeft, Size(cs, cs))
            }
            if (v != 0) {
                val scale = if (i == popCell) lastPlaced.value else 1f
                val color = when {
                    s.isGiven(i) -> scheme.onSurface
                    s.isWrong(i) && s.prefs.showMistakes -> scheme.error
                    else -> accent
                }
                val style = givenStyle.copy(color = color, fontSize = (cs * 0.58f * scale / density).sp, fontWeight = if (s.isGiven(i)) FontWeight.SemiBold else FontWeight.Medium)
                val layout = measurer.measure(digitLabel(v), style)
                drawText(layout, topLeft = Offset(topLeft.x + (cs - layout.size.width) / 2, topLeft.y + (cs - layout.size.height) / 2))
            } else if (s.notes[i] != 0) {
                val m = s.notes[i]
                val nw = cs / noteCols
                val nh = cs / noteRows
                val style = TextStyle(fontSize = (minOf(nw, nh) * 0.72f / density).sp, color = scheme.onSurfaceVariant)
                for (d in 1..n) {
                    if (m and bitOf(d) == 0) continue
                    val k = d - 1
                    val ox = topLeft.x + (k % noteCols) * nw
                    val oy = topLeft.y + (k / noteCols) * nh
                    val elim = s.hint?.eliminations?.any { it.first == i && it.second == d } == true
                    val layout = measurer.measure(digitLabel(d), if (selValue == d) style.copy(color = accent, fontWeight = FontWeight.Bold) else if (elim) style.copy(color = Color(0xFFEA580C), fontWeight = FontWeight.Bold) else style)
                    drawText(layout, topLeft = Offset(ox + (nw - layout.size.width) / 2, oy + (nh - layout.size.height) / 2))
                }
            }
        }
        gridLines(n, g.boxRows, g.boxCols, cs, scheme.outlineVariant, scheme.onSurface.copy(alpha = 0.75f))
    }
}

private fun DrawScope.gridLines(n: Int, boxRows: Int, boxCols: Int, cs: Float, thin: Color, thick: Color) {
    val thinW = 1.dp.toPx()
    val thickW = 2.2.dp.toPx()
    for (k in 1 until n) {
        val vThick = k % boxCols == 0
        val hThick = k % boxRows == 0
        drawLine(if (vThick) thick else thin, Offset(k * cs, 0f), Offset(k * cs, size.height), if (vThick) thickW else thinW)
        drawLine(if (hThick) thick else thin, Offset(0f, k * cs), Offset(size.width, k * cs), if (hThick) thickW else thinW)
    }
}

@Composable
private fun Controls(s: SudokuState, vm: SudokuViewModel) {
    Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ControlButton(Icons.AutoMirrored.Rounded.Undo, "Undo", enabled = s.canUndo) { vm.undo() }
        ControlButton(Icons.AutoMirrored.Rounded.Redo, "Redo", enabled = s.canRedo) { vm.redo() }
        ControlButton(Icons.Rounded.Backspace, "Erase") { vm.erase() }
        ControlButton(Icons.Rounded.EditNote, if (s.notesMode) "Notes on" else "Notes", active = s.notesMode) { vm.toggleNotes() }
        ControlButton(Icons.Rounded.AutoAwesome, "Auto notes") { vm.autoNotes() }
        ControlButton(Icons.Rounded.Lightbulb, "Hint") { vm.hint() }
    }
}

@Composable
private fun ControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        active -> Accents.Sudoku.start
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (active) Accents.Sudoku.start.copy(alpha = 0.14f) else Color.Transparent).bouncyClickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
private fun NumberPad(s: SudokuState, onDigit: (Int) -> Unit) {
    val n = s.n
    val perRow = if (n > 9) 8 else n
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (rowStart in 1..n step perRow) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in rowStart until minOf(n + 1, rowStart + perRow)) {
                    val left = s.remaining(d)
                    val enabled = left > 0 && !s.generating && !s.won
                    val selectedValue = if (s.selected >= 0) s.valueAt(s.selected) else 0
                    val scale by animateFloatAsState(if (selectedValue == d) 1.06f else 1f, label = "pad")
                    Surface(
                        Modifier.weight(1f).aspectRatio(if (n > 9) 0.95f else 0.78f).bouncyClickable(enabled = enabled) { onDigit(d) }.padding(0.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = if (selectedValue == d) Accents.Sudoku.start.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(
                                digitLabel(d),
                                style = MaterialTheme.typography.headlineSmall.copy(fontSize = (if (n > 9) 20 else 26).sp * scale),
                                color = if (enabled) (if (s.notesMode) MaterialTheme.colorScheme.onSurfaceVariant else Accents.Sudoku.start) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                            )
                            if (n <= 9) Text(if (left > 0) "$left" else "✓", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HintCard(h: com.vitmap.core.sudoku.Step, onApply: () -> Unit, onDismiss: () -> Unit) {
    Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(top = 8.dp), shape = RoundedCornerShape(18.dp), color = Color(0xFFFACC15).copy(alpha = 0.16f)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lightbulb, null, tint = Color(0xFFCA8A04))
                Spacer(Modifier.width(8.dp))
                Text(h.technique.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close") }
                Button(onClick = onApply) { Text(if (h.isPlacement) "Place ${digitLabel(h.digit)}" else "Apply") }
            }
            Text(h.technique.explain, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EndCard(title: String, body: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>?) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        androidx.compose.animation.AnimatedVisibility(true, enter = scaleIn(spring(dampingRatio = 0.6f)) + fadeIn()) {
            Surface(Modifier.padding(32.dp), shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        secondary?.let { (l, a) -> OutlinedButton(onClick = a) { Text(l) } }
                        Button(onClick = primary.second) { Text(primary.first) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewGameSheet(s: SudokuState, onDismiss: () -> Unit, onDaily: () -> Unit, onStart: (Int, Variant, Difficulty) -> Unit) {
    var size by remember { mutableIntStateOf(s.n) }
    var variant by remember { mutableStateOf(s.geometry.variant) }
    var difficulty by remember { mutableStateOf(s.difficulty) }
    val today = LocalDate.now().toString()
    val dailyDone = today in s.prefs.dailyDone
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New game", style = MaterialTheme.typography.headlineSmall)
            Surface(Modifier.fillMaxWidth().bouncyClickable(onClick = onDaily), shape = RoundedCornerShape(20.dp), color = Accents.Sudoku.start.copy(alpha = 0.12f)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CalendarToday, null, tint = Accents.Sudoku.start)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Daily challenge", style = MaterialTheme.typography.titleMedium)
                        Text(if (dailyDone) "Solved today, come back tomorrow" else "The same puzzle for everyone today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (dailyDone) Text("✓", style = MaterialTheme.typography.titleLarge, color = Accents.Sudoku.start)
                }
            }
            Text("Board", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in listOf(4, 6, 9, 16)) FilterChip(selected = size == n, onClick = {
                    size = n
                    if (n == 16 && difficulty.ordinal > Difficulty.HARD.ordinal) difficulty = Difficulty.HARD
                    if (n != 9) variant = Variant.CLASSIC
                }, label = { Text("$n×$n") })
            }
            if (size == 9) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in Variant.entries) FilterChip(selected = variant == v, onClick = { variant = v }, label = { Text(v.label) })
                }
            }
            Text("Difficulty", style = MaterialTheme.typography.labelLarge)
            val levels = if (size == 16) Difficulty.entries.take(3) else if (size <= 6) Difficulty.entries.take(3) else Difficulty.entries
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (d in levels) FilterChip(selected = difficulty == d, onClick = { difficulty = d }, label = { Text(d.label) })
            }
            Text(
                when (difficulty) {
                    Difficulty.EASY -> "Solvable with singles only."
                    Difficulty.MEDIUM -> "Needs locked candidates now and then."
                    Difficulty.HARD -> "Pairs and triples come into play."
                    Difficulty.EXPERT -> "X-Wings, Swordfish and XY-Wings."
                    Difficulty.MASTER -> "Few clues; may need trial and error."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { onStart(size, variant, if (difficulty in levels) difficulty else levels.last()) }, modifier = Modifier.fillMaxWidth()) { Text("Start") }
        }
    }
}

@Composable
private fun SettingsSheet(p: SudokuPrefs, onDismiss: () -> Unit, onChange: ((SudokuPrefs) -> SudokuPrefs) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            SettingRow("Mistake limit", "End the game after three mistakes", p.mistakeLimit) { v -> onChange { it.copy(mistakeLimit = v) } }
            SettingRow("Show mistakes", "Mark wrong numbers in red right away", p.showMistakes) { v -> onChange { it.copy(showMistakes = v) } }
            SettingRow("Highlight matching numbers", "Shade every cell with the selected number", p.highlightSame) { v -> onChange { it.copy(highlightSame = v) } }
            SettingRow("Tidy notes automatically", "Placing a number removes it from notes in the same row, column and box", p.autoRemoveNotes) { v -> onChange { it.copy(autoRemoveNotes = v) } }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun StatsSheet(s: SudokuState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Statistics · ${s.n}×${s.n} ${s.geometry.variant.label.lowercase()}", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth()) {
                for (h in listOf("Level", "Played", "Won", "Best", "Average", "Streak")) Text(h, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (d in Difficulty.entries) {
                val st = s.prefs.stats["${s.n}-${s.geometry.variant.name}-${d.name}"] ?: SudokuStat()
                Row(Modifier.fillMaxWidth()) {
                    Text(d.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${st.played}", Modifier.weight(1f))
                    Text(if (st.played > 0) "${st.won * 100 / st.played}%" else "–", Modifier.weight(1f))
                    Text(if (st.bestMs > 0) formatTime(st.bestMs) else "–", Modifier.weight(1f))
                    Text(if (st.won > 0) formatTime(st.totalMs / st.won) else "–", Modifier.weight(1f))
                    Text("${st.streak} (${st.bestStreak})", Modifier.weight(1f))
                }
            }
        }
    }
}
