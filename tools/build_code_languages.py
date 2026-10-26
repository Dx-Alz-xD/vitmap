#!/usr/bin/env python3
"""Builds app/src/main/assets/code/languages.json, the Code Writer's language catalog.

Each language says how it is highlighted (a tm-grammars name), how it runs (a built-in runner, a
downloadable web runtime, Wandbox or Compiler Explorer) and what a new file starts with. Languages
the cloud services add later appear in the app automatically; this list gives the common ones
proper names, templates and offline runners.
"""
import json
import os

BF_HELLO = "++++++++[>++++[>++>+++>+++>+<<<<-]>+>+>->>+[<]<-]>>.>---.+++++++..+++.>>.<-.<.+++.------.--------.>>+.>++."
OOK = {">": "Ook. Ook?", "<": "Ook? Ook.", "+": "Ook. Ook.", "-": "Ook! Ook!", ".": "Ook! Ook.", ",": "Ook. Ook!", "[": "Ook! Ook?", "]": "Ook? Ook!"}


def ook(bf):
    words = [OOK[c] for c in bf if c in OOK]
    return "\n".join(" ".join(words[i:i + 8]) for i in range(0, len(words), 8)) + "\n"


def ws_number(n):
    bits = bin(abs(n))[2:]
    return ("\t" if n < 0 else " ") + "".join("\t" if b == "1" else " " for b in bits) + "\n"


def whitespace(text):
    # Visible words are comments in Whitespace, so each line says what it does.
    out = []
    for ch in text:
        out.append("push" + "  " + ws_number(ord(ch)))
        out.append("print" + "\t\n  ")
    out.append("end" + "\n\n\n")
    return "".join(out)


L = []


def lang(id, name, ext, grammar=None, comment="//", category="general", template="", local=None, web=None, wandbox=None, godbolt=None, compiler=None, preview=None, data=None, stdin=False, aliases=()):
    L.append({k: v for k, v in dict(
        id=id, name=name, ext=ext, grammar=grammar, comment=comment, category=category, template=template.lstrip("\n"),
        local=local, web=web, wandbox=wandbox, godbolt=godbolt, compiler=compiler, preview=preview, data=data, stdin=stdin or None, aliases=list(aliases) or None,
    ).items() if v is not None})


# ------------------------------------------------------------------ runs on the phone

lang("javascript", "JavaScript", "js", "javascript", category="popular", local="quickjs", web="js", wandbox="JavaScript", aliases=["node", "es"], template="""
// Runs offline with QuickJS (or in a browser engine / Node.js in the cloud).
const greet = (name) => `Hello, ${name}!`;
console.log(greet("World"));

const squares = [1, 2, 3, 4, 5].map((n) => n * n);
console.log("Squares:", squares.join(", "));
""")

lang("python", "Python", "py", "python", "#", "popular", local="python", wandbox="Python", godbolt="python", stdin=True, aliases=["py3"], template="""
import sys

name = sys.stdin.readline().strip() or "World"
print(f"Hello, {name}!")
print("Squares:", [n * n for n in range(1, 6)])
""")

lang("bash", "Shell script", "sh", "shellscript", "#", "scripting", local="shell", wandbox="Bash script", stdin=True, aliases=["sh", "bash", "zsh"], template="""
read -r name
echo "Hello, ${name:-World}!"
for i in 1 2 3 4 5; do
  printf '%d squared is %d\\n' "$i" $((i * i))
done
""")

lang("sql", "SQL (SQLite)", "sql", "sql", "--", "data", local="sqlite", wandbox="SQL", template="""
CREATE TABLE planets (name TEXT, moons INTEGER, gas_giant INTEGER);
INSERT INTO planets VALUES ('Earth', 1, 0), ('Mars', 2, 0), ('Jupiter', 95, 1), ('Saturn', 146, 1);

SELECT name, moons FROM planets WHERE gas_giant = 1 ORDER BY moons DESC;
SELECT COUNT(*) AS planets, SUM(moons) AS total_moons FROM planets;
""")

lang("lua", "Lua", "lua", "lua", "--", "scripting", local="lua", wandbox="Lua", godbolt="lua", stdin=True, template="""
local name = io.read("*l") or ""
if name == "" then name = "World" end
print("Hello, " .. name .. "!")

local squares = {}
for i = 1, 5 do squares[#squares + 1] = i * i end
print("Squares: " .. table.concat(squares, ", "))
""")

