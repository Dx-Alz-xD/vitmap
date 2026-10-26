#!/usr/bin/env bash
set -u
gmaven() {
  local path="$1"
  local v
  v=$(curl -sS --max-time 20 "https://dl.google.com/android/maven2/$path/maven-metadata.xml" | grep -oE '<version>[^<]*</version>' | sed -E 's/<\/?version>//g' | tail -6 | tr '\n' ' ')
  echo "GMAVEN $path: $v"
}
for a in \
  com/google/android/gms/play-services-mlkit-text-recognition \
  com/google/android/gms/play-services-mlkit-text-recognition-chinese \
  com/google/android/gms/play-services-mlkit-text-recognition-devanagari \
  com/google/android/gms/play-services-mlkit-text-recognition-japanese \
  com/google/android/gms/play-services-mlkit-text-recognition-korean \
  com/google/android/gms/play-services-mlkit-text-recognition-common \
  com/google/android/gms/play-services-base \
  com/google/android/gms/play-services-mlkit-subject-segmentation \
  com/google/android/gms/play-services-mlkit-document-scanner \
  com/google/mlkit/segmentation-selfie \
  com/google/mlkit/translate \
  androidx/media3/media3-transformer \
  androidx/media3/media3-exoplayer \
  androidx/media3/media3-ui-compose \
  androidx/media3/media3-ui \
  androidx/media3/media3-effect \
  androidx/heifwriter/heifwriter \
  androidx/javascriptengine/javascriptengine \
  androidx/documentfile/documentfile \
  androidx/work/work-runtime-ktx \
  androidx/camera/camera-core \
  androidx/graphics/graphics-core \
  androidx/compose/compose-bom \
  androidx/webkit/webkit \
  androidx/test/ext/junit \
  androidx/test/runner \
  androidx/test/rules \
  androidx/test/uiautomator/uiautomator \
  androidx/compose/ui/ui-test-junit4 \
  androidx/test/espresso/espresso-core \
  com/android/tools/desugar_jdk_libs ; do gmaven "$a"; done

show() { echo; echo "=== $1"; curl -sS -L --max-time 30 -A "Mozilla/5.0 (Linux; Android 14) Vitmap" "$1" -o /tmp/out 2>&1; echo "status/size: $(stat -c %s /tmp/out 2>/dev/null)"; head -c "${2:-1500}" /tmp/out; echo; }
show https://emkc.org/api/v2/piston/runtimes 6000
curl -sS --max-time 30 -X POST https://emkc.org/api/v2/piston/execute -H 'content-type: application/json' -d '{"language":"python","version":"*","files":[{"content":"print(1+1)"}]}'; echo
show https://wandbox.org/api/list.json 400
curl -sS --max-time 30 https://wandbox.org/api/list.json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d)); import collections; c=collections.OrderedDict();
for x in d: c.setdefault(x["language"],[]).append(x["name"])
for k,v in c.items(): print(k, "->", ", ".join(v[:4]), "(", len(v), ")")'
curl -sS --max-time 30 -X POST https://wandbox.org/api/compile.json -H 'content-type: application/json' -d '{"compiler":"gcc-head","code":"#include <stdio.h>\nint main(){puts(\"hi\");}"}'; echo
curl -sS --max-time 30 'https://godbolt.org/api/languages?fields=id,name' -H 'Accept: application/json' | head -c 3000; echo
show https://open.er-api.com/v6/latest/USD 600
show https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies.json 300
curl -sS --max-time 30 https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies.json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d)); print(" ".join(sorted(d.keys())))'
curl -sS --max-time 30 https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["date"], len(d["usd"]))'
show https://latest.currency-api.pages.dev/v1/currencies/eur.json 200
show "https://api.frankfurter.dev/v1/2025-01-01..2025-01-10?base=USD&symbols=EUR,INR" 600
show https://api.frankfurter.dev/v1/currencies 2000
show https://cdn.jsdelivr.net/pyodide/v0.28.3/full/pyodide-lock.json 200
show https://data.jsdelivr.com/v1/packages/npm/pyodide 600
show https://data.jsdelivr.com/v1/packages/npm/@ruby/3.4-wasm-wasi 400
show https://data.jsdelivr.com/v1/packages/npm/wasmoon 300
show https://data.jsdelivr.com/v1/packages/npm/@php-wasm/web 300
show https://data.jsdelivr.com/v1/packages/npm/tm-grammars 300
show https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages 300
show https://www.youtube.com/watch?v=jNQXAC9IVRw 300
show https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest 300
