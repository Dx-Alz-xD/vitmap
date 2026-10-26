package com.vitmap.core.code

/** Thrown by an interpreter when a program is malformed or runs away. */
class EsolangException(message: String) : RuntimeException(message)

/**
 * Small interpreters for esoteric languages. Output is buffered and handed to [Io.out] in chunks;
 * [Io.cancelled] is polled so a runaway program can be stopped.
 */
object Esolangs {
    class Io(input: String, private val sink: (String) -> Unit, val cancelled: () -> Boolean = { false }, val maxSteps: Long = 2_000_000_000L) {
        private val bytes = input.toByteArray()
        private var at = 0
        private val buf = StringBuilder()
        var steps = 0L
            private set

        fun readByte(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1

        /** Reads a decimal integer, skipping leading whitespace; null at end of input. */
        fun readInt(): Long? {
            while (at < bytes.size && bytes[at].toInt().toChar().isWhitespace()) at++
            val start = at
            if (at < bytes.size && (bytes[at] == '-'.code.toByte() || bytes[at] == '+'.code.toByte())) at++
            while (at < bytes.size && bytes[at].toInt().toChar().isDigit()) at++
            val text = String(bytes, start, at - start)
            // Consume the rest of the line, as most implementations read a whole line.
            while (at < bytes.size && bytes[at] != '\n'.code.toByte()) at++
            if (at < bytes.size) at++
            return text.toLongOrNull()
        }

        fun write(s: String) {
            buf.append(s)
            if (buf.length >= 4096) flush()
        }

        fun write(c: Char) = write(c.toString())

        fun flush() {
            if (buf.isNotEmpty()) {
                sink(buf.toString())
                buf.setLength(0)
            }
        }

        fun tick() {
            steps++
            if (steps and 0xFFFF == 0L) {
                if (cancelled()) throw EsolangException("Stopped")
                flush()
            }
            if (steps > maxSteps) throw EsolangException("Stopped after $maxSteps steps; is there an endless loop?")
        }
    }

    val names = listOf("brainfuck", "ook", "befunge", "whitespace", "deadfish", "slashes")

    /** Runs [code] in language [name]; returns the exit code. */
    fun run(name: String, code: String, io: Io): Int {
        try {
            when (name) {
                "brainfuck" -> brainfuck(code, io)
                "ook" -> brainfuck(ookToBrainfuck(code), io)
                "befunge" -> befunge(code, io)
                "whitespace" -> whitespace(code, io)
                "deadfish" -> deadfish(code, io)
                "slashes" -> slashes(code, io)
                else -> throw EsolangException("Unknown language $name")
            }
        } finally {
            io.flush()
        }
        return 0
    }

    // ---------------------------------------------------------------- Brainfuck & Ook!

    fun brainfuck(code: String, io: Io) {
        val ops = code.filter { it in "+-<>[].," }
        val jump = IntArray(ops.length)
        val stack = ArrayDeque<Int>()
        for ((i, c) in ops.withIndex()) {
            if (c == '[') stack.addLast(i)
            if (c == ']') {
                val open = stack.removeLastOrNull() ?: throw EsolangException("Unmatched ] at instruction ${i + 1}")
                jump[open] = i
                jump[i] = open
            }
        }
        if (stack.isNotEmpty()) throw EsolangException("Unmatched [ at instruction ${stack.last() + 1}")
        var tape = ByteArray(30_000)
        var ptr = 0
        var pc = 0
        while (pc < ops.length) {
            io.tick()
            when (ops[pc]) {
                '+' -> tape[ptr]++
                '-' -> tape[ptr]--
                '>' -> {
                    ptr++
                    if (ptr >= tape.size) tape = tape.copyOf(tape.size * 2)
                }
                '<' -> {
                    ptr--
                    if (ptr < 0) throw EsolangException("Moved left of the first cell at instruction ${pc + 1}")
                }
                '.' -> io.write((tape[ptr].toInt() and 0xFF).toChar())
                ',' -> io.readByte().let { if (it >= 0) tape[ptr] = it.toByte() }
                '[' -> if (tape[ptr].toInt() == 0) pc = jump[pc]
                ']' -> if (tape[ptr].toInt() != 0) pc = jump[pc]
            }
            pc++
        }
    }

