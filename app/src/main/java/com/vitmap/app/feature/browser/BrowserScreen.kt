package com.vitmap.app.feature.browser

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vitmap.app.ui.components.copyToClipboard
import com.vitmap.app.ui.components.shareText
import com.vitmap.app.ui.theme.Accents
import com.vitmap.app.ui.theme.LocalDarkTheme

@Composable
fun BrowserScreen(onBack: () -> Unit, initialUrl: String? = null) {
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    val session = BrowserSession
    var ready by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showInspector by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf<Int?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    var pendingCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingCallback = null
    }

    LaunchedEffect(Unit) {
        session.attach(context)
        session.ensureLoaded()
        if (!initialUrl.isNullOrBlank()) {
            val cur = session.current
            if (cur != null && cur.isStartPage) session.load(cur, initialUrl) else session.newTab(session.resolve(initialUrl))
        }
        ready = true
    }
    DisposableEffect(Unit) {
        session.fileChooser = { cb, params ->
            pendingCallback?.onReceiveValue(null)
            pendingCallback = cb
            runCatching { chooser.launch(params.createIntent()) }.isSuccess.also { if (!it) pendingCallback = null }
        }
        onDispose {
            session.fileChooser = null
            session.current?.let { session.captureThumbnail(it) }
            session.tabs.forEach { t -> (t.webView?.parent as? ViewGroup)?.removeView(t.webView) }
        }
    }

    val tab = session.current
    BackHandler(enabled = true) {
        when {
            session.customView != null -> session.customViewCallback?.onCustomViewHidden()
            showTabs -> showTabs = false
            showInspector -> showInspector = false
            showFind -> {
                showFind = false
                tab?.webView?.clearMatches()
            }
            tab != null && tab.canGoBack && !tab.isStartPage -> tab.webView?.goBack()
            tab != null && !tab.isStartPage -> session.load(tab, "")
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (tab != null) {
                AddressBar(tab, onSubmit = { session.load(tab, it) })
                Box(Modifier.fillMaxWidth().height(3.dp)) {
                    val progress by animateFloatAsState(tab.progress / 100f, spring(stiffness = 120f), label = "progress")
                    AnimatedVisibility(tab.loading && !tab.isStartPage, enter = fadeIn(), exit = fadeOut(tween(400))) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(), color = Accents.Browser.start, trackColor = Color.Transparent)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (ready && tab != null) {
                    AnimatedContent(tab.id to tab.isStartPage, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "page") { (_, start) ->
                        if (start) {
                            StartPage(onOpen = { session.load(tab, it) })
                        } else {
                            WebViewHost(tab, dark)
                        }
                    }
                    tab.error?.let { err -> ErrorPage(err, tab.url) { tab.error = null; tab.webView?.reload() } }
                }
            }
            AnimatedVisibility(showFind && tab != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                if (tab != null) FindBar(tab) { showFind = false; tab.webView?.clearMatches() }
            }
            AnimatedVisibility(showInspector && tab != null && !tab.isStartPage, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                if (tab != null) InspectorPanel(tab, onClose = { showInspector = false }, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.55f))
            }
            if (tab != null) {
                BottomBar(
                    tab = tab,
                    tabCount = session.tabs.size,
                    onBack = { if (tab.canGoBack) tab.webView?.goBack() else if (!tab.isStartPage) session.load(tab, "") else onBack() },
                    onForward = { tab.webView?.goForward() },
                    onHome = { session.load(tab, "") },
                    onTabs = { session.captureThumbnail(tab); showTabs = true },
                    onMenu = { showMenu = true },
                )
            }
        }

        AnimatedVisibility(showTabs, enter = fadeIn() + scaleIn(initialScale = 1.08f), exit = fadeOut() + scaleOut(targetScale = 1.08f)) {
            TabSwitcher(
                onSelect = { session.select(it); showTabs = false },
                onClose = { session.close(it) },
                onNew = { incognito -> session.newTab("", incognito); showTabs = false },
                onDismiss = { showTabs = false },
            )
        }

        session.customView?.let { view ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { (view.parent as? ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize())
            }
        }
    }

    if (showMenu && tab != null) {
        BrowserMenu(
            tab = tab,
            onDismiss = { showMenu = false },
            onNewTab = { session.newTab("", false) },
            onIncognito = { session.newTab("", true) },
            onBookmarks = { library = 0 },
            onHistory = { library = 1 },
            onFind = { showFind = true },
            onInspect = { showInspector = true },
            onSettings = { showSettings = true },
            onShare = { shareText(context, tab.url, tab.title) },
            onOpenExternal = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tab.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        )
    }
    library?.let { which ->
        LibrarySheet(which, onDismiss = { library = null }) { url ->
            library = null
            val t = session.current ?: return@LibrarySheet
            session.load(t, url)
        }
    }
    if (showSettings) BrowserSettingsSheet { showSettings = false }

    session.hitResult?.let { hit ->
        LinkMenuDialog(
            hit = hit,
            onDismiss = { session.hitResult = null },
            onOpenNewTab = { incognito -> session.newTab(hit.extra, incognito = incognito); session.hitResult = null },
            onCopy = { copyToClipboard(context, hit.extra, "link"); session.hitResult = null },
            onShare = { shareText(context, hit.extra); session.hitResult = null },
            onDownload = { session.downloadUrl(hit.extra); session.hitResult = null },
        )
    }
    session.permissionPrompt?.let { req -> PermissionDialog(req) { session.permissionPrompt = null } }
}

