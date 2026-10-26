package com.vitmap.app.feature.code

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * A hidden WebView that runs JavaScript-hosted language runtimes. Runtime files are served from
 * their downloaded pack folders through [WebViewAssetLoader], so nothing loads from the network.
 */
class WebSandbox(private val context: Context) {
    var view by mutableStateOf<WebView?>(null)
        private set
    @Volatile private var ready = CompletableDeferred<Unit>()
    @Volatile private var sink: RunSink? = null
    @Volatile private var done: CompletableDeferred<Int>? = null
    private val input = ArrayDeque<String>()

    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/rt/") { path -> serveRuntime(path) }
        .build()

    private fun serveRuntime(path: String): WebResourceResponse? {
        val packId = path.substringBefore('/')
        val runtime = WebRuntimes.all.firstOrNull { it.pack?.id == packId } ?: return null
        val dir = runtime.dir(context)?.canonicalFile ?: return null
        val file = File(dir, path.substringAfter('/')).canonicalFile
        if (!file.path.startsWith(dir.path + File.separator) || !file.isFile) return null
        val mime = when (file.extension) {
            "js" -> "text/javascript"
            "wasm" -> "application/wasm"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
        return WebResourceResponse(mime, null, file.inputStream())
    }

    inner class Bridge {
        @JavascriptInterface
        fun write(kind: String, text: String) {
            val s = sink ?: return
            when (kind) {
                "err" -> s.err(text)
                "info" -> s.info(text)
                else -> s.out(text)
            }
        }

        @JavascriptInterface
        fun readLine(): String? = synchronized(input) { input.removeFirstOrNull() }

        @JavascriptInterface
        fun done(code: Int) {
            done?.complete(code)
        }

        @JavascriptInterface
        fun ready() {
            ready.complete(Unit)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        addJavascriptInterface(Bridge(), "VitmapHost")
        webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Expected after we terminate a runaway script; build a fresh WebView.
                recreate()
                return true
            }
        }
        ready = CompletableDeferred()
        loadUrl("https://appassets.androidplatform.net/assets/code/web/runner.html")
    }

    private fun recreate() {
        val old = view
        view = create()
        (old?.parent as? android.view.ViewGroup)?.removeView(old)
        old?.destroy()
    }

    /** Must be called on the main thread before the first run. */
    fun start() {
        if (view == null) view = create()
    }

    private fun quote(s: String) = JsonPrimitive(s).toString()

    suspend fun run(kind: String, code: String, stdin: String, sink: RunSink, timeoutMs: Long): Int {
        withContext(Dispatchers.Main) { start() }
        withTimeout(30_000) { ready.await() }
        synchronized(input) {
            input.clear()
            input.addAll(stdin.split('\n').let { if (it.lastOrNull().isNullOrEmpty()) it.dropLast(1) else it })
        }
        val finished = CompletableDeferred<Int>()
        this.sink = sink
        done = finished
        try {
            withContext(Dispatchers.Main) { view?.evaluateJavascript("vitmapRun(${quote(kind)}, ${quote(code)}, ${quote(stdin)})", null) }
            return withTimeout(timeoutMs) { finished.await() }
        } catch (e: TimeoutCancellationException) {
            sink.err("\nStopped after ${timeoutMs / 1000} s.\n")
            withContext(NonCancellable + Dispatchers.Main) { kill() }
            return 124
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.Main) { kill() }
            throw e
        } finally {
            this.sink = null
            done = null
        }
    }

    /** Stops whatever is running: kills the renderer where possible, otherwise reloads. */
    private fun kill() {
        val v = view ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && v.webViewRenderProcess?.terminate() == true) return
        recreate()
    }

    fun destroy() {
        view?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.destroy()
        }
        view = null
    }
}
