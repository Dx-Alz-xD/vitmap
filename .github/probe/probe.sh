#!/usr/bin/env bash
# Runs every cloud template from the Code Writer catalog on its backend, picking compilers the
# same way the app does, and prints a pass/fail table.
set -u
cp app/src/main/assets/code/languages.json langs.json
python3 - <<'EOF'
import json, re, urllib.request

UA = {"User-Agent": "Vitmap-probe/1.0", "Accept": "application/json", "Content-Type": "application/json"}

def get(url):
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))

def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=UA, method="POST")
    return json.load(urllib.request.urlopen(req, timeout=120))

langs = json.load(open("langs.json"))
wb = get("https://wandbox.org/api/list.json")
gl = {l["id"]: l for l in get("https://godbolt.org/api/languages?fields=id,name,defaultCompiler")}
gc = get("https://godbolt.org/api/compilers?fields=id,name,lang,supportsExecute")
ansi = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")

def wandbox_compiler(language):
    cs = [c["name"] for c in wb if c["language"] == language]
    stable = [c for c in cs if "head" not in c]
    return (stable or cs or [None])[0]

def godbolt_compiler(lang):
    ex = [c["id"] for c in gc if c["lang"] == lang and c.get("supportsExecute")]
    d = gl.get(lang, {}).get("defaultCompiler")
    return d if d in ex else (ex[-1] if ex else None)

def short(s):
    return ansi.sub("", s).replace("\n", " | ")[:220]

for l in langs:
    for backend in ("wandbox", "godbolt"):
        if backend not in l:
            continue
        try:
            if backend == "wandbox":
                comp = wandbox_compiler(l["wandbox"])
                r = post("https://wandbox.org/api/compile.json", {"code": l["template"], "compiler": comp, "stdin": "Vitmap", "save": False})
                code = r.get("status")
                out = r.get("program_output", "")
                err = (r.get("compiler_error", "") + r.get("program_error", ""))
            else:
                comp = godbolt_compiler(l["godbolt"])
                body = {"source": l["template"], "compiler": comp, "lang": l["godbolt"], "allowStoreCodeDebug": True,
                        "options": {"userArguments": "", "executeParameters": {"args": [], "stdin": "Vitmap"},
                                    "compilerOptions": {"executorRequest": True}, "filters": {"execute": True}, "tools": [], "libraries": []}}
                r = post(f"https://godbolt.org/api/compiler/{comp}/compile", body)
                code = r.get("code")
                out = "".join(x.get("text", "") + "\n" for x in r.get("stdout", []))
                b = r.get("buildResult", {})
                err = "".join(x.get("text", "") + "\n" for x in r.get("stderr", [])) + "".join(x.get("text", "") + "\n" for x in b.get("stderr", []))
            ok = "Hello" in out or "Vitmap" in out or (l["id"] in ("lazyk",) and str(code) == "0")
            print(f"{'PASS' if ok else 'FAIL'}\t{l['id']}\t{backend}\t{comp}\tcode={code}\tout={short(out)}\terr={short(err) if not ok else ''}")
        except Exception as e:
            print(f"ERR\t{l['id']}\t{backend}\t{e}")
EOF
