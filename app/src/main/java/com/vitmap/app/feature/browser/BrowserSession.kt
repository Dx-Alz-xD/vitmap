package com.vitmap.app.feature.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.applyCanvas
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vitmap.app.VitmapApp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.net.URLEncoder

@Serializable
data class Bookmark(val url: String, val title: String, val time: Long)

@Serializable
data class HistoryEntry(val url: String, val title: String, val time: Long)

@Serializable
data class BrowserPrefs(
    val bookmarks: List<Bookmark> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val engine: String = SearchEngine.DUCKDUCKGO.name,
    val javascript: Boolean = true,
    val blockTrackers: Boolean = true,
    val darkPages: Boolean = true,
    val textZoom: Int = 100,
    val openTabs: List<String> = emptyList(),
)

enum class SearchEngine(val title: String, val query: String, val home: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=", "https://duckduckgo.com"),
    GOOGLE("Google", "https://www.google.com/search?q=", "https://www.google.com"),
    BING("Bing", "https://www.bing.com/search?q=", "https://www.bing.com"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=", "https://search.brave.com"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=", "https://www.startpage.com"),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=", "https://www.ecosia.org"),
}

data class ConsoleLine(val level: String, val message: String, val source: String, val time: Long = System.currentTimeMillis())

data class NetRequest(val method: String, val url: String, val type: String, val blocked: Boolean, val time: Long = System.currentTimeMillis())

data class InspectedElement(
    val selector: String,
    val tag: String,
    val id: String,
    val classes: String,
    val text: String,
    val html: String,
    val inline: String,
    val box: String,
    val styles: Map<String, String>,
    val attrs: Map<String, String>,
)

data class HitResult(val type: Int, val extra: String)

/** One browser tab. All fields are Compose state so the UI follows the WebView. */
class BrowserTab(val id: Long, val incognito: Boolean) {
    var webView: WebView? = null
    var url by mutableStateOf("")
    var title by mutableStateOf("New tab")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var favicon by mutableStateOf<Bitmap?>(null)
    var thumbnail by mutableStateOf<Bitmap?>(null)
    var desktop by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var inspected by mutableStateOf<InspectedElement?>(null)
    var blockedCount by mutableIntStateOf(0)
    var lastActive by mutableLongStateOf(System.currentTimeMillis())
    val console = mutableStateListOf<ConsoleLine>()
    val requests = mutableStateListOf<NetRequest>()

    val isStartPage: Boolean get() = url.isBlank()
    val secure: Boolean get() = url.startsWith("https://")
}

/** Process-wide browser state, so tabs survive leaving and re-opening AllBrowser. */
object BrowserSession {
    val tabs = mutableStateListOf<BrowserTab>()
    var currentId by mutableLongStateOf(-1L)
    var prefs by mutableStateOf(BrowserPrefs())
        private set
    var customView by mutableStateOf<View?>(null)
    var customViewCallback: WebChromeClient.CustomViewCallback? = null
    var fileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
    var permissionPrompt by mutableStateOf<PermissionRequest?>(null)
    var hitResult by mutableStateOf<HitResult?>(null)
    var findMatches by mutableStateOf(0 to 0)

    private var nextId = 1L
    private var loaded = false
    private val wrapper by lazy { MutableContextWrapper(VitmapApp.instance) }
    private val app get() = VitmapApp.instance
    private val store by lazy { app.store("browser", BrowserPrefs.serializer()) { BrowserPrefs() } }
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }

    const val INCOGNITO_PROFILE = "vitmap-incognito"

    val current: BrowserTab? get() = tabs.firstOrNull { it.id == currentId } ?: tabs.lastOrNull()

    val incognitoIsolated: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        prefs = store.load()
        if (tabs.isEmpty()) {
            val restore = prefs.openTabs.take(12)
            if (restore.isEmpty()) newTab("") else restore.forEach { newTab(it, select = false) }
            if (currentId == -1L) currentId = tabs.last().id
        }
    }

    /** WebViews are created against a wrapper so they can follow the current Activity. */
    fun attach(context: Context) {
        wrapper.baseContext = context
    }

    private fun savePrefs(t: (BrowserPrefs) -> BrowserPrefs) {
        prefs = t(prefs)
        val p = prefs
        app.appScope.launch { store.set(p) }
    }

    fun persistTabs() = savePrefs { p -> p.copy(openTabs = tabs.filter { !it.incognito && it.url.isNotBlank() }.map { it.url }) }

    fun newTab(url: String, incognito: Boolean = false, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(nextId++, incognito)
        tab.url = url
        tabs += tab
        if (select) currentId = tab.id
        persistTabs()
        return tab
    }

    fun select(tab: BrowserTab) {
        currentId = tab.id
        tab.lastActive = System.currentTimeMillis()
    }

    fun close(tab: BrowserTab) {
        tab.webView?.let { wv ->
            wv.stopLoading()
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        tab.webView = null
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        if (tab.incognito && tabs.none { it.incognito }) clearIncognito()
        if (tabs.isEmpty()) newTab("")
        if (currentId == tab.id) currentId = tabs[(index - 1).coerceIn(0, tabs.lastIndex)].id
        persistTabs()
    }

    fun closeAll(incognitoOnly: Boolean) {
        tabs.filter { !incognitoOnly || it.incognito }.forEach { close(it) }
    }

    private fun clearIncognito() {
        if (incognitoIsolated) {
            main.post { runCatching { ProfileStore.getInstance().deleteProfile(INCOGNITO_PROFILE) } }
        }
    }

    // ------------------------------------------------------------------ prefs

    val engine: SearchEngine get() = runCatching { SearchEngine.valueOf(prefs.engine) }.getOrDefault(SearchEngine.DUCKDUCKGO)

    fun setEngine(e: SearchEngine) = savePrefs { it.copy(engine = e.name) }

    fun setJavascript(on: Boolean) {
        savePrefs { it.copy(javascript = on) }
        tabs.forEach { t -> t.webView?.settings?.javaScriptEnabled = on }
    }

    fun setBlockTrackers(on: Boolean) = savePrefs { it.copy(blockTrackers = on) }

    fun setDarkPages(on: Boolean) {
        savePrefs { it.copy(darkPages = on) }
        tabs.forEach { t -> t.webView?.let { applyDark(it, on) } }
    }

    fun setTextZoom(z: Int) {
        savePrefs { it.copy(textZoom = z) }
        tabs.forEach { t -> t.webView?.settings?.textZoom = z }
    }

    fun toggleBookmark(url: String, title: String) = savePrefs { p ->
        if (p.bookmarks.any { it.url == url }) p.copy(bookmarks = p.bookmarks.filter { it.url != url })
        else p.copy(bookmarks = listOf(Bookmark(url, title.ifBlank { url }, System.currentTimeMillis())) + p.bookmarks)
    }

    fun removeBookmark(b: Bookmark) = savePrefs { p -> p.copy(bookmarks = p.bookmarks.filter { it.url != b.url }) }

    fun isBookmarked(url: String) = prefs.bookmarks.any { it.url == url }

    private fun addHistory(url: String, title: String) = savePrefs { p ->
        val rest = p.history.filter { it.url != url }
        p.copy(history = (listOf(HistoryEntry(url, title.ifBlank { url }, System.currentTimeMillis())) + rest).take(1000))
    }

    fun removeHistory(h: HistoryEntry) = savePrefs { p -> p.copy(history = p.history.filter { it.time != h.time }) }

    fun clearHistory() = savePrefs { it.copy(history = emptyList()) }

    fun clearBrowsingData() {
        clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        tabs.forEach { it.webView?.clearCache(true) }
        Toast.makeText(app, "Browsing data cleared", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ navigation

    /** Turns address bar input into a URL: a link, a bare domain, or a search. */
    fun resolve(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("file://") || t.startsWith("about:") || t.startsWith("data:") || t.startsWith("view-source:")) return t
        val looksLikeDomain = !t.contains(' ') && (Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(t) || t.startsWith("localhost"))
        if (looksLikeDomain) return "https://$t"
        return engine.query + URLEncoder.encode(t, "UTF-8")
    }

    fun load(tab: BrowserTab, input: String) {
        val url = resolve(input)
        tab.error = null
        if (url.isBlank()) {
            tab.url = ""
            tab.title = "New tab"
            return
        }
        tab.url = url
        tab.webView?.loadUrl(url)
        persistTabs()
    }

    fun setDesktop(tab: BrowserTab, on: Boolean) {
        tab.desktop = on
        val wv = tab.webView ?: return
        wv.settings.userAgentString = if (on) DESKTOP_UA else null
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        wv.reload()
    }

    // ------------------------------------------------------------------ WebView

    private fun applyDark(wv: WebView, on: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, on)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun webViewFor(tab: BrowserTab, darkTheme: Boolean): WebView {
        tab.webView?.let { return it }
        val wv = WebView(wrapper)
        if (tab.incognito && incognitoIsolated) {
            runCatching {
                ProfileStore.getInstance().getOrCreateProfile(INCOGNITO_PROFILE)
                WebViewCompat.setProfile(wv, INCOGNITO_PROFILE)
            }
        }
        wv.settings.apply {
            javaScriptEnabled = prefs.javascript
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = if (tab.incognito && !incognitoIsolated) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            textZoom = prefs.textZoom
            if (tab.desktop) userAgentString = DESKTOP_UA
        }
        applyDark(wv, darkTheme && prefs.darkPages)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !tab.incognito)
        wv.addJavascriptInterface(InspectorBridge(tab), "VitmapInspector")
        wv.webViewClient = Client(tab)
        wv.webChromeClient = Chrome(tab)
        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ -> download(url, userAgent, contentDisposition, mimetype) }
        wv.setFindListener { active, total, done -> if (done) findMatches = (if (total == 0) 0 else active + 1) to total }
        wv.setOnLongClickListener {
            val hit = wv.hitTestResult
            val extra = hit.extra
            if (extra != null && hit.type in setOf(WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, WebView.HitTestResult.IMAGE_TYPE)) {
                hitResult = HitResult(hit.type, extra)
                true
            } else false
        }
        tab.webView = wv
        if (tab.url.isNotBlank()) wv.loadUrl(tab.url)
        return wv
    }

    fun captureThumbnail(tab: BrowserTab) {
        val wv = tab.webView ?: return
        if (wv.width <= 0 || wv.height <= 0) return
        runCatching {
            val scale = 0.35f
            val bmp = Bitmap.createBitmap((wv.width * scale).toInt().coerceAtLeast(1), (wv.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            bmp.applyCanvas {
                scale(scale, scale)
                translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
                wv.draw(this)
            }
            tab.thumbnail = bmp
        }
    }

    private fun download(url: String, userAgent: String?, contentDisposition: String?, mimetype: String?) {
        runCatching {
            val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            app.getSystemService(DownloadManager::class.java).enqueue(req)
            Toast.makeText(app, "Downloading $name", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(app, "Download failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show() }
    }

    fun downloadUrl(url: String) = download(url, null, null, null)

    private class InspectorBridge(val tab: BrowserTab) {
        @JavascriptInterface
        fun onSelect(payload: String) {
            val el = parseElement(payload) ?: return
            Handler(Looper.getMainLooper()).post { tab.inspected = el }
        }
    }

    fun parseElement(payload: String): InspectedElement? = runCatching {
        val o = json.parseToJsonElement(payload) as kotlinx.serialization.json.JsonObject
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        fun map(k: String): Map<String, String> = (o[k] as? kotlinx.serialization.json.JsonObject)?.mapValues { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" } ?: emptyMap()
        val box = o["box"] as? kotlinx.serialization.json.JsonObject
        val boxText = box?.let { b -> "${(b["w"] as? kotlinx.serialization.json.JsonPrimitive)?.content} × ${(b["h"] as? kotlinx.serialization.json.JsonPrimitive)?.content} at (${(b["x"] as? kotlinx.serialization.json.JsonPrimitive)?.content}, ${(b["y"] as? kotlinx.serialization.json.JsonPrimitive)?.content})" } ?: ""
        InspectedElement(str("selector"), str("tag"), str("id"), str("classes"), str("text"), str("html"), str("inline"), boxText, map("styles"), map("attrs"))
    }.getOrNull()

    private class Client(val tab: BrowserTab) : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            tab.url = url ?: tab.url
            tab.loading = true
            tab.error = null
            tab.favicon = favicon
            tab.inspected = null
            tab.requests.clear()
            tab.blockedCount = 0
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (view == null || url == null) return
            tab.loading = false
            tab.url = url
            tab.title = view.title?.takeIf { it.isNotBlank() } ?: url
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            captureThumbnail(tab)
            if (!tab.incognito && url.startsWith("http")) addHistory(url, tab.title)
            persistTabs()
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            if (view == null || url == null) return
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
            tab.url = url
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            if (view == null || request == null) return false
            val uri = request.url
            val scheme = uri.scheme ?: return false
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") return false
            // mailto:, tel:, intent:, market: … hand off to other apps.
            runCatching {
                val intent = if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                view.context.startActivity(intent)
            }.onFailure {
                if (scheme == "intent") {
                    runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }.getOrNull()?.let { view.loadUrl(it) }
                }
            }
            return true
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request == null) return null
            val url = request.url.toString()
            val host = request.url.host ?: ""
            val blocked = prefs.blockTrackers && !request.isForMainFrame && Trackers.isBlocked(host)
            val entry = NetRequest(request.method ?: "GET", url, guessType(url, request.requestHeaders?.get("Accept")), blocked)
            main.post {
                if (tab.requests.size > 600) tab.requests.removeAt(0)
                tab.requests += entry
                if (blocked) tab.blockedCount++
            }
            return if (blocked) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                tab.error = error?.description?.toString() ?: "Couldn't load the page"
                tab.loading = false
            }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request == null || errorResponse == null) return
            if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                tab.console += ConsoleLine("error", "HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase ?: ""} for ${request.url}", "network")
            }
        }
    }

    private class Chrome(val tab: BrowserTab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            tab.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (!title.isNullOrBlank()) tab.title = title
        }

        override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
            tab.favicon = icon
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (consoleMessage == null) return false
            if (tab.console.size > 500) tab.console.removeAt(0)
            tab.console += ConsoleLine(
                consoleMessage.messageLevel().name.lowercase(),
                consoleMessage.message(),
                "${consoleMessage.sourceId().substringAfterLast('/')}:${consoleMessage.lineNumber()}",
            )
            return true
        }

        override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
            if (filePathCallback == null || fileChooserParams == null) return false
            val chooser = fileChooser ?: return false
            return chooser(filePathCallback, fileChooserParams)
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            permissionPrompt = request
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            customView = view
            customViewCallback = callback
        }

        override fun onHideCustomView() {
            customView = null
            customViewCallback = null
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (resultMsg == null) return false
            // target=_blank and window.open(): open a new tab and hand its WebView to the caller.
            val newTab = newTab("", incognito = tab.incognito)
            val child = webViewFor(newTab, darkTheme = false)
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            tabs.firstOrNull { it.webView === window }?.let { close(it) }
        }
    }

    private fun guessType(url: String, accept: String?): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> "script"
            path.endsWith(".css") -> "stylesheet"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") || path.endsWith(".ico") || path.endsWith(".avif") -> "image"
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf") -> "font"
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".mp3") -> "media"
            path.endsWith(".json") || accept?.contains("json") == true -> "fetch"
            accept?.contains("text/html") == true -> "document"
            else -> "other"
        }
    }

    const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
}

