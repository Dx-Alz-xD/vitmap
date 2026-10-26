#!/usr/bin/env bash
# Tries template variants for the languages whose first templates failed, and checks the
# compiler-selection heuristic.
set -u
python3 - <<'EOF'
import json, re, urllib.request

UA = {"User-Agent": "Vitmap-probe/1.0", "Accept": "application/json", "Content-Type": "application/json"}
def get(url): return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))
def post(url, body): return json.load(urllib.request.urlopen(urllib.request.Request(url, data=json.dumps(body).encode(), headers=UA, method="POST"), timeout=120))

gl = {l["id"]: l for l in get("https://godbolt.org/api/languages?fields=id,name,defaultCompiler")}
gc = get("https://godbolt.org/api/compilers?fields=id,name,lang,supportsExecute")
ansi = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
ARCH = re.compile(r"\b(arm\w*|aarch64|hexagon|risc-?v\w*|rv32\w*|rv64\w*|mips\w*|power\w*|ppc\w*|s390x?|sparc\w*|msp430|avr|wasm\w*|loongarch\w*|m68k|xtensa|6502|bpf|kvx|tricore|vax|e?z80|sh4?|ia64|c6x|arc|cris|frv)\b")
UNSTABLE = re.compile(r"trunk|nightly|snapshot|assert|experimental|\bdev\b|head|latest|\(main\)|preview|beta|rc\d")
def ver(n): return tuple(int(x) for x in re.findall(r"\d+", n)[:4])

def pick(lang):
    ex = [c for c in gc if c["lang"] == lang and c.get("supportsExecute")]
    d = gl.get(lang, {}).get("defaultCompiler")
    if any(c["id"] == d for c in ex): return d
    native = [c for c in ex if not ARCH.search(c["name"].lower())] or ex
    stable = [c for c in native if not UNSTABLE.search(c["name"].lower())] or native
    return max(stable, key=lambda c: ver(c["name"]))["id"] if stable else None

def godbolt(lang, src, comp=None, stdin="Vitmap"):
    comp = comp or pick(lang)
    body = {"source": src, "compiler": comp, "lang": lang, "allowStoreCodeDebug": True,
            "options": {"userArguments": "", "executeParameters": {"args": [], "stdin": stdin},
                        "compilerOptions": {"executorRequest": True}, "filters": {"execute": True}, "tools": [], "libraries": []}}
    r = post(f"https://godbolt.org/api/compiler/{comp}/compile", body)
    out = "".join(x.get("text", "") + "\n" for x in r.get("stdout", []))
    b = r.get("buildResult", {})
    err = "".join(x.get("text", "") + "\n" for x in r.get("stderr", [])) + "".join(x.get("text", "") + "\n" for x in b.get("stderr", []))
    return comp, r.get("code"), ansi.sub("", out), ansi.sub("", err)

def wandbox(compiler, src, stdin="Vitmap"):
    r = post("https://wandbox.org/api/compile.json", {"code": src, "compiler": compiler, "stdin": stdin, "save": False})
    return compiler, r.get("status"), r.get("program_output", ""), r.get("compiler_error", "") + r.get("program_error", "")

def show(tag, res):
    comp, code, out, err = res
    print(f"--- {tag} [{comp}] code={code}\nOUT: {out[:300]!r}\nERR(head): {err[:300]!r}\nERR(tail): {err[-400:]!r}")

for lang in ["perl", "wasm", "llvm", "kotlin", "scala", "clojure", "java", "objc", "julia", "zig", "assembly", "typescript"]:
    print("PICK", lang, pick(lang))

