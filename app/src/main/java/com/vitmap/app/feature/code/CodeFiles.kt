package com.vitmap.app.feature.code

import android.content.Context
import com.vitmap.app.data.JsonFileStore
import com.vitmap.app.vitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class CodeFile(
    val id: String,
    val name: String,
    val lang: String,
    /** "KIND:id" of the chosen runner, or null for the language's default. */
    val runner: String? = null,
    val stdin: String = "",
    val compilerArgs: String = "",
    val args: String = "",
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
)

@Serializable
data class CodeSettings(
    /** A tm-themes name; null follows the app's light/dark mode. */
    val theme: String? = null,
    val fontSize: Float = 14f,
    val wrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val tabSize: Int = 4,
    val keyBar: Boolean = true,
    val timeoutSec: Int = 60,
)

@Serializable
data class CodeState(val files: List<CodeFile> = emptyList(), val settings: CodeSettings = CodeSettings(), val recentLangs: List<String> = emptyList())

/** Code Writer files: text in `files/code/files/<id>`, metadata in a store. */
class CodeRepository private constructor(private val context: Context) {
    private val store: JsonFileStore<CodeState> = context.vitmap.store("code", CodeState.serializer()) { CodeState() }
    val state: StateFlow<CodeState?> = store.flow

    private fun textFile(id: String) = File(context.filesDir, "code/files/$id").apply { parentFile?.mkdirs() }

    suspend fun load(): CodeState = store.load()

    suspend fun read(id: String): String = withContext(Dispatchers.IO) { textFile(id).takeIf { it.exists() }?.readText().orEmpty() }

    suspend fun write(id: String, text: String) = withContext(Dispatchers.IO) {
        val f = textFile(id)
        val tmp = File(f.parentFile, "$id.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    suspend fun create(name: String, lang: String, text: String): CodeFile {
        val file = CodeFile(UUID.randomUUID().toString(), name, lang)
        write(file.id, text)
        store.update { s -> s.copy(files = listOf(file) + s.files, recentLangs = (listOf(lang) + s.recentLangs).distinct().take(8)) }
        return file
    }

    suspend fun update(id: String, change: (CodeFile) -> CodeFile) {
        store.update { s -> s.copy(files = s.files.map { if (it.id == id) change(it) else it }) }
    }

    suspend fun touch(id: String) = update(id) { it.copy(updated = System.currentTimeMillis()) }

    suspend fun delete(id: String) {
        store.update { s -> s.copy(files = s.files.filter { it.id != id }) }
        withContext(Dispatchers.IO) { textFile(id).delete() }
    }

    suspend fun duplicate(id: String): CodeFile? {
        val src = load().files.firstOrNull { it.id == id } ?: return null
        val base = src.name.substringBeforeLast('.')
        val ext = src.name.substringAfterLast('.', "")
        return create(if (ext.isEmpty()) "$base copy" else "$base copy.$ext", src.lang, read(id))
    }

    suspend fun settings(change: (CodeSettings) -> CodeSettings) {
        store.update { s -> s.copy(settings = change(s.settings)) }
    }

    /** A file name that isn't taken yet, like "main.py", "main 2.py". */
    suspend fun freshName(base: String, ext: String): String {
        val taken = load().files.map { it.name.lowercase() }.toSet()
        var n = 1
        while (true) {
            val name = if (n == 1) "$base.$ext" else "$base $n.$ext"
            if (name.lowercase() !in taken) return name
            n++
        }
    }

    companion object {
        @Volatile private var instance: CodeRepository? = null

        fun get(context: Context): CodeRepository = instance ?: synchronized(this) {
            instance ?: CodeRepository(context.applicationContext).also { instance = it }
        }
    }
}