# Esoteric languages with built-in interpreters
lang("brainfuck", "Brainfuck", "bf", None, "", "esoteric", local="esolang:brainfuck", stdin=True, template=BF_HELLO + "\n")
lang("ook", "Ook!", "ook", None, "", "esoteric", local="esolang:ook", template=ook(BF_HELLO))
lang("befunge", "Befunge-93", "bf93", None, "", "esoteric", local="esolang:befunge", aliases=["b93"], template='"!dlroW ,olleH">:#,_@\n')
lang("whitespace", "Whitespace", "ws", None, "", "esoteric", local="esolang:whitespace", template=whitespace("Hello, World!\n"))
lang("deadfish", "Deadfish", "df", None, "", "esoteric", local="esolang:deadfish", template="iisso\niiiiiiiiio\ndddddddddddddddo\n")
lang("slashes", "/// (Slashes)", "slashes", None, "", "esoteric", local="esolang:slashes", template="/World/Vitmap/Hello, World!\n")

# Checked and pretty-printed on the phone
lang("json", "JSON", "json", "json", "", "data", data="json", template='{\n  "name": "Vitmap",\n  "tools": ["chess", "scanner", "code"],\n  "offline": true\n}\n')
lang("yaml", "YAML", "yaml", "yaml", "#", "data", data="yaml", aliases=["yml"], template="name: Vitmap\ntools:\n  - chess\n  - scanner\n  - code\noffline: true\n")
lang("xml", "XML", "xml", "xml", "", "data", data="xml", template='<?xml version="1.0" encoding="UTF-8"?>\n<app name="Vitmap">\n  <tool>chess</tool>\n  <tool>scanner</tool>\n</app>\n')
lang("csv", "CSV", "csv", "csv", "", "data", data="csv", template="name,moons\nEarth,1\nMars,2\nJupiter,95\n")
lang("toml", "TOML", "toml", "toml", "#", "data", template='[app]\nname = "Vitmap"\ntools = ["chess", "scanner"]\n')

# Rendered previews
lang("html", "HTML", "html", "html", "", "web", preview="html", aliases=["htm"], template="""
<!doctype html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <style>
    body { font-family: system-ui, sans-serif; display: grid; place-items: center; min-height: 90vh; background: linear-gradient(135deg, #6366f1, #ec4899); color: white; }
    button { font-size: 1.2rem; padding: .6em 1.2em; border: 0; border-radius: 999px; }
  </style>
</head>
<body>
  <div>
    <h1 id="title">Hello, World!</h1>
    <button onclick="count()">Clicked 0 times</button>
  </div>
  <script>
    let n = 0;
    function count() { document.querySelector("button").textContent = `Clicked ${++n} times`; }
  </script>
</body>
</html>
""")
lang("markdown", "Markdown", "md", "markdown", "", "web", preview="markdown", template="# Hello, World!\n\nWrite **Markdown** here and press *Run* to preview it.\n\n- Lists\n- `code`\n- [Links](https://example.com)\n")
lang("svg", "SVG", "svg", "xml", "", "web", preview="svg", template='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">\n  <defs>\n    <linearGradient id="g" x1="0" x2="1"><stop offset="0" stop-color="#6366f1"/><stop offset="1" stop-color="#ec4899"/></linearGradient>\n  </defs>\n  <circle cx="100" cy="100" r="80" fill="url(#g)"/>\n  <text x="100" y="108" text-anchor="middle" font-size="22" fill="white" font-family="sans-serif">Hello!</text>\n</svg>\n')
lang("text", "Plain text", "txt", None, "", "other", aliases=["text", "log"], template="")
lang("css", "CSS", "css", "css", "", "web", template="body {\n  font-family: system-ui, sans-serif;\n  color: #1f2937;\n}\n")

