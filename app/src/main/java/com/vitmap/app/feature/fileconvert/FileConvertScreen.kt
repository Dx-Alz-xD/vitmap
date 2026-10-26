package com.vitmap.app.feature.fileconvert

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.data.FileSaver
import com.vitmap.app.data.SaveCollection
import com.vitmap.app.data.packs.PackState
import com.vitmap.app.feature.compress.Progress
import com.vitmap.app.media.EngineMissingException
import com.vitmap.app.media.MediaEngine
import com.vitmap.app.ui.components.EmptyState
import com.vitmap.app.ui.components.OptionChips
import com.vitmap.app.ui.components.OptionSwitch
import com.vitmap.app.ui.components.PackPrompt
import com.vitmap.app.ui.components.Panel
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.fileThumbnail
import com.vitmap.app.ui.components.formatBytes
import com.vitmap.app.ui.components.rememberPackState
import com.vitmap.app.ui.theme.Accents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

data class ConvInput(val uri: Uri, val file: File, val name: String, val mime: String, val size: Long, val fmt: Fmt?, val preview: Bitmap?)

sealed interface ConvStatus {
    data object Waiting : ConvStatus
    data class Running(val progress: Float?, val stage: String) : ConvStatus
    data class Done(val file: File, val mime: String, val saved: Boolean = false) : ConvStatus
    data class Failed(val message: String) : ConvStatus
}

data class ConvJob(val label: String, val status: ConvStatus)

data class FileConvertState(
    val inputs: List<ConvInput> = emptyList(),
    val loading: Boolean = false,
    val target: Fmt? = null,
    val query: String = "",
    val options: ConvertOptions = ConvertOptions(),
    val combinePdf: Boolean = true,
    val jobs: List<ConvJob> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
)

/** Formats every selected file can be converted to, in the first file's order. */
fun targetsFor(inputs: List<ConvInput>): List<Fmt> {
    if (inputs.isEmpty()) return emptyList()
    val common = inputs.map { i -> Formats.targets(i.fmt).map { it.id }.toSet() }.reduce { a, b -> a intersect b }
    return Formats.targets(inputs.first().fmt).filter { it.id in common }
}

private fun previewMime(fmt: Fmt?, mime: String) = when (fmt?.family) {
    Family.IMAGE -> "image/*"
    Family.VIDEO -> if (fmt.mime.startsWith("image/")) "image/*" else "video/*"
    else -> if (fmt?.id == "pdf") "application/pdf" else mime
}

private fun collectionFor(mime: String) = when {
    mime.startsWith("image/") -> SaveCollection.PICTURES
    mime.startsWith("video/") -> SaveCollection.MOVIES
    mime.startsWith("audio/") -> SaveCollection.MUSIC
    mime == "application/pdf" || mime.startsWith("text/") || mime.contains("document") || mime == "application/rtf" -> SaveCollection.DOCUMENTS
    else -> SaveCollection.DOWNLOADS
}

class FileConvertViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(FileConvertState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var initial: String? = null

    fun openInitial(uri: String) {
        if (initial == uri) return
        initial = uri
        add(listOf(Uri.parse(uri)))
    }

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val added = ArrayList<ConvInput>()
            var failed = 0
            for (uri in uris) {
                try {
                    val name = FileSaver.displayName(ctx, uri)
                    val mime = FileSaver.mime(ctx, uri)
                    val file = FileSaver.copyToCache(ctx, uri, name, "convert-in")
                    val fmt = Formats.detect(name, mime)
                    added += ConvInput(uri, file, name, mime, file.length(), fmt, fileThumbnail(ctx, file, previewMime(fmt, mime), 256))
                } catch (e: Exception) {
                    failed++
                }
            }
            _state.update { s ->
                val inputs = s.inputs + added
                val keep = s.target?.takeIf { t -> targetsFor(inputs).any { it.id == t.id } }
                s.copy(inputs = inputs, loading = false, target = keep, jobs = emptyList(), error = if (failed > 0) "Couldn't open $failed file${if (failed > 1) "s" else ""}" else null)
            }
        }
    }

    fun remove(input: ConvInput) {
        input.file.delete()
        _state.update { s ->
            val inputs = s.inputs - input
            s.copy(inputs = inputs, target = s.target?.takeIf { t -> targetsFor(inputs).any { it.id == t.id } }, jobs = emptyList())
        }
    }

    fun setTarget(f: Fmt) = _state.update { it.copy(target = if (it.target?.id == f.id) null else f, jobs = emptyList()) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setOptions(change: (ConvertOptions) -> ConvertOptions) = _state.update { it.copy(options = change(it.options), jobs = if (it.running) it.jobs else emptyList()) }
    fun setCombine(v: Boolean) = _state.update { it.copy(combinePdf = v, jobs = emptyList()) }

    fun start() {
        val s = _state.value
        val target = s.target ?: return
        if (s.inputs.isEmpty() || s.running) return
        val combine = s.combinePdf && target.id == "pdf" && s.inputs.size > 1 && s.inputs.all { it.fmt?.family == Family.IMAGE }
        val labels = if (combine) listOf("${s.inputs.size} images → one PDF") else s.inputs.map { it.name }
        _state.update { it.copy(running = true, error = null, jobs = labels.map { l -> ConvJob(l, ConvStatus.Waiting) }) }
        job = viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (combine) {
                run(0, "application/pdf") { p ->
                    val dir = File(ctx.cacheDir, "converted").apply { mkdirs() }
                    val out = FileConverters.uniqueFile(dir, FileSaver.safeName(s.inputs.first().name.substringBeforeLast('.')) + ".pdf")
                    withContext(Dispatchers.Default) {
                        FileConverters.imagesToPdf(ctx, s.inputs.map { it.file to it.fmt }, out) { i, n -> p.update(i.toFloat() / n, "Adding image ${i + 1} of $n") }
                    }
                    out
                }
            } else {
                for ((i, input) in s.inputs.withIndex()) {
                    run(i, target.mime) { p -> FileConverters.convert(ctx, input.file, input.name, input.fmt, target, s.options, p) }
                }
            }
            _state.update { it.copy(running = false) }
        }
    }

    private suspend fun run(index: Int, mime: String, work: suspend (Progress) -> File) {
        setJob(index, ConvStatus.Running(null, "Starting"))
        var lastFraction = -1f
        var lastStage = ""
        val progress = Progress { f, stage ->
            // Engines report very often; only publish visible changes.
            if (stage != lastStage || f == null || abs(f - lastFraction) >= 0.005f) {
                lastStage = stage
                lastFraction = f ?: -1f
                setJob(index, ConvStatus.Running(f, stage))
            }
        }
        try {
            setJob(index, ConvStatus.Done(work(progress), mime))
        } catch (e: CancellationException) {
            setJob(index, ConvStatus.Failed("Cancelled"))
            throw e
        } catch (e: EngineMissingException) {
            setJob(index, ConvStatus.Failed("Download the media engine first"))
        } catch (e: Throwable) {
            setJob(index, ConvStatus.Failed(e.localizedMessage ?: e.javaClass.simpleName))
        }
    }

    private fun setJob(index: Int, status: ConvStatus) = _state.update { s ->
        s.copy(jobs = s.jobs.mapIndexed { i, j -> if (i == index) j.copy(status = status) else j })
    }

    fun cancel() {
        job?.cancel()
        _state.update { s -> s.copy(running = false, jobs = s.jobs.map { if (it.status is ConvStatus.Waiting || it.status is ConvStatus.Running) it.copy(status = ConvStatus.Failed("Cancelled")) else it }) }
    }

    fun save(index: Int) {
        val done = _state.value.jobs.getOrNull(index)?.status as? ConvStatus.Done ?: return
        viewModelScope.launch {
            val ok = runCatching { FileSaver.saveFile(getApplication(), collectionFor(done.mime), done.file, mime = done.mime) }.getOrNull() != null
            if (ok) setJob(index, done.copy(saved = true)) else _state.update { it.copy(error = "Couldn't save ${done.file.name}") }
        }
    }

    fun saveAll() = _state.value.jobs.forEachIndexed { i, j -> if ((j.status as? ConvStatus.Done)?.saved == false) save(i) }

    fun markSaved(index: Int) = (_state.value.jobs.getOrNull(index)?.status as? ConvStatus.Done)?.let { setJob(index, it.copy(saved = true)) }
}

private val suggested = mapOf(
    Family.IMAGE to listOf("jpg", "png", "webp", "pdf", "heic", "avif"),
    Family.VIDEO to listOf("mp4", "mp3", "gif", "webm", "m4a", "mkv"),
    Family.AUDIO to listOf("mp3", "m4a", "wav", "flac", "opus", "ogg"),
    Family.DOCUMENT to listOf("pdf", "docx", "txt", "md", "html", "pdf-images"),
    Family.DATA to listOf("json", "csv", "yaml", "xml", "toml"),
    Family.SUBTITLE to listOf("srt", "vtt", "ass", "lrc"),
    Family.ARCHIVE to listOf("zip", "7z", "tgz", "txz"),
)

