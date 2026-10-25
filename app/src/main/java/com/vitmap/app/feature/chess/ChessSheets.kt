package com.vitmap.app.feature.chess

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.theme.MonoStyle
import com.vitmap.core.chess.BotLevel
import com.vitmap.core.chess.MoveQuality
import com.vitmap.core.chess.Piece
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun NewGameSheet(current: GameConfig, hasCustomPosition: Boolean, onDismiss: () -> Unit, onStart: (GameConfig, Boolean, Boolean?) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by remember { mutableStateOf(current.mode) }
    var playAs by remember { mutableStateOf(if (current.mode == ChessMode.VS_BOT && current.whiteBot != null) 2 else 0) }
    var elo by remember { mutableFloatStateOf((current.blackBot ?: current.whiteBot ?: 1200).toFloat()) }
    var whiteElo by remember { mutableFloatStateOf((current.whiteBot ?: 1500).toFloat()) }
    var blackElo by remember { mutableFloatStateOf((current.blackBot ?: 1500).toFloat()) }
    var delayMs by remember { mutableFloatStateOf(current.botDelayMs.toFloat()) }
    var tc by remember { mutableStateOf(current.timeControl) }
    var fromCurrent by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("New game", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard(Icons.Rounded.SmartToy, "vs Bot", mode == ChessMode.VS_BOT, Modifier.weight(1f)) { mode = ChessMode.VS_BOT }
                ModeCard(Icons.Rounded.Groups, "2 players", mode == ChessMode.PVP, Modifier.weight(1f)) { mode = ChessMode.PVP }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard(Icons.Rounded.SportsEsports, "Bot vs Bot", mode == ChessMode.BOT_VS_BOT, Modifier.weight(1f)) { mode = ChessMode.BOT_VS_BOT }
                ModeCard(Icons.Rounded.Insights, "Analysis", mode == ChessMode.ANALYSIS, Modifier.weight(1f)) { mode = ChessMode.ANALYSIS }
            }

            AnimatedContent(mode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "modeOptions") { m ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (m) {
                        ChessMode.VS_BOT -> {
                            Text("Play as", style = MaterialTheme.typography.titleSmall)
                            val sides = listOf("White", "Random", "Black")
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                sides.forEachIndexed { i, label ->
                                    SegmentedButton(selected = playAs == i, onClick = { playAs = i }, shape = SegmentedButtonDefaults.itemShape(i, sides.size)) { Text(label) }
                                }
                            }
                            EloPicker("Bot strength", elo) { elo = it }
                        }
                        ChessMode.BOT_VS_BOT -> {
                            EloPicker("White bot", whiteElo) { whiteElo = it }
                            EloPicker("Black bot", blackElo) { blackElo = it }
                            Text("Move delay: ${(delayMs / 100).roundToInt() / 10.0}s", style = MaterialTheme.typography.titleSmall)
                            Slider(delayMs, { delayMs = it }, valueRange = 100f..3000f)
                        }
                        else -> {}
                    }
                    if (m == ChessMode.VS_BOT || m == ChessMode.PVP) {
                        Text("Clock", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = tc == null, onClick = { tc = null }, label = { Text("No clock") })
                            for (t in TimeControl.presets) FilterChip(selected = tc == t, onClick = { tc = t }, label = { Text(t.label) })
                        }
                    }
                }
            }

            if (hasCustomPosition) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start from the current position", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(fromCurrent, { fromCurrent = it })
                }
            }

            Button(
                onClick = {
                    val cfg = when (mode) {
                        ChessMode.VS_BOT -> {
                            val humanWhite = when (playAs) { 0 -> true; 2 -> false; else -> Random.nextBoolean() }
                            GameConfig(
                                mode = mode,
                                whiteBot = if (humanWhite) null else elo.roundToInt(),
                                blackBot = if (humanWhite) elo.roundToInt() else null,
                                timeControl = tc,
                            )
                        }
                        ChessMode.PVP -> GameConfig(mode = mode, whiteBot = null, blackBot = null, timeControl = tc)
                        ChessMode.BOT_VS_BOT -> GameConfig(mode = mode, whiteBot = whiteElo.roundToInt(), blackBot = blackElo.roundToInt(), botDelayMs = delayMs.roundToInt())
                        ChessMode.ANALYSIS -> GameConfig(mode = mode, whiteBot = null, blackBot = null)
                    }
                    val flip = if (cfg.mode == ChessMode.VS_BOT) cfg.whiteBot != null else null
                    onStart(cfg, fromCurrent, flip)
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Start", style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ModeCard(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, label = "mode")
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "modeBorder")
    Column(
        modifier
            .bouncyClickable(shape = RoundedCornerShape(20.dp), onClick = onClick)
            .background(bg)
            .border(2.dp, border, RoundedCornerShape(20.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(28.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun EloPicker(title: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            AnimatedContent(value.roundToInt(), label = "elo") { v ->
                Text("$v · ${BotLevel.title(v)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Slider(
            value = value,
            onValueChange = { onChange((it / 50).roundToInt() * 50f) },
            valueRange = BotLevel.MIN_ELO.toFloat()..BotLevel.MAX_ELO.toFloat(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (preset in listOf(400, 800, 1200, 1600, 2000, 2400, 2800)) {
                FilterChip(selected = value.roundToInt() == preset, onClick = { onChange(preset.toFloat()) }, label = { Text("$preset") })
            }
        }
    }
}

@Composable
fun PromotionPicker(color: Int, onPick: (Int?) -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f * appear.value))
            .bouncyClickable(pressedScale = 1f, haptic = false) { onPick(null) },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .graphicsLayer { scaleX = appear.value; scaleY = appear.value }
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (t in listOf(Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT)) {
                Box(
                    Modifier
                        .size(64.dp)
                        .bouncyClickable(shape = RoundedCornerShape(16.dp)) { onPick(t) }
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(pieceDrawable(Piece.make(color, t))), null, Modifier.size(52.dp))
                }
            }
        }
    }
}

@Composable
fun TextImportDialog(
    title: String,
    hint: String,
    initial: String,
    confirm: String,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> String?,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(hint) },
                    singleLine = singleLine,
                    textStyle = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (singleLine) 0.dp else 160.dp),
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.let { text = it; error = null } }) { Icon(Icons.Rounded.ContentPaste, "Paste") }
                    },
                )
                AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = onConfirm(text.trim())
                if (e == null) onDismiss() else error = e
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ThemePickerDialog(selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Board theme") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BoardThemes.forEachIndexed { i, t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .bouncyClickable(shape = RoundedCornerShape(14.dp), pressedScale = 0.97f) { onPick(i) }
                            .background(if (i == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.clip(RoundedCornerShape(6.dp))) {
                            for (k in 0..3) Box(Modifier.size(22.dp).background(if (k % 2 == 0) t.light else t.dark))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(t.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun ReportSheet(
    state: ChessUiState,
    report: Pair<ChessViewModel.SideReport, ChessViewModel.SideReport>,
    onSelectPly: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Game report", style = MaterialTheme.typography.headlineSmall)
            val done = state.qualities.size
            val total = state.record.size
            if (done < total) {
                Text("Analysing move ${done + 1} of $total…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { if (total == 0) 0f else done / total.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            state.opening?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AccuracyCircle(report.first.accuracy, state.config.playerName(Piece.WHITE), Piece.WHITE)
                AccuracyCircle(report.second.accuracy, state.config.playerName(Piece.BLACK), Piece.BLACK)
            }
            EvalGraph(state.evals, state.record.size, state.viewIndex, onSelect = onSelectPly)
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (q in MoveQuality.entries) {
                        val w = report.first.counts[q] ?: 0
                        val b = report.second.counts[q] ?: 0
                        if (w == 0 && b == 0 && q == MoveQuality.FORCED) continue
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("$w", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), color = if (w > 0) q.color else MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                QualityBadge(q, size = 22.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(q.label, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("$b", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), color = if (b > 0) q.color else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun CircleSwatch(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).clip(CircleShape).background(color))
}
