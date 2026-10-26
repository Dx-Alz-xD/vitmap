package com.vitmap.app.feature.code

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitmap.app.data.FileSaver
import com.vitmap.app.data.packs.Pack
import com.vitmap.app.media.EngineMissingException
import com.vitmap.app.media.MediaEngine
import com.vitmap.app.vitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class OutKind { OUT, ERR, INFO }

data class OutChunk(val kind: OutKind, val text: String)

data class RunState(
    val running: Boolean = false,
    val chunks: List<OutChunk> = emptyList(),
    val outcome: RunOutcome? = null,
    val runner: Runner? = null,
    val error: String? = null,
    /** Set when the run needs a download first. */
    val needPack: Pack? = null,
)

sealed interface CodePage {
    data object Home : CodePage
    data class Picker(val forFile: String? = null) : CodePage
    data class Editor(val fileId: String) : CodePage
}

class CodeViewModel(app: Application) : AndroidViewModel(app) {
    val repo = CodeRepository.get(app)
    val state: StateFlow<CodeState?> = repo.state
    private val _languages = MutableStateFlow<List<Language>>(emptyList())
    val languages = _languages.asStateFlow()
    private val _cloud = MutableStateFlow(Cloud.cached(app))
    val cloud = _cloud.asStateFlow()
    private val _page = MutableStateFlow<CodePage>(CodePage.Home)
    val page = _page.asStateFlow()
    private val _run = MutableStateFlow(RunState())
    val run = _run.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val backStack = ArrayDeque<CodePage>()
    private var runJob: Job? = null
    private var saveJob: Job? = null
    @Volatile var pendingText: String? = null
        private set

    init {
        viewModelScope.launch {
            repo.load()
            rebuild()
            Cloud.catalog(getApplication())?.let { fresh ->
                if (fresh != _cloud.value) {
                    _cloud.value = fresh
                    rebuild()
                }
            }
        }
    }

    private suspend fun rebuild() {
        _languages.value = withContext(Dispatchers.IO) { CodeCatalog.languages(getApplication(), _cloud.value) }
    }

    fun refreshCloud() {
        viewModelScope.launch {
            val fresh = Cloud.catalog(getApplication(), force = true)
            if (fresh == null) _message.value = "Couldn't reach the compiler services" else {
                _cloud.value = fresh
                rebuild()
                _message.value = "Compiler lists updated"
            }
        }
    }

    fun consumeMessage() { _message.value = null }

    fun language(id: String?): Language? = _languages.value.firstOrNull { it.id == id }

    // ------------------------------------------------------------ navigation

    fun go(p: CodePage) {
        backStack.addLast(_page.value)
        _page.value = p
    }

    /** Returns false when there's nothing to go back to (leave the tool). */
    fun back(): Boolean {
        flushSave()
        val prev = backStack.removeLastOrNull() ?: return false
        _page.value = prev
        if (prev !is CodePage.Editor) _run.value = RunState()
        return true
    }

    // ------------------------------------------------------------ files

    fun newFile(lang: Language) {
        viewModelScope.launch {
            val base = when (lang.id) {
                "java" -> "Main"
                "html" -> "index"
                "markdown" -> "README"
                else -> "main"
            }
            val f = repo.create(repo.freshName(base, lang.ext), lang.id, lang.spec.template)
            _run.value = RunState()
            _page.value = CodePage.Editor(f.id)
            backStack.clear()
            backStack.addLast(CodePage.Home)
        }
    }

    fun open(id: String) {
        _run.value = RunState()
        go(CodePage.Editor(id))
    }

    suspend fun text(id: String): String = repo.read(id)

