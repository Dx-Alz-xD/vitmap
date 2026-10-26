package com.vitmap.app.feature.photo

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.FilterFrames
import androidx.compose.material.icons.rounded.FilterTiltShift
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tonality
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vignette
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.data.FileSaver
import com.vitmap.app.ui.components.EmptyState
import com.vitmap.app.ui.components.OptionSwitch
import com.vitmap.app.ui.components.PackDialog
import com.vitmap.app.ui.components.Panel
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.formatBytes
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.loadBitmap
import com.vitmap.app.ui.theme.Accents
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val PhotoTool.icon: ImageVector
    get() = when (this) {
        PhotoTool.PRESETS -> Icons.Rounded.AutoAwesome
        PhotoTool.ADJUST -> Icons.Rounded.Tune
        PhotoTool.CURVES -> Icons.AutoMirrored.Rounded.ShowChart
        PhotoTool.HSL -> Icons.Rounded.Palette
        PhotoTool.GRADE -> Icons.Rounded.Tonality
        PhotoTool.DETAIL -> Icons.Rounded.CenterFocusStrong
        PhotoTool.EFFECTS -> Icons.Rounded.Vignette
        PhotoTool.CROP -> Icons.Rounded.Crop
        PhotoTool.HEAL -> Icons.Rounded.Healing
        PhotoTool.MASKS -> Icons.Rounded.FilterTiltShift
        PhotoTool.BACKGROUND -> Icons.Rounded.Wallpaper
        PhotoTool.TEXT -> Icons.Rounded.TextFields
        PhotoTool.DRAW -> Icons.Rounded.Draw
        PhotoTool.STICKERS -> Icons.Rounded.EmojiEmotions
        PhotoTool.SHAPES -> Icons.Rounded.Category
        PhotoTool.LAYERS -> Icons.Rounded.Layers
        PhotoTool.FRAME -> Icons.Rounded.FilterFrames
    }

/** The neutral grey professional editors put behind the photo, so colours are judged fairly. */
private val CanvasBackdrop = Color(0xFF1A1A1E)

@Composable
fun PhotoScreen(onBack: () -> Unit, initialUri: String? = null) {
    val vm: PhotoViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.import(uri) }
    var handledInitial by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialUri) {
        if (initialUri != null && !handledInitial) {
            handledInitial = true
            vm.import(Uri.parse(initialUri))
        }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    val openPicker = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    if (ui.project == null) {
        PhotoHome(vm, ui, snackbar, onBack, openPicker)
    } else {
        BackHandler { vm.close() }
        PhotoEditor(vm, ui, snackbar)
    }
}

// ---------------------------------------------------------------- projects

@Composable
private fun PhotoHome(vm: PhotoViewModel, ui: PhotoUi, snackbar: SnackbarHostState, onBack: () -> Unit, onOpen: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val projects = library?.projects.orEmpty()
    var deleting by remember { mutableStateOf<PhotoProject?>(null) }
    ToolScaffold(
        title = "Photo Editor",
        subtitle = ui.busy ?: "Pro-grade edits, all on your phone",
        accent = Accents.PhotoEditor,
        onBack = onBack,
        snackbarHostState = snackbar,
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Panel {
                    Text("Edit a photo", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Looks, light and colour, curves, colour mixing and grading, masks, healing, background removal, text, drawing, stickers, layers and frames. Every edit stays editable and can be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    Button(onClick = onOpen, enabled = ui.busy == null) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null)
                        Spacer(Modifier.width(8.dp))
                        Text(ui.busy ?: "Open a photo")
                    }
                }
            }
            if (projects.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Icons.Rounded.PhotoFilter, "No edits yet", "Photos you edit appear here, so you can come back and change anything later.")
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Recent edits") }
                items(projects, key = { it.id }) { p -> ProjectCard(vm, p, onOpen = { vm.openProject(p.id) }, onDelete = { deleting = p }) }
            }
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this edit?") },
            text = { Text("“${p.name}” and its edits are removed from Vitmap. Photos you exported stay in your gallery.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(p.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProjectCard(vm: PhotoViewModel, p: PhotoProject, onOpen: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, p.id, p.updated) {
        val f = vm.store.thumbFile(p.id)
        value = if (f.exists()) loadBitmap(context, Uri.fromFile(f), 480) else runCatching { vm.store.base(p.id, p.edit.baseVersion, 480) }.getOrNull()
    }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(CanvasBackdrop), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.updated)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- editor

