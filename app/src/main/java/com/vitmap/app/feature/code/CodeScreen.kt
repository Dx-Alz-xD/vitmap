package com.vitmap.app.feature.code

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.ui.components.EmptyState
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.StatusPill
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.theme.Accents
import kotlin.math.abs

/** A stable colour per language for its badge. */
fun languageColor(id: String): Color {
    val palette = listOf(0xFF6366F1, 0xFFEC4899, 0xFF22C55E, 0xFFF59E0B, 0xFF06B6D4, 0xFFEF4444, 0xFF8B5CF6, 0xFF14B8A6, 0xFFF97316, 0xFF3B82F6, 0xFF84CC16, 0xFFD946EF)
    return Color(palette[abs(id.hashCode()) % palette.size])
}

@Composable
fun LanguageBadge(lang: Language?, size: Int = 40) {
    val label = lang?.ext?.take(3)?.uppercase() ?: "?"
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(languageColor(lang?.id ?: "")),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = (size * 0.3f).sp, maxLines = 1)
    }
}

@Composable
fun CodeScreen(onBack: () -> Unit) {
    val vm: CodeViewModel = viewModel()
    val page by vm.page.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    BackHandler(enabled = page !is CodePage.Home) { if (!vm.back()) onBack() }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState !is CodePage.Home
            (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
        },
        contentKey = { it::class },
        label = "codePage",
    ) { p ->
        when (p) {
            CodePage.Home -> CodeHome(vm, snackbar, onBack)
            is CodePage.Picker -> LanguagePicker(vm, p.forFile, onClose = { vm.back() })
            is CodePage.Editor -> CodeEditorScreen(vm, p.fileId, snackbar, onBack = { if (!vm.back()) onBack() })
        }
    }
}

private val quickStart = listOf("python", "javascript", "cpp", "java", "kotlin", "rust", "go", "typescript", "c", "html", "sql", "lua", "csharp", "ruby", "brainfuck")

@Composable
private fun CodeHome(vm: CodeViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val languages by vm.languages.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    val files = state?.files.orEmpty()
    val runnable = languages.count { it.runnable }
    ToolScaffold(
        title = "Code Writer",
        subtitle = if (languages.isEmpty()) "Loading languages…" else "${languages.size} languages · $runnable can run",
        accent = Accents.Code,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = { IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.FolderOpen, "Open a file") } },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { vm.go(CodePage.Picker()) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New file") })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionTitle("Start something")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val recent = state?.recentLangs.orEmpty()
                    for (id in (recent + quickStart).distinct()) {
                        val lang = languages.firstOrNull { it.id == id } ?: continue
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable { vm.newFile(lang) }) {
                            Column(Modifier.width(92.dp).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                LanguageBadge(lang, 44)
                                Spacer(Modifier.height(8.dp))
                                Text(lang.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable { vm.go(CodePage.Picker()) }) {
                        Column(Modifier.width(92.dp).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Search, null) }
                            Spacer(Modifier.height(8.dp))
                            Text("All ${languages.size}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item { SectionTitle(if (files.isEmpty()) "Your files" else "Your files · ${files.size}") }
            if (files.isEmpty()) {
                item {
                    EmptyState(Icons.Rounded.Code, "No files yet", "Pick a language above or tap New file. Programs run on your phone where possible, and on free online compilers for everything else.") {
                        OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Open a file from the device") }
                    }
                }
            }
            items(files.sortedByDescending { it.updated }, key = { it.id }) { f ->
                FileRow(f, vm.language(f.lang), onOpen = { vm.open(f.id) }, vm = vm)
            }
        }
    }
}

@Composable
private fun FileRow(f: CodeFile, lang: Language?, onOpen: () -> Unit, vm: CodeViewModel) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LanguageBadge(lang)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${lang?.name ?: f.lang} · ${DateUtils.getRelativeTimeSpanString(f.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { menu = false; vm.duplicate(f.id) })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }
    if (renaming) RenameDialog(f.name, onDismiss = { renaming = false }) { vm.rename(f.id, it); renaming = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${f.name}?") },
            text = { Text("The file is removed from Code Writer. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(f.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("File name") }) },
        confirmButton = { TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Summary chips for how a language runs. */
@Composable
fun RunBadges(lang: Language) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val kinds = lang.runners.map { it.kind }.toSet()
        val offline = lang.runners.any { (it.kind == RunKind.LOCAL && it.id != "python") || it.kind == RunKind.DATA || it.kind == RunKind.PREVIEW }
        if (offline) StatusPill("Offline", Color(0xFF16A34A))
        if (RunKind.WEB in kinds || lang.runners.any { it.id == "python" }) StatusPill("Download", Color(0xFF7C3AED))
        if (RunKind.WANDBOX in kinds || RunKind.GODBOLT in kinds) StatusPill("Cloud", Color(0xFF2563EB))
        if (kinds.isEmpty()) StatusPill("Edit only", MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun LanguagePicker(vm: CodeViewModel, forFile: String?, onClose: () -> Unit) {
    val languages by vm.languages.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var runnableOnly by remember { mutableStateOf(false) }
    val q = query.trim().lowercase().removePrefix(".")
    val shown = languages.filter { l ->
        (!runnableOnly || l.runnable) && (q.isEmpty() || q in l.name.lowercase() || q == l.ext.lowercase() || l.spec.aliases.any { it.equals(q, true) } || l.grammar?.aliases?.any { it.equals(q, true) } == true)
    }
    ToolScaffold(
        title = if (forFile != null) "Change language" else "New file",
        subtitle = "${shown.size} of ${languages.size} languages",
        accent = Accents.Code,
        onBack = onClose,
        actions = { TextButton(onClick = { vm.refreshCloud() }) { Text("Refresh") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                query, { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                placeholder = { Text("Search languages or extensions") },
                shape = RoundedCornerShape(18.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = runnableOnly, onClick = { runnableOnly = !runnableOnly }, label = { Text("Can run") })
                Spacer(Modifier.width(8.dp))
                Text("Offline = runs on the phone · Download = one-time runtime · Cloud = free online compilers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                val groups = shown.groupBy { it.spec.category }
                for ((cat, title) in categoryNames) {
                    val list = groups[cat] ?: continue
                    item(key = "h-$cat") { SectionTitle("$title · ${list.size}") }
                    items(list.sortedBy { it.name.lowercase() }, key = { it.id }) { lang ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                                if (forFile != null) {
                                    vm.setLanguage(forFile, lang)
                                    onClose()
                                } else {
                                    vm.newFile(lang)
                                }
                            }.padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LanguageBadge(lang, 36)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(lang.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(".${lang.ext}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                            }
                            RunBadges(lang)
                        }
                    }
                }
                if (shown.isEmpty()) item { Text("No language matches “$query”.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
