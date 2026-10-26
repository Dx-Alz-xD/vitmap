package com.vitmap.app.feature.compress

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.data.FileSaver
import com.vitmap.app.data.SaveCollection
import com.vitmap.app.data.packs.PackState
import com.vitmap.app.media.EngineMissingException
import com.vitmap.app.media.MediaEngine
import com.vitmap.app.ui.components.EmptyState
import com.vitmap.app.ui.components.OptionChips
import com.vitmap.app.ui.components.OptionSwitch
import com.vitmap.app.ui.components.fileThumbnail
import com.vitmap.app.ui.components.PackPrompt
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.formatBytes
import com.vitmap.app.ui.components.rememberPackState
import com.vitmap.app.ui.theme.Accents
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToLong

data class InputFile(val uri: Uri, val file: File, val name: String, val mime: String, val size: Long, val kind: FileKind, val preview: Bitmap?)

data class CompressState(
    val input: InputFile? = null,
    val loading: Boolean = false,
    val targetBytes: Long = 0,
    val options: CompressOptions = CompressOptions(0),
    val running: Boolean = false,
    val progress: Float? = null,
    val stage: String = "",
    val result: CompressResult? = null,
    val error: String? = null,
)

class CompressViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(CompressState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun open(uri: Uri) {
        _state.value = CompressState(loading = true)
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val name = FileSaver.displayName(ctx, uri)
                val mime = FileSaver.mime(ctx, uri)
                val file = FileSaver.copyToCache(ctx, uri, name, "compress-in")
                val kind = Compressor.kindOf(name, mime)
                val preview = fileThumbnail(ctx, file, previewMime(kind))
                val size = file.length()
                val target = suggestTarget(size)
                _state.value = CompressState(input = InputFile(uri, file, name, mime, size, kind, preview), targetBytes = target, options = CompressOptions(target))
            } catch (e: Exception) {
                _state.value = CompressState(error = e.localizedMessage ?: "Couldn't open that file")
            }
        }
    }

    private fun suggestTarget(size: Long): Long = when {
        size > 50L shl 20 -> 25L shl 20
        size > 10L shl 20 -> 5L shl 20
        size > 2L shl 20 -> 1L shl 20
        size > 500L shl 10 -> 200L shl 10
        else -> (size * 0.5).roundToLong()
    }

    fun setTarget(bytes: Long) = _state.update { it.copy(targetBytes = bytes.coerceAtLeast(1024), options = it.options.copy(targetBytes = bytes.coerceAtLeast(1024)), result = null) }

    fun setOptions(change: (CompressOptions) -> CompressOptions) = _state.update { it.copy(options = change(it.options), result = null) }

    fun start() {
        val s = _state.value
        val input = s.input ?: return
        job?.cancel()
        _state.update { it.copy(running = true, progress = null, stage = "Starting", result = null, error = null) }
        job = viewModelScope.launch {
            try {
                val r = Compressor.compress(getApplication(), input.file, input.name, input.kind, s.options.copy(targetBytes = s.targetBytes)) { f, stage ->
                    _state.update { it.copy(progress = f, stage = stage) }
                }
                _state.update { it.copy(running = false, result = r) }
            } catch (e: EngineMissingException) {
                _state.update { it.copy(running = false, error = "Download the media engine first") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(running = false, stage = "") }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = e.localizedMessage ?: "Compression failed") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(running = false) }
    }
}

private fun previewMime(kind: FileKind) = when (kind) {
    FileKind.IMAGE -> "image/*"
    FileKind.VIDEO -> "video/*"
    FileKind.PDF -> "application/pdf"
    else -> ""
}

private fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.IMAGE -> Icons.Rounded.Image
    FileKind.VIDEO -> Icons.Rounded.Movie
    FileKind.AUDIO -> Icons.Rounded.AudioFile
    FileKind.PDF -> Icons.Rounded.PictureAsPdf
    FileKind.OFFICE -> Icons.Rounded.Description
    FileKind.OTHER -> Icons.Rounded.InsertDriveFile
}