@Composable
private fun PhotoEditor(vm: PhotoViewModel, ui: PhotoUi, snackbar: SnackbarHostState) {
    val project = ui.project ?: return
    val state = remember(project.id) { EditorState() }
    var exporting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var imagePurpose by remember { mutableStateOf(ImagePurpose.LAYER) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) when (imagePurpose) {
            ImagePurpose.LAYER -> vm.addImageLayer(uri)
            ImagePurpose.BACKGROUND -> vm.setBackgroundImage(uri)
        }
    }
    val pickImage: (ImagePurpose) -> Unit = { purpose ->
        imagePurpose = purpose
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val editText: (String?) -> Unit = { id -> state.editingText = id ?: "" }

    ToolScaffold(
        title = project.name,
        subtitle = ui.busy ?: ui.tool.label,
        accent = Accents.PhotoEditor,
        onBack = vm::close,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = vm::undo, enabled = ui.canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
            IconButton(onClick = vm::redo, enabled = ui.canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
            // Hold to see the photo before any edits.
            Box(
                Modifier.size(48.dp).pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        state.comparing = true
                        tryAwaitRelease()
                        state.comparing = false
                    })
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Compare, "Hold to compare", tint = if (state.comparing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Reset all edits") }, onClick = { menu = false; vm.reset() })
                    DropdownMenuItem(text = { Text("Reset zoom") }, onClick = { menu = false; state.resetView() })
                }
            }
            FilledTonalButton(onClick = { exporting = true }, enabled = ui.busy == null, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Rounded.Save, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth > maxHeight && maxWidth >= 600.dp
            val canvas: @Composable (Modifier) -> Unit = { m ->
                Box(m.background(CanvasBackdrop).clipToBounds()) {
                    PhotoCanvas(ui, vm, state, onEditText = { state.editingText = it })
                    if (ui.pixels == null && ui.busy == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    if (ui.rendering) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Accents.PhotoEditor.start, trackColor = Color.Transparent)
                    if (state.comparing) Text("Original", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                    ui.busy?.let { BusyScrim(it) }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    canvas(Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(380.dp).fillMaxHeight()) {
                        ToolTabs(ui.tool, vm)
                        HorizontalDivider()
                        key(ui.tool) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { ToolPanel(ui, vm, state, pickImage, editText) }
                        }
                    }
                }
            } else {
                val panelMax = maxHeight * 0.42f
                Column(Modifier.fillMaxSize()) {
                    canvas(Modifier.weight(1f).fillMaxWidth())
                    key(ui.tool) {
                        Column(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) { ToolPanel(ui, vm, state, pickImage, editText) }
                    }
                    HorizontalDivider()
                    ToolTabs(ui.tool, vm)
                }
            }
        }
    }

    state.editingText?.let { id -> TextDialog(vm, ui, id, onDone = { state.editingText = null }) }
    ui.needSegmentation?.let { c ->
        PackDialog(Segmentation.pack, onDismiss = vm::segmentationHandled, onReady = { vm.cutOut(c.mode, c.image) }, accent = Accents.PhotoEditor)
    }
    if (exporting || ui.exported != null) {
        ExportSheet(vm, ui, onDismiss = { exporting = false; vm.dismissExported() })
    }
}

