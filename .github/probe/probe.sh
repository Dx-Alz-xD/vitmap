#!/usr/bin/env bash
# Runs every cloud template from the Code Writer catalog on its backend, choosing compilers the way
# the app does, then a few one-off checks.
set -u
cp app/src/main/assets/code/languages.json langs.json
python3 - <<'PY'
import json, re, urllib.request

UA = {"User-Agent": "Vitmap-probe/1.0", "Accept": "application/json", "Content-Type": "application/json"}
def get(url): return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))
def post(url, body): return json.load(urllib.request.urlopen(urllib.request.Request(url, data=json.dumps(body).encode(), headers=UA, method="POST"), timeout=120))

langs = json.load(open("langs.json"))
wb = get("https://wandbox.org/api/list.json")
gl = {l["id"]: l for l in get("https://godbolt.org/api/languages?fields=id,name,defaultCompiler")}
gc = get("https://godbolt.org/api/compilers?fields=id,name,lang,supportsExecute")
ansi = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
ARCH = re.compile(r"\b(arm\w*|aarch64|hexagon|risc-?v\w*|rv32\w*|rv64\w*|mips\w*|power\w*|ppc\w*|s390x?|sparc\w*|msp430|avr|wasm\w*|loongarch\w*|m68k|xtensa|6502|bpf|kvx|tricore|vax|e?z80|sh4?|ia64|c6x|arc|cris|frv)\b")
UNSTABLE = re.compile(r"trunk|nightly|snapshot|assert|experimental|\bdev\b|head|latest|\(main\)|preview|beta|rc\d")
def ver(n): return tuple(int(x) for x in re.findall(r"\d+", n)[:4])

def wandbox_compiler(language):
    cs = [c["name"] for c in wb if c["language"] == language]
    return ([c for c in cs if "head" not in c] or cs or [None])[0]

def godbolt_compiler(lang):
    ex = [c for c in gc if c["lang"] == lang and c.get("supportsExecute")]
    d = gl.get(lang, {}).get("defaultCompiler")
    if any(c["id"] == d for c in ex): return d
    native = [c for c in ex if not ARCH.search(c["name"].lower())] or ex
    stable = [c for c in native if not UNSTABLE.search(c["name"].lower())] or native
    return max(stable, key=lambda c: ver(c["name"]))["id"] if stable else None

def run_wandbox(comp, src):
    r = post("https://wandbox.org/api/compile.json", {"code": src, "compiler": comp, "stdin": "Vitmap", "save": False})
    return r.get("status"), r.get("program_output", ""), r.get("compiler_error", "") + r.get("program_error", "")

def run_godbolt(lang, comp, src):
    body = {"source": src, "compiler": comp, "lang": lang, "allowStoreCodeDebug": True,
            "options": {"userArguments": "", "executeParameters": {"args": [], "stdin": "Vitmap"},
                        "compilerOptions": {"executorRequest": True}, "filters": {"execute": True}, "tools": [], "libraries": []}}
    r = post(f"https://godbolt.org/api/compiler/{comp}/compile", body)
    out = "".join(x.get("text", "") + "\n" for x in r.get("stdout", []))
    b = r.get("buildResult", {})
    err = "".join(x.get("text", "") + "\n" for x in r.get("stderr", [])) + "".join(x.get("text", "") + "\n" for x in b.get("stderr", []))
    return r.get("code"), out, err

def short(s): return ansi.sub("", s).replace("\n", " | ")[:200]

for l in langs:
    for backend in ("wandbox", "godbolt"):
        if backend not in l: continue
        try:
            if backend == "wandbox":
                comp = wandbox_compiler(l["wandbox"]); code, out, err = run_wandbox(comp, l["template"])
            else:
                comp = godbolt_compiler(l["godbolt"]); code, out, err = run_godbolt(l["godbolt"], comp, l["template"])
            ok = str(code) == "0" and (out.strip() or l["id"] in ("cmake",))
            print(f"{'PASS' if ok else 'FAIL'}\t{l['id']}\t{backend}\t{comp}\tcode={code}\tout={short(out)}\terr={short(err) if not ok else ''}")
        except Exception as e:
            print(f"ERR\t{l['id']}\t{backend}\t{e}")

scala = 'object Main {\n  def main(args: Array[String]): Unit = {\n    println("Hello, World!")\n    println((1 to 5).map(n => n * n).mkString(", "))\n  }\n}\n'
for comp in ["scala-3.3.4", "scala-2.13.15"]:
    try: print("SCALA", comp, run_wandbox(comp, scala))
    except Exception as e: print("SCALA", comp, "ERR", e)
wat = next(l["template"] for l in langs if l["id"] == "wat")
for comp in ["wasmtime2200", "wasmtime2101", "wasmtime2001"]:
    try: print("WAT", comp, run_godbolt("wasm", comp, wat))
    except Exception as e: print("WAT", comp, "ERR", e)
PY