# Downloadable in-browser runtimes
lang("typescript", "TypeScript", "ts", "typescript", category="popular", web="ts", wandbox="TypeScript", template="""
interface Planet {
  name: string;
  moons: number;
}

const planets: Planet[] = [
  { name: "Earth", moons: 1 },
  { name: "Mars", moons: 2 },
  { name: "Jupiter", moons: 95 },
];

const total = planets.reduce((sum, p) => sum + p.moons, 0);
for (const p of planets) console.log(`${p.name} has ${p.moons} moon${p.moons === 1 ? "" : "s"}`);
console.log(`Total: ${total}`);
""")
lang("coffeescript", "CoffeeScript", "coffee", "coffee", "#", "web", web="coffee", template="""
greet = (name = "World") -> "Hello, #{name}!"
console.log greet()
squares = (n * n for n in [1..5])
console.log "Squares: #{squares.join ', '}"
""")
lang("scheme", "Scheme", "scm", "scheme", ";", "functional", web="scheme", template="""
(define (square x) (* x x))
(display "Hello, World!")
(newline)
(display (map square '(1 2 3 4 5)))
(newline)
""")
lang("prolog", "Prolog", "pl", "prolog", "%", "functional", web="prolog", aliases=["pro"], template="""
parent(tom, bob).
parent(bob, ann).
parent(bob, pat).
grandparent(X, Z) :- parent(X, Y), parent(Y, Z).

main :- write('Hello, World!'), nl,
    forall(grandparent(tom, G), (write(G), nl)).

:- initialization(main).
""")
lang("ruby", "Ruby", "rb", "ruby", "#", "scripting", web="ruby", wandbox="Ruby", godbolt="ruby", stdin=True, template="""
name = ($stdin.gets || "").strip
name = "World" if name.empty?
puts "Hello, #{name}!"
puts (1..5).map { |n| n * n }.inspect
""")
lang("clojure", "Clojure", "clj", "clojure", ";", "functional", web="clojure", aliases=["cljs"], template="""
(ns example)

(defn square [x] (* x x))

(println "Hello, World!")
(println "Squares:" (map square (range 1 6)))
""")

# ------------------------------------------------------------------ cloud compilers

