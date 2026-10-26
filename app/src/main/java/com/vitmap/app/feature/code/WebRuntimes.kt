package com.vitmap.app.feature.code

import android.content.Context
import com.vitmap.app.data.packs.FilesInstaller
import com.vitmap.app.data.packs.Pack
import com.vitmap.app.data.packs.PackGroup
import com.vitmap.app.data.packs.RemoteFile
import java.io.File

/**
 * Language runtimes that run inside the system browser engine. Their files come from npm (via
 * jsDelivr, with unpkg as a mirror) the first time they're used.
 */
class WebRuntime(val id: String, val label: String, val pack: Pack?, private val installer: FilesInstaller?) {
    fun dir(context: Context): File? = installer?.dir(context)
}

object WebRuntimes {
    private fun npm(pkg: String, version: String, path: String) = listOf(
        "https://cdn.jsdelivr.net/npm/$pkg@$version/$path",
        "https://unpkg.com/$pkg@$version/$path",
    )

    private fun runtime(id: String, label: String, title: String, description: String, bytes: Long, version: String, files: List<RemoteFile>): WebRuntime {
        val installer = FilesInstaller("code-$id", version) { files }
        return WebRuntime(id, label, Pack("code-$id", title, description, bytes, PackGroup.CODE, installer), installer)
    }

    val js = WebRuntime("js", "Browser engine (offline)", null, null)

    val ts = runtime(
        "ts", "TypeScript 5.9 compiler", "TypeScript compiler",
        "The official TypeScript 5.9 compiler. Turns TypeScript into JavaScript on the phone and reports errors; the result runs in the browser engine.",
        9_112_572, "5.9.3",
        listOf(RemoteFile(npm("typescript", "5.9.3", "lib/typescript.js"), "typescript.js", label = "Downloading TypeScript")),
    )

    val coffee = runtime(
        "coffee", "CoffeeScript 2.7", "CoffeeScript compiler",
        "CoffeeScript 2.7, compiled to JavaScript on the phone.",
        529_751, "2.7.1",
        listOf(RemoteFile(npm("coffeescript", "2.7.1", "lib/coffeescript-browser-compiler-legacy/coffeescript.js"), "coffeescript.js")),
    )

    val scheme = runtime(
        "scheme", "BiwaScheme 0.8 (R7RS)", "Scheme (BiwaScheme)",
        "A Scheme interpreter (mostly R7RS) written in JavaScript.",
        377_921, "0.8.3",
        listOf(RemoteFile(npm("biwascheme", "0.8.3", "release/biwascheme-min.js"), "biwascheme-min.js")),
    )

    val prolog = runtime(
        "prolog", "Tau Prolog 0.3 (ISO)", "Prolog (Tau Prolog)",
        "An ISO Prolog interpreter in JavaScript, with the lists and format libraries.",
        433_573, "0.3.4",
        listOf(
            RemoteFile(npm("tau-prolog", "0.3.4", "modules/core.js"), "core.js"),
            RemoteFile(npm("tau-prolog", "0.3.4", "modules/lists.js"), "lists.js"),
            RemoteFile(npm("tau-prolog", "0.3.4", "modules/format.js"), "format.js"),
        ),
    )

    val ruby = runtime(
        "ruby", "Ruby 3.4 (WebAssembly)", "Ruby 3.4",
        "CRuby 3.4 with its standard library, compiled to WebAssembly by the ruby.wasm project.",
        30_736_022, "2.10.1",
        listOf(
            RemoteFile(npm("@ruby/wasm-wasi", "2.10.1", "dist/browser.umd.js"), "browser.umd.js"),
            RemoteFile(npm("@ruby/3.4-wasm-wasi", "2.10.1", "dist/ruby+stdlib.wasm"), "ruby+stdlib.wasm", label = "Downloading Ruby"),
        ),
    )

    val clojure = runtime(
        "clojure", "Scittle (ClojureScript)", "Clojure (Scittle)",
        "The Small Clojure Interpreter for the browser: Clojure without the JVM.",
        972_244, "0.8.33",
        listOf(RemoteFile(npm("scittle", "0.8.33", "dist/scittle.js"), "scittle.js")),
    )

    val all = listOf(js, ts, coffee, scheme, prolog, ruby, clojure)
    val packs: List<Pack> get() = all.mapNotNull { it.pack }

    fun byId(id: String): WebRuntime? = all.firstOrNull { it.id == id }
}
