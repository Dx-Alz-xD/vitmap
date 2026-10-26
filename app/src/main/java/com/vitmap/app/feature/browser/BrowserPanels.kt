package com.vitmap.app.feature.browser

import android.content.Intent
import android.webkit.WebView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vitmap.app.ui.components.AuroraBackground
import com.vitmap.app.ui.components.SectionTitle
import com.vitmap.app.ui.components.bouncyClickable
import com.vitmap.app.ui.theme.Accents
import com.vitmap.core.ytdl.Ytdl
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private data class Shortcut(val title: String, val url: String, val color: Color)

private val SHORTCUTS = listOf(
    Shortcut("Wikipedia", "https://www.wikipedia.org", Color(0xFF636466)),
    Shortcut("YouTube", "https://m.youtube.com", Color(0xFFFF0033)),
    Shortcut("GitHub", "https://github.com", Color(0xFF24292F)),
    Shortcut("Reddit", "https://www.reddit.com", Color(0xFFFF4500)),
    Shortcut("Hacker News", "https://news.ycombinator.com", Color(0xFFFF6600)),
    Shortcut("Maps", "https://www.openstreetmap.org", Color(0xFF7EBC6F)),
    Shortcut("Weather", "https://wttr.in", Color(0xFF38BDF8)),
    Shortcut("Lichess", "https://lichess.org", Color(0xFF3D3D3D)),
)