    private val ookPairs = mapOf(
        ".?" to '>', "?." to '<', ".." to '+', "!!" to '-', "!." to '.', ".!" to ',', "!?" to '[', "?!" to ']',
    )

    fun ookToBrainfuck(code: String): String {
        val marks = Regex("Ook([.?!])", RegexOption.IGNORE_CASE).findAll(code).map { it.groupValues[1] }.toList()
        if (marks.size % 2 != 0) throw EsolangException("Ook! words come in pairs; found an odd number")
        return buildString { for (i in marks.indices step 2) append(ookPairs[marks[i] + marks[i + 1]]) }
    }

    // ---------------------------------------------------------------- Befunge-93

    fun befunge(code: String, io: Io) {
        val w = 80
        val h = 25
        val grid = Array(h) { CharArray(w) { ' ' } }
        code.replace("\r\n", "\n").lines().take(h).forEachIndexed { y, line -> line.take(w).forEachIndexed { x, c -> grid[y][x] = c } }
        val stack = ArrayDeque<Long>()
        fun pop() = stack.removeLastOrNull() ?: 0L
        var x = 0
        var y = 0
        var dx = 1
        var dy = 0
        var string = false
        val rnd = java.util.Random()
        while (true) {
            io.tick()
            val c = grid[y][x]
            if (string) {
                if (c == '"') string = false else stack.addLast(c.code.toLong())
            } else when (c) {
                in '0'..'9' -> stack.addLast((c - '0').toLong())
                '+' -> { val b = pop(); val a = pop(); stack.addLast(a + b) }
                '-' -> { val b = pop(); val a = pop(); stack.addLast(a - b) }
                '*' -> { val b = pop(); val a = pop(); stack.addLast(a * b) }
                '/' -> { val b = pop(); val a = pop(); stack.addLast(if (b == 0L) 0 else Math.floorDiv(a, b)) }
                '%' -> { val b = pop(); val a = pop(); stack.addLast(if (b == 0L) 0 else Math.floorMod(a, b)) }
                '!' -> stack.addLast(if (pop() == 0L) 1 else 0)
                '`' -> { val b = pop(); val a = pop(); stack.addLast(if (a > b) 1 else 0) }
                '>' -> { dx = 1; dy = 0 }
                '<' -> { dx = -1; dy = 0 }
                '^' -> { dx = 0; dy = -1 }
                'v' -> { dx = 0; dy = 1 }
                '?' -> when (rnd.nextInt(4)) {
                    0 -> { dx = 1; dy = 0 }
                    1 -> { dx = -1; dy = 0 }
                    2 -> { dx = 0; dy = -1 }
                    else -> { dx = 0; dy = 1 }
                }
                '_' -> { dy = 0; dx = if (pop() == 0L) 1 else -1 }
                '|' -> { dx = 0; dy = if (pop() == 0L) 1 else -1 }
                '"' -> string = true
                ':' -> { val a = pop(); stack.addLast(a); stack.addLast(a) }
                '\\' -> { val b = pop(); val a = pop(); stack.addLast(b); stack.addLast(a) }
                '$' -> pop()
                '.' -> io.write("${pop()} ")
                ',' -> io.write(pop().toInt().toChar())
                '#' -> { x = Math.floorMod(x + dx, w); y = Math.floorMod(y + dy, h) }
                'g' -> {
                    val gy = pop().toInt(); val gx = pop().toInt()
                    stack.addLast(if (gx in 0 until w && gy in 0 until h) grid[gy][gx].code.toLong() else 0)
                }
                'p' -> {
                    val py = pop().toInt(); val px = pop().toInt(); val v = pop()
                    if (px in 0 until w && py in 0 until h) grid[py][px] = v.toInt().toChar()
                }
                '&' -> stack.addLast(io.readInt() ?: -1)
                '~' -> stack.addLast(io.readByte().toLong())
                '@' -> return
                ' ' -> {}
                else -> {}
            }
            x = Math.floorMod(x + dx, w)
            y = Math.floorMod(y + dy, h)
        }
    }

