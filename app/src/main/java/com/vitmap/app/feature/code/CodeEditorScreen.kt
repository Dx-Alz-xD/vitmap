package com.vitmap.app.feature.code

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitmap.app.data.FileSaver
import com.vitmap.app.ui.components.OptionChips
import com.vitmap.app.ui.components.OptionSwitch
import com.vitmap.app.ui.components.PackDialog
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.copyToClipboard
import com.vitmap.app.ui.theme.Accents
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.launch
import java.io.File

/** Owns the Sora editor view so Compose code can drive it. */
class EditorHolder {
    var editor: CodeEditor? = null
    fun text(): String = editor?.text?.toString().orEmpty()
}

@Composable
fun CodeEditorScreen(vm: CodeViewModel, fileId: String, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val languages by vm.languages.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val file = state?.files?.firstOrNull { it.id == fileId }
    val settings = state?.settings ?: CodeSettings()
    val lang = file?.let { f -> languages.firstOrNull { it.id == f.lang } }
    val runner = if (file != null && lang != null) vm.runnerFor(file, lang) else null
    val holder = remember(fileId) { EditorHolder() }
    val sandbox = remember { WebSandbox(context) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { sandbox.destroy() } }

    var initial by remember(fileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(fileId) { initial = vm.text(fileId) }

    // Theme and grammar
    val dark = isSystemInDarkTheme()
    val themeName = settings.theme ?: if (dark) "github-dark-default" else "github-light-default"
    var themeTick by remember { mutableStateOf(0) }
    LaunchedEffect(themeName) {
        val info = CodeCatalog.themes(context).firstOrNull { it.name == themeName }
        if (!Highlighting.applyTheme(context, themeName, info?.dark ?: dark)) snackbar.showSnackbar("Couldn't load the $themeName theme")
        themeTick++
    }
    var grammarScope by remember(fileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(lang?.grammar?.name, themeTick) {
        val g = lang?.grammar ?: return@LaunchedEffect
        grammarScope = Highlighting.ensureGrammar(context, g.name, lang.spec.comment)
    }

    var showOutput by remember(fileId) { mutableStateOf(false) }
    var showRunners by remember { mutableStateOf(false) }
    var showIo by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch { runCatching { vm.exportTo(fileId, uri) }.onFailure { snackbar.showSnackbar(it.message ?: "Couldn't save") } }
    }

    fun doRun() {
        val f = file ?: return
        showOutput = true
        vm.runCode(f, holder.text(), sandbox)
    }

    ToolScaffold(
        title = file?.name ?: "…",
        subtitle = listOfNotNull(lang?.name, runner?.label).joinToString(" · "),
        accent = Accents.Code,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (run.running) {
                IconButton(onClick = vm::stop) { Icon(Icons.Rounded.Stop, "Stop", tint = MaterialTheme.colorScheme.error) }
            } else {
                IconButton(onClick = ::doRun, enabled = lang?.runnable == true) { Icon(Icons.Rounded.PlayArrow, "Run", tint = if (lang?.runnable == true) Accents.Code.end else MaterialTheme.colorScheme.outline) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Find & replace") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, onClick = { menu = false; showFind = true })
                    DropdownMenuItem(text = { Text("Input & arguments") }, leadingIcon = { Icon(Icons.Rounded.Input, null) }, onClick = { menu = false; showIo = true })
                    if (lang?.runnable == true) DropdownMenuItem(text = { Text("Run with…") }, leadingIcon = { Icon(Icons.Rounded.Tune, null) }, onClick = { menu = false; showRunners = true })
                    DropdownMenuItem(text = { Text("Change language") }, leadingIcon = { Icon(Icons.Rounded.Translate, null) }, onClick = { menu = false; vm.go(CodePage.Picker(fileId)) })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Save to device…") }, onClick = { menu = false; exporter.launch(file?.name ?: "code.txt") })
                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                        menu = false
                        val f = File(context.cacheDir, "share").apply { mkdirs() }.let { File(it, file?.name ?: "code.txt") }
                        f.writeText(holder.text())
                        FileSaver.share(context, FileSaver.uriFor(context, f), "text/plain")
                    })
                    DropdownMenuItem(text = { Text("Editor settings") }, leadingIcon = { Icon(Icons.Rounded.Settings, null) }, onClick = { menu = false; showSettings = true })
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val total = maxHeight
            var panelFraction by remember { mutableFloatStateOf(0.42f) }
            val density = LocalDensity.current
            Column(Modifier.fillMaxSize()) {
                if (showFind) FindBar(holder, onClose = { showFind = false })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (initial != null) {
                        key(fileId) { SoraEditor(holder, initial!!, settings, grammarScope, themeTick, onChange = { vm.edited(fileId, it) }) }
                    } else {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    // The hidden browser engine used by web runtimes.
                    sandbox.view?.let { v ->
                        AndroidView(factory = { FrameLayout(it) }, update = { frame ->
                            if (frame.getChildAt(0) !== v) {
                                frame.removeAllViews()
                                (v.parent as? ViewGroup)?.removeView(v)
                                frame.addView(v, FrameLayout.LayoutParams(1, 1))
                            }
                        }, modifier = Modifier.size(1.dp))
                    }
                }
                if (settings.keyBar) KeyBar(holder, settings.tabSize)
                AnimatedVisibility(showOutput) {
                    Column(Modifier.fillMaxWidth().height(total * panelFraction)) {
                        Box(
                            Modifier.fillMaxWidth().height(18.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh).pointerInput(Unit) {
                                detectVerticalDragGestures { _, drag ->
                                    val delta = with(density) { drag.toDp() } / total
                                    panelFraction = (panelFraction - delta).coerceIn(0.18f, 0.85f)
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline)) }
                        OutputPanel(run, onClose = { showOutput = false }, onRerun = ::doRun, onStop = vm::stop, onInput = { showIo = true }, onClear = vm::clearOutput)
                    }
                }
            }
        }
    }

    if (file != null && lang != null && showRunners) RunnerSheet(lang, runner, onPick = { vm.setRunner(fileId, it); showRunners = false }, onDismiss = { showRunners = false })
    if (file != null && showIo) IoSheet(file, lang, onSave = { s, a, c -> vm.setIo(fileId, s, a, c); showIo = false }, onDismiss = { showIo = false })
    if (showSettings) SettingsSheet(settings, vm, onDismiss = { showSettings = false })
    if (renaming && file != null) RenameDialog(file.name, onDismiss = { renaming = false }) { vm.rename(fileId, it); renaming = false }
    run.needPack?.let { pack -> PackDialog(pack, onDismiss = { vm.clearOutput() }, onReady = { vm.clearOutput(); doRun() }, accent = Accents.Code) }
}