@Composable
private fun BusyScrim(text: String) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = Color.White)
            Text(text, color = Color.White, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ToolTabs(selected: PhotoTool, vm: PhotoViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (t in PhotoTool.entries) {
            val on = t == selected
            val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier.width(68.dp).clip(RoundedCornerShape(14.dp)).background(if (on) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                    .clickable { vm.tool(t) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, null, tint = tint)
                Text(t.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Adds text ([id] empty) or edits an existing text layer. */
@Composable
private fun TextDialog(vm: PhotoViewModel, ui: PhotoUi, id: String, onDone: () -> Unit) {
    val existing = ui.edit.overlays.firstOrNull { it.id == id } as? Overlay.TextLayer
    var text by remember(id) { mutableStateOf(existing?.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (existing == null) "Add text" else "Edit text") },
        text = {
            OutlinedTextField(
                text, { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Type something") },
                minLines = 2,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    existing == null && text.isNotBlank() -> {
                        vm.tool(PhotoTool.TEXT)
                        vm.addOverlay(Overlay.TextLayer(vm.newId(), text.trimEnd()))
                    }
                    existing != null && text.isBlank() -> vm.removeOverlay(existing.id)
                    existing != null -> vm.updateOverlay(existing.id) { (it as Overlay.TextLayer).copy(text = text.trimEnd()) }
                }
                onDone()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- export

private val sizes = listOf(0 to "Full size", 4096 to "4096 px", 2048 to "2048 px", 1080 to "1080 px")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(vm: PhotoViewModel, ui: PhotoUi, onDismiss: () -> Unit) {
    val project = ui.project ?: return
    val library by vm.library.collectAsStateWithLifecycle()
    val last = library?.lastExport ?: ExportOptions()
    val transparent = ui.edit.background.mode == BackgroundMode.TRANSPARENT
    var format by remember { mutableStateOf(if (transparent && !last.format.alpha) ExportFormat.PNG else last.format) }
    var quality by remember { mutableStateOf(last.quality.toFloat()) }
    var maxSize by remember { mutableStateOf(last.maxSize) }
    var keepExif by remember { mutableStateOf(last.keepExif) }
    var name by remember { mutableStateOf(project.name) }
    val formats = ExportFormat.entries.filter { it != ExportFormat.HEIC || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val done = ui.exported
            if (done != null) {
                ExportDone(done, format) { onDismiss() }
                return@Column
            }
            Text("Save photo", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), suffix = { Text(".${format.ext}") })
            SheetLabel("Format")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in formats) FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
            }
            if (transparent && !format.alpha) Text("${format.label} can't store transparency; the background will be white. Choose PNG or WebP to keep it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (format.lossy) AdjustSlider("Quality", quality, { quality = it }, {}, {}, 40f..100f, 92f)
            SheetLabel("Size")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((px, label) in sizes) FilterChip(selected = maxSize == px, onClick = { maxSize = px }, label = { Text(label) })
            }
            val (w, h) = outputSize(project, ui.edit.geometry, maxSize)
            Text("About $w × $h pixels", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (format != ExportFormat.HEIC) OptionSwitch("Keep photo info", "Camera, date and location (EXIF) from the original", keepExif) { keepExif = it }
            Button(
                onClick = { vm.export(ExportOptions(format, quality.roundToInt(), maxSize, keepExif, name.trim())) },
                enabled = ui.busy == null && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (ui.busy != null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(ui.busy)
                } else {
                    Icon(Icons.Rounded.Save, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save to Pictures")
                }
            }
            Text("Your edit stays here too, so you can change it and save again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SheetLabel(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ExportDone(r: PhotoStore.Exported, format: ExportFormat, onClose: () -> Unit) {
    val context = LocalContext.current
    val uri = r.uri ?: FileSaver.uriFor(context, r.file)
    val preview by produceState<Bitmap?>(null, r.file) { value = loadBitmap(context, Uri.fromFile(r.file), 900) }
    Text("Saved", style = MaterialTheme.typography.titleLarge)
    Surface(shape = RoundedCornerShape(16.dp), color = CanvasBackdrop, modifier = Modifier.fillMaxWidth().aspectRatio(preview?.let { max(0.6f, min(2f, it.width.toFloat() / it.height)) } ?: 1.5f)) {
        preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
    Text("${r.file.name} · ${r.width} × ${r.height} · ${formatBytes(r.file.length())}", style = MaterialTheme.typography.bodyMedium)
    Text(if (r.uri != null) "In your gallery under Pictures/Vitmap" else "Ready to share", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { FileSaver.open(context, uri, format.mime) }) { Text("Open") }
        OutlinedButton(onClick = { FileSaver.share(context, uri, format.mime) }) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
        Spacer(Modifier.weight(1f))
        Button(onClick = onClose) { Text("Done") }
    }
}

/** Expected export dimensions: rotation and crop applied, then the size limit. */
private fun outputSize(p: PhotoProject, g: Geometry, maxSize: Int): Pair<Int, Int> {
    val (w, h) = if (g.quarter % 2 == 1) p.height to p.width else p.width to p.height
    val cw = w * (g.right - g.left)
    val ch = h * (g.bottom - g.top)
    val limit = if (maxSize > 0) maxSize else 8000
    val s = min(1f, limit / max(cw, ch))
    return (cw * s).roundToInt() to (ch * s).roundToInt()
}
