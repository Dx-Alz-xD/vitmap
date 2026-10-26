package com.vitmap.app.feature.scan

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.data.FileSaver
import com.vitmap.app.ui.components.EmptyState
import com.vitmap.app.ui.components.GradientIcon
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.theme.Accent
import com.vitmap.app.ui.theme.Accents
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Hosts the document flow (camera, page editor, document editor, annotator) above a tool-specific
 * home screen. Used by both the scanner and PDF tools.
 */
@Composable
fun DocFlowHost(vm: ScanViewModel, accent: Accent, home: @Composable () -> Unit) {
    val stack by vm.stack.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    BackHandler(stack.size > 1) { vm.pop() }
    // Previous stack depth, to pick the slide direction (not state: read only by the transition).
    val depth = remember { intArrayOf(stack.size) }
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = {
                val forward = stack.size >= depth[0]
                depth[0] = stack.size
                if (forward) (slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(260))) togetherWith (slideOutHorizontally(tween(320)) { -it / 5 } + fadeOut(tween(200)))
                else (slideInHorizontally(tween(320)) { -it / 5 } + fadeIn(tween(260))) togetherWith (slideOutHorizontally(tween(320)) { it / 3 } + fadeOut(tween(200)))
            },
            label = "docFlow",
        ) { screen ->
            when (screen) {
                DocScreen.Home -> home()
                is DocScreen.Camera -> ScanCameraScreen(vm, screen.docId, onBack = { vm.pop() })
                is DocScreen.PageEdit -> PageEditScreen(vm, screen.docId, screen.pageId, screen.fromCamera)
                is DocScreen.Editor -> DocEditorScreen(vm, screen.docId, accent, onBack = { vm.pop() })
                is DocScreen.Annotate -> AnnotateScreen(vm, screen.docId, screen.pageIndex, accent)
            }
        }
        busy?.let { label ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).bouncyClickable(pressedScale = 1f, haptic = false) {}, contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp, color = accent.start)
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }
}

@Composable
fun ScannerScreen(onBack: () -> Unit) {
    val vm: ScanViewModel = viewModel()
    DocFlowHost(vm, Accents.Scanner) { ScanLibrary(vm, onBack) }
}

@Composable
private fun ScanLibrary(vm: ScanViewModel, onBack: () -> Unit) {
    val docs by vm.docs.collectAsState()
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) vm.importImages(null, uris)
    }
    val pdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importPdfs(null, uris)
    }
    ToolScaffold(
        title = "PDF Scanner",
        subtitle = if (docs.isEmpty()) "Scan documents with automatic edge detection" else "${docs.size} document${if (docs.size == 1) "" else "s"}",
        accent = Accents.Scanner,
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.push(DocScreen.Camera(null)) },
                icon = { Icon(Icons.Rounded.DocumentScanner, null) },
                text = { Text("Scan") },
                containerColor = Accents.Scanner.start,
                contentColor = Color.White,
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickAction(Icons.Rounded.DocumentScanner, "Scan", "Camera", Accents.Scanner, Modifier.weight(1f)) { vm.push(DocScreen.Camera(null)) }
                    QuickAction(Icons.Rounded.AddPhotoAlternate, "Photos", "From gallery", Accents.ColorPicker, Modifier.weight(1f)) {
                        images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    QuickAction(Icons.Rounded.PictureAsPdf, "PDF", "Open & edit", Accents.PdfTools, Modifier.weight(1f)) { pdfs.launch(arrayOf("application/pdf")) }
                }
            }
            if (docs.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        Icons.Rounded.Description,
                        "No documents yet",
                        "Point the camera at a page: edges are found automatically and the page is captured when it's steady. You can also switch to manual capture, tweak contrast before shooting, and edit everything afterwards.",
                    )
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Documents") }
                items(docs, key = { it.id }) { doc -> DocCard(vm, doc, Modifier.animateItem()) }
            }
        }
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, accent: Accent, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.bouncyClickable(onClick = onClick), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp)) {
            GradientIcon(icon, accent, size = 40.dp)
            Spacer(Modifier.size(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun DocCard(vm: ScanViewModel, doc: ScanDoc, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(modifier.bouncyClickable { vm.push(DocScreen.Editor(doc.id)) }, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.85f).padding(10.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                val first = doc.pages.firstOrNull()
                if (first != null) PageThumb(doc.id, first, Modifier.fillMaxSize(), maxDim = 360)
                else Icon(Icons.Rounded.Description, null, Modifier.align(Alignment.Center).size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.padding(start = 14.dp, end = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(doc.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(doc.updated))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Share PDF") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                            menu = false
                            scope.launch {
                                val f = vm.buildPdf(doc.id, ExportOptions(), doc.name) ?: return@launch
                                FileSaver.share(context, FileSaver.uriFor(context, f), "application/pdf", doc.name)
                            }
                        })
                        DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { menu = false; vm.duplicateDoc(doc.id) })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${doc.name}”?") },
            text = { Text("The document and its photos are removed from this device. PDFs you already saved elsewhere aren't affected.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteDoc(doc.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
