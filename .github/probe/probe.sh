#!/usr/bin/env bash
set -u
curl -sS --max-time 60 https://wandbox.org/api/list.json -o wb.json
echo "=== WANDBOX TEMPLATES"
python3 - <<'EOF'
import json, urllib.request
d = json.load(open("wb.json"))
seen = {}
for c in d:
    for t in c.get("templates", []):
        seen.setdefault(t, (c["language"], c["name"]))
for t, (lang, comp) in seen.items():
    try:
        r = json.load(urllib.request.urlopen(f"https://wandbox.org/api/template/{t}", timeout=30))
        code = r.get("code", "")
    except Exception as e:
        code = f"ERR {e}"
    print(f"--- {lang} | {t} | {comp}")
    print(code[:900])
EOF
echo "=== GODBOLT LANGUAGES"
curl -sS --max-time 60 -H "Accept: application/json" "https://godbolt.org/api/languages?fields=id,name,extensions,defaultCompiler" -o gl.json
python3 - <<'EOF'
import json
for x in json.load(open("gl.json")):
    print(x["id"], "|", x["name"], "|", ",".join(x.get("extensions", [])), "|", x.get("defaultCompiler"))
EOF
echo "=== GODBOLT EXAMPLES"
curl -sS --max-time 60 -H "Accept: application/json" https://godbolt.org/source/builtin/list -o ex.json
python3 - <<'EOF'
import json, urllib.request
ex = json.load(open("ex.json"))
langs = {}
for e in ex:
    langs.setdefault(e["lang"], []).append(e["file"])
want = ["java", "kotlin", "csharp", "fsharp", "vb", "dart", "fortran", "cobol", "ada", "pascal", "modula2", "odin", "mojo", "vala", "carbon", "hylo", "clean", "crystal", "raku", "ocaml", "haskell", "zig", "d", "go", "swift", "objc", "clojure", "julia", "cmakescript", "wasm", "llvm", "assembly", "spice", "scala", "ruby", "perl", "python", "lua", "typescript", "android-java"]
for l in want:
    files = langs.get(l, [])
    print(f"--- {l}: {files[:8]}")
    if files:
        try:
            r = json.load(urllib.request.urlopen(urllib.request.Request(f"https://godbolt.org/source/builtin/load/{l}/{files[0]}", headers={"Accept": "application/json"}), timeout=30))
            print(r.get("file", "")[:700])
        except Exception as e:
            print("ERR", e)
EOF
