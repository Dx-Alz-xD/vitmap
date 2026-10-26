package com.vitmap.app.feature.code

import android.content.Context
import com.vitmap.app.data.packs.Http
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language as SoraLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File

/**
 * TextMate grammars and themes for the editor. Two themes ship in the APK; every grammar and the
 * other themes download once from the tm-grammars / tm-themes packages and are kept on disk.
 */
object Highlighting {
    private const val GRAMMARS_VERSION = "1.32.22"
    private const val THEMES_VERSION = "1.12.12"
    val bundledThemes = setOf("github-light-default", "github-dark-default")
    private val mutex = Mutex()
    private val loaded = HashSet<String>()
    private val loadedThemes = HashSet<String>()

    private fun dir(context: Context, sub: String) = File(context.filesDir, "code/$sub").apply { mkdirs() }

    private fun cdn(pkg: String, version: String, path: String) = listOf(
        "https://cdn.jsdelivr.net/npm/$pkg@$version/$path",
        "https://unpkg.com/$pkg@$version/$path",
    )

    fun isGrammarDownloaded(context: Context, name: String) = File(dir(context, "grammars"), "$name.json").exists()

    fun isThemeAvailable(context: Context, name: String) = name in bundledThemes || File(dir(context, "themes"), "$name.json").exists()

    private suspend fun fetch(urls: List<String>, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        Http.download(urls, dest)
    }

    /**
     * Makes [name] (and the grammars it embeds, within a size budget) available to the editor.
     * Returns the scope name, or null when the grammar can't be fetched.
     */
    suspend fun ensureGrammar(context: Context, name: String, comment: String): String? = withContext(Dispatchers.IO) {
        val all = CodeCatalog.grammars(context)
        val info = all[name] ?: return@withContext null
        mutex.withLock {
            if (name in loaded) return@withLock info.scope
            val gdir = dir(context, "grammars")
            runCatching {
                // Embedded languages (CSS and JS in HTML, code fences in Markdown) as far as the budget allows.
                var budget = 1_500_000L
                for (dep in info.embedded.mapNotNull { all[it] }) {
                    if (dep.name in loaded) continue
                    if (!isGrammarDownloaded(context, dep.name)) {
                        if (dep.bytes > budget) continue
                        budget -= dep.bytes
                        runCatching { fetch(cdn("tm-grammars", GRAMMARS_VERSION, "grammars/${dep.name}.json"), File(gdir, "${dep.name}.json")) }
                    }
                    if (isGrammarDownloaded(context, dep.name)) register(context, dep, "")
                }
                fetch(cdn("tm-grammars", GRAMMARS_VERSION, "grammars/$name.json"), File(gdir, "$name.json"))
                register(context, info, comment)
                info.scope
            }.getOrNull()
        }
    }

    private fun register(context: Context, info: GrammarInfo, comment: String) {
        if (info.name in loaded) return
        val file = File(dir(context, "grammars"), "${info.name}.json")
        val config = File(dir(context, "grammars"), "${info.name}.config.json").apply { writeText(languageConfiguration(comment)) }
        GrammarRegistry.getInstance().loadGrammar(
            DefaultGrammarDefinition.withLanguageConfiguration(IGrammarSource.fromFile(file), config.absolutePath, info.name, info.scope),
        )
        loaded += info.name
    }

    /** Brackets, auto-closing pairs and comment tokens for the editor's typing helpers. */
    private fun languageConfiguration(comment: String): String {
        val lispy = comment == ";"
        val quotes = if (lispy) "" else """,{"open":"'","close":"'","notIn":["string","comment"]}"""
        val line = if (comment.isNotEmpty()) """"lineComment":${JsonPrimitive(comment)},""" else ""
        return """{"comments":{$line"blockComment":["/*","*/"]},"brackets":[["{","}"],["[","]"],["(",")"]],""" +
            """"autoClosingPairs":[{"open":"{","close":"}"},{"open":"[","close":"]"},{"open":"(","close":")"},{"open":"\"","close":"\"","notIn":["string"]}$quotes],""" +
            """"surroundingPairs":[["{","}"],["[","]"],["(",")"],["\"","\""],["'","'"]]}"""
    }

    /** Loads [name] as the current theme, downloading it first if it isn't bundled. */
    suspend fun applyTheme(context: Context, name: String, dark: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val registry = ThemeRegistry.getInstance()
                if (name !in loadedThemes) {
                    val stream = if (name in bundledThemes) {
                        context.assets.open("code/themes/$name.json")
                    } else {
                        val f = File(dir(context, "themes"), "$name.json")
                        fetch(cdn("tm-themes", THEMES_VERSION, "themes/$name.json"), f)
                        f.inputStream()
                    }
                    registry.loadTheme(ThemeModel(IThemeSource.fromInputStream(stream, "$name.json", null), name).apply { isDark = dark }, false)
                    loadedThemes += name
                }
                registry.setTheme(name)
                true
            }.getOrDefault(false)
        }
    }

    fun colorScheme(): EditorColorScheme = runCatching { TextMateColorScheme.create(ThemeRegistry.getInstance()) as EditorColorScheme }.getOrElse { EditorColorScheme() }

    fun language(scope: String?): SoraLanguage = scope?.let { runCatching { TextMateLanguage.create(it, true) }.getOrNull() } ?: EmptyLanguage()
}