@Composable
private fun SoraEditor(holder: EditorHolder, initial: String, settings: CodeSettings, scopeName: String?, themeTick: Int, onChange: (String) -> Unit) {
    AndroidView(
        factory = { ctx ->
            CodeEditor(ctx).also { e ->
                holder.editor = e
                e.setTypefaceText(Typeface.MONOSPACE)
                e.props.symbolPairAutoCompletion = true
                e.props.autoIndent = true
                e.props.stickyScroll = true
                e.setHighlightCurrentLine(true)
                e.setText(initial)
                e.subscribeAlways(ContentChangeEvent::class.java) { onChange(e.text.toString()) }
            }
        },
        update = { e ->
            e.setTextSize(settings.fontSize)
            e.setWordwrap(settings.wrap)
            e.setLineNumberEnabled(settings.lineNumbers)
            e.setTabWidth(settings.tabSize)
            // Re-applied when the theme or grammar changes (themeTick/scopeName).
            val key = "$scopeName#$themeTick"
            if (e.tag != key) {
                e.tag = key
                e.setColorScheme(Highlighting.colorScheme())
                e.setEditorLanguage(Highlighting.language(scopeName))
            }
        },
        onRelease = { e ->
            holder.editor = null
            e.release()
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private val keys = listOf("⇥", "{", "}", "(", ")", "[", "]", ";", ":", "\"", "'", "=", "<", ">", "+", "-", "*", "/", "%", "&", "|", "!", "?", "#", "$", "_", ".", ",", "\\", "@", "^", "~", "`")

@Composable
private fun KeyBar(holder: EditorHolder, tabSize: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { holder.editor?.undo() }) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
            IconButton(onClick = { holder.editor?.redo() }) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
            for (k in keys) {
                Box(
                    Modifier.padding(horizontal = 2.dp, vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable {
                        val e = holder.editor ?: return@clickable
                        when (k) {
                            "⇥" -> e.insertText(" ".repeat(tabSize), tabSize)
                            else -> e.insertText(k, 1)
                        }
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text(k, fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
            }
        }
    }
}

@Composable
private fun FindBar(holder: EditorHolder, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var caseSensitive by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf(0) }
    fun search() {
        val e = holder.editor ?: return
        if (query.isEmpty()) {
            e.searcher.stopSearch()
            count = 0
        } else {
            runCatching { e.searcher.search(query, EditorSearcher.SearchOptions(!caseSensitive, regex)) }
            count = runCatching { e.searcher.matchedPositionCount }.getOrDefault(0)
        }
    }
    LaunchedEffect(query, regex, caseSensitive) { search() }
    DisposableEffect(Unit) { onDispose { holder.editor?.searcher?.stopSearch() } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find") }, supportingText = null, trailingIcon = { Text(if (query.isEmpty()) "" else "$count", style = MaterialTheme.typography.labelSmall) })
                IconButton(onClick = { runCatching { holder.editor?.searcher?.gotoPrevious() } }) { Icon(Icons.Rounded.KeyboardArrowUp, "Previous") }
                IconButton(onClick = { runCatching { holder.editor?.searcher?.gotoNext() } }) { Icon(Icons.Rounded.KeyboardArrowDown, "Next") }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(replacement, { replacement = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Replace with") })
                TextButton(onClick = { runCatching { holder.editor?.searcher?.replaceCurrentMatch(replacement) }; search() }, enabled = count > 0) { Text("Replace") }
                TextButton(onClick = { runCatching { holder.editor?.searcher?.replaceAll(replacement) } }, enabled = count > 0) { Text("All") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = caseSensitive, onClick = { caseSensitive = !caseSensitive }, label = { Text("Aa") })
                FilterChip(selected = regex, onClick = { regex = !regex }, label = { Text(".* Regex") })
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OutputPanel(run: RunState, onClose: () -> Unit, onRerun: () -> Unit, onStop: () -> Unit, onInput: () -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val outcome = run.outcome
                when {
                    run.running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    run.error != null -> Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    outcome is RunOutcome.Exit -> Icon(if (outcome.code == 0) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null, tint = if (outcome.code == 0) Color(0xFF16A34A) else MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    else -> {}
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        run.running -> "Running · ${run.runner?.label.orEmpty()}"
                        run.error != null -> "Couldn't run"
                        outcome is RunOutcome.Exit -> "Exit ${outcome.code ?: "?"} · ${"%.2f".format(outcome.ms / 1000.0)} s"
                        outcome is RunOutcome.Preview -> "Preview"
                        else -> "Output"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onInput) { Icon(Icons.Rounded.Input, "Input") }
                IconButton(onClick = { copyToClipboard(context, run.chunks.joinToString("") { it.text }, "Output copied") }) { Icon(Icons.Rounded.ContentCopy, "Copy output") }
                if (run.running) IconButton(onClick = onStop) { Icon(Icons.Rounded.Stop, "Stop") } else IconButton(onClick = onRerun) { Icon(Icons.Rounded.PlayArrow, "Run again") }
                IconButton(onClick = { onClear(); onClose() }) { Icon(Icons.Rounded.ExpandMore, "Hide") }
            }
            HorizontalDivider()
            val outcome = run.outcome
            if (outcome is RunOutcome.Preview) {
                AndroidView(factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                    }
                }, update = { w ->
                    if (w.tag != outcome.html) {
                        w.tag = outcome.html
                        w.loadDataWithBaseURL("https://preview.vitmap.local/", outcome.html, "text/html", "utf-8", null)
                    }
                }, onRelease = { it.destroy() }, modifier = Modifier.fillMaxSize())
            } else {
                val list = rememberLazyListState()
                LaunchedEffect(run.chunks.size, run.chunks.lastOrNull()?.text?.length) {
                    if (run.chunks.isNotEmpty()) list.scrollToItem(run.chunks.lastIndex, Int.MAX_VALUE / 2)
                }
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = list) {
                        if (run.chunks.isEmpty() && !run.running && run.error == null) item {
                            Text("Press ▶ to run. Program input goes in Input.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        items(run.chunks) { c ->
                            Text(
                                c.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = when (c.kind) {
                                    OutKind.OUT -> MaterialTheme.colorScheme.onSurface
                                    OutKind.ERR -> MaterialTheme.colorScheme.error
                                    OutKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        run.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunnerSheet(lang: Language, current: Runner?, onPick: (Runner?) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text("Run ${lang.name} with", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onPick(null) }) { Text("Use the recommended runner") }
            for ((kind, list) in lang.runners.groupBy { it.kind }) {
                SectionTitle(kind.label)
                var expanded by remember(kind) { mutableStateOf(list.size <= 6) }
                for (r in if (expanded) list else list.take(6)) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(r) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = current?.kind == r.kind && current.id == r.id, onClick = { onPick(r) })
                        Text(r.label.substringAfter(" · "), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (list.size > 6) TextButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    Text(if (expanded) "Fewer" else "All ${list.size} versions")
                }
            }
            if (lang.runners.any { it.kind == RunKind.WANDBOX || it.kind == RunKind.GODBOLT }) {
                Text(
                    "Cloud runs send your code to wandbox.org or godbolt.org, free public compilers. Don't include secrets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IoSheet(file: CodeFile, lang: Language?, onSave: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var stdin by remember { mutableStateOf(file.stdin) }
    var args by remember { mutableStateOf(file.args) }
    var compilerArgs by remember { mutableStateOf(file.compilerArgs) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { onSave(stdin, args, compilerArgs) }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Input & arguments", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(stdin, { stdin = it }, Modifier.fillMaxWidth().height(160.dp), label = { Text("Standard input") }, placeholder = { Text("Lines your program reads") }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            OutlinedTextField(args, { args = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Program arguments") })
            if (lang?.runners?.any { it.kind == RunKind.WANDBOX || it.kind == RunKind.GODBOLT } == true) {
                OutlinedTextField(compilerArgs, { compilerArgs = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Compiler flags (cloud)") }, placeholder = { Text("-O2 -std=c++20") })
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(stdin, args, compilerArgs) }) { Text("Save") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsSheet(s: CodeSettings, vm: CodeViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val themes = remember { CodeCatalog.themes(context) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Editor settings", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Text size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("${s.fontSize.toInt()} sp", style = MaterialTheme.typography.labelLarge, color = Accents.Code.end)
            }
            Slider(s.fontSize, { v -> vm.settings { it.copy(fontSize = v) } }, valueRange = 9f..26f, steps = 16)
            OptionSwitch("Wrap long lines", "Instead of scrolling sideways", s.wrap) { b -> vm.settings { it.copy(wrap = b) } }
            OptionSwitch("Line numbers", "Shown in the gutter", s.lineNumbers) { b -> vm.settings { it.copy(lineNumbers = b) } }
            OptionSwitch("Symbol bar", "Brackets, operators, undo and redo above the keyboard", s.keyBar) { b -> vm.settings { it.copy(keyBar = b) } }
            OptionChips("Tab width", listOf(2, 4, 8), s.tabSize, { "$it spaces" }) { n -> vm.settings { it.copy(tabSize = n) } }
            OptionChips("Time limit", listOf(10, 30, 60, 120, 300), s.timeoutSec, { if (it >= 60) "${it / 60} min" else "$it s" }) { n -> vm.settings { it.copy(timeoutSec = n) } }
            SectionTitle("Theme")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = s.theme == null, onClick = { vm.settings { it.copy(theme = null) } }, label = { Text("Match the app") })
                for (t in themes) {
                    val have = Highlighting.isThemeAvailable(context, t.name)
                    FilterChip(
                        selected = s.theme == t.name,
                        onClick = { vm.settings { it.copy(theme = t.name) } },
                        label = { Text(t.display + if (have) "" else " ↓") },
                        leadingIcon = { Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(if (t.dark) Color(0xFF1F2937) else Color(0xFFF3F4F6))) },
                    )
                }
            }
            Text("↓ downloads the theme (a few KB) the first time. Syntax colours for each language download the same way.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
}
