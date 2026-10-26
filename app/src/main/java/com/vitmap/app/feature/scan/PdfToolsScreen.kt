package com.vitmap.app.feature.scan

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BrandingWatermark
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.data.FileSaver
import com.vitmap.app.data.SaveCollection
import com.vitmap.app.ml.Ocr
import com.vitmap.app.ml.OcrMissingException
import com.vitmap.app.ui.components.GradientIcon
import com.vitmap.app.ui.components.PackDialog
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.components.copyToClipboard
import com.vitmap.app.ui.components.formatBytes
import com.vitmap.app.ui.components.shareText
import com.vitmap.app.ui.theme.Accents
import com.vitmap.core.scan.PageFilter
import kotlinx.coroutines.launch
import java.io.File

enum class PdfOp(val title: String, val description: String, val icon: ImageVector) {
    EDIT("Edit & annotate", "Text, pen, highlighter, shapes, images and whiteout; reorder, rotate or delete pages", Icons.Rounded.EditNote),
    SIGN("Sign", "Draw a signature once, then place it on any page", Icons.Rounded.Draw),
    MERGE("Merge", "Combine PDFs in the order you choose, keeping links and bookmarks", Icons.Rounded.MergeType),
    SPLIT("Split & extract", "Pull out page ranges, or split every page into its own file", Icons.Rounded.CallSplit),
    IMAGES("Images to PDF", "Turn photos or screenshots into a single PDF", Icons.Rounded.Collections),
    TO_IMAGES("PDF to images", "Save every page as a JPG or PNG", Icons.Rounded.Image),
    PROTECT("Protect", "Add a password; optionally block printing, copying or editing", Icons.Rounded.Lock),
    UNLOCK("Unlock", "Remove the password from a PDF you can open", Icons.Rounded.LockOpen),
    NUMBERS("Page numbers", "Stamp page numbers in the corner or centre", Icons.Rounded.FormatListNumbered),
    WATERMARK("Watermark", "A diagonal text watermark on every page", Icons.Rounded.BrandingWatermark),
    TEXT("Extract text", "Copy the text of a PDF, with OCR for scans", Icons.AutoMirrored.Rounded.TextSnippet),
    INFO("Document info", "See page count and edit title, author and keywords", Icons.Rounded.Info),
    COMPRESS("Compress", "Shrink a PDF down to a size you choose", Icons.Rounded.Compress),
}

@Composable
fun PdfToolsScreen(onBack: () -> Unit, onOpenTool: (String, String?) -> Unit) {
    val vm: ScanViewModel = viewModel(key = "pdftools")
    DocFlowHost(vm, Accents.PdfTools) { PdfToolsHome(vm, onBack, onOpenTool) }
}

