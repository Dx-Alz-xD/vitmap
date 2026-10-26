package com.vitmap.app.feature.code

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.vitmap.app.feature.fileconvert.DocConvert
import com.vitmap.app.media.MediaEngine
import com.vitmap.core.code.CodeText
import com.vitmap.core.code.EsolangException
import com.vitmap.core.code.Esolangs
import com.vitmap.core.code.LuaRunner
import com.vitmap.core.convert.DataFormat
import com.vitmap.core.convert.DataFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Where a run's output goes as it arrives. */
interface RunSink {
    fun out(text: String)
    fun err(text: String)
    fun info(text: String)
}

data class RunRequest(
    val language: Language,
    val runner: Runner,
    val code: String,
    val stdin: String,
    val compilerArgs: String,
    val programArgs: String,
    val fileName: String,
    val timeoutSec: Int,
)

sealed interface RunOutcome {
    val ms: Long

    data class Exit(val code: Int?, override val ms: Long) : RunOutcome
    data class Preview(val html: String, override val ms: Long) : RunOutcome
}

object CodeRunners {
    suspend fun run(context: Context, r: RunRequest, sink: RunSink, sandbox: WebSandbox): RunOutcome {
        val started = System.currentTimeMillis()
        fun exit(code: Int?) = RunOutcome.Exit(code, System.currentTimeMillis() - started)
        return when (r.runner.kind) {
            RunKind.LOCAL -> exit(local(context, r, sink))
            RunKind.WEB -> exit(sandbox.run(r.runner.id, r.code, r.stdin, sink, r.timeoutSec * 1000L))
            RunKind.PREVIEW -> RunOutcome.Preview(preview(r), System.currentTimeMillis() - started)
            RunKind.DATA -> exit(data(r, sink))
            RunKind.WANDBOX, RunKind.GODBOLT -> exit(cloud(context, r, sink))
        }
    }

    private fun workDir(context: Context) = File(context.cacheDir, "code-run").apply { mkdirs() }

    private suspend fun local(context: Context, r: RunRequest, sink: RunSink): Int? {
        val id = r.runner.id
        val args = r.programArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val limit = r.timeoutSec * 1000L
        return when {
            id == "quickjs" -> {
                val dir = workDir(context)
                val prelude = File(dir, "prelude.js").apply {
                    writeText(
                        "globalThis.readline = () => std.in.getline();\n" +
                            "globalThis.input = (m) => { if (m) { std.out.puts(String(m)); std.out.flush(); } return std.in.getline(); };\n" +
                            "globalThis.prompt = globalThis.input;\n",
                    )
                }
                val main = File(dir, r.fileName.ifBlank { "main.js" }).apply { writeText(r.code) }
                process(context, listOf(MediaEngine.quickJsBin(context).absolutePath, "--std", "--memory-limit", "512M", "-I", prelude.absolutePath, main.absolutePath) + args, dir, r.stdin, sink, limit)
            }
            id == "python" -> {
                val dir = workDir(context)
                val main = File(dir, r.fileName.ifBlank { "main.py" }).apply { writeText(r.code) }
                withTimeoutOrNull(limit) {
                    MediaEngine.python(context, listOf("-u", main.absolutePath) + args, dir, r.stdin, onStdout = { sink.out(it + "\n") }, onStderr = { sink.err(it + "\n") }).exitCode
                } ?: timedOut(sink, limit)
            }
            id == "shell" -> {
                val dir = workDir(context)
                val main = File(dir, r.fileName.ifBlank { "main.sh" }).apply { writeText(r.code) }
                process(context, listOf("/system/bin/sh", main.absolutePath) + args, dir, r.stdin, sink, limit)
            }
            id == "sqlite" -> withContext(Dispatchers.IO) { sqlite(r.code, sink) }
            id == "lua" -> {
                val job = currentCoroutineContext().job
                withTimeoutOrNull(limit) {
                    runInterruptible(Dispatchers.Default) {
                        LuaRunner.run(r.code, r.stdin, sink::out, sink::err, cancelled = { !job.isActive }, chunkName = r.fileName.ifBlank { "main.lua" })
                    }
                } ?: timedOut(sink, limit)
            }
            id.startsWith("esolang:") -> {
                val job = currentCoroutineContext().job
                withContext(Dispatchers.Default) {
                    try {
                        val deadline = System.currentTimeMillis() + limit
                        Esolangs.run(id.removePrefix("esolang:"), r.code, Esolangs.Io(r.stdin, sink::out, cancelled = { !job.isActive || System.currentTimeMillis() > deadline }))
                    } catch (e: EsolangException) {
                        sink.err("\n${e.message}\n")
                        1
                    }
                }
            }
            else -> {
                sink.err("Unknown runner $id\n")
                1
            }
        }
    }

    private fun timedOut(sink: RunSink, limit: Long): Int {
        sink.err("\nStopped after ${limit / 1000} s. Change the time limit in the editor settings.\n")
        return 124
    }

