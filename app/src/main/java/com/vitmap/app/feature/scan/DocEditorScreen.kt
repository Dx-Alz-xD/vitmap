package com.vitmap.app.feature.scan

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vitmap.app.data.FileSaver
import com.vitmap.app.ui.components.EmptyState
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.components.formatBytes
import com.vitmap.app.ui.theme.Accent
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DocEditorScreen(vm: ScanViewModel, docId: String, accent: Accent, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs by vm.docs.collectAsState()
    val doc = docs.firstOrNull { it.id == docId }
    if (doc == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var renaming by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) vm.importImages(docId, uris)
    }
    val pdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importPdfs(docId, uris)
    }

    ToolScaffold(
        title = doc.name,
        subtitle = if (selecting) "${selected.size} selected" else "${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"} · hold and drag to reorder",
        accent = accent,
        onBack = { if (selecting) selected = emptySet() else onBack() },
        actions = {
            if (selecting) {
                IconButton(onClick = { selected = if (selected.size == doc.pages.size) emptySet() else doc.pages.map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Select all") }
            } else {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Rename") }
                IconButton(onClick = {
                    scope.launch {
                        val f = vm.buildPdf(docId, ExportOptions(), doc.name) ?: return@launch
                        FileSaver.share(context, FileSaver.uriFor(context, f), "application/pdf", doc.name)
                    }
                }, enabled = doc.pages.isNotEmpty()) { Icon(Icons.Rounded.Share, "Share PDF") }
            }
        },
        floatingActionButton = {
            if (!selecting) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { if (doc.pages.isEmpty()) addMenu = true else exporting = true },
                        icon = { Icon(if (doc.pages.isEmpty()) Icons.Rounded.Add else Icons.Rounded.PictureAsPdf, null) },
                        text = { Text(if (doc.pages.isEmpty()) "Add pages" else "Save PDF") },
                        containerColor = accent.start,
                        contentColor = Color.White,
                    )
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(selecting, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BarAction(Icons.AutoMirrored.Rounded.RotateRight, "Rotate") { vm.rotatePages(docId, selected, 90) }
                        if (selected.size == 1) {
                            val page = doc.pages.firstOrNull { it.id in selected }
                            if (page?.kind == PageKind.IMAGE) BarAction(Icons.Rounded.Crop, "Crop") {
                                vm.push(DocScreen.PageEdit(docId, page.id))
                                selected = emptySet()
                            }
                        }
                        BarAction(Icons.Rounded.ContentCopy, "Duplicate") { vm.duplicatePages(docId, selected); selected = emptySet() }
                        BarAction(Icons.Rounded.CallSplit, "Extract") { vm.extractPages(docId, selected); selected = emptySet() }
                        BarAction(Icons.Rounded.DeleteOutline, "Delete") { vm.deletePages(docId, selected); selected = emptySet() }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (doc.pages.isEmpty()) {
                EmptyState(Icons.Rounded.NoteAdd, "No pages yet", "Scan with the camera, add photos from your gallery, or merge in pages from other PDFs.", Modifier.align(Alignment.Center)) {
                    AddButtons(onCamera = { vm.push(DocScreen.Camera(docId)) }, onImages = { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, onPdf = { pdfs.launch(arrayOf("application/pdf")) }, onBlank = { vm.addBlankPage(docId) })
                }
            } else {
                PageGrid(
                    vm = vm,
                    doc = doc,
                    selected = selected,
                    accent = accent,
                    onTap = { i, page ->
                        if (selecting) selected = if (page.id in selected) selected - page.id else selected + page.id
                        else vm.push(DocScreen.Annotate(docId, i))
                    },
                    onSelect = { page -> selected = selected + page.id },
                    trailing = {
                        Box(Modifier.aspectRatio(0.72f).clip(RoundedCornerShape(16.dp)).border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).bouncyClickable { addMenu = true }, contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.Add, null, tint = accent.start, modifier = Modifier.size(36.dp))
                                Text("Add pages", style = MaterialTheme.typography.labelLarge, color = accent.start)
                            }
                            DropdownMenu(addMenu, onDismissRequest = { addMenu = false }) {
                                DropdownMenuItem(text = { Text("Scan with camera") }, leadingIcon = { Icon(Icons.Rounded.CameraAlt, null) }, onClick = { addMenu = false; vm.push(DocScreen.Camera(docId)) })
                                DropdownMenuItem(text = { Text("Photos from gallery") }, leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null) }, onClick = { addMenu = false; images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                                DropdownMenuItem(text = { Text("Pages from PDFs (merge)") }, leadingIcon = { Icon(Icons.Rounded.InsertDriveFile, null) }, onClick = { addMenu = false; pdfs.launch(arrayOf("application/pdf")) })
                                DropdownMenuItem(text = { Text("Blank page") }, leadingIcon = { Icon(Icons.Rounded.NoteAdd, null) }, onClick = { addMenu = false; vm.addBlankPage(docId) })
                            }
                        }
                    },
                )
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(doc.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Document name") }) },
            confirmButton = { TextButton(onClick = { vm.rename(docId, name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (exporting) ExportSheet(vm, doc, accent, onDismiss = { exporting = false })
}

@Composable
private fun AddButtons(onCamera: () -> Unit, onImages: () -> Unit, onPdf: () -> Unit, onBlank: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCamera) {
            Icon(Icons.Rounded.CameraAlt, null)
            Spacer(Modifier.width(8.dp))
            Text("Scan with camera")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onImages) { Text("Photos") }
            FilledTonalButton(onClick = onPdf) { Text("PDF") }
            FilledTonalButton(onClick = onBlank) { Text("Blank") }
        }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).bouncyClickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Thumbnail of a finished page including annotations. */
@Composable
fun PageThumb(docId: String, page: DocPage, modifier: Modifier = Modifier, maxDim: Int = 420) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, page) {
        value = runCatching { PageRenderer.renderWithAnnotations(context, docId, page, maxDim) }.getOrNull()
    }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun PageGrid(
    vm: ScanViewModel,
    doc: ScanDoc,
    selected: Set<String>,
    accent: Accent,
    onTap: (Int, DocPage) -> Unit,
    onSelect: (DocPage) -> Unit,
    trailing: @Composable () -> Unit,
) {
    val state = rememberLazyGridState()
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var moved by remember { mutableStateOf(false) }
    val pages = doc.pages

    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        state = state,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().pointerInput(pages.size) {
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    val hit = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                        pos.x >= info.offset.x && pos.x <= info.offset.x + info.size.width && pos.y >= info.offset.y && pos.y <= info.offset.y + info.size.height
                    }
                    val idx = hit?.index
                    if (idx != null && idx < pages.size) {
                        dragIndex = idx
                        dragOffset = Offset.Zero
                        moved = false
                    }
                },
                onDrag = { change, amount ->
                    val from = dragIndex ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    dragOffset += amount
                    moved = true
                    val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == from } ?: return@detectDragGesturesAfterLongPress
                    val center = Offset(info.offset.x + info.size.width / 2f, info.offset.y + info.size.height / 2f) + dragOffset
                    val target = state.layoutInfo.visibleItemsInfo.firstOrNull { t ->
                        t.index != from && t.index < pages.size &&
                            center.x >= t.offset.x && center.x <= t.offset.x + t.size.width && center.y >= t.offset.y && center.y <= t.offset.y + t.size.height
                    }
                    if (target != null) {
                        vm.movePage(doc.id, from, target.index)
                        // Keep the dragged card under the finger after the swap.
                        dragOffset += Offset((info.offset.x - target.offset.x).toFloat(), (info.offset.y - target.offset.y).toFloat())
                        dragIndex = target.index
                    }
                },
                onDragEnd = {
                    val i = dragIndex
                    if (!moved && i != null && i < pages.size) onSelect(pages[i])
                    dragIndex = null
                    dragOffset = Offset.Zero
                },
                onDragCancel = { dragIndex = null; dragOffset = Offset.Zero },
            )
        },
    ) {
        itemsIndexed(pages, key = { _, p -> p.id }) { i, page ->
            val dragging = dragIndex == i
            val scale by animateFloatAsState(if (dragging) 1.06f else if (page.id in selected) 0.94f else 1f, label = "pageScale")
            Column(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        if (dragging) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                        }
                        scaleX = scale
                        scaleY = scale
                    }
                    .then(if (dragging) Modifier else Modifier.animateItem()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.72f)
                        .shadow(if (dragging) 12.dp else 3.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .border(if (page.id in selected) 3.dp else 0.dp, if (page.id in selected) accent.start else Color.Transparent, RoundedCornerShape(16.dp))
                        .bouncyClickable(pressedScale = 0.97f) { onTap(i, page) },
                ) {
                    PageThumb(doc.id, page, Modifier.fillMaxSize())
                    if (page.id in selected) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = accent.start, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).background(Color.White, CircleShape))
                    }
                    if (page.kind == PageKind.PDF) {
                        Icon(Icons.Rounded.PictureAsPdf, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).background(Color(0xCCE11D48), RoundedCornerShape(6.dp)).padding(3.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "add") { trailing() }
    }
}

