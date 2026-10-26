package com.vitmap.app.feature.forex

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.feature.clock.flagEmoji
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.theme.Accents
import com.vitmap.core.forex.CurrencyKind
import com.vitmap.core.forex.MoneyFormat
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.format.DateTimeFormatter
import java.util.Date

@Composable
private fun CurrencyBadge(info: CurrencyInfo?, code: String, size: Int = 30) {
    val flag = info?.countries?.firstOrNull()
    Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        when {
            info?.kind == CurrencyKind.CRYPTO -> Text(code.take(1).uppercase(), color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = (size * 0.45).sp)
            info?.kind == CurrencyKind.METAL -> Text("◆", color = if (code.startsWith("xau")) Color(0xFFEAB308) else Color(0xFF94A3B8), fontSize = (size * 0.5).sp)
            code == "eur" -> Text("🇪🇺", fontSize = (size * 0.6).sp)
            flag != null -> Text(flagEmoji(flag), fontSize = (size * 0.6).sp)
            else -> Text(code.take(2).uppercase(), fontSize = (size * 0.38).sp)
        }
    }
}

@Composable
fun ForexScreen(onBack: () -> Unit) {
    val vm: ForexViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<String?>(null) }
    val catalog = vm.repo.byCode
    ToolScaffold(
        title = "ForEx",
        subtitle = s.error ?: s.table?.let { t -> "Rates of ${t.date}" + (s.fetchedAt?.let { " · updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" } ?: " · offline snapshot") },
        accent = Accents.ForEx,
        onBack = onBack,
        actions = {
            IconButton(onClick = vm::refresh, enabled = !s.refreshing) {
                if (s.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, "Refresh rates")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Converter(vm, s, catalog, onPickFrom = { picking = "from" }, onPickTo = { picking = "to" }) }
            item { ChartCard(vm, s) }
            item {
                Text("Convert ${MoneyFormat.format(s.amount ?: 0.0)} ${s.from.uppercase()} to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            items(s.prefs.favorites.filter { it != s.from }, key = { it }) { code ->
                FavoriteRow(vm, s, code, catalog[code], Modifier.animateItem())
            }
            item {
                Surface(Modifier.fillMaxWidth().bouncyClickable { picking = "fav" }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text("Add or remove currencies (${catalog.size} available)", Modifier.padding(16.dp), color = Accents.ForEx.start, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                }
            }
            item {
                Text(
                    "Fiat rates are daily reference rates from free public feeds; local, pegged and historic currencies use their fixed official ratios. Not for trading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
    picking?.let { mode ->
        CurrencyPicker(
            vm = vm,
            s = s,
            title = when (mode) {
                "from" -> "Convert from"
                "to" -> "Convert to"
                else -> "Your currencies"
            },
            onDismiss = { picking = null },
        ) { code ->
            when (mode) {
                "from" -> { vm.setFrom(code); picking = null }
                "to" -> { vm.setTo(code); picking = null }
                else -> vm.toggleFavorite(code)
            }
        }
    }
}

@Composable
private fun Converter(vm: ForexViewModel, s: ForexState, catalog: Map<String, CurrencyInfo>, onPickFrom: () -> Unit, onPickTo: () -> Unit) {
    val scope = rememberCoroutineScope()
    val spin = remember { Animatable(0f) }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CurrencyButton(catalog[s.from], s.from, onPickFrom)
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = s.amountText,
                    onValueChange = { vm.setAmount(it.filter { c -> c.isDigit() || c in ".,+-*/()^% " }) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(Accents.ForEx.start),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(catalog[s.from]?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Surface(
                    Modifier.size(46.dp).bouncyClickable {
                        vm.swap()
                        scope.launch { spin.animateTo(spin.value + 180f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
                    },
                    shape = CircleShape,
                    color = Accents.ForEx.start,
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SwapVert, "Swap", tint = Color.White, modifier = Modifier.rotate(spin.value)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CurrencyButton(catalog[s.to], s.to, onPickTo)
                Spacer(Modifier.width(12.dp))
                AnimatedContent(
                    s.result?.let { MoneyFormat.format(it) } ?: "—",
                    transitionSpec = { (slideInVertically { it / 3 } + fadeIn()) togetherWith (slideOutVertically { -it / 3 } + fadeOut()) },
                    modifier = Modifier.weight(1f),
                    label = "result",
                ) { r ->
                    Text(r, style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End), color = Accents.ForEx.start, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
            }
            Text(catalog[s.to]?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(10.dp))
            val rate = s.rate
            if (rate != null) {
                Text("1 ${s.from.uppercase()} = ${MoneyFormat.format(rate)} ${s.to.uppercase()}  ·  1 ${s.to.uppercase()} = ${MoneyFormat.format(1 / rate)} ${s.from.uppercase()}", style = MaterialTheme.typography.labelLarge)
            } else {
                Text("No rate available for this pair", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
            s.table?.source?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun CurrencyButton(info: CurrencyInfo?, code: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).bouncyClickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CurrencyBadge(info, code)
        Spacer(Modifier.width(8.dp))
        Text(code.uppercase(), style = MaterialTheme.typography.titleMedium)
        Icon(Icons.Rounded.ExpandMore, null)
    }
}

@Composable
private fun ChartCard(vm: ForexViewModel, s: ForexState) {
    var scrub by remember(s.history) { mutableStateOf<Int?>(null) }
    val points = s.history
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val shown = scrub?.let { points.getOrNull(it) } ?: points.lastOrNull()
                    Text("${s.from.uppercase()} → ${s.to.uppercase()}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(shown?.let { MoneyFormat.format(it.value) } ?: "—", style = MaterialTheme.typography.headlineSmall)
                    Text(shown?.date?.format(DateTimeFormatter.ofPattern("d MMM yyyy")) ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (points.size >= 2) {
                    val ch = (points.last().value / points.first().value - 1) * 100
                    Text(
                        "${if (ch >= 0) "▲" else "▼"} ${"%.2f".format(kotlin.math.abs(ch))}%",
                        color = if (ch >= 0) Color(0xFF16A34A) else Color(0xFFDC2626),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                when {
                    s.historyLoading -> CircularProgressIndicator(Modifier.size(28.dp), color = Accents.ForEx.start)
                    points.size < 2 -> Text(s.historyError ?: "No history", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LineChart(points, scrub, onScrub = { scrub = it })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in HistoryRange.entries) FilterChip(selected = s.range == r, onClick = { vm.setRange(r) }, label = { Text(r.label) })
            }
        }
    }
}

@Composable
private fun LineChart(points: List<HistoryPoint>, scrub: Int?, onScrub: (Int?) -> Unit) {
    val line = Accents.ForEx.start
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(points) {
                detectDragGestures(onDragEnd = { onScrub(null) }, onDragCancel = { onScrub(null) }) { change, _ ->
                    onScrub(((change.position.x / size.width) * (points.size - 1)).toInt().coerceIn(0, points.lastIndex))
                }
            }
            .pointerInput(points) {
                detectTapGestures(onPress = { p ->
                    onScrub(((p.x / size.width) * (points.size - 1)).toInt().coerceIn(0, points.lastIndex))
                    tryAwaitRelease()
                    onScrub(null)
                })
            },
    ) {
        val min = points.minOf { it.value }
        val max = points.maxOf { it.value }
        val span = (max - min).takeIf { it > 0 } ?: (max * 0.01).coerceAtLeast(1e-9)
        fun xy(i: Int) = Offset(i * size.width / (points.size - 1), (size.height * (1 - (points[i].value - min) / span)).toFloat() * 0.9f + size.height * 0.05f)
        for (k in 0..3) {
            val y = size.height * (0.05f + 0.9f * k / 3)
            drawLine(grid.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        val path = Path().apply {
            moveTo(xy(0).x, xy(0).y)
            for (i in 1 until points.size) lineTo(xy(i).x, xy(i).y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        scrub?.let { i ->
            val p = xy(i)
            drawLine(line.copy(alpha = 0.5f), Offset(p.x, 0f), Offset(p.x, size.height), 1.5.dp.toPx())
            drawCircle(Color.White, 6.dp.toPx(), p)
            drawCircle(line, 4.dp.toPx(), p)
        }
    }
}

@Composable
private fun FavoriteRow(vm: ForexViewModel, s: ForexState, code: String, info: CurrencyInfo?, modifier: Modifier) {
    val value = s.amount?.let { s.table?.convert(it, s.from, code) }
    val change = s.change(code)
    Surface(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = { vm.setTo(code) }, onLongClick = { vm.toggleFavorite(code) }),
        shape = RoundedCornerShape(18.dp),
        color = if (code == s.to) Accents.ForEx.start.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CurrencyBadge(info, code, 36)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(code.uppercase(), style = MaterialTheme.typography.titleSmall)
                Text(info?.name ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text((info?.symbol?.takeIf { it.isNotEmpty() }?.let { "$it " } ?: "") + (value?.let { MoneyFormat.format(it) } ?: "—"), style = MaterialTheme.typography.titleMedium)
                if (change != null) Text(
                    "${if (change >= 0) "+" else ""}${"%.2f".format(change)}% today",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (change >= 0) Color(0xFF16A34A) else Color(0xFFDC2626),
                )
            }
        }
    }
}

@Composable
private fun CurrencyPicker(vm: ForexViewModel, s: ForexState, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<CurrencyKind?>(null) }
    val all = vm.repo.catalog
    val q = query.trim().lowercase()
    val list = remember(q, kind) {
        all.filter { (kind == null || it.kind == kind) && (q.isEmpty() || it.search.contains(q)) }
            .sortedWith(compareBy({ if (q.isNotEmpty() && it.code == q) 0 else 1 }, { it.kind.ordinal }, { it.name }))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, placeholder = { Text("Code, name or country code") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text("All ${all.size}") })
                for (k in CurrencyKind.entries) FilterChip(selected = kind == k, onClick = { kind = if (kind == k) null else k }, label = { Text("${k.label} ${all.count { it.kind == k }}") })
            }
            LazyColumn(Modifier.fillMaxWidth().height(560.dp)) {
                items(list, key = { it.code }) { c ->
                    val rate = s.table?.rate(s.from, c.code)
                    val fav = c.code in s.prefs.favorites
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).bouncyClickable(pressedScale = 0.98f) { onPick(c.code) }.padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CurrencyBadge(c, c.code, 34)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${c.upper}  ${c.symbol}", style = MaterialTheme.typography.titleSmall)
                            Text(c.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(rate?.let { MoneyFormat.format(it) } ?: "—", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { vm.toggleFavorite(c.code) }) {
                            Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (fav) "Remove from list" else "Add to list", tint = if (fav) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