@Composable
fun CompressScreen(onBack: () -> Unit, initialUri: String? = null) {
    val vm: CompressViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.open(uri) }
    LaunchedEffect(initialUri) { initialUri?.let { vm.open(Uri.parse(it)) } }

    ToolScaffold(title = "Compress", subtitle = s.input?.let { "${it.kind.label} · ${formatBytes(it.size)}" } ?: "Shrink any file to the size you need", accent = Accents.Compress, onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val input = s.input
            if (input == null) {
                EmptyState(
                    Icons.Rounded.Compress,
                    if (s.loading) "Opening…" else "Choose a file",
                    s.error ?: "Photos, videos, audio, PDFs, Word/PowerPoint/Excel and EPUB files are re-encoded to hit your target size. Anything else is packed into ZIP or XZ.",
                ) { Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !s.loading) { Text("Choose file") } }
                return@Column
            }
            FileCard(input, onChange = { picker.launch(arrayOf("*/*")) })
            if (input.kind == FileKind.VIDEO || input.kind == FileKind.AUDIO) {
                val engine by rememberPackState(MediaEngine.ffmpeg)
                if (engine !is PackState.Ready) PackPrompt(MediaEngine.ffmpeg, accent = Accents.Compress, compact = true)
            }
            TargetPicker(input.size, s.targetBytes, vm::setTarget)
            OptionsFor(input.kind, s.options, vm::setOptions)
            AnimatedContent(
                targetState = when {
                    s.running -> 1
                    s.result != null -> 2
                    else -> 0
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "compressState",
            ) { phase ->
                when (phase) {
                    1 -> ProgressCard(s, vm::cancel)
                    2 -> s.result?.let { ResultCard(input, it, onAgain = vm::start) }
                    else -> Column {
                        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp)) }
                        Button(onClick = vm::start, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = s.targetBytes < input.size || input.kind == FileKind.OTHER) {
                            Icon(Icons.Rounded.Compress, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Compress to ${formatBytes(s.targetBytes)}")
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FileCard(input: InputFile, onChange: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                val p = input.preview
                if (p != null) Image(p.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(kindIcon(input.kind), null, tint = Accents.Compress.end)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(input.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${input.kind.label} · ${formatBytes(input.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onChange) { Text("Change") }
        }
    }
}

@Composable
private fun TargetPicker(original: Long, target: Long, onTarget: (Long) -> Unit) {
    var text by remember(target) { mutableStateOf(if (target >= 1L shl 20) "%.2f".format(target / 1048576.0).trimEnd('0').trimEnd('.') else (target / 1024).toString()) }
    var mb by remember { mutableStateOf(target >= 1L shl 20) }
    SectionTitle("Target size")
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    text,
                    { v ->
                        text = v.filter { it.isDigit() || it == '.' }
                        text.toDoubleOrNull()?.let { onTarget((it * if (mb) 1048576.0 else 1024.0).roundToLong()) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    label = { Text("Maximum size") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = !mb, onClick = { mb = false; text.toDoubleOrNull()?.let { onTarget((it * 1024).roundToLong()) } }, label = { Text("KB") })
                Spacer(Modifier.width(4.dp))
                FilterChip(selected = mb, onClick = { mb = true; text.toDoubleOrNull()?.let { onTarget((it * 1048576).roundToLong()) } }, label = { Text("MB") })
            }
            val fraction = (target.toFloat() / original).coerceIn(0.02f, 1f)
            Slider(fraction, { onTarget((original * it).roundToLong()) }, valueRange = 0.02f..0.98f)
            Text("${(100 - fraction * 100).toInt()}% smaller than ${formatBytes(original)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val presets = listOf(
                    "100 KB" to (100L shl 10), "200 KB" to (200L shl 10), "500 KB" to (500L shl 10), "1 MB" to (1L shl 20),
                    "2 MB" to (2L shl 20), "5 MB" to (5L shl 20), "10 MB" to (10L shl 20), "16 MB" to (16L shl 20), "25 MB" to (25L shl 20), "50 MB" to (50L shl 20),
                )
                for ((label, bytes) in presets.filter { it.second < original }) {
                    FilterChip(selected = target == bytes, onClick = { onTarget(bytes) }, label = { Text("Under $label") })
                }
            }
        }
    }
}

@Composable
private fun OptionsFor(kind: FileKind, o: CompressOptions, set: ((CompressOptions) -> CompressOptions) -> Unit) {
    SectionTitle("Options")
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (kind) {
                FileKind.IMAGE -> {
                    OptionChips("Output", ImageOut.entries, o.imageOut, { it.label }) { v -> set { it.copy(imageOut = v) } }
                    OptionSwitch("Keep camera details (EXIF)", "Date, camera and location; off removes them for privacy", o.keepMetadata) { v -> set { it.copy(keepMetadata = v) } }
                }
                FileKind.VIDEO -> {
                    OptionChips("Codec", VideoCodec.entries, o.videoCodec, { it.label }) { v -> set { it.copy(videoCodec = v) } }
                    OptionChips("Max resolution", listOf(0, 1080, 720, 480, 360), o.maxHeight, { if (it == 0) "Auto" else "${it}p" }) { v -> set { it.copy(maxHeight = v) } }
                    OptionChips("Audio", listOf(0, 128, 96, 64, 32), o.audioKbps, { if (it == 0) "Auto" else "$it kb/s" }) { v -> set { it.copy(audioKbps = v) } }
                    OptionSwitch("Mono audio", "Halves the audio size; fine for speech", o.mono) { v -> set { it.copy(mono = v) } }
                    Text("Two-pass encoding hits the target precisely; H.265 is smaller but slower.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FileKind.AUDIO -> {
                    OptionChips("Output", AudioOut.entries, o.audioOut, { it.label }) { v -> set { it.copy(audioOut = v) } }
                    OptionSwitch("Mono", "Good for voice memos and podcasts", o.mono) { v -> set { it.copy(mono = v) } }
                }
                FileKind.PDF -> {
                    OptionSwitch("Grayscale images", "Removes colour from pictures for extra savings", o.grayscale) { v -> set { it.copy(grayscale = v) } }
                    OptionSwitch("Allow rasterising", "If needed, turn pages into images to reach the target (text stops being selectable)", o.rasterizePdf) { v -> set { it.copy(rasterizePdf = v) } }
                }
                FileKind.OFFICE -> Text("Pictures inside the document are re-encoded and resized; text and formatting stay as they are.", style = MaterialTheme.typography.bodyMedium)
                FileKind.OTHER -> {
                    OptionChips("Archive", ArchiveOut.entries, o.archive, { it.label }) { v -> set { it.copy(archive = v) } }
                    Text("Lossless: the file comes back exactly the same when extracted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(s: CompressState, onCancel: () -> Unit) {
    val animated by animateFloatAsState(s.progress ?: 0f, label = "compressProgress")
    Surface(shape = RoundedCornerShape(22.dp), color = Accents.Compress.start.copy(alpha = 0.12f)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.stage.ifEmpty { "Working…" }, style = MaterialTheme.typography.titleSmall)
            if (s.progress != null) LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Accents.Compress.end)
            else LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), color = Accents.Compress.end)
            OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
        }
    }
}

@Composable
private fun ResultCard(input: InputFile, r: CompressResult, onAgain: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember(r) { mutableStateOf(false) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(r.mime.ifEmpty { "application/octet-stream" })) { uri ->
        if (uri != null) scope.launch { runCatching { FileSaver.writeTo(context, uri, r.file) }.onSuccess { saved = true } }
    }
    val newSize = r.file.length()
    val saving = 1 - newSize.toDouble() / input.size
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (r.reachedTarget) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (r.reachedTarget) Accents.Compress.end else MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(if (r.reachedTarget) "Target reached" else "Smallest possible with these settings", style = MaterialTheme.typography.titleMedium)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatBytes(input.size), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("  →  ", style = MaterialTheme.typography.titleMedium)
                Text(formatBytes(newSize), style = MaterialTheme.typography.headlineMedium, color = Accents.Compress.end)
                Spacer(Modifier.width(10.dp))
                Text(if (saving > 0) "${(saving * 100).toInt()}% smaller" else "no smaller", style = MaterialTheme.typography.labelLarge)
            }
            r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(r.file.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Button(onClick = {
                        scope.launch {
                            val collection = when {
                                r.mime.startsWith("image/") -> SaveCollection.PICTURES
                                r.mime.startsWith("video/") -> SaveCollection.MOVIES
                                r.mime.startsWith("audio/") -> SaveCollection.MUSIC
                                else -> SaveCollection.DOWNLOADS
                            }
                            saved = runCatching { FileSaver.saveFile(context, collection, r.file, mime = r.mime.ifEmpty { FileSaver.mimeFor(r.file.name) }) }.getOrNull() != null
                        }
                    }, enabled = !saved) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (saved) "Saved" else "Save")
                    }
                }
                OutlinedButton(onClick = { saveAs.launch(r.file.name) }) { Text("Save as…") }
                OutlinedButton(onClick = { FileSaver.share(context, FileSaver.uriFor(context, r.file), r.mime.ifEmpty { "application/octet-stream" }) }) {
                    Icon(Icons.Rounded.Share, null)
                }
            }
            AnimatedVisibility(!r.reachedTarget) {
                TextButton(onClick = onAgain) { Text("Try again with these settings") }
            }
        }
    }
}