lang("c", "C", "c", "c", category="popular", wandbox="C", godbolt="c", stdin=True, aliases=["h"], template="""
#include <stdio.h>

int main(void) {
    char name[64] = "World";
    if (scanf("%63s", name) != 1) name[0] = 'W';
    printf("Hello, %s!\\n", name);
    for (int i = 1; i <= 5; i++) printf("%d squared is %d\\n", i, i * i);
    return 0;
}
""")
lang("cpp", "C++", "cpp", "cpp", category="popular", wandbox="C++", godbolt="c++", stdin=True, aliases=["cc", "cxx", "hpp", "hh"], template="""
#include <iostream>
#include <numeric>
#include <string>
#include <vector>

int main() {
    std::string name;
    if (!(std::cin >> name)) name = "World";
    std::cout << "Hello, " << name << "!\\n";

    std::vector<int> v{5, 3, 8, 1};
    std::cout << "Sum: " << std::accumulate(v.begin(), v.end(), 0) << '\\n';
}
""")
lang("csharp", "C#", "cs", "csharp", category="popular", wandbox="C#", godbolt="csharp", stdin=True, template="""
using System;
using System.Linq;

class Program
{
    static void Main()
    {
        var name = Console.ReadLine();
        if (string.IsNullOrWhiteSpace(name)) name = "World";
        Console.WriteLine($"Hello, {name}!");
        Console.WriteLine("Squares: " + string.Join(", ", Enumerable.Range(1, 5).Select(n => n * n)));
    }
}
""")
lang("java", "Java", "java", "java", category="popular", godbolt="java", wandbox="Java", stdin=True, template="""
import java.util.Scanner;

// Not "public": each service saves the file under its own name.
class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        String name = in.hasNextLine() ? in.nextLine().trim() : "";
        if (name.isEmpty()) name = "World";
        System.out.println("Hello, " + name + "!");
        for (int i = 1; i <= 5; i++) System.out.println(i + " squared is " + i * i);
    }
}
""")
lang("kotlin", "Kotlin", "kt", "kotlin", category="popular", godbolt="kotlin", aliases=["kts"], template="""
fun square(n: Int) = n * n

fun main() {
    println("Hello, World!")
    for (i in 1..5) println("$i squared is ${square(i)}")
}
""")
lang("go", "Go", "go", "go", category="popular", wandbox="Go", godbolt="go", stdin=True, template="""
package main

import (
\t"bufio"
\t"fmt"
\t"os"
\t"strings"
)

func main() {
\tname, _ := bufio.NewReader(os.Stdin).ReadString('\\n')
\tname = strings.TrimSpace(name)
\tif name == "" {
\t\tname = "World"
\t}
\tfmt.Printf("Hello, %s!\\n", name)
\tfor i := 1; i <= 5; i++ {
\t\tfmt.Printf("%d squared is %d\\n", i, i*i)
\t}
}
""")
lang("rust", "Rust", "rs", "rust", category="popular", wandbox="Rust", godbolt="rust", stdin=True, template="""
use std::io::{self, Read};

fn main() {
    let mut input = String::new();
    io::stdin().read_to_string(&mut input).ok();
    let name = match input.trim() {
        "" => "World",
        n => n,
    };
    println!("Hello, {}!", name);
    let squares: Vec<u32> = (1..=5).map(|n| n * n).collect();
    println!("Squares: {:?}", squares);
}
""")
lang("swift", "Swift", "swift", "swift", category="popular", godbolt="swift", stdin=True, template="""
let line = readLine() ?? ""
let name = line.isEmpty ? "World" : line
print("Hello, \\(name)!")
print("Squares:", (1...5).map { $0 * $0 })
""")
lang("php", "PHP", "php", "php", category="web", wandbox="PHP", stdin=True, template="""
<?php
$name = trim(fgets(STDIN) ?: "");
if ($name === "") $name = "World";
echo "Hello, $name!\\n";
echo "Squares: " . implode(", ", array_map(fn($n) => $n * $n, range(1, 5))) . "\\n";
""")
lang("perl", "Perl", "pl", "perl", "#", "scripting", wandbox="Perl", godbolt="perl", stdin=True, aliases=["pm"], template="""
use strict;
use warnings;

my $name = <STDIN> // "";
chomp $name;
$name = "World" if $name eq "";
print "Hello, $name!\\n";
print "Squares: ", join(", ", map { $_ * $_ } 1 .. 5), "\\n";
""")
lang("raku", "Raku", "raku", "raku", "#", "scripting", godbolt="raku", aliases=["p6"], template="""
my $name = 'World';
say "Hello, $name!";
say 'Squares: ', (1..5).map(* ** 2).join(', ');
""")
lang("r", "R", "r", "r", "#", "scientific", wandbox="R", template="""
values <- c(4, 8, 15, 16, 23, 42)
cat("Hello, World!\\n")
cat("Mean:", mean(values), " Median:", median(values), "\\n")
print(summary(values))
""")
lang("julia", "Julia", "jl", "julia", "#", "scientific", wandbox="Julia", stdin=True, template="""
name = strip(readline())
name = isempty(name) ? "World" : name
println("Hello, $(name)!")
println("Squares: ", [n^2 for n in 1:5])
""")
lang("dart", "Dart", "dart", "dart", category="general", godbolt="dart", stdin=True, template="""
import 'dart:io';

void main() {
  final name = stdin.readLineSync()?.trim() ?? '';
  print('Hello, ${name.isEmpty ? 'World' : name}!');
  print('Squares: ${[for (var n = 1; n <= 5; n++) n * n]}');
}
""")
lang("scala", "Scala", "scala", "scala", category="jvm", wandbox="Scala", godbolt="scala", template="""
object Main {
  def main(args: Array[String]): Unit = {
    println("Hello, World!")
    println("Squares: " + (1 to 5).map(n => n * n).mkString(", "))
  }
}
""")
lang("groovy", "Groovy", "groovy", "groovy", category="jvm", wandbox="Groovy", template="""
def greet(name) { "Hello, ${name}!" }
println greet("World")
println "Squares: ${(1..5).collect { it * it }}"
""")
lang("haskell", "Haskell", "hs", "haskell", "--", "functional", wandbox="Haskell", godbolt="haskell", stdin=True, template="""
main :: IO ()
main = do
  input <- getContents
  let name = case lines input of
        (l : _) | not (null l) -> l
        _ -> "World"
  putStrLn ("Hello, " ++ name ++ "!")
  print (map (^ 2) [1 .. 5 :: Int])
""")
lang("elixir", "Elixir", "ex", "elixir", "#", "functional", aliases=["exs"], template="""
defmodule Hello do
  def square(n), do: n * n
end

IO.puts("Hello, World!")
IO.inspect(Enum.map(1..5, &Hello.square/1), label: "Squares")
""")
lang("erlang", "Erlang", "erl", "erlang", "%", "functional", template="""
-module(prog).
-export([main/1]).

main(_) ->
    io:format("Hello, World!~n"),
    io:format("Squares: ~p~n", [[N * N || N <- lists:seq(1, 5)]]).
""")
lang("fsharp", "F#", "fs", "fsharp", category="dotnet", godbolt="fsharp", template="""
module Program

let square x = x * x

[<EntryPoint>]
let main _ =
    printfn "Hello, World!"
    printfn "Squares: %A" (List.map square [1..5])
    0
""")
lang("vbnet", "Visual Basic .NET", "vb", "vb", "'", "dotnet", godbolt="vb", template="""
Imports System

Module Program
    Sub Main()
        Console.WriteLine("Hello, World!")
        For i As Integer = 1 To 5
            Console.WriteLine($"{i} squared is {i * i}")
        Next
    End Sub
End Module
""")
lang("ocaml", "OCaml", "ml", "ocaml", "", "functional", godbolt="ocaml", template="""
let square x = x * x

let () =
  print_endline "Hello, World!";
  List.iter (fun n -> Printf.printf "%d " (square n)) [1; 2; 3; 4; 5];
  print_newline ()
""")
lang("zig", "Zig", "zig", "zig", category="systems", wandbox="Zig", godbolt="zig", template="""
const std = @import("std");

pub fn main() !void {
    const out = std.io.getStdOut().writer();
    try out.print("Hello, World!\\n", .{});
    var i: u32 = 1;
    while (i <= 5) : (i += 1) {
        try out.print("{d} squared is {d}\\n", .{ i, i * i });
    }
}
""")
lang("nim", "Nim", "nim", "nim", "#", "systems", wandbox="Nim", template="""
proc square(x: int): int = x * x

echo "Hello, World!"
for i in 1..5:
  echo i, " squared is ", square(i)
""")
lang("crystal", "Crystal", "cr", "crystal", "#", "systems", godbolt="crystal", template="""
def square(x)
  x * x
end

puts "Hello, World!"
puts "Squares: #{(1..5).map { |n| square(n) }}"
""")
lang("d", "D", "d", "d", category="systems", wandbox="D", godbolt="d", template="""
import std.stdio;
import std.algorithm : map;
import std.range : iota;

void main()
{
    writeln("Hello, World!");
    writeln("Squares: ", iota(1, 6).map!(n => n * n));
}
""")
lang("fortran", "Fortran", "f90", "fortran-free-form", "!", "scientific", godbolt="fortran", aliases=["f95", "f03", "f08", "f"], template="""
program hello
  implicit none
  integer :: i
  print *, 'Hello, World!'
  do i = 1, 5
    print '(I0, A, I0)', i, ' squared is ', i * i
  end do
end program hello
""")
lang("cobol", "COBOL", "cob", "cobol", "*>", "legacy", godbolt="cobol", aliases=["cbl"], template="""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. HELLO.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 I PIC 9 VALUE 1.
       PROCEDURE DIVISION.
           DISPLAY "Hello, World!".
           PERFORM UNTIL I > 5
               DISPLAY "Line " I
               ADD 1 TO I
           END-PERFORM.
           STOP RUN.
""")
lang("ada", "Ada", "adb", "ada", "--", "legacy", godbolt="ada", aliases=["ads"], template="""
with Ada.Text_IO; use Ada.Text_IO;

procedure Example is
begin
   Put_Line ("Hello, World!");
   for I in 1 .. 5 loop
      Put_Line (Integer'Image (I) & " squared is" & Integer'Image (I * I));
   end loop;
end Example;
""")
lang("pascal", "Pascal", "pas", "pascal", "//", "legacy", wandbox="Pascal", godbolt="pascal", aliases=["pp", "dpr"], template="""
program Hello;
var
  i: Integer;
begin
  WriteLn('Hello, World!');
  for i := 1 to 5 do
    WriteLn(i, ' squared is ', i * i);
end.
""")
lang("modula2", "Modula-2", "mod", None, "", "legacy", godbolt="modula2", template="""
MODULE Hello;

FROM InOut IMPORT WriteString, WriteLn;

BEGIN
  WriteString('Hello, World!');
  WriteLn;
END Hello.
""")
lang("objc", "Objective-C", "m", "objective-c", category="systems", godbolt="objc", template="""
#include <stdio.h>

// Objective-C is a superset of C; GCC's runtime has no Foundation, so this sticks to C I/O.
int main(void) {
    printf("Hello, World!\\n");
    for (int i = 1; i <= 5; i++) printf("%d squared is %d\\n", i, i * i);
    return 0;
}
""")
lang("commonlisp", "Common Lisp", "lisp", "common-lisp", ";", "functional", wandbox="Lisp", aliases=["cl", "lsp"], template="""
(defun square (x) (* x x))

(format t "Hello, World!~%")
(format t "Squares: ~a~%" (mapcar #'square '(1 2 3 4 5)))
""")
lang("mojo", "Mojo", "mojo", "mojo", "#", "systems", godbolt="mojo", template="""
def main():
    print("Hello, World!")
    for i in range(1, 6):
        print(i, "squared is", i * i)
""")
lang("odin", "Odin", "odin", "odin", category="systems", godbolt="odin", template="""
package main

import "core:fmt"

main :: proc() {
\tfmt.println("Hello, World!")
\tfor i in 1..=5 {
\t\tfmt.printf("%d squared is %d\\n", i, i * i)
\t}
}
""")
lang("vala", "Vala", "vala", "vala", category="systems", godbolt="vala", template="""
int main () {
    print ("Hello, World!\\n");
    for (int i = 1; i <= 5; i++) {
        print ("%d squared is %d\\n", i, i * i);
    }
    return 0;
}
""")
lang("hylo", "Hylo", "hylo", None, category="systems", godbolt="hylo", template="""
public fun main() {
  print("Hello, World!")
}
""")
lang("pony", "Pony", "pony", None, category="systems", template="""
actor Main
  new create(env: Env) =>
    env.out.print("Hello, World!")
""")
lang("viml", "Vim script", "vim", "viml", '"', "scripting", template="""
let s:names = ['World', 'Vim']
for s:n in s:names
  echo 'Hello, ' . s:n . '!'
endfor
""")
lang("cmake", "CMake script", "cmake", "cmake", "#", "scripting", godbolt="cmakescript", template="""
set(planets Mercury Venus Earth Mars)
list(LENGTH planets count)
message("Hello, World!")
message("There are ${count} inner planets: ${planets}")
""")
lang("wat", "WebAssembly (WAT)", "wat", "wasm", ";;", "systems", godbolt="wasm", aliases=["wast"], template="""
(module
  (import "wasi_snapshot_preview1" "fd_write" (func $fd_write (param i32 i32 i32 i32) (result i32)))
  (memory (export "memory") 1)
  (data (i32.const 8) "Hello, World!\\n")
  (func (export "_start")
    (i32.store (i32.const 0) (i32.const 8))
    (i32.store (i32.const 4) (i32.const 14))
    (drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 32)))))
""")
lang("llvmir", "LLVM IR", "ll", "llvm", ";", "systems", godbolt="llvm", template="""
@msg = private constant [15 x i8] c"Hello, World!\\0A\\00"

declare i32 @printf(ptr, ...)

define i32 @main() {
  call i32 (ptr, ...) @printf(ptr @msg)
  ret i32 0
}
""")
lang("asm", "Assembly (x86-64)", "asm", "asm", ";", "systems", godbolt="assembly", aliases=["s", "nasm"], template="""
; NASM, Linux x86-64
        global  _start
        section .text
_start: mov     rax, 1          ; write
        mov     rdi, 1          ; stdout
        mov     rsi, msg
        mov     rdx, len
        syscall
        mov     rax, 60         ; exit
        xor     rdi, rdi
        syscall

        section .data
msg:    db      "Hello, World!", 10
len:    equ     $ - msg
""")
lang("cuda", "CUDA C++", "cu", "cpp", category="scientific", godbolt="cuda", template="""
#include <cstdio>

__global__ void hello() { printf("Hello from GPU thread %d!\\n", threadIdx.x); }

int main() {
    hello<<<1, 4>>>();
    cudaDeviceSynchronize();
    printf("Hello, World!\\n");
}
""")
lang("clean", "Clean", "icl", None, "//", "functional", template="""
module example

import StdEnv

Start = "Hello, World!"
""")

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "code", "languages.json")

if __name__ == "__main__":
    ids = [l["id"] for l in L]
    assert len(ids) == len(set(ids)), "duplicate ids"
    with open(OUT, "w") as f:
        json.dump(L, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(len(L), "languages ->", os.path.normpath(OUT))
