package com.vitmap.core.code

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.CodingErrorAction

/** Forwards bytes to [sink] as text, a line (or a flush) at a time. */
class TextSink(private val sink: (String) -> Unit) : OutputStream() {
    private val buf = java.io.ByteArrayOutputStream()
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)

    override fun write(b: Int) {
        buf.write(b)
        if (b == '\n'.code || buf.size() >= 8192) flush()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        buf.write(b, off, len)
        if (buf.size() >= 8192 || (len > 0 && b[off + len - 1] == '\n'.code.toByte())) flush()
    }

    override fun flush() {
        if (buf.size() == 0) return
        sink(decoder.decode(java.nio.ByteBuffer.wrap(buf.toByteArray())).toString())
        buf.reset()
    }
}

/** Runs Lua 5.2 (LuaJ) in-process. */
object LuaRunner {
    private class Exit(val code: Int) : RuntimeException()

    fun run(code: String, stdin: String, out: (String) -> Unit, err: (String) -> Unit, cancelled: () -> Boolean, chunkName: String = "main.lua"): Int {
        val outSink = TextSink(out)
        val errSink = TextSink(err)
        val globals = JsePlatform.debugGlobals()
        globals.STDOUT = PrintStream(outSink, true, "UTF-8")
        globals.STDERR = PrintStream(errSink, true, "UTF-8")
        globals.STDIN = ByteArrayInputStream(stdin.toByteArray())
        // No reflection into the app, and os.exit must not end the whole process.
        globals.set("luajava", LuaValue.NIL)
        globals.get("os").set("exit", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val a = args.arg1()
                throw Exit(if (a.isboolean()) (if (a.toboolean()) 0 else 1) else a.optint(0))
            }
        })
        // A count hook lets a runaway loop be stopped.
        val hook = object : ZeroArgFunction() {
            override fun call(): LuaValue {
                if (cancelled()) throw LuaError("Stopped")
                return NONE
            }
        }
        globals.get("debug").get("sethook").call(hook, LuaValue.valueOf(""), LuaValue.valueOf(20_000))
        return try {
            globals.load(code, "@$chunkName").call()
            0
        } catch (e: Exit) {
            e.code
        } catch (e: LuaError) {
            (e.cause as? Exit)?.let { return it.code }
            globals.STDERR.println(e.message ?: "error")
            1
        } catch (e: StackOverflowError) {
            globals.STDERR.println("stack overflow")
            1
        } finally {
            globals.STDOUT.flush()
            globals.STDERR.flush()
        }
    }
}
