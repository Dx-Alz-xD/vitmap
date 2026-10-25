package com.vitmap.app.feature.translate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.components.copyToClipboard
import com.vitmap.app.ui.components.shareText
import com.vitmap.app.ui.theme.Accents

private data class TabSpec(val title: String, val icon: ImageVector)

private val TABS = listOf(
    TabSpec("Text", Icons.Rounded.Translate),
    TabSpec("Camera", Icons.Rounded.CameraAlt),
    TabSpec("Voice", Icons.Rounded.Mic),
    TabSpec("Docs", Icons.Rounded.Description),
    TabSpec("Dictionary", Icons.AutoMirrored.Rounded.MenuBook),
)

@Composable
fun TranslatorScreen(onBack: () -> Unit, initialText: String? = null) {
    val vm: TranslatorViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showPacks by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(initialText) { if (!initialText.isNullOrBlank()) vm.setInput(initialText) }
    LaunchedEffect(s.error) {
        val e = s.error ?: return@LaunchedEffect
        snackbar.showSnackbar(e)
        vm.clearError()
    }

    ToolScaffold(
        title = "Translator",
        subtitle = "${vm.langName(s.effectiveSource ?: s.source)} → ${vm.langName(s.target)}",
        accent = Accents.Translator,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = { showPacks = true }) {
                BadgedBox(badge = { Badge { Text("${s.downloaded.size}") } }) { Icon(Icons.Rounded.OfflinePin, "Offline languages") }
            }
        },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, null) }, label = { Text(t.title, maxLines = 1) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (tab != 4) LanguageBar(vm, s)
            MissingModelsBanner(vm, s)
            AnimatedContent(tab, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "translatorTab") { t ->
                when (t) {
                    0 -> TextTab(vm, s) { tab = 4 }
                    1 -> PhotoTranslateTab(vm, s) { text -> vm.setInput(text); tab = 0 }
                    2 -> VoiceTab(vm, s)
                    3 -> DocsTab(vm, s)
                    else -> DictionaryTab(vm, s)
                }
            }
        }
    }
    if (showPacks) LanguagePacksSheet(vm, s) { showPacks = false }
}

@Composable
fun LanguageBar(vm: TranslatorViewModel, s: TranslatorState) {
    var picking by remember { mutableStateOf<Int?>(null) }
    var spins by remember { mutableIntStateOf(0) }
    val spin by animateFloatAsState(spins * 180f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "swapSpin")
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LangChip(
            label = if (s.source == AUTO) (s.detected?.let { "${vm.langName(it)} · detected" } ?: "Detect language") else vm.langName(s.source),
            offline = s.effectiveSource?.let { it in s.downloaded } ?: true,
            modifier = Modifier.weight(1f),
        ) { picking = 0 }
        FilledTonalIconButton(
            onClick = { spins++; vm.swap() },
            enabled = s.effectiveSource != null,
            modifier = Modifier.padding(horizontal = 6.dp).graphicsLayer { rotationZ = spin },
        ) { Icon(Icons.Rounded.SwapHoriz, "Swap languages") }
        LangChip(vm.langName(s.target), s.target in s.downloaded, Modifier.weight(1f)) { picking = 1 }
    }
    picking?.let { side ->
        LanguagePickerSheet(vm, s, allowAuto = side == 0, selected = if (side == 0) s.source else s.target, onDismiss = { picking = null }) { code ->
            if (side == 0) vm.setSource(code) else vm.setTarget(code)
            picking = null
        }
    }
}

