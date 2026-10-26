package com.vitmap.app.feature.ytdl

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.data.FileSaver
import com.vitmap.app.data.packs.Http
import com.vitmap.app.data.packs.PackState
import com.vitmap.app.media.EngineMissingException
import com.vitmap.app.media.MediaEngine
import com.vitmap.app.ui.components.OptionChips
import com.vitmap.app.ui.components.OptionSwitch
import com.vitmap.app.ui.components.PackPrompt
import com.vitmap.app.ui.components.Panel
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.formatBytes
import com.vitmap.app.ui.components.hasPermission
import com.vitmap.app.ui.components.rememberPackState
import com.vitmap.app.ui.theme.Accents
import com.vitmap.core.ytdl.YtAudio
import com.vitmap.core.ytdl.YtCodec
import com.vitmap.core.ytdl.YtContainer
import com.vitmap.core.ytdl.YtFormat
import com.vitmap.core.ytdl.YtMode
import com.vitmap.core.ytdl.YtPlaylist
import com.vitmap.core.ytdl.YtRequest
import com.vitmap.core.ytdl.YtSponsor
import com.vitmap.core.ytdl.YtSubs
import com.vitmap.core.ytdl.YtVideo
import com.vitmap.core.ytdl.Ytdl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date

data class YtdlUiState(
    val url: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val video: YtVideo? = null,
    val playlist: YtPlaylist? = null,
    val thumb: Bitmap? = null,
    val request: YtRequest = YtRequest(""),
    val selected: Set<Int> = emptySet(),
    val pickVideo: String? = null,
    val pickAudio: String? = null,
    val version: String? = null,
    val updating: Boolean = false,
    val message: String? = null,
)

/** Small in-memory cache so list thumbnails don't refetch while scrolling. */
private object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(url: String, maxWidth: Int = 720): Bitmap? {
        cache.get(url)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val c = Http.connect(url)
                val bytes = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
        bmp?.let { cache.put(url, it) }
        return bmp
    }
}

private fun ranges(indices: List<Int>): String {
    if (indices.isEmpty()) return ""
    val out = ArrayList<String>()
    var start = indices[0]
    var prev = start
    for (i in indices.drop(1) + Int.MIN_VALUE) {
        if (i == prev + 1) { prev = i; continue }
        out += if (start == prev) "$start" else "$start-$prev"
        start = i
        prev = i
    }
    return out.joinToString(",")
}

class YtdlViewModel(app: Application) : AndroidViewModel(app) {
    private val queue = YtQueue.get(app)
    val history = queue.history
    val live = queue.live
    private val _state = MutableStateFlow(YtdlUiState())
    val state = _state.asStateFlow()
    private var fetchJob: Job? = null
    private var initial: String? = null

    init {
        viewModelScope.launch {
            queue.load().defaults?.let { d -> _state.update { it.copy(request = d.copy(url = it.request.url)) } }
        }
    }

    fun setUrl(u: String) = _state.update { it.copy(url = u, error = null) }

    fun openInitial(text: String) {
        if (initial == text) return
        initial = text
        setUrl(Ytdl.extractUrl(text) ?: text)
        fetch()
    }

    fun paste(context: Context) {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        setUrl(Ytdl.extractUrl(text) ?: text.trim())
        fetch()
    }

    fun clear() {
        fetchJob?.cancel()
        _state.update { it.copy(url = "", loading = false, error = null, video = null, playlist = null, thumb = null) }
    }