private fun familyIcon(f: Family?): ImageVector = when (f) {
    Family.IMAGE -> Icons.Rounded.Image
    Family.VIDEO -> Icons.Rounded.Movie
    Family.AUDIO -> Icons.Rounded.AudioFile
    Family.DOCUMENT -> Icons.Rounded.Description
    Family.DATA -> Icons.Rounded.DataObject
    Family.SUBTITLE -> Icons.Rounded.Subtitles
    Family.ARCHIVE -> Icons.Rounded.FolderZip
    null -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

@Composable
fun FileConvertScreen(onBack: () -> Unit, initialUri: String? = null) {
    val vm: FileConvertViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.add(it) }
    LaunchedEffect(initialUri) { initialUri?.let(vm::openInitial) }
    val targets = remember(s.inputs) { targetsFor(s.inputs) }
    val subtitle = when {
        s.inputs.isEmpty() -> "Photos, video, audio, documents, data and archives"
        s.target != null -> "${s.inputs.size} file${if (s.inputs.size > 1) "s" else ""} → ${s.target!!.label}"
        else -> "${s.inputs.size} file${if (s.inputs.size > 1) "s" else ""} · ${targets.size} formats available"
    }

    ToolScaffold(title = "File Converter", subtitle = subtitle, accent = Accents.FileConvert, onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.inputs.isEmpty()) {
                Intro(s.loading, s.error) { picker.launch(arrayOf("*/*")) }
                return@Column
            }
            InputsCard(s.inputs, enabled = !s.running, onAdd = { picker.launch(arrayOf("*/*")) }, onRemove = vm::remove)
            TargetPicker(s.inputs, targets, s.target, s.query, vm::setQuery, vm::setTarget)
            val target = s.target
            if (target != null) {
                if (s.inputs.any { FileConverters.needsFfmpeg(it.fmt, target) }) {
                    val engine by rememberPackState(MediaEngine.ffmpeg)
                    if (engine !is PackState.Ready) PackPrompt(MediaEngine.ffmpeg, accent = Accents.FileConvert, compact = true)
                }
                OptionsCard(s, target, vm)
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                AnimatedContent(s.jobs.isEmpty() && !s.running, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "convertPhase") { idle ->
                    if (idle) {
                        Button(onClick = vm::start, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Rounded.Transform, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (s.inputs.size == 1) "Convert to ${target.label}" else "Convert ${s.inputs.size} files to ${target.label}")
                        }
                    } else {
                        JobsCard(s, vm)
                    }
                }
            } else if (targets.isEmpty()) {
                Text("These files have no output format in common. Convert them separately.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Intro(loading: Boolean, error: String?, onPick: () -> Unit) {
    EmptyState(
        Icons.Rounded.Transform,
        if (loading) "Opening…" else "Convert anything",
        error ?: "Choose one or more files. Vitmap detects the format and shows everything it can become.",
    ) { Button(onClick = onPick, enabled = !loading) { Text("Choose files") } }
    val counts = remember { Formats.all.groupBy { it.family }.mapValues { (_, v) -> v.distinctBy { it.label }.size } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for (f in Family.entries) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(familyIcon(f), null, tint = Accents.FileConvert.end, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${f.label} · ${counts[f] ?: 0}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    Text(
        "Images, documents, data, subtitles and archives convert offline right away. Video and audio use the media engine, a one-time download.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun InputsCard(inputs: List<ConvInput>, enabled: Boolean, onAdd: () -> Unit, onRemove: (ConvInput) -> Unit) {
    SectionTitle("Files") { TextButton(onClick = onAdd, enabled = enabled) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("Add") } }
    Panel(Modifier.fillMaxWidth().animateContentSize(), padding = 8.dp) {
        for (input in inputs) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    val p = input.preview
                    if (p != null) Image(p.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(familyIcon(input.fmt?.family), null, tint = Accents.FileConvert.end)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(input.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${input.fmt?.label ?: "Unknown format"} · ${formatBytes(input.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onRemove(input) }, enabled = enabled) { Icon(Icons.Rounded.Close, "Remove") }
            }
        }
    }
}

@Composable
private fun FormatChip(f: Fmt, selected: Boolean, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(buildAnnotatedString {
                append(f.label)
                if (!f.label.contains(f.ext, ignoreCase = true)) withStyle(SpanStyle(color = muted, fontSize = 11.sp)) { append("  .${f.ext}") }
            })
        },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accents.FileConvert.start.copy(alpha = 0.22f)),
    )
}