/** A compact host blocklist for common ad and tracking networks. */
object Trackers {
    private val hosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
        "googletagservices.com", "adservice.google.com", "adnxs.com", "adsrvr.org", "advertising.com", "amazon-adsystem.com",
        "scorecardresearch.com", "quantserve.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "rubiconproject.com",
        "pubmatic.com", "openx.net", "casalemedia.com", "moatads.com", "hotjar.com", "mixpanel.com", "segment.io", "segment.com",
        "facebook.net", "connect.facebook.net", "ads-twitter.com", "analytics.twitter.com", "bat.bing.com", "clarity.ms",
        "yieldmo.com", "sharethrough.com", "media.net", "zedo.com", "smartadserver.com", "teads.tv", "adform.net", "bidswitch.net",
        "chartbeat.com", "newrelic.com", "nr-data.net", "branch.io", "appsflyer.com", "adjust.com", "onesignal.com", "mookie1.com",
        "serving-sys.com", "2mdn.net", "imrworldwide.com", "krxd.net", "exelator.com", "bluekai.com", "demdex.net", "everesttech.net",
        "tapad.com", "agkn.com", "rlcdn.com", "adsafeprotected.com", "doubleverify.com", "yahoo.com.ads", "ads.yahoo.com",
        "adtech.de", "contextweb.com", "lijit.com", "33across.com", "indexww.com", "gumgum.com", "sonobi.com", "triplelift.com",
    )

    fun isBlocked(host: String): Boolean {
        var h = host.lowercase()
        while (true) {
            if (h in hosts) return true
            val i = h.indexOf('.')
            if (i < 0 || i == h.lastIndexOf('.')) return false
            h = h.substring(i + 1)
        }
    }
}