    private suspend fun process(context: Context, command: List<String>, dir: File, stdin: String, sink: RunSink, limit: Long): Int =
        withTimeoutOrNull(limit) {
            MediaEngine.run(context, command, dir, stdin, onStdout = { sink.out(it + "\n") }, onStderr = { sink.err(it + "\n") }).exitCode
        } ?: timedOut(sink, limit)

    private fun sqlite(script: String, sink: RunSink): Int {
        val db = SQLiteDatabase.create(null)
        try {
            val statements = CodeText.splitSql(script)
            if (statements.isEmpty()) sink.info("Nothing to run.\n")
            for ((i, sql) in statements.withIndex()) {
                try {
                    if (CodeText.returnsRows(sql)) {
                        db.rawQuery(sql, null).use { c -> sink.out(render(c) + "\n\n") }
                    } else {
                        db.execSQL(sql)
                        val changed = db.rawQuery("SELECT changes()", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                        val verb = sql.trimStart().substringBefore(' ').uppercase()
                        sink.info(if (verb in setOf("INSERT", "UPDATE", "DELETE", "REPLACE")) "$verb: $changed row${if (changed == 1) "" else "s"}\n" else "$verb: done\n")
                    }
                } catch (e: Exception) {
                    sink.err("Statement ${i + 1}: ${e.message?.substringBefore(" (code")}\n")
                    return 1
                }
            }
            return 0
        } finally {
            db.close()
        }
    }

    private fun render(c: Cursor): String {
        val cols = c.columnNames.toList()
        val rows = ArrayList<List<String?>>()
        while (c.moveToNext() && rows.size < 1000) {
            rows += cols.indices.map { i ->
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> "<${c.getBlob(i).size} bytes>"
                    else -> c.getString(i)
                }
            }
        }
        return CodeText.table(cols, rows)
    }

    private fun preview(r: RunRequest): String = when (r.runner.id) {
        "markdown" -> DocConvert.toHtml(DocConvert.markdown(r.code), r.fileName)
        "svg" -> "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>html,body{margin:0;height:100%}body{display:grid;place-items:center;background:#fff}svg{max-width:100%;max-height:100vh}</style></head><body>${r.code}</body></html>"
        else -> r.code
    }

    private fun data(r: RunRequest, sink: RunSink): Int {
        val fmt = DataFormat.entries.firstOrNull { it.ext == r.runner.id } ?: return 1.also { sink.err("Unknown format\n") }
        return try {
            if (fmt == DataFormat.CSV || fmt == DataFormat.TSV) {
                val rows = DataFormats.parseCsv(r.code, if (fmt == DataFormat.CSV) ',' else '\t')
                sink.info("✓ Valid ${fmt.label}: ${rows.size} rows, ${rows.firstOrNull()?.size ?: 0} columns\n\n")
                if (rows.isNotEmpty()) sink.out(CodeText.table(rows.first(), rows.drop(1).take(500)) + "\n")
            } else {
                val e = DataFormats.read(r.code, fmt)
                sink.info("✓ Valid ${fmt.label}\n\n")
                sink.out(DataFormats.write(e, fmt).trimEnd() + "\n")
            }
            0
        } catch (e: Exception) {
            sink.err("✗ Not valid ${fmt.label}:\n${e.message}\n")
            1
        }
    }

    private suspend fun cloud(context: Context, r: RunRequest, sink: RunSink): Int? {
        val catalog = Cloud.catalog(context)
        val result = withTimeoutOrNull(maxOf(r.timeoutSec, 30) * 1000L + 30_000) {
            if (r.runner.kind == RunKind.WANDBOX) {
                val lang = r.language.spec.wandbox!!
                val compiler = r.runner.id.ifEmpty { catalog?.wandboxDefault(lang) ?: error("Couldn't reach Wandbox to find a $lang compiler") }
                sink.info("Running on wandbox.org ($compiler)…\n")
                Cloud.wandbox(compiler, r.code, r.stdin, r.compilerArgs, r.programArgs)
            } else {
                val lang = r.language.spec.godbolt!!
                val compiler = r.runner.id.ifEmpty { catalog?.godboltDefault(lang) ?: error("Couldn't reach Compiler Explorer to find a compiler") }
                sink.info("Running on godbolt.org (${catalog?.godbolt?.get(lang)?.compilers?.firstOrNull { it.id == compiler }?.name ?: compiler})…\n")
                Cloud.godbolt(lang, compiler, r.code, r.stdin, r.compilerArgs, r.programArgs)
            }
        } ?: return timedOut(sink, maxOf(r.timeoutSec, 30) * 1000L + 30_000)
        if (result.build.isNotBlank()) {
            if (result.buildFailed) sink.err(result.build.trimEnd() + "\n") else sink.info(result.build.trimEnd() + "\n")
        }
        if (result.stdout.isNotEmpty()) sink.out(result.stdout)
        if (result.stderr.isNotEmpty()) sink.err(result.stderr)
        return if (result.buildFailed && result.exitCode == null) 1 else result.exitCode
    }
}