@Composable
private fun TargetPicker(inputs: List<ConvInput>, targets: List<Fmt>, target: Fmt?, query: String, onQuery: (String) -> Unit, onSelect: (Fmt) -> Unit) {
    if (targets.isEmpty()) return
    SectionTitle("Convert to")
    OutlinedTextField(
        query, onQuery,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear") } },
        placeholder = { Text("Search ${targets.size} formats") },
        shape = RoundedCornerShape(18.dp),
    )
    val q = query.trim().lowercase().removePrefix(".")
    val shown = if (q.isEmpty()) targets else targets.filter { f -> q in f.label.lowercase() || q in f.ext || q in f.id || f.aliases.any { q in it } || q in f.family.label.lowercase() }
    Panel(Modifier.fillMaxWidth().animateContentSize()) {
        if (q.isEmpty()) {
            val picks = suggested[inputs.first().fmt?.family].orEmpty().mapNotNull { id -> targets.firstOrNull { it.id == id } }
            if (picks.isNotEmpty()) {
                Text("Popular", style = MaterialTheme.typography.labelLarge, color = Accents.FileConvert.end)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (f in picks) FormatChip(f, target?.id == f.id) { onSelect(f) } }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (shown.isEmpty()) Text("No format matches “$query”", color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (family in shown.map { it.family }.distinct()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Icon(familyIcon(family), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(family.label, style = MaterialTheme.typography.labelLarge)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in shown.filter { it.family == family }) FormatChip(f, target?.id == f.id) { onSelect(f) }
            }
        }
    }
}

private fun parseTime(s: String): Double? {
    val t = s.trim()
    if (t.isEmpty()) return 0.0
    val parts = t.split(':')
    if (parts.size > 3) return null
    var total = 0.0
    for (p in parts) total = total * 60 + (p.toDoubleOrNull() ?: return null)
    return total.takeIf { it >= 0 }
}

private fun formatTime(sec: Double): String {
    if (sec <= 0) return ""
    val m = (sec / 60).toInt()
    val r = sec - m * 60
    return if (m > 0) "%d:%04.1f".format(m, r) else "%.1f".format(r)
}

@Composable
private fun TimeField(label: String, value: Double, modifier: Modifier, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(formatTime(value)) }
    OutlinedTextField(
        text,
        { v -> text = v; parseTime(v)?.let(onValue) },
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text("m:ss") },
        isError = parseTime(text) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun TrimRow(o: ConvertOptions, set: ((ConvertOptions) -> ConvertOptions) -> Unit) {
    Text("Trim (optional)", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeField("Start", o.trimStart, Modifier.weight(1f)) { v -> set { it.copy(trimStart = v) } }
        TimeField("End", o.trimEnd, Modifier.weight(1f)) { v -> set { it.copy(trimEnd = v) } }
    }
}

@Composable
private fun QualitySlider(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text("$value", style = MaterialTheme.typography.labelLarge, color = Accents.FileConvert.end)
    }
    Slider(value.toFloat(), { onValue(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
}

private val LOSSLESS_AUDIO = setOf("flac", "wav", "aiff", "alac", "au")
private val ANIMATED = setOf("gif", "webp-anim", "apng")
private val LOSSY_IMAGES = setOf("jpg", "webp", "heic", "avif")

@Composable
private fun OptionsCard(s: FileConvertState, target: Fmt, vm: FileConvertViewModel) {
    val o = s.options
    val set: ((ConvertOptions) -> ConvertOptions) -> Unit = vm::setOptions
    val fromVideo = s.inputs.any { it.fmt?.family == Family.VIDEO }
    SectionTitle("Options")
    Panel(Modifier.fillMaxWidth().animateContentSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                target.family == Family.IMAGE && fromVideo -> {
                    Text("Saves one frame of the video as a picture.", style = MaterialTheme.typography.bodyMedium)
                    TimeField("Frame at", o.frameAt, Modifier.fillMaxWidth()) { v -> set { it.copy(frameAt = v) } }
                }
                target.family == Family.IMAGE -> {
                    if (target.id in LOSSY_IMAGES) QualitySlider("Quality", o.quality, 30..100) { v -> set { it.copy(quality = v) } }
                    OptionChips("Size", listOf(0, 4096, 2048, 1600, 1080, 720, 512, 256), o.maxDim, { if (it == 0) "Original" else "≤ $it px" }) { v -> set { it.copy(maxDim = v) } }
                    if (target.id == "ico") Text("Icons are made at 256 × 256 or smaller.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (target.id == "svg") Text("SVG wraps the picture; it isn't traced into vector shapes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                target.id in ANIMATED -> {
                    OptionChips("Width", listOf(240, 320, 480, 640, 800, 1080), o.gifWidth, { "$it px" }) { v -> set { it.copy(gifWidth = v) } }
                    OptionChips("Frame rate", listOf(6, 8, 10, 12, 15, 20, 24, 30), o.gifFps, { "$it fps" }) { v -> set { it.copy(gifFps = v) } }
                    if (target.id == "webp-anim") QualitySlider("Quality", o.quality, 30..100) { v -> set { it.copy(quality = v) } }
                    TrimRow(o, set)
                }
                target.family == Family.VIDEO -> {
                    val copyable = target.id in setOf("mp4", "mkv", "m4v")
                    if (copyable) OptionSwitch("Fast copy", "Changes only the container; no re-encoding, no quality loss", o.fastCopy) { v -> set { it.copy(fastCopy = v) } }
                    if (!(copyable && o.fastCopy)) {
                        OptionChips("Quality", VideoQuality.entries, o.videoQuality, { it.label }) { v -> set { it.copy(videoQuality = v) } }
                        OptionChips("Resolution", listOf(0, 2160, 1440, 1080, 720, 480, 360, 240), o.height, { if (it == 0) "Original" else "${it}p" }) { v -> set { it.copy(height = v) } }
                        OptionChips("Frame rate", listOf(0, 60, 30, 25, 24, 15), o.fps, { if (it == 0) "Original" else "$it fps" }) { v -> set { it.copy(fps = v) } }
                        OptionChips("Audio bitrate", listOf(64, 96, 128, 160, 192, 256, 320), o.audioKbps, { "$it kb/s" }) { v -> set { it.copy(audioKbps = v) } }
                        OptionSwitch("Remove audio", "Makes a silent video", o.noAudio) { v -> set { it.copy(noAudio = v) } }
                    }
                    TrimRow(o, set)
                }
                target.family == Family.AUDIO -> {
                    if (fromVideo) Text("Extracts the soundtrack from the video.", style = MaterialTheme.typography.bodyMedium)
                    if (target.id !in LOSSLESS_AUDIO) OptionChips("Bitrate", listOf(64, 96, 128, 160, 192, 256, 320), o.audioKbps, { "$it kb/s" }) { v -> set { it.copy(audioKbps = v) } }
                    OptionChips("Sample rate", listOf(0, 8000, 16000, 22050, 44100, 48000, 96000), o.sampleRate, { if (it == 0) "Original" else "%.1f kHz".format(it / 1000.0).replace(".0 ", " ") }) { v -> set { it.copy(sampleRate = v) } }
                    OptionChips("Channels", listOf(0, 1, 2), o.channels, { when (it) { 1 -> "Mono"; 2 -> "Stereo"; else -> "Original" } }) { v -> set { it.copy(channels = v) } }
                    TrimRow(o, set)
                }
                target.id == "pdf-images" -> {
                    OptionChips("Resolution", listOf(72, 110, 150, 200, 300), o.dpi, { "$it dpi" }) { v -> set { it.copy(dpi = v) } }
                    QualitySlider("JPEG quality", o.quality, 40..100) { v -> set { it.copy(quality = v) } }
                    Text("Every page becomes a JPEG inside one ZIP file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                target.id == "pdf" && s.inputs.all { it.fmt?.family == Family.IMAGE } -> {
                    if (s.inputs.size > 1) OptionSwitch("Combine into one PDF", "One page per image, in the order above", s.combinePdf, vm::setCombine)
                    else Text("The image becomes a single PDF page.", style = MaterialTheme.typography.bodyMedium)
                }
                target.family == Family.DOCUMENT -> Text(
                    "Headings, paragraphs, lists and code blocks are carried over. Pictures, tables and complex layouts are simplified. For a scanned PDF, use PDF Tools → Extract text.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                target.family == Family.DATA -> Text(
                    "Nested objects become dotted columns in CSV/TSV and properties; tables become lists of records in JSON, YAML, XML and TOML.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                target.family == Family.SUBTITLE -> {
                    Text("Shift timing", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (d in listOf(-1000L, -100L)) OutlinedButton(onClick = { set { it.copy(subtitleShiftMs = it.subtitleShiftMs + d) } }) { Text("${d / 1000.0}s") }
                        Text("%+.1f s".format(o.subtitleShiftMs / 1000.0), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 6.dp))
                        for (d in listOf(100L, 1000L)) OutlinedButton(onClick = { set { it.copy(subtitleShiftMs = it.subtitleShiftMs + d) } }) { Text("+${d / 1000.0}s") }
                    }
                }
                target.family == Family.ARCHIVE -> Text(
                    if (s.inputs.any { it.fmt?.family == Family.ARCHIVE } && target.id !in setOf("gz", "xz", "bz2")) "Every file and folder inside is repacked into the new archive."
                    else "Packs the file losslessly; it comes back exactly the same when extracted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun JobsCard(s: FileConvertState, vm: FileConvertViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saveAsIndex by remember { mutableStateOf(-1) }
    var saveAsPending by remember { mutableStateOf(false) }
    val saveAsMime = (s.jobs.getOrNull(saveAsIndex)?.status as? ConvStatus.Done)?.mime ?: "application/octet-stream"
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(saveAsMime)) { uri ->
        val i = saveAsIndex
        val done = s.jobs.getOrNull(i)?.status as? ConvStatus.Done
        if (uri != null && done != null) scope.launch { runCatching { FileSaver.writeTo(context, uri, done.file) }.onSuccess { vm.markSaved(i) } }
    }
    // Launch after recomposition so the picker gets this file's MIME type.
    LaunchedEffect(saveAsPending) {
        if (saveAsPending) {
            saveAsPending = false
            (s.jobs.getOrNull(saveAsIndex)?.status as? ConvStatus.Done)?.let { saveAs.launch(it.file.name) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(Modifier.fillMaxWidth().animateContentSize()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((i, job) in s.jobs.withIndex()) JobRow(job, onSave = { vm.save(i) }, onSaveAs = {
                    saveAsIndex = i
                    saveAsPending = true
                })
            }
        }
        val done = s.jobs.mapNotNull { it.status as? ConvStatus.Done }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.running) {
                OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
            } else {
                if (done.size > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Button(onClick = vm::saveAll, enabled = done.any { !it.saved }) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (done.all { it.saved }) "All saved" else "Save all")
                    }
                }
                if (done.size > 1) {
                    OutlinedButton(onClick = { FileSaver.shareFiles(context, done.map { FileSaver.uriFor(context, it.file) }, done.map { it.mime }.distinct().singleOrNull() ?: "*/*") }) {
                        Icon(Icons.Rounded.Share, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Share all")
                    }
                }
                TextButton(onClick = vm::start) { Text("Convert again") }
            }
        }
    }
}

@Composable
private fun JobRow(job: ConvJob, onSave: () -> Unit, onSaveAs: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = when (job.status) {
                is ConvStatus.Done -> Icons.Rounded.CheckCircle to Accents.FileConvert.end
                is ConvStatus.Failed -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
                is ConvStatus.Running -> Icons.Rounded.Transform to Accents.FileConvert.start
                ConvStatus.Waiting -> Icons.Rounded.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, null, tint = tint)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val st = job.status
                Text(if (st is ConvStatus.Done) st.file.name else job.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (st) {
                        is ConvStatus.Done -> formatBytes(st.file.length()) + if (st.saved) " · saved" else ""
                        is ConvStatus.Failed -> st.message
                        is ConvStatus.Running -> st.stage + (st.progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
                        ConvStatus.Waiting -> "Waiting"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (st is ConvStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (val st = job.status) {
            is ConvStatus.Running -> {
                val animated by animateFloatAsState(st.progress ?: 0f, label = "jobProgress")
                if (st.progress != null) LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().height(6.dp), color = Accents.FileConvert.end)
                else LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = Accents.FileConvert.end)
            }
            is ConvStatus.Done -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    FilterChip(selected = st.saved, onClick = onSave, enabled = !st.saved, label = { Text(if (st.saved) "Saved" else "Save") }, leadingIcon = { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)) })
                }
                FilterChip(selected = false, onClick = onSaveAs, label = { Text("Save as…") })
                FilterChip(selected = false, onClick = { FileSaver.open(context, FileSaver.uriFor(context, st.file), st.mime) }, label = { Text("Open") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)) })
                FilterChip(selected = false, onClick = { FileSaver.share(context, FileSaver.uriFor(context, st.file), st.mime) }, label = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)) })
            }
            else -> {}
        }
    }
}
