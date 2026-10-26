package com.vitmap.core.code

/** Text helpers for running code: SQL splitting, ANSI stripping and result tables. */
object CodeText {
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    fun stripAnsi(s: String): String = ansi.replace(s, "")

    /**
     * Splits an SQL script into statements on semicolons outside strings, quoted identifiers and
     * comments. CREATE TRIGGER bodies (BEGIN ... END) stay together.
     */
    fun splitSql(script: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        var depth = 0
        fun word(at: Int, w: String): Boolean {
            if (!script.regionMatches(at, w, 0, w.length, ignoreCase = true)) return false
            val before = if (at == 0) ' ' else script[at - 1]
            val after = if (at + w.length >= script.length) ' ' else script[at + w.length]
            return !before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_'
        }
        while (i < script.length) {
            val c = script[i]
            when {
                c == '\'' || c == '"' || c == '`' || c == '[' -> {
                    val close = if (c == '[') ']' else c
                    cur.append(c)
                    i++
                    while (i < script.length) {
                        cur.append(script[i])
                        if (script[i] == close) {
                            // A doubled quote is an escaped quote inside the string.
                            if (close != ']' && i + 1 < script.length && script[i + 1] == close) {
                                cur.append(script[i + 1])
                                i += 2
                                continue
                            }
                            break
                        }
                        i++
                    }
                    i++
                }
                c == '-' && i + 1 < script.length && script[i + 1] == '-' -> {
                    while (i < script.length && script[i] != '\n') i++
                }
                c == '/' && i + 1 < script.length && script[i + 1] == '*' -> {
                    val end = script.indexOf("*/", i + 2)
                    i = if (end < 0) script.length else end + 2
                    cur.append(' ')
                }
                word(i, "BEGIN") && Regex("(?is)^\\s*CREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TRIGGER").containsMatchIn(cur) -> {
                    depth++
                    cur.append(script, i, i + 5)
                    i += 5
                }
                word(i, "CASE") && depth > 0 -> {
                    depth++
                    cur.append(script, i, i + 4)
                    i += 4
                }
                word(i, "END") && depth > 0 -> {
                    depth--
                    cur.append(script, i, i + 3)
                    i += 3
                }
                c == ';' && depth == 0 -> {
                    cur.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
                    cur.setLength(0)
                    i++
                }
                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        cur.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    /** True for statements that return rows. */
    fun returnsRows(sql: String): Boolean {
        val head = sql.trimStart().take(12).uppercase()
        return head.startsWith("SELECT") || head.startsWith("WITH") || head.startsWith("PRAGMA") || head.startsWith("VALUES") || head.startsWith("EXPLAIN")
    }

    /** Draws rows as an aligned text table with a header rule. */
    fun table(columns: List<String>, rows: List<List<String?>>, maxWidth: Int = 40): String {
        fun cell(s: String?) = (s ?: "NULL").replace('\n', ' ').let { if (it.length > maxWidth) it.take(maxWidth - 1) + "…" else it }
        val widths = columns.indices.map { c -> maxOf(columns[c].length, rows.maxOfOrNull { cell(it.getOrNull(c)).length } ?: 0) }
        fun line(values: List<String>) = values.mapIndexed { i, v -> v.padEnd(widths[i]) }.joinToString(" │ ").trimEnd()
        return buildString {
            appendLine(line(columns))
            appendLine(widths.joinToString("─┼─") { "─".repeat(it) })
            for (r in rows) appendLine(line(columns.indices.map { cell(r.getOrNull(it)) }))
            append("(${rows.size} row${if (rows.size == 1) "" else "s"})")
        }
    }
}