@Composable
private fun ExportSheet(vm: ScanViewModel, doc: ScanDoc, accent: Accent, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by vm.prefs.flow.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(doc.name) }
    var quality by remember(prefs) { mutableStateOf(prefs?.exportQuality?.let { runCatching { ExportQuality.valueOf(it) }.getOrNull() } ?: ExportQuality.MEDIUM) }
    var size by remember(prefs) { mutableStateOf(prefs?.pageSize?.let { runCatching { ExportPageSize.valueOf(it) }.getOrNull() } ?: ExportPageSize.FIT) }
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<File?>(null) }
    val progress by vm.exportProgress.collectAsState()

    fun options() = ExportOptions(quality, size, password.takeIf { protect && it.isNotEmpty() }, name)

    suspend fun build(): File? {
        vm.setPrefs { it.copy(exportQuality = quality.name, pageSize = size.name) }
        vm.rename(doc.id, name)
        return vm.buildPdf(doc.id, options(), name)?.also { result = it }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            val f = result ?: build() ?: return@launch
            runCatching { FileSaver.writeTo(context, uri, f) }.onSuccess { vm.toast("Saved ${f.name}"); onDismiss() }.onFailure { vm.toast(it.localizedMessage ?: "Couldn't save") }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Save as PDF", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(name, { name = it; result = null }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), suffix = { Text(".pdf") })
            Text("Image quality", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExportQuality.entries.forEachIndexed { i, q ->
                    SegmentedButton(selected = quality == q, onClick = { quality = q; result = null }, shape = SegmentedButtonDefaults.itemShape(i, ExportQuality.entries.size)) { Text(q.label) }
                }
            }
            Text("Page size for photos", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExportPageSize.entries.forEachIndexed { i, s ->
                    SegmentedButton(selected = size == s, onClick = { size = s; result = null }, shape = SegmentedButtonDefaults.itemShape(i, ExportPageSize.entries.size)) { Text(s.label) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Password protect", style = MaterialTheme.typography.titleSmall)
                    Text("AES-256; the PDF asks for this password when opened", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(protect, { protect = it; result = null })
            }
            AnimatedVisibility(protect) {
                OutlinedTextField(password, { password = it; result = null }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            }
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            result?.let { Text("Ready: ${formatBytes(it.length())}", style = MaterialTheme.typography.bodyMedium, color = accent.start) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Button(onClick = {
                        scope.launch {
                            val f = result ?: build() ?: return@launch
                            val uri = vm.saveToDownloads(f, name)
                            if (uri != null) {
                                vm.toast("Saved to Downloads/Vitmap")
                                FileSaver.open(context, uri, "application/pdf")
                                onDismiss()
                            }
                        }
                    }, modifier = Modifier.weight(1f), enabled = progress == null) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Downloads")
                    }
                }
                OutlinedButton(onClick = { saveAs.launch("${FileSaver.safeName(name)}.pdf") }, modifier = Modifier.weight(1f), enabled = progress == null) { Text("Save as…") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val f = result ?: build() ?: return@launch
                        FileSaver.share(context, FileSaver.uriFor(context, f), "application/pdf", name)
                    }
                }, modifier = Modifier.weight(1f), enabled = progress == null) {
                    Icon(Icons.Rounded.Share, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
                OutlinedButton(onClick = { vm.exportImages(doc.id, png = false); onDismiss() }, modifier = Modifier.weight(1f), enabled = progress == null) {
                    Icon(Icons.Rounded.Image, null)
                    Spacer(Modifier.width(6.dp))
                    Text("As images")
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Rounded.Close, null)
                Spacer(Modifier.width(4.dp))
                Text("Close")
            }
        }
    }
}