    // ---------------------------------------------------------------- Whitespace

    private sealed interface Ws {
        data class Push(val n: Long) : Ws
        data object Dup : Ws
        data class Copy(val n: Long) : Ws
        data object Swap : Ws
        data object Drop : Ws
        data class Slide(val n: Long) : Ws
        data class Arith(val op: Char) : Ws
        data object Store : Ws
        data object Load : Ws
        data class Mark(val label: String) : Ws
        data class Call(val label: String) : Ws
        data class Jump(val label: String) : Ws
        data class Jz(val label: String) : Ws
        data class Jn(val label: String) : Ws
        data object Ret : Ws
        data object End : Ws
        data object OutChar : Ws
        data object OutNum : Ws
        data object InChar : Ws
        data object InNum : Ws
    }

    private fun parseWhitespace(code: String): List<Ws> {
        val t = code.filter { it == ' ' || it == '\t' || it == '\n' }
        var i = 0
        fun next(): Char = if (i < t.length) t[i++] else throw EsolangException("Program ends in the middle of an instruction")
        fun peek2(a: Char, b: Char): Boolean = i + 1 < t.length && t[i] == a && t[i + 1] == b
        fun number(): Long {
            val sign = next()
            var n = 0L
            while (true) {
                when (next()) {
                    ' ' -> n = n * 2
                    '\t' -> n = n * 2 + 1
                    else -> break
                }
            }
            return if (sign == '\t') -n else n
        }
        fun label(): String = buildString { while (true) { val c = next(); if (c == '\n') break; append(if (c == ' ') 'S' else 'T') } }
        val out = ArrayList<Ws>()
        while (i < t.length) {
            when {
                t[i] == ' ' -> {
                    i++
                    out += when (next()) {
                        ' ' -> Ws.Push(number())
                        '\t' -> when (next()) { ' ' -> Ws.Copy(number()); '\n' -> Ws.Slide(number()); else -> throw EsolangException("Unknown stack instruction") }
                        else -> when (next()) { ' ' -> Ws.Dup; '\t' -> Ws.Swap; else -> Ws.Drop }
                    }
                }
                peek2('\t', ' ') -> {
                    i += 2
                    val a = next(); val b = next()
                    out += Ws.Arith(when ("$a$b") { "  " -> '+'; " \t" -> '-'; " \n" -> '*'; "\t " -> '/'; "\t\t" -> '%'; else -> throw EsolangException("Unknown arithmetic instruction") })
                }
                peek2('\t', '\t') -> {
                    i += 2
                    out += when (next()) { ' ' -> Ws.Store; '\t' -> Ws.Load; else -> throw EsolangException("Unknown heap instruction") }
                }
                peek2('\t', '\n') -> {
                    i += 2
                    val a = next(); val b = next()
                    out += when ("$a$b") { "  " -> Ws.OutChar; " \t" -> Ws.OutNum; "\t " -> Ws.InChar; "\t\t" -> Ws.InNum; else -> throw EsolangException("Unknown I/O instruction") }
                }
                t[i] == '\n' -> {
                    i++
                    val a = next(); val b = next()
                    out += when ("$a$b") {
                        "  " -> Ws.Mark(label())
                        " \t" -> Ws.Call(label())
                        " \n" -> Ws.Jump(label())
                        "\t " -> Ws.Jz(label())
                        "\t\t" -> Ws.Jn(label())
                        "\t\n" -> Ws.Ret
                        "\n\n" -> Ws.End
                        else -> throw EsolangException("Unknown flow instruction")
                    }
                }
                else -> throw EsolangException("Unexpected token")
            }
        }
        return out
    }