@Composable
private fun LangChip(label: String, offline: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .bouncyClickable(shape = RoundedCornerShape(18.dp), pressedScale = 0.96f, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(label, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "lang", modifier = Modifier.weight(1f, fill = false)) { l ->
            Text(l, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!offline) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.CloudDownload, "Not downloaded", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun MissingModelsBanner(vm: TranslatorViewModel, s: TranslatorState) {
    AnimatedVisibility(s.missing.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Download once, translate offline forever", style = MaterialTheme.typography.titleSmall)
                Text("Each language pack is about 30 MB. This is the only step that needs internet.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (code in s.missing) {
                        val busy = code in s.downloading
                        Button(onClick = { vm.download(code) }, enabled = !busy) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (busy) "Downloading ${vm.langName(code)}…" else "Get ${vm.langName(code)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextTab(vm: TranslatorViewModel, s: TranslatorState, onDefine: () -> Unit) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(6.dp)) {
                TextField(
                    value = s.input,
                    onValueChange = vm::setInput,
                    placeholder = { Text("Type or paste text") },
                    textStyle = MaterialTheme.typography.titleLarge,
                    minLines = 3,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.speak(s.input, s.effectiveSource, "input") }, enabled = s.input.isNotBlank()) {
                        SpeakIcon(s.speaking == "input")
                    }
                    IconButton(onClick = { clipboard.getText()?.text?.let(vm::setInput) }) { Icon(Icons.Rounded.ContentPaste, "Paste") }
                    val singleWord = s.input.trim().let { it.isNotEmpty() && !it.contains(' ') && it.length < 40 }
                    AnimatedVisibility(singleWord) {
                        TextButton(onClick = onDefine) {
                            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Define")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${s.input.length}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.input.isNotEmpty()) IconButton(onClick = { vm.setInput("") }) { Icon(Icons.Rounded.Close, "Clear") }
                }
            }
        }

        AnimatedVisibility(s.input.isNotBlank(), enter = expandVertically(spring(dampingRatio = 0.8f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            OutputCard(
                text = s.output,
                loading = s.translating,
                speaking = s.speaking == "output",
                onSpeak = { vm.speak(s.output, s.target, "output") },
                onCopy = { copyToClipboard(context, s.output, "translation") },
                onShare = { shareText(context, s.output) },
            )
        }

        val history = s.prefs.history
        if (s.input.isBlank() && history.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Recent", Modifier.weight(1f))
                TextButton(onClick = vm::clearHistory) { Text("Clear") }
            }
            for (item in history.sortedByDescending { it.favorite }.take(40)) {
                HistoryRow(item, vm)
            }
        } else if (s.input.isBlank()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Translate, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Translations happen on your device", style = MaterialTheme.typography.titleSmall)
                Text("Type above, or use the camera, voice and document tabs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun SpeakIcon(active: Boolean) {
    val pulse = rememberInfiniteTransition(label = "speak")
    val s by pulse.animateFloat(1f, if (active) 1.25f else 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "speakPulse")
    Icon(
        if (active) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
        if (active) "Stop" else "Listen",
        Modifier.graphicsLayer { scaleX = s; scaleY = s },
        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun OutputCard(text: String, loading: Boolean, speaking: Boolean, onSpeak: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit) {
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val x by shimmer.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "shimmerX")
    Surface(shape = RoundedCornerShape(26.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.background(Accents.Translator.brush).padding(16.dp)) {
            if (loading && text.isBlank()) {
                repeat(2) { i ->
                    Box(
                        Modifier
                            .fillMaxWidth(if (i == 0) 0.9f else 0.6f)
                            .height(22.dp)
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.15f)), start = androidx.compose.ui.geometry.Offset(x * 600f, 0f), end = androidx.compose.ui.geometry.Offset(x * 600f + 300f, 0f))),
                    )
                }
            } else {
                AnimatedContent(text, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "out") { t ->
                    Text(t, style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSpeak, enabled = text.isNotBlank()) {
                    Icon(if (speaking) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp, "Listen", tint = Color.White)
                }
                IconButton(onClick = onCopy, enabled = text.isNotBlank()) { Icon(Icons.Rounded.ContentCopy, "Copy", tint = Color.White) }
                IconButton(onClick = onShare, enabled = text.isNotBlank()) { Icon(Icons.Rounded.Share, "Share", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, vm: TranslatorViewModel) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().bouncyClickable(pressedScale = 0.98f) { vm.loadHistory(item) }) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${vm.langName(item.source)} → ${vm.langName(item.target)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(item.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.translation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.toggleFavorite(item) }) {
                Icon(if (item.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favourite", tint = if (item.favorite) Color(0xFFFFC145) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.deleteHistory(item) }) { Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun LanguagePickerSheet(vm: TranslatorViewModel, s: TranslatorState, allowAuto: Boolean, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                q, { q = it }, singleLine = true, placeholder = { Text("Search ${vm.languages.size} languages") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            val filtered = vm.languages.filter { it.name.contains(q, true) || it.native.contains(q, true) || it.code.equals(q, true) }
            val recent = s.prefs.recent.mapNotNull { c -> vm.languages.firstOrNull { it.code == c } }.filter { q.isBlank() }
            LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (allowAuto && q.isBlank()) item { LangRow("Detect language", "Automatic", selected == AUTO, true) { onPick(AUTO) } }
                if (recent.isNotEmpty()) {
                    item { SectionTitle("Recent", Modifier.padding(top = 8.dp)) }
                    items(recent, key = { "r-" + it.code }) { l -> LangRow(l.name, l.native, l.code == selected, l.code in s.downloaded) { onPick(l.code) } }
                    item { SectionTitle("All languages", Modifier.padding(top = 8.dp)) }
                }
                items(filtered, key = { it.code }) { l -> LangRow(l.name, l.native, l.code == selected, l.code in s.downloaded) { onPick(l.code) } }
            }
        }
    }
}

@Composable
private fun LangRow(name: String, native: String, selected: Boolean, offline: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (native != name) Text(native, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            if (offline) Icons.Rounded.CheckCircle else Icons.Rounded.CloudDownload,
            if (offline) "Available offline" else "Needs download",
            tint = if (offline) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun LanguagePacksSheet(vm: TranslatorViewModel, s: TranslatorState, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var q by remember { mutableStateOf("") }
    var rate by remember { mutableFloatStateOf(s.prefs.speechRate) }
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Offline languages", style = MaterialTheme.typography.headlineSmall)
            Text("${s.downloaded.size} of ${vm.languages.size} ready offline · ~30 MB each", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Speech speed ${"%.1f".format(rate)}×", style = MaterialTheme.typography.labelLarge)
            Slider(rate, { rate = it }, onValueChangeFinished = { vm.setSpeechRate(rate) }, valueRange = 0.5f..2f)
            OutlinedButton(onClick = { runCatching { context.startActivity(vm.installVoicesIntent()) } }, modifier = Modifier.fillMaxWidth()) { Text("Install more voices") }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search") }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            val sorted = vm.languages.filter { it.name.contains(q, true) || it.native.contains(q, true) }.sortedByDescending { it.code in s.downloaded }
            LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(sorted, key = { it.code }) { l ->
                    val ready = l.code in s.downloaded
                    val busy = l.code in s.downloading
                    Row(Modifier.fillMaxWidth().animateItem().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name, style = MaterialTheme.typography.bodyLarge)
                            Text(if (ready) "Ready offline" else "Not downloaded", style = MaterialTheme.typography.bodySmall, color = if (ready) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            ready && l.code != "en" -> IconButton(onClick = { vm.deleteModel(l.code) }) { Icon(Icons.Rounded.Delete, "Remove") }
                            !ready -> IconButton(onClick = { vm.download(l.code) }) { Icon(Icons.Rounded.CloudDownload, "Download") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PulsingMic(listening: Boolean, level: Float, onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 96) {
    val l by animateFloatAsState(level, spring(dampingRatio = 0.6f, stiffness = 500f), label = "level")
    val ring = rememberInfiniteTransition(label = "rings")
    val phase by ring.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.size((size * 2).dp), contentAlignment = Alignment.Center) {
        if (listening) {
            for (k in 0..2) {
                val p = (phase + k / 3f) % 1f
                Box(
                    Modifier
                        .size((size + size * p * (0.6f + l)).dp)
                        .graphicsLayer { alpha = (1f - p) * 0.5f }
                        .clip(CircleShape)
                        .background(primary.copy(alpha = 0.35f)),
                )
            }
        }
        Box(
            Modifier
                .size((size * (1f + (if (listening) l * 0.18f else 0f))).dp)
                .bouncyClickable(shape = CircleShape, onClick = onClick)
                .background(Accents.Translator.brush),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, if (listening) "Stop" else "Speak", tint = Color.White, modifier = Modifier.size((size * 0.42f).dp))
        }
    }
}
