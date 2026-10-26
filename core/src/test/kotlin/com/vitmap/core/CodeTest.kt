package com.vitmap.core

import com.vitmap.core.code.CodeText
import com.vitmap.core.code.CompilerPick
import com.vitmap.core.code.EsolangException
import com.vitmap.core.code.Esolangs
import com.vitmap.core.code.LuaRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodeTest {
    private fun run(lang: String, code: String, input: String = ""): String {
        val sb = StringBuilder()
        Esolangs.run(lang, code, Esolangs.Io(input, { sb.append(it) }))
        return sb.toString()
    }

    private val bfHello = "++++++++[>++++[>++>+++>+++>+<<<<-]>+>+>->>+[<]<-]>>.>---.+++++++..+++.>>.<-.<.+++.------.--------.>>+.>++."

    @Test
    fun brainfuckAndOok() {
        assertEquals("Hello World!\n", run("brainfuck", bfHello))
        assertEquals("cba", run("brainfuck", ",>,>,.<.<.", "abc"))
        assertFailsWith<EsolangException> { run("brainfuck", "[[]") }
        val ook = "Ook. Ook. ".repeat(65) + "Ook! Ook."
        assertEquals("A", run("ook", ook))
    }

    @Test
    fun befunge() {
        assertEquals("Hello, World!", run("befunge", "\"!dlroW ,olleH\">:#,_@"))
        // Reads a number, doubles it, prints it.
        assertEquals("42 ", run("befunge", "&2*.@", "21\n"))
    }

    @Test
    fun whitespace() {
        val s = ' '; val t = '\t'; val l = '\n'
        val pushH = "$s$s$s$t$s$s$t$s$s$s$l"
        val outChar = "$t$l$s$s"
        val end = "$l$l$l"
        // Letters are comments; spaces are not, so the words are glued to the tokens.
        assertEquals("H", run("whitespace", "push${pushH}print${outChar}end$end"))
        val add = "$s$s$s$t$s$l" + "$s$s$s$t$t$l" + "$t$s$s$s" + "$t$l$s$t" + end
        assertEquals("5", run("whitespace", add))
    }

    @Test
    fun deadfishAndSlashes() {
        assertEquals("16\n0\n", run("deadfish", "iisso dddddddddddddddddo"))
        assertEquals("barbar", run("slashes", "/foo/bar/foofoo"))
        assertEquals("bbb/", run("slashes", "/a/b/aaa\\/"))
        assertEquals("Hello, Vitmap!", run("slashes", "/World/Vitmap/Hello, World!"))
    }

    @Test
    fun runawayProgramsStop() {
        assertFailsWith<EsolangException> { Esolangs.run("brainfuck", "+[]", Esolangs.Io("", {}, maxSteps = 100_000)) }
        var calls = 0
        assertFailsWith<EsolangException> { Esolangs.run("befunge", ">v\n^<", Esolangs.Io("", {}, cancelled = { ++calls > 2 })) }
    }

    @Test
    fun splitsSql() {
        val parts = CodeText.splitSql(
            """
            CREATE TABLE t (a TEXT); -- comment; not a split
            INSERT INTO t VALUES ('it''s; fine'), ("x;y");
            /* block; comment */ SELECT * FROM t;
            CREATE TRIGGER tr AFTER INSERT ON t BEGIN UPDATE t SET a = CASE WHEN a = 'x' THEN 'y' ELSE a END; SELECT 1; END;
            SELECT 2
            """.trimIndent(),
        )
        assertEquals(5, parts.size, parts.joinToString("\n---\n"))
        assertEquals("INSERT INTO t VALUES ('it''s; fine'), (\"x;y\")", parts[1])
        assertTrue(parts[3].startsWith("CREATE TRIGGER") && parts[3].endsWith("END"))
        assertTrue(CodeText.returnsRows(parts[2]))
        assertEquals("error: x", CodeText.stripAnsi("\u001b[01;31m\u001b[Kerror: \u001b[m\u001b[Kx"))
        val table = CodeText.table(listOf("name", "n"), listOf(listOf("Earth", "1"), listOf("Jupiter", null)))
        assertTrue("Jupiter │ NULL" in table && table.endsWith("(2 rows)"), table)
    }

    @Test
    fun runsLua() {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = LuaRunner.run("local n = io.read('*l') or 'World'\nprint('Hello, ' .. n .. '!')\nio.write(1+1, '\\n')", "Lua\n", { out.append(it) }, { err.append(it) }, { false })
        assertEquals(0, code)
        assertEquals("Hello, Lua!\n2\n", out.toString())
        assertEquals(3, LuaRunner.run("print('bye') os.exit(3)", "", {}, {}, { false }))
        val e = StringBuilder()
        assertEquals(1, LuaRunner.run("error('boom')", "", {}, { e.append(it) }, { false }))
        assertTrue("boom" in e.toString(), e.toString())
        var polls = 0
        assertEquals(1, LuaRunner.run("while true do end", "", {}, {}, { ++polls > 3 }))
    }

    @Test
    fun picksCompilers() {
        val perl = listOf("perl589" to "Perl 5.8.9", "perl5400" to "Perl 5.40.0", "perl5101" to "Perl 5.10.1")
        assertEquals("perl5400", CompilerPick.godbolt(null, perl))
        assertEquals("perl589", CompilerPick.godbolt("perl589", perl))
        val ir = listOf("irclangtrunk" to "clang (trunk)", "irclang1800" to "clang 18.0.0", "irhexagonclang1605" to "Hexagon clang 16.0.5", "irclang1700" to "clang 17.0.0")
        assertEquals("irclang1800", CompilerPick.godbolt("llctrunk", ir))
        val wasm = listOf("wasmtime2001" to "Wasmtime 20.0.1", "wasmtimetrunk" to "Wasmtime (trunk)", "wasmtime2200" to "Wasmtime 22.0.0")
        // "Wasmtime" is the runtime's name, not a cross target, so every entry stays a candidate.
        assertEquals("wasmtime2200", CompilerPick.godbolt(null, wasm))
        assertEquals("gcc-13.2.0", CompilerPick.wandbox(listOf("gcc-head", "gcc-13.2.0", "gcc-12.3.0")))
    }
}