@Composable
private fun PdfToolsHome(vm: ScanViewModel, onBack: () -> Unit, onOpenTool: (String, String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs by vm.docs.collectAsState()
    var op by remember { mutableStateOf<PdfOp?>(null) }
    var input by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var results by remember { mutableStateOf<List<File>>(emptyList()) }

    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val o = op ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            op = null
            return@rememberLauncherForActivityResult
        }
        when (o) {
            PdfOp.EDIT, PdfOp.SIGN -> {
                op = null
                scope.launch {
                    val doc = vm.ensureDoc(null, FileSaver.displayName(context, uri).removeSuffix(".pdf"))
                    vm.importPdfs(doc.id, listOf(uri), open = false).join()
                    if (vm.repo.get(doc.id)?.pages?.isNotEmpty() == true) {
                        vm.push(DocScreen.Editor(doc.id))
                        if (o == PdfOp.SIGN) vm.push(DocScreen.Annotate(doc.id, 0))
                    } else {
                        vm.deleteDoc(doc.id)
                    }
                }
            }
            PdfOp.COMPRESS -> {
                op = null
                onOpenTool("compress", uri.toString())
            }
            else -> input = listOf(uri)
        }
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty() && input.isEmpty()) op = null else input = input + uris
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        op = null
        if (uris.isNotEmpty()) vm.importImages(null, uris, autoCrop = false, filter = PageFilter.ORIGINAL)
    }

    fun start(o: PdfOp) {
        op = o
        input = emptyList()
        results = emptyList()
        when (o) {
            PdfOp.MERGE -> pickMany.launch(arrayOf("application/pdf"))
            PdfOp.IMAGES -> pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            else -> pickOne.launch(arrayOf("application/pdf"))
        }
    }

    ToolScaffold(title = "PDF Tools", subtitle = "Everything happens on your device", accent = Accents.PdfTools, onBack = onBack) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(168.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(PdfOp.entries, key = { it.name }) { o ->
                Surface(Modifier.bouncyClickable { start(o) }, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(16.dp)) {
                        GradientIcon(o.icon, Accents.PdfTools, size = 42.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(o.title, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.size(2.dp))
                        Text(o.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (docs.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Your documents") }
                items(docs.take(12), key = { it.id }) { DocCard(vm, it) }
            }
        }
    }

    val o = op
    if (o != null && input.isNotEmpty() && results.isEmpty()) {
        OpSheet(vm, o, input, onAddMore = { pickMany.launch(arrayOf("application/pdf")) }, onInput = { input = it }, onDismiss = { op = null; input = emptyList() }, onResult = { results = it })
    }
    if (results.isNotEmpty()) {
        ResultSheet(vm, results, onDismiss = { results = emptyList(); op = null; input = emptyList() })
    }
}

@Composable
private fun OpSheet(
    vm: ScanViewModel,
    op: PdfOp,
    input: List<Uri>,
    onAddMore: () -> Unit,
    onInput: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
    onResult: (List<File>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val first = input.first()
    val baseName = remember(first) { FileSaver.displayName(context, first).removeSuffix(".pdf").removeSuffix(".PDF") }
    var info by remember(first) { mutableStateOf<PdfInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(first) {
        info = runCatching { PdfOps.info(context, first) }.onFailure { if (it is PdfPasswordException && op != PdfOp.UNLOCK) error = "This PDF is password-protected. Unlock it first." }.getOrNull()
    }

    fun run(block: suspend () -> List<File>) {
        working = true
        error = null
        scope.launch {
            try {
                onResult(block())
            } catch (e: PdfPasswordException) {
                error = if (op == PdfOp.UNLOCK) "Wrong password" else "This PDF is password-protected. Unlock it first."
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Something went wrong"
            } finally {
                working = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientIcon(op.icon, Accents.PdfTools, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(op.title, style = MaterialTheme.typography.titleLarge)
                    if (op != PdfOp.MERGE) Text(
                        listOfNotNull(baseName, info?.let { "${it.pages} pages" }, info?.let { formatBytes(it.bytes) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
            }
            when (op) {
                PdfOp.MERGE -> MergeOptions(input, onInput, onAddMore) { name -> run { listOf(PdfOps.merge(context, input, name) { d, t -> progress = d.toFloat() / t }) } }
                PdfOp.SPLIT -> SplitOptions(info?.pages ?: 0) { ranges, separate ->
                    run {
                        if (separate) PdfOps.split(context, first, ranges, baseName)
                        else listOf(PdfOps.extract(context, first, ranges.flatMap { it.toList() }, "$baseName (extract)"))
                    }
                }
                PdfOp.TO_IMAGES -> ToImagesOptions { dpi, png ->
                    working = true
                    scope.launch {
                        try {
                            val n = PdfOps.toImages(context, first, dpi, png, baseName) { d, t -> progress = d.toFloat() / t }
                            vm.toast(if (n > 0) "Saved $n image${if (n == 1) "" else "s"} to Pictures/Vitmap/$baseName" else "Saving images needs Android 10 or newer")
                            onDismiss()
                        } catch (e: Exception) {
                            error = e.localizedMessage
                        } finally {
                            working = false
                        }
                    }
                }
                PdfOp.PROTECT -> ProtectOptions { user, owner, print, copy, edit -> run { listOf(PdfOps.protect(context, first, user, owner, print, copy, edit, "$baseName (protected)")) } }
                PdfOp.UNLOCK -> UnlockOptions { pw -> run { listOf(PdfOps.unlock(context, first, pw, "$baseName (unlocked)")) } }
                PdfOp.NUMBERS -> NumberOptions { pos, start, pattern, size -> run { listOf(PdfOps.pageNumbers(context, first, pos, start, pattern, size, "$baseName (numbered)")) } }
                PdfOp.WATERMARK -> WatermarkOptions { text, opacity, color -> run { listOf(PdfOps.watermark(context, first, text, opacity, color, "$baseName (watermarked)")) } }
                PdfOp.TEXT -> ExtractText(vm, first, baseName)
                PdfOp.INFO -> info?.let { i -> InfoOptions(i) { t, a, s, k -> run { listOf(PdfOps.setInfo(context, first, t, a, s, k, baseName)) } } }
                else -> {}
            }
            if (working) {
                if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun MergeOptions(input: List<Uri>, onInput: (List<Uri>) -> Unit, onAddMore: () -> Unit, onMerge: (String) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("Merged") }
    Text("${input.size} file${if (input.size == 1) "" else "s"} · reorder with the arrows", style = MaterialTheme.typography.labelLarge)
    input.forEachIndexed { i, u ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PictureAsPdf, null, tint = Accents.PdfTools.start)
            Spacer(Modifier.width(10.dp))
            Text(FileSaver.displayName(context, u), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { onInput(input.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Icon(Icons.Rounded.ArrowUpward, "Move up") }
            IconButton(onClick = { onInput(input.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < input.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Move down") }
            IconButton(onClick = { onInput(input.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remove") }
        }
    }
    OutlinedButton(onClick = onAddMore) {
        Icon(Icons.Rounded.Add, null)
        Spacer(Modifier.width(6.dp))
        Text("Add PDFs")
    }
    OutlinedTextField(name, { name = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), suffix = { Text(".pdf") })
    Button(onClick = { onMerge(name) }, enabled = input.size >= 2, modifier = Modifier.fillMaxWidth()) { Text("Merge ${input.size} PDFs") }
}

@Composable
private fun SplitOptions(pages: Int, onSplit: (List<IntRange>, Boolean) -> Unit) {
    var mode by remember { mutableStateOf(0) }
    var spec by remember { mutableStateOf("1-${maxOf(1, pages / 2)}") }
    var every by remember { mutableStateOf("1") }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("Extract pages", "Split by ranges", "Every N pages").forEachIndexed { i, l ->
            SegmentedButton(selected = mode == i, onClick = { mode = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l, maxLines = 1) }
        }
    }
    when (mode) {
        0, 1 -> OutlinedTextField(spec, { spec = it }, label = { Text("Pages, e.g. 1-3, 5, 8-") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(if (mode == 0) "Chosen pages go into one new PDF" else "Each range becomes its own PDF") })
        else -> OutlinedTextField(every, { every = it.filter(Char::isDigit) }, label = { Text("Pages per file") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    }
    Button(onClick = {
        if (pages <= 0) return@Button
        when (mode) {
            0 -> onSplit(PdfOps.parseRanges(spec, pages), false)
            1 -> onSplit(PdfOps.parseRanges(spec, pages), true)
            else -> {
                val n = every.toIntOrNull()?.coerceAtLeast(1) ?: 1
                onSplit((0 until pages step n).map { it..minOf(pages - 1, it + n - 1) }, true)
            }
        }
    }, modifier = Modifier.fillMaxWidth(), enabled = pages > 0) { Text("Split") }
}

@Composable
private fun ToImagesOptions(onGo: (Int, Boolean) -> Unit) {
    var dpi by remember { mutableStateOf(150) }
    var png by remember { mutableStateOf(false) }
    Text("Resolution", style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(96, 150, 220, 300).forEachIndexed { i, d ->
            SegmentedButton(selected = dpi == d, onClick = { dpi = d }, shape = SegmentedButtonDefaults.itemShape(i, 4)) { Text("$d dpi") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !png, onClick = { png = false }, label = { Text("JPG") })
        FilterChip(selected = png, onClick = { png = true }, label = { Text("PNG") })
    }
    Button(onClick = { onGo(dpi, png) }, modifier = Modifier.fillMaxWidth(), enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { Text("Save images") }
}

@Composable
private fun ProtectOptions(onGo: (String, String, Boolean, Boolean, Boolean) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var print by remember { mutableStateOf(true) }
    var copy by remember { mutableStateOf(true) }
    var edit by remember { mutableStateOf(false) }
    PasswordField(pw, "Password to open") { pw = it }
    PasswordField(confirm, "Repeat password") { confirm = it }
    PasswordField(owner, "Owner password (optional, for changing permissions)") { owner = it }
    CheckRow("Allow printing", print) { print = it }
    CheckRow("Allow copying text", copy) { copy = it }
    CheckRow("Allow editing and form filling", edit) { edit = it }
    if (confirm.isNotEmpty() && confirm != pw) Text("Passwords don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Button(onClick = { onGo(pw, owner, print, copy, edit) }, enabled = pw.isNotEmpty() && pw == confirm, modifier = Modifier.fillMaxWidth()) { Text("Protect with AES-256") }
}

@Composable
private fun UnlockOptions(onGo: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    PasswordField(pw, "Current password") { pw = it }
    Button(onClick = { onGo(pw) }, modifier = Modifier.fillMaxWidth()) { Text("Remove password") }
}

@Composable
private fun NumberOptions(onGo: (NumberPosition, Int, String, Float) -> Unit) {
    var pos by remember { mutableStateOf(NumberPosition.BOTTOM_CENTER) }
    var start by remember { mutableStateOf("1") }
    val patterns = listOf("{n}", "Page {n}", "Page {n} of {total}", "{n} / {total}", "- {n} -")
    var pattern by remember { mutableStateOf(patterns[0]) }
    var size by remember { mutableFloatStateOf(10f) }
    Text("Position", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in NumberPosition.entries) FilterChip(selected = pos == p, onClick = { pos = p }, label = { Text(p.label) })
    }
    Text("Format", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in patterns) FilterChip(selected = pattern == p, onClick = { pattern = p }, label = { Text(p.replace("{n}", "3").replace("{total}", "12")) })
    }
    OutlinedTextField(start, { start = it.filter(Char::isDigit) }, label = { Text("First number") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Size ${size.toInt()} pt", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(90.dp))
        Slider(size, { size = it }, valueRange = 7f..24f, modifier = Modifier.weight(1f))
    }
    Button(onClick = { onGo(pos, start.toIntOrNull() ?: 1, pattern, size) }, modifier = Modifier.fillMaxWidth()) { Text("Add page numbers") }
}

@Composable
private fun WatermarkOptions(onGo: (String, Float, Long) -> Unit) {
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    var opacity by remember { mutableFloatStateOf(0.18f) }
    val colors = listOf(0xFF6B7280, 0xFFDC2626, 0xFF2563EB, 0xFF16A34A, 0xFF111111)
    var color by remember { mutableStateOf(colors[0]) }
    OutlinedTextField(text, { text = it }, label = { Text("Watermark text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Opacity", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(80.dp))
        Slider(opacity, { opacity = it }, valueRange = 0.05f..0.6f, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in colors) Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).clickable { color = c }.then(if (c == color) Modifier.padding(9.dp).clip(CircleShape).background(Color.White) else Modifier))
    }
    Button(onClick = { onGo(text, opacity, color) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Add watermark") }
}

@Composable
private fun InfoOptions(i: PdfInfo, onGo: (String, String, String, String) -> Unit) {
    var title by remember { mutableStateOf(i.title) }
    var author by remember { mutableStateOf(i.author) }
    var subject by remember { mutableStateOf(i.subject) }
    var keywords by remember { mutableStateOf(i.keywords) }
    Text("PDF ${i.version} · ${i.pages} pages · ${formatBytes(i.bytes)}${if (i.encrypted) " · encrypted" else ""}", style = MaterialTheme.typography.bodyMedium)
    if (i.creator.isNotEmpty() || i.producer.isNotEmpty()) Text("Made with ${listOf(i.creator, i.producer).filter { it.isNotEmpty() }.joinToString(" / ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(author, { author = it }, label = { Text("Author") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(keywords, { keywords = it }, label = { Text("Keywords") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = { onGo(title, author, subject, keywords) }, modifier = Modifier.fillMaxWidth()) { Text("Save a copy with these details") }
}

@Composable
private fun ExtractText(vm: ScanViewModel, uri: Uri, baseName: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Reading text…") }
    var needPack by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        text = runCatching { PdfOps.extractText(context, uri) }.onFailure { status = it.localizedMessage ?: "Couldn't read the PDF" }.getOrNull()
        if (text?.isBlank() == true) status = "This PDF has no text layer (it's probably a scan)."
    }
    fun ocr() {
        scope.launch {
            status = "Running OCR…"
            try {
                val f = FileSaver.copyToCache(context, uri, folder = "pdf-in")
                val out = StringBuilder()
                android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    android.graphics.pdf.PdfRenderer(pfd).use { r ->
                        for (i in 0 until r.pageCount) {
                            status = "Reading page ${i + 1} of ${r.pageCount}…"
                            val bmp = r.openPage(i).use { p ->
                                android.graphics.Bitmap.createBitmap(p.width * 2, p.height * 2, android.graphics.Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(android.graphics.Color.WHITE)
                                    p.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                }
                            }
                            out.appendLine(Ocr.recognize(context, bmp).text).appendLine()
                            bmp.recycle()
                        }
                    }
                }
                text = out.toString()
            } catch (e: OcrMissingException) {
                needPack = true
                status = "Text recognition needs a one-time download"
            } catch (e: Exception) {
                status = e.localizedMessage ?: "OCR failed"
            }
        }
    }
    if (needPack) PackDialog(Ocr.pack(com.vitmap.app.ml.OcrScript.LATIN), onDismiss = { needPack = false }, onReady = { ocr() }, accent = Accents.PdfTools)
    val t = text
    if (t.isNullOrBlank()) {
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (t != null) Button(onClick = { ocr() }) { Text("Read it with OCR") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copyToClipboard(context, t, "text") }) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy") }
            OutlinedButton(onClick = { shareText(context, t, baseName) }) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
            OutlinedButton(onClick = {
                scope.launch {
                    val uriOut = FileSaver.save(context, SaveCollection.DOWNLOADS, "$baseName.txt", "text/plain") { it.write(t.toByteArray()) }
                    vm.toast(if (uriOut != null) "Saved to Downloads/Vitmap" else "Saving needs Android 10 or newer")
                }
            }) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text(".txt") }
        }
        Text("${t.length} characters", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            SelectionContainer { Text(t.take(20_000), Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

@Composable
private fun ResultSheet(vm: ScanViewModel, files: List<File>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var saved by remember { mutableStateOf(false) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            runCatching { FileSaver.writeTo(context, uri, files.first()) }.onSuccess { vm.toast("Saved"); onDismiss() }.onFailure { vm.toast(it.localizedMessage ?: "Couldn't save") }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (files.size == 1) "Done" else "${files.size} files ready", style = MaterialTheme.typography.headlineSmall)
            for (f in files.take(30)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PictureAsPdf, null, tint = Accents.PdfTools.start)
                    Spacer(Modifier.width(10.dp))
                    Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatBytes(f.length()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (files.size > 30) Text("…and ${files.size - 30} more", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Button(onClick = {
                        scope.launch {
                            var last: Uri? = null
                            for (f in files) last = runCatching { FileSaver.saveFile(context, SaveCollection.DOCUMENTS, f, mime = "application/pdf") }.getOrNull()
                            saved = true
                            vm.toast("Saved to Downloads/Vitmap")
                            if (files.size == 1 && last != null) FileSaver.open(context, last, "application/pdf")
                        }
                    }, modifier = Modifier.weight(1f), enabled = !saved) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (saved) "Saved" else "Downloads")
                    }
                }
                if (files.size == 1) OutlinedButton(onClick = { saveAs.launch(files.first().name) }, modifier = Modifier.weight(1f)) { Text("Save as…") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { FileSaver.shareFiles(context, files.map { FileSaver.uriFor(context, it) }, "application/pdf") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
                if (files.size == 1) OutlinedButton(onClick = {
                    onDismiss()
                    vm.importPdfs(null, listOf(Uri.fromFile(files.first())), name = files.first().nameWithoutExtension)
                }, modifier = Modifier.weight(1f)) { Text("Open in editor") }
            }
            AnimatedVisibility(saved) { TextButton(onClick = onDismiss) { Text("Close") } }
        }
    }
}
