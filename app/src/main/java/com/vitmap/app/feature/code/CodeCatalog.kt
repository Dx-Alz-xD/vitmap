package com.vitmap.app.feature.code

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One entry of assets/code/languages.json. */
@Serializable
data class LangSpec(
    val id: String,
    val name: String,
    val ext: String,
    val grammar: String? = null,
    val comment: String = "//",
    val category: String = "general",
    val template: String = "",
    val local: String? = null,
    val web: String? = null,
    val wandbox: String? = null,
    val godbolt: String? = null,
    val compiler: String? = null,
    val preview: String? = null,
    val data: String? = null,
    val stdin: Boolean = false,
    val aliases: List<String> = emptyList(),
)

data class GrammarInfo(val name: String, val scope: String, val display: String, val aliases: List<String>, val embedded: List<String>, val bytes: Long, val license: String)

data class ThemeInfo(val name: String, val display: String, val dark: Boolean, val bytes: Long, val license: String)

/** How a language can run, best first. */
enum class RunKind(val label: String) {
    LOCAL("On this phone"),
    WEB("On this phone (downloadable runtime)"),
    PREVIEW("Preview"),
    DATA("Check & format"),
    WANDBOX("Cloud · Wandbox"),
    GODBOLT("Cloud · Compiler Explorer"),
}

data class Runner(val kind: RunKind, val id: String, val label: String)

data class Language(val spec: LangSpec, val grammar: GrammarInfo?, val runners: List<Runner>) {
    val id get() = spec.id
    val name get() = spec.name
    val ext get() = spec.ext
    val runnable get() = runners.isNotEmpty()
    val offline get() = runners.any { it.kind == RunKind.LOCAL || it.kind == RunKind.DATA || it.kind == RunKind.PREVIEW }
}

val categoryNames = linkedMapOf(
    "popular" to "Popular",
    "web" to "Web",
    "scripting" to "Scripting",
    "systems" to "Systems",
    "jvm" to "JVM",
    "dotnet" to ".NET",
    "functional" to "Functional",
    "scientific" to "Science & data",
    "data" to "Data & config",
    "legacy" to "Classic",
    "esoteric" to "Esoteric",
    "general" to "General purpose",
    "cloud" to "More compilers",
    "other" to "Edit only",
)

object CodeCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var specs: List<LangSpec>? = null
    @Volatile private var grammars: Map<String, GrammarInfo>? = null
    @Volatile private var themes: List<ThemeInfo>? = null

    fun specs(context: Context): List<LangSpec> = specs ?: synchronized(this) {
        specs ?: json.decodeFromString<List<LangSpec>>(context.assets.open("code/languages.json").bufferedReader().readText()).also { specs = it }
    }

    fun grammars(context: Context): Map<String, GrammarInfo> = grammars ?: synchronized(this) {
        grammars ?: context.assets.open("code/grammars.tsv").bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val p = line.split('\t')
            fun list(i: Int) = p.getOrNull(i).orEmpty().split(',').filter { it.isNotBlank() }
            p[0] to GrammarInfo(p[0], p[1], p[2], list(3), list(4), p.getOrNull(5)?.toLongOrNull() ?: 0, p.getOrNull(6).orEmpty())
        }.also { grammars = it }
    }

    fun themes(context: Context): List<ThemeInfo> = themes ?: synchronized(this) {
        themes ?: context.assets.open("code/themes.tsv").bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val p = line.split('\t')
            ThemeInfo(p[0], p[1], p[2] == "dark", p.getOrNull(3)?.toLongOrNull() ?: 0, p.getOrNull(4).orEmpty())
        }.also { themes = it }
    }

    /**
     * Every language: the curated catalog, then cloud-only languages Wandbox and Compiler Explorer
     * report, then anything the editor can highlight.
     */
    fun languages(context: Context, cloud: CloudCatalog?): List<Language> {
        val g = grammars(context)
        val curated = specs(context)
        val out = ArrayList<Language>()
        val usedGrammars = HashSet<String>()
        val wandboxUsed = HashSet<String>()
        val godboltUsed = HashSet<String>()
        for (s in curated) {
            s.grammar?.let { usedGrammars += it }
            s.wandbox?.let { wandboxUsed += it }
            s.godbolt?.let { godboltUsed += it }
            out += Language(s, s.grammar?.let { g[it] }, runnersFor(s, cloud))
        }
        if (cloud != null) {
            for ((lang, compilers) in cloud.wandbox) {
                if (lang in wandboxUsed || compilers.isEmpty()) continue
                val spec = LangSpec(id = "wb-" + slug(lang), name = lang, ext = "txt", category = "cloud", wandbox = lang, comment = "")
                out += Language(spec, guessGrammar(lang, g), runnersFor(spec, cloud))
            }
            for ((lang, info) in cloud.godbolt) {
                if (lang in godboltUsed || info.compilers.isEmpty()) continue
                val spec = LangSpec(id = "ce-$lang", name = info.name, ext = info.ext.ifEmpty { "txt" }, category = "cloud", godbolt = lang, comment = "")
                out += Language(spec, guessGrammar(info.name, g) ?: guessGrammar(lang, g), runnersFor(spec, cloud))
            }
        }
        for (info in g.values) {
            if (info.name in usedGrammars) continue
            val spec = LangSpec(id = "tm-${info.name}", name = info.display, ext = info.aliases.firstOrNull { it.length <= 6 && it.all(Char::isLetterOrDigit) } ?: info.name, grammar = info.name, category = "other", comment = "")
            out += Language(spec, info, emptyList())
        }
        return out
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun guessGrammar(name: String, g: Map<String, GrammarInfo>): GrammarInfo? {
        val key = slug(name)
        return g[key] ?: g.values.firstOrNull { it.display.equals(name, true) || key in it.aliases }
    }

    fun runnersFor(s: LangSpec, cloud: CloudCatalog?): List<Runner> = buildList {
        s.local?.let { add(Runner(RunKind.LOCAL, it, localLabel(it))) }
        s.web?.let { w -> WebRuntimes.byId(w)?.let { add(Runner(RunKind.WEB, w, it.label)) } }
        s.preview?.let { add(Runner(RunKind.PREVIEW, it, "Preview")) }
        s.data?.let { add(Runner(RunKind.DATA, it, "Check & format")) }
        s.wandbox?.let { lang ->
            val compilers = cloud?.wandbox?.get(lang)
            if (compilers == null) add(Runner(RunKind.WANDBOX, "", "Wandbox"))
            else for (c in compilers) add(Runner(RunKind.WANDBOX, c.name, "Wandbox · ${c.display}"))
        }
        s.godbolt?.let { lang ->
            val info = cloud?.godbolt?.get(lang)
            if (info == null) add(Runner(RunKind.GODBOLT, "", "Compiler Explorer"))
            else for (c in info.compilers) add(Runner(RunKind.GODBOLT, c.id, "Compiler Explorer · ${c.name}"))
        }
    }

    private fun localLabel(id: String) = when {
        id == "quickjs" -> "QuickJS (offline)"
        id == "python" -> "Python 3 (downloadable)"
        id == "shell" -> "Android shell (offline)"
        id == "sqlite" -> "SQLite (offline)"
        id == "lua" -> "Lua 5.2 (offline)"
        id.startsWith("esolang:") -> "Built-in interpreter (offline)"
        else -> id
    }

    /** Best default: the first runner, or for cloud the pre-picked stable compiler. */
    fun defaultRunner(lang: Language, cloud: CloudCatalog?): Runner? {
        val first = lang.runners.firstOrNull() ?: return null
        return when (first.kind) {
            RunKind.WANDBOX -> cloud?.wandboxDefault(lang.spec.wandbox!!)?.let { c -> lang.runners.firstOrNull { it.id == c } } ?: first
            RunKind.GODBOLT -> cloud?.godboltDefault(lang.spec.godbolt!!)?.let { c -> lang.runners.firstOrNull { it.id == c } } ?: first
            else -> first
        }
    }

    fun byExtension(fileName: String, all: List<Language>): Language? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return all.firstOrNull { it.ext.equals(ext, true) } ?: all.firstOrNull { l -> l.spec.aliases.any { it.equals(ext, true) } }
            ?: all.firstOrNull { l -> l.grammar?.aliases?.any { it.equals(ext, true) } == true }
    }
}
