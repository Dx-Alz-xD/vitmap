package com.vitmap.app.feature.code

import android.content.Context
import com.vitmap.app.data.packs.Http
import com.vitmap.core.code.CodeText
import com.vitmap.core.code.CompilerPick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

@Serializable
data class WbCompiler(val name: String, val display: String)

@Serializable
data class CeCompiler(val id: String, val name: String)

@Serializable
data class CeLanguage(val name: String, val ext: String, val defaultCompiler: String? = null, val compilers: List<CeCompiler>)

/** What Wandbox and Compiler Explorer can run, cached for a few days. */
@Serializable
data class CloudCatalog(val fetched: Long, val wandbox: Map<String, List<WbCompiler>>, val godbolt: Map<String, CeLanguage>) {
    fun wandboxDefault(lang: String): String? = wandbox[lang]?.let { cs -> CompilerPick.wandbox(cs.map { it.name }) }

    fun godboltDefault(lang: String): String? = godbolt[lang]?.let { l -> CompilerPick.godbolt(l.defaultCompiler, l.compilers.map { it.id to it.name }) }
}

data class CloudResult(val exitCode: Int?, val stdout: String, val stderr: String, val build: String, val buildFailed: Boolean)

object Cloud {
    private const val WANDBOX = "https://wandbox.org/api"
    private const val GODBOLT = "https://godbolt.org/api"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val accept = mapOf("Accept" to "application/json")
    @Volatile private var memory: CloudCatalog? = null

    private fun file(context: Context) = File(context.filesDir, "code/cloud.json")

    fun cached(context: Context): CloudCatalog? = memory ?: runCatching {
        json.decodeFromString<CloudCatalog>(file(context).readText())
    }.getOrNull()?.also { memory = it }

    /** The cached catalog, refreshed when older than three days (or when [force]). Falls back to the cache offline. */
    suspend fun catalog(context: Context, force: Boolean = false): CloudCatalog? = withContext(Dispatchers.IO) {
        val old = cached(context)
        if (!force && old != null && System.currentTimeMillis() - old.fetched < 3 * 24 * 3600_000L) return@withContext old
        val fresh = runCatching { fetch() }.getOrNull() ?: return@withContext old
        file(context).parentFile?.mkdirs()
        file(context).writeText(json.encodeToString(CloudCatalog.serializer(), fresh))
        memory = fresh
        fresh
    }

    private suspend fun fetch(): CloudCatalog = coroutineScope {
        val wb = async { Http.text("$WANDBOX/list.json", accept) }
        val langs = async { Http.text("$GODBOLT/languages?fields=id,name,extensions,defaultCompiler", accept) }
        val comps = async { Http.text("$GODBOLT/compilers?fields=id,name,lang,supportsExecute", accept) }
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        val wandbox = LinkedHashMap<String, MutableList<WbCompiler>>()
        runCatching {
            for (el in json.parseToJsonElement(wb.await()).jsonArray) {
                val o = el.jsonObject
                val lang = o.s("language") ?: continue
                val name = o.s("name") ?: continue
                val display = listOfNotNull(o.s("display-name"), o.s("version")).joinToString(" ").ifEmpty { name }
                wandbox.getOrPut(lang) { ArrayList() } += WbCompiler(name, display)
            }
        }
        val godbolt = LinkedHashMap<String, CeLanguage>()
        runCatching {
            val executable = LinkedHashMap<String, MutableList<CeCompiler>>()
            for (el in json.parseToJsonElement(comps.await()).jsonArray) {
                val o = el.jsonObject
                if ((o["supportsExecute"] as? JsonPrimitive)?.booleanOrNull != true) continue
                val lang = o.s("lang") ?: continue
                executable.getOrPut(lang) { ArrayList() } += CeCompiler(o.s("id") ?: continue, o.s("name") ?: continue)
            }
            for (el in json.parseToJsonElement(langs.await()).jsonArray) {
                val o = el.jsonObject
                val id = o.s("id") ?: continue
                val compilers = executable[id] ?: continue
                val ext = (o["extensions"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull?.removePrefix(".").orEmpty()
                godbolt[id] = CeLanguage(o.s("name") ?: id, ext, o.s("defaultCompiler"), compilers)
            }
        }
        if (wandbox.isEmpty() && godbolt.isEmpty()) throw java.io.IOException("Couldn't reach the compiler services")
        CloudCatalog(System.currentTimeMillis(), wandbox, godbolt)
    }

    suspend fun wandbox(compiler: String, code: String, stdin: String, compilerArgs: String, runtimeArgs: String): CloudResult {
        val body = buildJsonObject {
            put("code", code)
            put("compiler", compiler)
            put("stdin", stdin)
            put("save", false)
            if (compilerArgs.isNotBlank()) put("compiler-option-raw", compilerArgs.trim().split(Regex("\\s+")).joinToString("\n"))
            if (runtimeArgs.isNotBlank()) put("runtime-option-raw", runtimeArgs.trim().split(Regex("\\s+")).joinToString("\n"))
        }
        val r = json.parseToJsonElement(Http.post("$WANDBOX/compile.json", body.toString())).jsonObject
        fun s(k: String) = (r[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val status = s("status").toIntOrNull()
        val build = CodeText.stripAnsi(s("compiler_output") + s("compiler_error"))
        val signal = s("signal")
        return CloudResult(
            exitCode = status,
            stdout = s("program_output"),
            stderr = s("program_error") + if (signal.isNotEmpty()) "\n[Killed by $signal]" else "",
            build = build,
            buildFailed = status == null && s("program_output").isEmpty() && s("compiler_error").isNotEmpty(),
        )
    }

    suspend fun godbolt(lang: String, compiler: String, code: String, stdin: String, compilerArgs: String, runtimeArgs: String): CloudResult {
        val body = buildJsonObject {
            put("source", code)
            put("compiler", compiler)
            put("lang", lang)
            put("allowStoreCodeDebug", true)
            putJsonObject("options") {
                put("userArguments", compilerArgs)
                putJsonObject("executeParameters") {
                    putJsonArray("args") { runtimeArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) } }
                    put("stdin", stdin)
                }
                putJsonObject("compilerOptions") { put("executorRequest", true) }
                putJsonObject("filters") { put("execute", true) }
                putJsonArray("tools") {}
                putJsonArray("libraries") {}
            }
        }
        val r = json.parseToJsonElement(Http.post("$GODBOLT/compiler/$compiler/compile", body.toString())).jsonObject
        fun lines(o: JsonObject?, k: String) = (o?.get(k) as? JsonArray)?.joinToString("") { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() + "\n" }.orEmpty()
        val exec = r["execResult"] as? JsonObject ?: r
        val build = exec["buildResult"] as? JsonObject ?: r["buildResult"] as? JsonObject
        val buildCode = (build?.get("code") as? JsonPrimitive)?.intOrNull ?: 0
        val didExecute = (exec["didExecute"] as? JsonPrimitive)?.booleanOrNull ?: true
        return CloudResult(
            exitCode = (exec["code"] as? JsonPrimitive)?.intOrNull,
            stdout = CodeText.stripAnsi(lines(exec, "stdout")),
            // Compiler Explorer's JVM sandbox can't load jansi; its warning isn't the program's.
            stderr = CodeText.stripAnsi(lines(exec, "stderr")).lines().filterNot { "jansi" in it }.joinToString("\n")
                .let { if (!didExecute && it.trim() == "Build failed") "" else it },
            build = CodeText.stripAnsi(lines(build, "stdout") + lines(build, "stderr")),
            buildFailed = buildCode != 0 || !didExecute,
        )
    }
}