    /** Called on every edit; writes to disk after a short pause. */
    fun edited(id: String, text: String) {
        pendingText = text
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            repo.write(id, text)
            repo.touch(id)
            pendingText = null
        }
    }

    private fun flushSave() {
        val p = _page.value as? CodePage.Editor ?: return
        val t = pendingText ?: return
        saveJob?.cancel()
        // The app scope, so the write survives the ViewModel being cleared.
        getApplication<Application>().vitmap.appScope.launch {
            repo.write(p.fileId, t)
            repo.touch(p.fileId)
        }
        pendingText = null
    }

    fun rename(id: String, name: String) {
        val n = FileSaver.safeName(name)
        viewModelScope.launch { repo.update(id) { it.copy(name = n) } }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
        if ((_page.value as? CodePage.Editor)?.fileId == id) {
            pendingText = null
            saveJob?.cancel()
            back()
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch { repo.duplicate(id)?.let { _message.value = "Made ${it.name}" } }
    }

    fun setLanguage(id: String, lang: Language) {
        viewModelScope.launch {
            repo.update(id) { f ->
                val base = f.name.substringBeforeLast('.')
                f.copy(lang = lang.id, runner = null, name = "$base.${lang.ext}")
            }
        }
    }

    fun setRunner(id: String, runner: Runner?) {
        viewModelScope.launch { repo.update(id) { it.copy(runner = runner?.let { r -> "${r.kind.name}:${r.id}" }) } }
    }

    fun setIo(id: String, stdin: String? = null, args: String? = null, compilerArgs: String? = null) {
        viewModelScope.launch {
            repo.update(id) { f -> f.copy(stdin = stdin ?: f.stdin, args = args ?: f.args, compilerArgs = compilerArgs ?: f.compilerArgs) }
        }
    }

    fun settings(change: (CodeSettings) -> CodeSettings) {
        viewModelScope.launch { repo.settings(change) }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val name = FileSaver.displayName(ctx, uri)
                val size = FileSaver.size(ctx, uri)
                if (size > 4L shl 20) throw IOException("That file is larger than 4 MB")
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } } ?: throw IOException("Couldn't read the file")
                val lang = CodeCatalog.byExtension(name, _languages.value) ?: language("text")
                val f = repo.create(FileSaver.safeName(name), lang?.id ?: "text", text)
                open(f.id)
            } catch (e: Exception) {
                _message.value = e.message ?: "Couldn't open that file"
            }
        }
    }

    suspend fun exportTo(id: String, uri: Uri) {
        val text = pendingText ?: repo.read(id)
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: throw IOException("Couldn't write the file")
        }
        _message.value = "Saved"
    }

    // ------------------------------------------------------------ running

    fun runnerFor(file: CodeFile, lang: Language): Runner? {
        file.runner?.let { saved ->
            val kind = saved.substringBefore(':')
            val rid = saved.substringAfter(':')
            lang.runners.firstOrNull { it.kind.name == kind && it.id == rid }?.let { return it }
        }
        return CodeCatalog.defaultRunner(lang, _cloud.value)
    }

    fun runCode(file: CodeFile, code: String, sandbox: WebSandbox) {
        val lang = language(file.lang) ?: return
        val runner = runnerFor(file, lang) ?: run {
            _run.value = RunState(error = "${lang.name} can be edited here but not run.")
            return
        }
        // Make sure a downloadable runtime is present before starting.
        val needed: Pack? = when {
            runner.kind == RunKind.WEB -> WebRuntimes.byId(runner.id)?.pack
            runner.kind == RunKind.LOCAL && runner.id == "python" -> MediaEngine.python
            else -> null
        }
        runJob?.cancel()
        runJob = viewModelScope.launch {
            if (needed != null && !getApplication<Application>().vitmap.packs.isReady(needed)) {
                _run.value = RunState(needPack = needed, runner = runner)
                return@launch
            }
            val chunks = ArrayList<OutChunk>()
            var total = 0
            var dirty = false
            val lock = Any()
            fun add(kind: OutKind, text: String) = synchronized(lock) {
                if (total > 2_000_000) return@synchronized
                total += text.length
                val last = chunks.lastOrNull()
                if (last != null && last.kind == kind && last.text.length < 8000) chunks[chunks.lastIndex] = OutChunk(kind, last.text + text) else chunks += OutChunk(kind, text)
                if (total > 2_000_000) chunks += OutChunk(OutKind.INFO, "\n… output truncated at 2 MB\n")
                dirty = true
            }
            val sink = object : RunSink {
                override fun out(text: String) = add(OutKind.OUT, text)
                override fun err(text: String) = add(OutKind.ERR, text)
                override fun info(text: String) = add(OutKind.INFO, text)
            }
            _run.value = RunState(running = true, runner = runner)
            // Publish output a few times a second rather than on every write.
            val ticker = launch {
                while (true) {
                    delay(90)
                    synchronized(lock) {
                        if (dirty) {
                            dirty = false
                            _run.update { it.copy(chunks = chunks.toList()) }
                        }
                    }
                }
            }
            val settings = state.value?.settings ?: CodeSettings()
            try {
                val outcome = CodeRunners.run(
                    getApplication(),
                    RunRequest(lang, runner, code, file.stdin, file.compilerArgs, file.args, file.name, settings.timeoutSec),
                    sink,
                    sandbox,
                )
                ticker.cancel()
                _run.update { RunState(running = false, chunks = synchronized(lock) { chunks.toList() }, outcome = outcome, runner = runner) }
            } catch (e: CancellationException) {
                ticker.cancel()
                _run.update { RunState(running = false, chunks = synchronized(lock) { chunks.toList() } + OutChunk(OutKind.INFO, "\nStopped.\n"), runner = runner) }
                throw e
            } catch (e: EngineMissingException) {
                ticker.cancel()
                _run.update { RunState(needPack = e.pack, runner = runner) }
            } catch (e: Exception) {
                ticker.cancel()
                val msg = when {
                    e is IOException && runner.kind in setOf(RunKind.WANDBOX, RunKind.GODBOLT) -> "Couldn't reach the compiler service: ${e.message}"
                    else -> e.message ?: e.javaClass.simpleName
                }
                _run.update { RunState(running = false, chunks = synchronized(lock) { chunks.toList() }, error = msg, runner = runner) }
            }
        }
    }

    fun stop() {
        runJob?.cancel()
    }

    fun clearOutput() {
        _run.value = RunState()
    }

    override fun onCleared() {
        flushSave()
        super.onCleared()
    }
}