@Composable
fun StartPage(onOpen: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val prefs = BrowserSession.prefs
    val engine = BrowserSession.engine
    Box(Modifier.fillMaxSize()) {
        AuroraBackground(listOf(Accents.Browser.start, Accents.Browser.end, Accents.Translator.end), Modifier.fillMaxWidth().height(320.dp), intensity = 0.35f)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(36.dp))
            Text("AllBrowser", style = MaterialTheme.typography.displaySmall)
            Text("Private by default · ${engine.title}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            TextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                placeholder = { Text("Search the web") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) onOpen(q) }),
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            val tiles = prefs.bookmarks.take(8).map { Shortcut(it.title, it.url, Accents.Browser.end) }.ifEmpty { SHORTCUTS }
            for (row in tiles.chunked(4)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (s in row) {
                        Column(
                            Modifier.width(76.dp).bouncyClickable(shape = RoundedCornerShape(16.dp)) { onOpen(s.url) }.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(s.color), contentAlignment = Alignment.Center) {
                                Text(s.title.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = Color.White)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(s.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (prefs.history.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SectionTitle("Recently visited")
                for (h in prefs.history.take(6)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(h.url) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(h.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TabSwitcher(onSelect: (BrowserTab) -> Unit, onClose: (BrowserTab) -> Unit, onNew: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val tabs = BrowserSession.tabs
    var incognitoView by remember { mutableStateOf(BrowserSession.current?.incognito == true) }
    val shown = tabs.filter { it.incognito == incognitoView }
    Surface(Modifier.fillMaxSize(), color = if (incognitoView) Color(0xFF16121F) else MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close", tint = if (incognitoView) Color.White else MaterialTheme.colorScheme.onSurface) }
                FilterChip(selected = !incognitoView, onClick = { incognitoView = false }, label = { Text("Tabs ${tabs.count { !it.incognito }}") }, leadingIcon = { Icon(Icons.Rounded.Tab, null, Modifier.size(16.dp)) })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = incognitoView, onClick = { incognitoView = true }, label = { Text("Incognito ${tabs.count { it.incognito }}") }, leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(16.dp)) })
                Spacer(Modifier.weight(1f))
                if (shown.isNotEmpty()) TextButton(onClick = { BrowserSession.closeAll(incognitoOnly = incognitoView) }) { Text("Close all") }
            }
            if (incognitoView && !BrowserSession.incognitoIsolated) {
                Text(
                    "Incognito tabs don't save history or cache. This WebView version can't fully isolate cookies.",
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.id }) { t -> TabCard(t, selected = t.id == BrowserSession.currentId, modifier = Modifier.animateItem(), onClick = { onSelect(t) }, onClose = { onClose(t) }) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(72.dp))
                }
            }
            Button(onClick = { onNew(incognitoView) }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(6.dp))
                Text(if (incognitoView) "New incognito tab" else "New tab")
            }
        }
    }
}

@Composable
private fun TabCard(tab: BrowserTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit, onClose: () -> Unit) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Column(
        modifier
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - (abs(offset.value) / 600f).coerceIn(0f, 0.9f)
                rotationZ = offset.value / 60f
            }
            .pointerInput(tab.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(offset.value) > size.width * 0.35f) {
                                offset.animateTo(if (offset.value > 0) size.width * 1.5f else -size.width * 1.5f, spring(stiffness = 600f))
                                onClose()
                            } else offset.animateTo(0f, spring(dampingRatio = 0.6f))
                        }
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + amount) }
                    },
                )
            }
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Accents.Browser.start.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            Favicon(tab)
            Spacer(Modifier.width(6.dp))
            Text(tab.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "Close tab", Modifier.size(16.dp)) }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(14.dp)).background(Color.White)) {
            val thumb = tab.thumbnail
            if (thumb != null && !tab.isStartPage) {
                Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            } else {
                Box(Modifier.fillMaxSize().background(Accents.Browser.brush), contentAlignment = Alignment.Center) {
                    Icon(if (tab.incognito) Icons.Rounded.VisibilityOff else Icons.Rounded.Search, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}

private data class MenuAction(val icon: ImageVector, val label: String, val onClick: () -> Unit, val active: Boolean = false)

@Composable
fun BrowserMenu(
    tab: BrowserTab,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onIncognito: () -> Unit,
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
    onFind: () -> Unit,
    onInspect: () -> Unit,
    onSettings: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onDownloadVideo: (() -> Unit)? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val bookmarked = BrowserSession.isBookmarked(tab.url)
    fun act(f: () -> Unit): () -> Unit = { onDismiss(); f() }
    val page = !tab.isStartPage
    val actions = buildList {
        add(MenuAction(Icons.Rounded.Add, "New tab", act(onNewTab)))
        add(MenuAction(Icons.Rounded.VisibilityOff, "Incognito", act(onIncognito)))
        add(MenuAction(Icons.Rounded.Bookmarks, "Bookmarks", act(onBookmarks)))
        add(MenuAction(Icons.Rounded.History, "History", act(onHistory)))
        add(MenuAction(Icons.Rounded.Download, "Downloads", act { runCatching { context.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }))
        if (page && onDownloadVideo != null && Ytdl.isMediaLink(tab.url)) add(MenuAction(Icons.Rounded.SmartDisplay, "Download video", act(onDownloadVideo)))
        if (page) {
            add(MenuAction(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (bookmarked) "Bookmarked" else "Bookmark", act { BrowserSession.toggleBookmark(tab.url, tab.title) }, bookmarked))
            add(MenuAction(Icons.Rounded.FindInPage, "Find in page", act(onFind)))
            add(MenuAction(Icons.Rounded.DesktopWindows, "Desktop site", act { BrowserSession.setDesktop(tab, !tab.desktop) }, tab.desktop))
            add(MenuAction(Icons.Rounded.Code, "Inspect", act(onInspect)))
            add(MenuAction(Icons.Rounded.Share, "Share", act(onShare)))
            add(MenuAction(Icons.Rounded.OpenInBrowser, "Open in app", act(onOpenExternal)))
        }
        add(MenuAction(Icons.Rounded.Settings, "Settings", act(onSettings)))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            if (page) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Favicon(tab)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tab.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tab.url, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            for (row in actions.chunked(4)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in row) {
                        Column(
                            Modifier
                                .weight(1f)
                                .bouncyClickable(shape = RoundedCornerShape(18.dp), onClick = a.onClick)
                                .background(if (a.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(a.icon, null)
                            Spacer(Modifier.height(6.dp))
                            Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun LibrarySheet(which: Int, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var tab by remember { mutableStateOf(which) }
    var q by remember { mutableStateOf("") }
    val prefs = BrowserSession.prefs
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Bookmarks ${prefs.bookmarks.size}") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("History ${prefs.history.size}") })
                Spacer(Modifier.weight(1f))
                if (tab == 1 && prefs.history.isNotEmpty()) TextButton(onClick = { BrowserSession.clearHistory() }) { Text("Clear") }
            }
            OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search") }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (tab == 0) {
                    val list = prefs.bookmarks.filter { it.title.contains(q, true) || it.url.contains(q, true) }
                    if (list.isEmpty()) item { Text("No bookmarks yet — tap Bookmark in the menu on any page.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
                    items(list, key = { it.url }) { b ->
                        LibraryRow(b.title, b.url, Icons.Rounded.Bookmark, onClick = { onOpen(b.url) }, onDelete = { BrowserSession.removeBookmark(b) }, modifier = Modifier.animateItem())
                    }
                } else {
                    val list = prefs.history.filter { it.title.contains(q, true) || it.url.contains(q, true) }.take(300)
                    items(list, key = { it.time }) { h ->
                        LibraryRow(h.title, "${fmt.format(Date(h.time))} · ${h.url}", Icons.Rounded.History, onClick = { onOpen(h.url) }, onDelete = { BrowserSession.removeHistory(h) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Remove", Modifier.size(18.dp)) }
    }
}

@Composable
fun BrowserSettingsSheet(onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val prefs = BrowserSession.prefs
    var zoom by remember { mutableFloatStateOf(prefs.textZoom.toFloat()) }
    var debugging by remember { mutableStateOf(false) }
    LaunchedEffect(debugging) { WebView.setWebContentsDebuggingEnabled(debugging) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Browser settings", style = MaterialTheme.typography.headlineSmall)
            Text("Search engine", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in SearchEngine.entries) FilterChip(selected = BrowserSession.engine == e, onClick = { BrowserSession.setEngine(e) }, label = { Text(e.title) })
            }
            SettingSwitch("Block trackers & ads", "Stops common tracking and ad networks from loading", prefs.blockTrackers) { BrowserSession.setBlockTrackers(it) }
            SettingSwitch("JavaScript", "Needed by most modern sites", prefs.javascript) { BrowserSession.setJavascript(it) }
            SettingSwitch("Dark web pages", "Darken pages automatically in dark theme", prefs.darkPages) { BrowserSession.setDarkPages(it) }
            SettingSwitch("Remote debugging", "Inspect pages from a computer via chrome://inspect", debugging) { debugging = it }
            Text("Text size ${zoom.toInt()}%", style = MaterialTheme.typography.titleSmall)
            Slider(zoom, { zoom = it }, onValueChangeFinished = { BrowserSession.setTextZoom(zoom.toInt()) }, valueRange = 50f..200f, steps = 14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { BrowserSession.clearHistory() }, modifier = Modifier.weight(1f)) { Text("Clear history") }
                FilledTonalButton(onClick = { BrowserSession.clearBrowsingData() }, modifier = Modifier.weight(1f)) { Text("Clear all data") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