tests = [
    ("java nonpublic godbolt", lambda: godbolt("java", 'import java.util.Scanner;\n\nclass Main {\n    public static void main(String[] args) {\n        System.out.println("Hello, World!");\n    }\n}\n')),
    ("java nonpublic wandbox", lambda: wandbox("openjdk-jdk-22+36", 'class Main {\n    public static void main(String[] args) {\n        System.out.println("Hello, World!");\n    }\n}\n')),
    ("java prog wandbox", lambda: wandbox("openjdk-jdk-22+36", 'public class prog {\n    public static void main(String[] args) {\n        System.out.println("Hello, World!");\n    }\n}\n')),
    ("kotlin simple", lambda: godbolt("kotlin", 'fun main() {\n    println("Hello, World!")\n}\n')),
    ("kotlin readln", lambda: godbolt("kotlin", 'fun main() {\n    val name = readlnOrNull()?.trim().orEmpty().ifEmpty { "World" }\n    println("Hello, $name!")\n}\n')),
    ("kotlin 1.9 readln", lambda: godbolt("kotlin", 'fun main() {\n    val name = readlnOrNull()?.trim().orEmpty().ifEmpty { "World" }\n    println("Hello, $name!")\n    println((1..5).map { it * it })\n}\n', comp="kotlinc1920")),
    ("kotlin 2.1 list", lambda: godbolt("kotlin", 'fun main() {\n    println("Hello, World!")\n    println((1..5).map { it * it })\n}\n', comp="kotlinc2120")),
    ("scala simple", lambda: godbolt("scala", 'object Main {\n  def main(args: Array[String]): Unit = {\n    println("Hello, World!")\n  }\n}\n')),
    ("scala 2.13", lambda: godbolt("scala", 'object Main {\n  def main(args: Array[String]): Unit = {\n    println("Hello, World!")\n    println((1 to 5).map(n => n * n).mkString(", "))\n  }\n}\n', comp="scalac21315")),
    ("clojure gen-class", lambda: godbolt("clojure", '(ns Main\n  (:gen-class))\n\n(defn -main [& args]\n  (println "Hello, World!")\n  (println "Squares:" (map #(* % %) (range 1 6))))\n')),
    ("clojure plain main", lambda: godbolt("clojure", '(ns main)\n\n(defn -main [& args]\n  (println "Hello, World!"))\n')),
    ("objc plain", lambda: godbolt("objc", '#include <stdio.h>\n\nint main(void) {\n    printf("Hello, World!\\n");\n    return 0;\n}\n')),
    ("perl new", lambda: godbolt("perl", 'my $name = <STDIN> // "";\nchomp $name;\nprint "Hello, ", ($name || "World"), "!\\n";\n')),
    ("wat", lambda: godbolt("wasm", '(module\n  (import "wasi_snapshot_preview1" "fd_write" (func $fd_write (param i32 i32 i32 i32) (result i32)))\n  (memory (export "memory") 1)\n  (data (i32.const 8) "Hello, World!\\n")\n  (func (export "_start")\n    (i32.store (i32.const 0) (i32.const 8))\n    (i32.store (i32.const 4) (i32.const 14))\n    (drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 32)))))\n')),
    ("llvm", lambda: godbolt("llvm", '@msg = private constant [15 x i8] c"Hello, World!\\0A\\00"\n\ndeclare i32 @printf(ptr, ...)\n\ndefine i32 @main() {\n  call i32 (ptr, ...) @printf(ptr @msg)\n  ret i32 0\n}\n')),
    ("julia wandbox", lambda: wandbox("julia-1.10.5", 'name = strip(readline())\nname = isempty(name) ? "World" : name\nprintln("Hello, $(name)!")\nprintln("Squares: ", [n^2 for n in 1:5])\n')),
    ("erlang main0", lambda: wandbox("erlang-27.1", '-module(prog).\n-export([main/0]).\n\nmain() ->\n    io:format("Hello, World!~n").\n')),
    ("erlang 26 main0", lambda: wandbox("erlang-26.2.5.3", '-module(prog).\n-export([main/0]).\n\nmain() ->\n    io:format("Hello, World!~n").\n')),
    ("elixir 1.16", lambda: wandbox("elixir-1.16.3", 'IO.puts("Hello, World!")\n')),
    ("elixir 1.17", lambda: wandbox("elixir-1.17.3", 'IO.puts("Hello, World!")\n')),
    ("pony", lambda: wandbox("pony-0.58.5", 'actor Main\n  new create(env: Env) =>\n    env.out.print("Hello, World!")\n')),
    ("zig stdout 0.13", lambda: wandbox("zig-0.13.0", 'const std = @import("std");\n\npub fn main() !void {\n    const out = std.io.getStdOut().writer();\n    try out.print("Hello, World!\\n", .{});\n}\n')),
    ("typescript godbolt", lambda: godbolt("typescript", 'function main() {\n    print("Hello World!");\n}\n')),
    ("swift godbolt stdin", lambda: godbolt("swift", 'let line = readLine() ?? ""\nprint("Hello, \\(line.isEmpty ? "World" : line)!")\n')),
]
for tag, fn in tests:
    try:
        show(tag, fn())
    except Exception as e:
        print("--- " + tag + " ERR", e)
EOF