    fun fetch(playlist: Boolean = false) {
        val raw = _state.value.url.trim()
        val url = Ytdl.extractUrl(raw) ?: if (Ytdl.looksLikeUrl(raw)) "https://$raw" else null
        if (url == null) {
            _state.update { it.copy(error = "Paste a link to a video, song or playlist") }
            return
        }
        fetchJob?.cancel()
        _state.update { it.copy(url = url, loading = true, error = null, video = null, playlist = null, thumb = null, selected = emptySet(), pickVideo = null, pickAudio = null) }
        fetchJob = viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                var lastError: String? = null
                val r = MediaEngine.ytdlp(ctx, Ytdl.infoArgs(url, playlist), ctx.cacheDir, onStderr = { if (it.startsWith("ERROR")) lastError = it })
                if (r.stdout.isBlank()) throw IOException(lastError?.removePrefix("ERROR:")?.trim()?.replace(Regex("^\\[[^]]+] [\\w-]+: "), "") ?: "Couldn't read this link")
                val info = Ytdl.parse(r.stdout)
                _state.update { s ->
                    s.copy(
                        loading = false,
                        video = info as? YtVideo,
                        playlist = info as? YtPlaylist,
                        selected = (info as? YtPlaylist)?.entries?.map { it.index }?.toSet().orEmpty(),
                        request = s.request.copy(url = url, playlist = info is YtPlaylist, mode = if (s.request.mode == YtMode.CUSTOM && info is YtPlaylist) YtMode.VIDEO else s.request.mode),
                    )
                }
                val thumb = (info as? YtVideo)?.thumbnail ?: (info as? YtPlaylist)?.thumbnail
                thumb?.let { u -> Thumbs.load(u)?.let { b -> _state.update { it.copy(thumb = b) } } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineMissingException) {
                _state.update { it.copy(loading = false, error = "Download yt-dlp first (below)") }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't read this link") }
            }
        }
    }

    fun edit(change: (YtRequest) -> YtRequest) = _state.update { it.copy(request = change(it.request)) }

    fun toggleEntry(index: Int) = _state.update { s -> s.copy(selected = if (index in s.selected) s.selected - index else s.selected + index) }
    fun selectAll(all: Boolean) = _state.update { s -> s.copy(selected = if (all) s.playlist?.entries?.map { it.index }?.toSet().orEmpty() else emptySet()) }
    fun pickVideo(id: String?) = _state.update { it.copy(pickVideo = if (it.pickVideo == id) null else id) }
    fun pickAudio(id: String?) = _state.update { it.copy(pickAudio = if (it.pickAudio == id) null else id) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun download() {
        val s = _state.value
        val video = s.video
        val playlist = s.playlist
        if (video == null && playlist == null) return
        var r = s.request.copy(url = (video?.webpageUrl ?: playlist?.webpageUrl).orEmpty().ifEmpty { s.request.url })
        if (playlist != null) {
            if (s.selected.isEmpty()) return _state.update { it.copy(message = "Pick at least one item") }
            r = r.copy(playlist = true, playlistItems = if (s.selected.size == playlist.entries.size) "" else ranges(s.selected.sorted()))
        } else {
            r = r.copy(playlist = false, playlistItems = "")
        }
        if (r.mode == YtMode.CUSTOM) {
            val f = listOfNotNull(s.pickVideo, s.pickAudio).joinToString("+")
            if (f.isEmpty()) return _state.update { it.copy(message = "Pick a video or an audio stream") }
            r = r.copy(formatId = f)
        }
        val summary = buildString {
            if (playlist != null) append("${s.selected.size} items · ")
            append(
                when (r.mode) {
                    YtMode.VIDEO -> "${if (r.quality > 0) "${r.quality}p" else "Best"} ${r.container.label}"
                    YtMode.AUDIO -> r.audio.label.substringBefore(" (") + if (!r.audio.lossless && r.audioKbps > 0) " ${r.audioKbps} kb/s" else ""
                    YtMode.CUSTOM -> "Formats ${r.formatId}"
                },
            )
        }
        queue.enqueue(r, video?.title ?: playlist!!.title, video?.uploader ?: playlist?.uploader, video?.thumbnail ?: playlist?.thumbnail, video?.durationSec ?: 0.0, summary)
        _state.update { it.copy(message = "Added to downloads") }
    }

    fun loadVersion() {
        viewModelScope.launch { _state.update { it.copy(version = runCatching { MediaEngine.ytdlpVersion(getApplication()) }.getOrNull()) } }
    }

    fun update() {
        if (_state.value.updating) return
        _state.update { it.copy(updating = true) }
        viewModelScope.launch {
            val msg = runCatching { MediaEngine.updateYtdlp(getApplication()) }.fold({ it }, { "Update failed: ${it.message}" })
            val v = runCatching { MediaEngine.ytdlpVersion(getApplication()) }.getOrNull()
            _state.update { it.copy(updating = false, version = v, message = msg) }
        }
    }

    fun retry(id: String) = queue.retry(id)
    fun cancel(id: String) = queue.cancel(id)
    fun remove(id: String) = queue.remove(id)
    fun clearFinished() = queue.clearFinished()
}