@Composable
private fun WebViewHost(tab: BrowserTab, dark: Boolean) {
    key(tab.id) {
        AndroidView(
            factory = {
                val wv: WebView = BrowserSession.webViewFor(tab, dark)
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun AddressBar(tab: BrowserTab, onSubmit: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var text by remember(tab.id) { mutableStateOf(TextFieldValue(tab.url)) }
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(tab.url, focused) { if (!focused) text = TextFieldValue(tab.url) }
    val display = remember(tab.url) {
        runCatching { Uri.parse(tab.url).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() } ?: tab.url
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val container = if (tab.incognito) Color(0xFF2B2440) else MaterialTheme.colorScheme.surfaceContainerHigh
        TextField(
            value = if (focused) text else TextFieldValue(if (tab.isStartPage) "" else display),
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(if (tab.incognito) "Search privately" else "Search or type a URL", color = if (tab.incognito) Color.White.copy(alpha = 0.6f) else Color.Unspecified) },
            leadingIcon = {
                val icon = when {
                    tab.incognito -> Icons.Rounded.VisibilityOff
                    focused || tab.isStartPage -> Icons.Rounded.Search
                    tab.secure -> Icons.Rounded.Lock
                    else -> Icons.Rounded.Public
                }
                Icon(icon, null, tint = if (tab.incognito) Color.White else if (tab.secure && !focused) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                AnimatedContent(focused, label = "trail") { f ->
                    if (f) {
                        IconButton(onClick = { text = TextFieldValue("") }) { Icon(Icons.Rounded.Close, "Clear") }
                    } else if (!tab.isStartPage) {
                        IconButton(onClick = { if (tab.loading) tab.webView?.stopLoading() else tab.webView?.reload() }) {
                            Icon(if (tab.loading) Icons.Rounded.Close else Icons.Rounded.Refresh, if (tab.loading) "Stop" else "Reload")
                        }
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = {
                onSubmit(text.text)
                focus.clearFocus()
            }),
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = container, unfocusedContainerColor = container,
                focusedTextColor = if (tab.incognito) Color.White else MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = if (tab.incognito) Color.White else MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused && !focused) text = TextFieldValue(tab.url, TextRange(0, tab.url.length))
                    focused = it.isFocused
                },
        )
        if (tab.blockedCount > 0 && !focused) {
            Spacer(Modifier.width(6.dp))
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Shield, "Trackers blocked", tint = Accents.Browser.start)
                Text("${tab.blockedCount}", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}

@Composable
private fun BottomBar(tab: BrowserTab, tabCount: Int, onBack: () -> Unit, onForward: () -> Unit, onHome: () -> Unit, onTabs: () -> Unit, onMenu: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            IconButton(onClick = onForward, enabled = tab.canGoForward) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward") }
            IconButton(onClick = onHome) { Icon(Icons.Rounded.Home, "Start page") }
            IconButton(onClick = onTabs) {
                Box(
                    Modifier.size(24.dp).border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(tabCount, transitionSpec = { (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut()) }, label = "count") { n ->
                        Text(if (n > 99) ":D" else "$n", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, "Menu") }
        }
    }
}

@Composable
private fun FindBar(tab: BrowserTab, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val (cur, total) = BrowserSession.findMatches
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = q,
                onValueChange = { q = it; tab.webView?.findAllAsync(it) },
                singleLine = true,
                placeholder = { Text("Find in page") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            Text(if (q.isEmpty()) "" else "$cur/$total", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { tab.webView?.findNext(false) }) { Icon(Icons.Rounded.KeyboardArrowUp, "Previous") }
            IconButton(onClick = { tab.webView?.findNext(true) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Next") }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") }
        }
    }
}

@Composable
private fun ErrorPage(message: String, url: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text("This page couldn't load", style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("AllBrowser is the one tool that needs an internet connection.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun LinkMenuDialog(hit: HitResult, onDismiss: () -> Unit, onOpenNewTab: (Boolean) -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onDownload: () -> Unit) {
    val isImage = hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isImage) "Image" else "Link", maxLines = 1) },
        text = {
            Column {
                Text(hit.extra, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onOpenNewTab(false) }) { Text("Open in new tab") }
                TextButton(onClick = { onOpenNewTab(true) }) { Text("Open in incognito tab") }
                TextButton(onClick = onCopy) { Text(if (isImage) "Copy image address" else "Copy link") }
                TextButton(onClick = onShare) { Text("Share") }
                if (isImage) TextButton(onClick = onDownload) { Text("Download image") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PermissionDialog(req: PermissionRequest, onDone: () -> Unit) {
    val what = req.resources.joinToString { r ->
        when (r) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "protected media"
            else -> "device features"
        }
    }
    AlertDialog(
        onDismissRequest = { req.deny(); onDone() },
        title = { Text("Allow ${req.origin.host ?: "this site"}?") },
        text = { Text("The page wants to use your $what.") },
        confirmButton = { TextButton(onClick = { req.grant(req.resources); onDone() }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { req.deny(); onDone() }) { Text("Block") } },
    )
}

@Composable
fun Favicon(tab: BrowserTab, modifier: Modifier = Modifier) {
    val fav = tab.favicon
    if (fav != null) {
        Image(fav.asImageBitmap(), null, modifier.size(18.dp).clip(RoundedCornerShape(4.dp)))
    } else {
        Box(modifier.size(18.dp).clip(CircleShape).background(Accents.Browser.brush), contentAlignment = Alignment.Center) {
            Text(tab.title.firstOrNull()?.uppercase() ?: "•", style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}