    fun whitespace(code: String, io: Io) {
        val prog = parseWhitespace(code)
        val labels = HashMap<String, Int>()
        prog.forEachIndexed { idx, ins -> if (ins is Ws.Mark) labels[ins.label] = idx }
        fun target(l: String) = labels[l] ?: throw EsolangException("Jump to an undefined label")
        val stack = ArrayList<Long>()
        val heap = HashMap<Long, Long>()
        val calls = ArrayDeque<Int>()
        fun pop(): Long = if (stack.isEmpty()) throw EsolangException("Stack is empty") else stack.removeAt(stack.lastIndex)
        var pc = 0
        while (pc < prog.size) {
            io.tick()
            when (val ins = prog[pc]) {
                is Ws.Push -> stack += ins.n
                Ws.Dup -> stack += stack.lastOrNull() ?: throw EsolangException("Stack is empty")
                is Ws.Copy -> stack += stack.getOrNull(stack.lastIndex - ins.n.toInt()) ?: throw EsolangException("Copy out of range")
                Ws.Swap -> { val b = pop(); val a = pop(); stack += b; stack += a }
                Ws.Drop -> pop()
                is Ws.Slide -> { val top = pop(); repeat(ins.n.toInt().coerceAtMost(stack.size)) { pop() }; stack += top }
                is Ws.Arith -> {
                    val b = pop(); val a = pop()
                    if ((ins.op == '/' || ins.op == '%') && b == 0L) throw EsolangException("Division by zero")
                    stack += when (ins.op) { '+' -> a + b; '-' -> a - b; '*' -> a * b; '/' -> Math.floorDiv(a, b); else -> Math.floorMod(a, b) }
                }
                Ws.Store -> { val v = pop(); val addr = pop(); heap[addr] = v }
                Ws.Load -> stack += heap[pop()] ?: 0
                is Ws.Mark -> {}
                is Ws.Call -> { calls.addLast(pc); pc = target(ins.label) }
                is Ws.Jump -> pc = target(ins.label)
                is Ws.Jz -> if (pop() == 0L) pc = target(ins.label)
                is Ws.Jn -> if (pop() < 0L) pc = target(ins.label)
                Ws.Ret -> pc = calls.removeLastOrNull() ?: throw EsolangException("Return without a call")
                Ws.End -> return
                Ws.OutChar -> io.write(pop().toInt().toChar())
                Ws.OutNum -> io.write(pop().toString())
                Ws.InChar -> heap[pop()] = io.readByte().toLong()
                Ws.InNum -> heap[pop()] = io.readInt() ?: throw EsolangException("Expected a number on input")
            }
            pc++
        }
    }

    // ---------------------------------------------------------------- Deadfish

    fun deadfish(code: String, io: Io) {
        var acc = 0L
        for (c in code) {
            io.tick()
            when (c) {
                'i', 'x' -> acc++
                'd' -> acc--
                's', 'k' -> acc *= acc
                'o', 'c' -> io.write("$acc\n")
                else -> continue
            }
            if (acc == -1L || acc == 256L) acc = 0
        }
    }

    // ---------------------------------------------------------------- /// (Slashes)

    fun slashes(code: String, io: Io) {
        val prog = StringBuilder(code)
        fun readUntilSlash(): String? {
            val sb = StringBuilder()
            while (prog.isNotEmpty()) {
                val c = prog[0]
                prog.deleteCharAt(0)
                when (c) {
                    '\\' -> if (prog.isNotEmpty()) { sb.append(prog[0]); prog.deleteCharAt(0) }
                    '/' -> return sb.toString()
                    else -> sb.append(c)
                }
            }
            return null
        }
        while (prog.isNotEmpty()) {
            io.tick()
            val c = prog[0]
            prog.deleteCharAt(0)
            when (c) {
                '\\' -> if (prog.isNotEmpty()) { io.write(prog[0]); prog.deleteCharAt(0) }
                '/' -> {
                    val pattern = readUntilSlash() ?: return
                    val replacement = readUntilSlash() ?: return
                    if (pattern.isEmpty()) {
                        // An empty pattern matches forever; that's an infinite loop by definition.
                        throw EsolangException("Empty pattern loops forever")
                    }
                    while (true) {
                        io.tick()
                        val at = prog.indexOf(pattern)
                        if (at < 0) break
                        prog.replace(at, at + pattern.length, replacement)
                        if (prog.length > 10_000_000) throw EsolangException("Program grew past 10 MB")
                    }
                }
                else -> io.write(c)
            }
        }
    }
}