private fun durationText(sec: Double): String {
    val s = sec.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun countText(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fK".format(n / 1e3)
    else -> n.toString()
}

private fun qualityLabel(q: Int) = when (q) {
    0 -> "Best"
    4320 -> "8K"
    2160 -> "4K"
    1440 -> "1440p"
    else -> "${q}p"
}

@Composable
fun YtdlScreen(onBack: () -> Unit, initialText: String? = null) {
    val vm: YtdlViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val engine by rememberPackState(MediaEngine.ytdlp)
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val ready = engine is PackState.Ready
    var asked by rememberSaveable { mutableStateOf(false) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(initialText) { initialText?.let(vm::openInitial) }
    LaunchedEffect(ready) { if (ready) vm.loadVersion() }
    LaunchedEffect(s.message) {
        s.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    val active = history?.items.orEmpty().count { it.state == YtState.RUNNING || it.state == YtState.QUEUED }
    var menu by remember { mutableStateOf(false) }

    ToolScaffold(
        title = "YT Downloader",
        subtitle = when {
            active > 0 -> "$active downloading"
            s.video != null -> s.video!!.site.ifEmpty { "Ready" }
            else -> "YouTube and 1,800+ other sites"
        },
        accent = Accents.Downloader,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (ready) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (s.updating) "Updating yt-dlp…" else "Update yt-dlp") },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                            enabled = !s.updating,
                            onClick = { menu = false; vm.update() },
                        )
                        DropdownMenuItem(text = { Text("Version ${s.version ?: "…"}", style = MaterialTheme.typography.bodySmall) }, onClick = { menu = false }, enabled = false)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UrlBar(s.url, s.loading, vm::setUrl, onPaste = { vm.paste(context) }, onClear = vm::clear, onFetch = { vm.fetch() })
            if (!ready) {
                PackPrompt(MediaEngine.ytdlp, accent = Accents.Downloader, icon = Icons.Rounded.SmartDisplay)
                Text(
                    "Also fetches Python and FFmpeg if you don't have them (about 55 MB together). Downloads run entirely on your phone. Only save videos you have the right to keep.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            s.error?.let { ErrorCard(it) }
            if (s.loading) LoadingCard()
            s.video?.let { v ->
                VideoCard(v, s.thumb)
                if (Ytdl.hasPlaylist(s.url)) {
                    OutlinedButton(onClick = { vm.fetch(playlist = true) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null)
                        Spacer(Modifier.width(8.dp))
                        Text("This link is part of a playlist. Load the whole playlist")
                    }
                }
            }
            s.playlist?.let { p -> PlaylistCard(p, s.thumb, s.selected, vm::toggleEntry, vm::selectAll) }
            if (s.video != null || s.playlist != null) {
                OptionsPanel(s, vm)
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !asked && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                            asked = true
                            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        vm.download()
                    },
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (s.playlist != null) "Download ${s.selected.size} item${if (s.selected.size == 1) "" else "s"}" else "Download")
                }
            }
            DownloadsSection(history?.items.orEmpty(), live, vm)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun UrlBar(url: String, loading: Boolean, onChange: (String) -> Unit, onPaste: () -> Unit, onClear: () -> Unit, onFetch: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                url, onChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Paste a video, Shorts, music or playlist link") },
                leadingIcon = { Icon(Icons.Rounded.SmartDisplay, null, tint = Accents.Downloader.start) },
                trailingIcon = { if (url.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "Clear") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onFetch() }),
                shape = RoundedCornerShape(18.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.ContentPaste, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Paste")
                }
                Button(onClick = onFetch, enabled = !loading && url.isNotBlank(), modifier = Modifier.weight(1f)) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.Rounded.Tune, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (loading) "Reading…" else "Get options")
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LoadingCard() {
    Panel(Modifier.fillMaxWidth()) {
        Text("Reading the link…", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text("yt-dlp is looking up the available qualities. The first time can take a few seconds.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accents.Downloader.start)
    }
}

@Composable
private fun Thumb(bitmap: Bitmap?, modifier: Modifier, badge: String? = null) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Accents.Downloader.start.copy(alpha = 0.35f), Accents.Downloader.end.copy(alpha = 0.25f))))) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.SmartDisplay, null, Modifier.align(Alignment.Center).size(40.dp), tint = Color.White)
        if (badge != null) {
            Surface(color = Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                Text(badge, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun VideoCard(v: YtVideo, thumb: Bitmap?) {
    Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
        Thumb(thumb, Modifier.fillMaxWidth().aspectRatio(16f / 9f), badge = if (v.isLive) "LIVE" else if (v.durationSec > 0) durationText(v.durationSec) else null)
        Spacer(Modifier.height(10.dp))
        Text(v.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val date = v.uploadDate?.takeIf { it.length == 8 }?.let { "${it.substring(6)}/${it.substring(4, 6)}/${it.substring(0, 4)}" }
        Text(
            listOfNotNull(v.uploader, if (v.views > 0) "${countText(v.views)} views" else null, date).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (v.isLive) Text("Live streams are recorded from now until you cancel.", style = MaterialTheme.typography.bodySmall, color = Accents.Downloader.start)
    }
}

@Composable
private fun PlaylistCard(p: YtPlaylist, thumb: Bitmap?, selected: Set<Int>, onToggle: (Int) -> Unit, onAll: (Boolean) -> Unit) {
    var showAll by remember(p.id) { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth().animateContentSize(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(thumb, Modifier.width(120.dp).aspectRatio(16f / 9f), badge = "${p.entries.size}")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(p.uploader, "${p.entries.size} items").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${selected.size} selected", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onAll(true) }) { Text("All") }
            TextButton(onClick = { onAll(false) }) { Text("None") }
        }
        val shown = if (showAll) p.entries else p.entries.take(30)
        for (e in shown) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onToggle(e.index) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(e.index in selected, { onToggle(e.index) })
                Text("${e.index}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp))
                Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (e.duration > 0) Text(durationText(e.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (p.entries.size > 30) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show fewer" else "Show all ${p.entries.size}") }
    }
}

@Composable
private fun ModeTabs(mode: YtMode, allowCustom: Boolean, onMode: (YtMode) -> Unit) {
    val modes = listOfNotNull(
        Triple(YtMode.VIDEO, "Video", Icons.Rounded.Movie),
        Triple(YtMode.AUDIO, "Audio only", Icons.Rounded.AudioFile),
        if (allowCustom) Triple(YtMode.CUSTOM, "Pick streams", Icons.Rounded.Tune) else null,
    )
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(4.dp)) {
            for ((m, label, icon) in modes) {
                val selected = m == mode
                val bg by animateColorAsState(if (selected) Accents.Downloader.start else Color.Transparent, label = "modeBg")
                val fg by animateColorAsState(if (selected) Color.White else MaterialTheme.colorScheme.onSurface, label = "modeFg")
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg).clickable { onMode(m) }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun OptionsPanel(s: YtdlUiState, vm: YtdlViewModel) {
    val r = s.request
    val v = s.video
    val set: ((YtRequest) -> YtRequest) -> Unit = vm::edit
    SectionTitle("Format")
    ModeTabs(r.mode, allowCustom = v != null && v.formats.size > 1) { m -> set { it.copy(mode = m) } }
    Panel(Modifier.fillMaxWidth().animateContentSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (r.mode) {
                YtMode.VIDEO -> VideoOptions(r, v, set)
                YtMode.AUDIO -> AudioOptions(r, v, set)
                YtMode.CUSTOM -> if (v != null) StreamPicker(v, s.pickVideo, s.pickAudio, r.container, vm, set)
            }
        }
    }
    NameField(r, v, s.playlist, set)
    Advanced(r, v, set)
}

@Composable
private fun VideoOptions(r: YtRequest, v: YtVideo?, set: ((YtRequest) -> YtRequest) -> Unit) {
    val qualities = listOf(0) + (v?.qualities?.takeIf { it.isNotEmpty() } ?: listOf(2160, 1440, 1080, 720, 480, 360, 240, 144))
    Text("Quality", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (q in qualities) {
            val size = v?.estimate(q)?.takeIf { it > 0 }
            FilterChip(
                selected = r.quality == q,
                onClick = { set { it.copy(quality = q) } },
                label = { Text(qualityLabel(q) + (size?.let { " · ${formatBytes(it)}" } ?: "")) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accents.Downloader.start.copy(alpha = 0.2f)),
            )
        }
    }
    OptionChips("Container", YtContainer.entries, r.container, { it.label }) { c -> set { it.copy(container = c) } }
    if (r.container != YtContainer.WEBM) OptionChips("Priority", YtCodec.entries, r.codec, { it.label }) { c -> set { it.copy(codec = c) } }
    val hasHighFps = v == null || v.videoFormats.any { it.fps > 31 }
    if (hasHighFps) OptionSwitch("Prefer 60 fps", "Smoother motion when the video has it, slightly larger", r.highFps) { b -> set { it.copy(highFps = b) } }
    val hasHdr = v?.videoFormats?.any { (it.dynamicRange ?: "SDR") != "SDR" } == true
    if (hasHdr) OptionSwitch("HDR", "Only for HDR screens; looks washed out elsewhere", r.hdr) { b -> set { it.copy(hdr = b) } }
    if (v != null && r.quality > 0 && v.qualities.isNotEmpty() && v.qualities.none { it <= r.quality }) {
        Text("This video has nothing at or under ${qualityLabel(r.quality)}; the smallest quality will be used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AudioOptions(r: YtRequest, v: YtVideo?, set: ((YtRequest) -> YtRequest) -> Unit) {
    OptionChips("Format", YtAudio.entries, r.audio, { it.label }) { a -> set { it.copy(audio = a) } }
    if (!r.audio.lossless && r.audio != YtAudio.ORIGINAL) {
        OptionChips("Bitrate", listOf(0, 320, 256, 192, 160, 128, 96, 64), r.audioKbps, { if (it == 0) "Best (VBR)" else "$it kb/s" }) { k -> set { it.copy(audioKbps = k) } }
    }
    val best = v?.audioFormats?.maxByOrNull { if (it.abr > 0) it.abr else it.tbr }
    if (v != null && best != null) {
        Text(
            "Source: ${best.codecLabel} ${if (best.abr > 0) "${best.abr.toInt()} kb/s" else ""} · about ${formatBytes(best.sizeFor(v.durationSec))}" +
                if (r.audio.lossless) ". Lossless output only avoids further loss; it can't add quality." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun streamLabel(f: YtFormat, duration: Double): String = buildString {
    if (f.hasVideo) {
        append(if (f.quality > 0) "${f.quality}p" else "Video")
        if (f.fps > 31) append(f.fps.toInt())
        if ((f.dynamicRange ?: "SDR") != "SDR") append(" HDR")
    } else {
        append(if (f.abr > 0) "${f.abr.toInt()} kb/s" else "Audio")
    }
    append(" · ${f.codecLabel.ifEmpty { "?" }} · ${f.ext}")
    if (f.hasVideo && f.hasAudio) append(" · with sound")
    f.language?.takeIf { !f.hasVideo && it != "und" }?.let { append(" · $it") }
    f.sizeFor(duration).takeIf { it > 0 }?.let { append(" · ${formatBytes(it)}") }
}

@Composable
private fun StreamPicker(v: YtVideo, pickVideo: String?, pickAudio: String?, container: YtContainer, vm: YtdlViewModel, set: ((YtRequest) -> YtRequest) -> Unit) {
    var all by remember { mutableStateOf(false) }
    Text("Pick one video and/or one audio stream. Two picks are merged into one file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val videos = v.videoFormats.sortedWith(compareByDescending<YtFormat> { it.quality }.thenByDescending { it.fps }.thenByDescending { it.tbr })
    val audios = v.audioFormats.sortedByDescending { if (it.abr > 0) it.abr else it.tbr }
    Text("Video", style = MaterialTheme.typography.labelLarge)
    for (f in if (all) videos else videos.take(10)) StreamRow(streamLabel(f, v.durationSec), f.id == pickVideo) { vm.pickVideo(f.id) }
    if (videos.size > 10) TextButton(onClick = { all = !all }) { Text(if (all) "Fewer" else "All ${videos.size} video streams") }
    if (audios.isNotEmpty()) {
        Text("Audio", style = MaterialTheme.typography.labelLarge)
        for (f in audios) StreamRow(streamLabel(f, v.durationSec), f.id == pickAudio) { vm.pickAudio(f.id) }
    }
    if (pickVideo != null && pickAudio != null) OptionChips("Merge into", YtContainer.entries, container, { it.label }) { c -> set { it.copy(container = c) } }
    val total = listOfNotNull(videos.firstOrNull { it.id == pickVideo }, audios.firstOrNull { it.id == pickAudio }).sumOf { it.sizeFor(v.durationSec) }
    if (total > 0) Text("About ${formatBytes(total)}", style = MaterialTheme.typography.labelLarge, color = Accents.Downloader.start)
}

@Composable
private fun StreamRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Accents.Downloader.start.copy(alpha = 0.16f) else Color.Transparent, label = "streamBg")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.Schedule, null, tint = if (selected) Accents.Downloader.start else Color.Transparent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun NameField(r: YtRequest, v: YtVideo?, p: YtPlaylist?, set: ((YtRequest) -> YtRequest) -> Unit) {
    SectionTitle("File name")
    Panel(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            r.nameTemplate,
            { t -> set { it.copy(nameTemplate = t) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Name") },
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((token, label) in Ytdl.templateTokens.filter { p != null || it.first !in setOf("{index}", "{playlist}") }) {
                FilterChip(selected = token in r.nameTemplate, onClick = { set { it.copy(nameTemplate = if (token in it.nameTemplate) it.nameTemplate.replace(token, "").replace(Regex("\\s{2,}"), " ").trim() else (it.nameTemplate.trimEnd() + " " + token).trim()) } }, label = { Text(label) })
            }
        }
        val ext = when (r.mode) {
            YtMode.AUDIO -> r.audio.ext.ifEmpty { "m4a/opus" }
            else -> r.container.ext
        }
        Text(
            "Saved as “${Ytdl.previewName(r.nameTemplate, v, p, r.quality, r.mode == YtMode.AUDIO).ifEmpty { "Title" }}.$ext” in ${if (r.mode == YtMode.AUDIO) "Music" else "Movies"}/Vitmap",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TimeBox(label: String, value: Double, modifier: Modifier, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(if (value > 0) durationText(value) else "") }
    fun parse(t: String): Double? {
        if (t.isBlank()) return 0.0
        var total = 0.0
        for (part in t.trim().split(':')) total = total * 60 + (part.toDoubleOrNull() ?: return null)
        return total
    }
    OutlinedTextField(
        text, { t -> text = t; parse(t)?.let(onValue) },
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text("m:ss") },
        isError = parse(text) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun Advanced(r: YtRequest, v: YtVideo?, set: ((YtRequest) -> YtRequest) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, tint = Accents.Downloader.start)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("More options", style = MaterialTheme.typography.titleSmall)
                Text("Subtitles, cover art, SponsorBlock, trimming, speed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
    }
    AnimatedVisibility(open) {
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val audioOnly = r.mode == YtMode.AUDIO
                OptionChips("Subtitles", if (audioOnly) listOf(YtSubs.OFF, YtSubs.FILE) else YtSubs.entries, r.subs, {
                    when (it) {
                        YtSubs.OFF -> "Off"
                        YtSubs.EMBED -> "Inside the video"
                        YtSubs.FILE -> "Separate .srt file"
                    }
                }) { m -> set { it.copy(subs = m) } }
                if (r.subs != YtSubs.OFF) {
                    val langs = (v?.subtitles.orEmpty() + if (r.autoSubs) v?.autoCaptions.orEmpty() else emptyList()).distinct()
                    val options = (langs.ifEmpty { listOf("en", "es", "fr", "de", "hi", "ja", "pt", "ar") } + r.subLangs).distinct()
                    Text("Languages", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (l in options.take(40)) FilterChip(selected = l in r.subLangs, onClick = { set { it.copy(subLangs = if (l in it.subLangs) it.subLangs - l else it.subLangs + l) } }, label = { Text(l) })
                    }
                    if (v != null && v.subtitles.isEmpty() && !r.autoSubs) Text("This video has no uploaded subtitles; turn on auto-generated ones below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OptionSwitch("Auto-generated captions", "Include machine captions${v?.autoCaptions?.size?.let { " ($it languages)" } ?: ""}", r.autoSubs) { b -> set { it.copy(autoSubs = b) } }
                }
                val coverOk = if (audioOnly) r.audio.coverArt else r.container != YtContainer.WEBM
                if (coverOk) OptionSwitch("Cover art", "Embed the thumbnail as the file's artwork", r.embedThumbnail) { b -> set { it.copy(embedThumbnail = b) } }
                OptionSwitch("Tags", "Title, artist, date and description inside the file", r.embedMetadata) { b -> set { it.copy(embedMetadata = b) } }
                if (v == null || v.chapters.isNotEmpty()) {
                    OptionSwitch("Chapters", "Keep chapter markers${v?.chapters?.size?.let { " ($it)" } ?: ""}", r.embedChapters) { b -> set { it.copy(embedChapters = b) } }
                    OptionSwitch("Split by chapter", "Also save each chapter as its own file", r.splitChapters) { b -> set { it.copy(splitChapters = b) } }
                }
                OptionChips("SponsorBlock", YtSponsor.entries, r.sponsor, {
                    when (it) {
                        YtSponsor.OFF -> "Off"
                        YtSponsor.MARK -> "Mark as chapters"
                        YtSponsor.REMOVE -> "Cut them out"
                    }
                }) { m -> set { it.copy(sponsor = m) } }
                if (r.sponsor != YtSponsor.OFF) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((id, label) in Ytdl.sponsorCategories) FilterChip(selected = id in r.sponsorCategories, onClick = { set { it.copy(sponsorCategories = if (id in it.sponsorCategories) it.sponsorCategories - id else it.sponsorCategories + id) } }, label = { Text(label) })
                    }
                    Text("Community-submitted segments from sponsor.ajay.app; YouTube only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Only part of it (optional)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeBox("From", r.sectionStart, Modifier.weight(1f)) { t -> set { it.copy(sectionStart = t) } }
                    TimeBox("To", r.sectionEnd, Modifier.weight(1f)) { t -> set { it.copy(sectionEnd = t) } }
                }
                OptionChips("Speed limit", listOf(0, 256, 512, 1024, 2048, 5120), r.rateLimitKbps, { if (it == 0) "No limit" else if (it >= 1024) "${it / 1024} MB/s" else "$it KB/s" }) { k -> set { it.copy(rateLimitKbps = k) } }
                OptionChips("Parallel connections", listOf(1, 2, 4, 8), r.fragments, { "$it" }) { n -> set { it.copy(fragments = n) } }
                OutlinedTextField(
                    r.extraArgs,
                    { t -> set { it.copy(extraArgs = t) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Extra yt-dlp options") },
                    placeholder = { Text("--cookies /sdcard/Download/cookies.txt") },
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun rememberThumb(url: String?): Bitmap? {
    val b by produceState<Bitmap?>(null, url) { value = url?.let { Thumbs.load(it, 320) } }
    return b
}

@Composable
private fun DownloadsSection(items: List<YtItem>, live: Map<String, YtLive>, vm: YtdlViewModel) {
    if (items.isEmpty()) return
    SectionTitle("Downloads") {
        if (items.any { it.state != YtState.RUNNING && it.state != YtState.QUEUED }) TextButton(onClick = vm::clearFinished) { Text("Clear finished") }
    }
    for (item in items.take(50)) DownloadRow(item, live[item.id], vm)
}

@Composable
private fun DownloadRow(item: YtItem, l: YtLive?, vm: YtdlViewModel) {
    val context = LocalContext.current
    Panel(Modifier.fillMaxWidth().animateContentSize(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(rememberThumb(item.thumbnail), Modifier.width(96.dp).aspectRatio(16f / 9f), badge = if (item.durationSec > 0) durationText(item.durationSec) else null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val status = when (item.state) {
                    YtState.QUEUED -> "Waiting · ${item.summary}"
                    YtState.RUNNING -> listOfNotNull(l?.item?.takeIf { it.isNotEmpty() }, l?.stage ?: "Starting", l?.progress?.let { "${(it * 100).toInt()}%" }).joinToString(" · ")
                    YtState.DONE -> "${item.summary} · ${formatBytes(item.files.sumOf { it.size })}" + if (item.files.size > 1) " · ${item.files.size} files" else ""
                    YtState.FAILED -> item.error ?: "Failed"
                    YtState.CANCELLED -> "Cancelled"
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (item.state == YtState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (item.state == YtState.RUNNING && l != null && (l.speed.isNotEmpty() || l.eta.isNotEmpty())) {
                    Text(listOf(l.speed, l.eta).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Accents.Downloader.start)
                }
                if (item.state == YtState.DONE) Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.created)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (item.state == YtState.RUNNING) {
            Spacer(Modifier.height(8.dp))
            val p = l?.progress
            val animated by animateFloatAsState(p ?: 0f, label = "ytProgress")
            if (p != null) LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().height(6.dp), color = if (l.audio) Accents.Downloader.end else Accents.Downloader.start)
            else LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = Accents.Downloader.start)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (item.state) {
                YtState.QUEUED, YtState.RUNNING -> TextButton(onClick = { vm.cancel(item.id) }) { Text("Cancel") }
                YtState.DONE -> {
                    val main = item.files.filter { it.mime.startsWith("video/") || it.mime.startsWith("audio/") }.maxByOrNull { it.size } ?: item.files.firstOrNull()
                    if (main != null) {
                        TextButton(onClick = { FileSaver.open(context, Uri.parse(main.uri), main.mime) }) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open")
                        }
                    }
                    TextButton(onClick = {
                        val mime = item.files.map { it.mime }.distinct().singleOrNull() ?: "*/*"
                        FileSaver.shareFiles(context, item.files.map { Uri.parse(it.uri) }, mime)
                    }) {
                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Share")
                    }
                }
                YtState.FAILED, YtState.CANCELLED -> TextButton(onClick = { vm.retry(item.id) }) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry")
                }
            }
            Spacer(Modifier.weight(1f))
            if (item.state != YtState.RUNNING) IconButton(onClick = { vm.remove(item.id) }) { Icon(Icons.Rounded.Delete, "Remove from list", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
