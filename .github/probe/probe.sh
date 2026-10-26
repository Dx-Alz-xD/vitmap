#!/usr/bin/env bash
set -u
echo "=== WANDBOX LIST"
curl -sS --max-time 60 https://wandbox.org/api/list.json -o wb.json
python3 - <<'EOF'
import json, collections
d = json.load(open("wb.json"))
print("compilers", len(d))
print("keys", sorted(d[0].keys()))
by = collections.OrderedDict()
for c in d:
    by.setdefault(c["language"], []).append(c["name"])
for lang, names in by.items():
    print(f"{lang}\t{len(names)}\t{' '.join(names[:4])}")
print("SAMPLE", json.dumps(d[0])[:1500])
EOF
echo "=== WANDBOX RUN"
curl -sS --max-time 60 -H "Content-Type: application/json" -d '{"code":"#include <iostream>\nint main(){std::string s;std::cin>>s;std::cout<<\"hi \"<<s<<std::endl;return 3;}","compiler":"gcc-head","stdin":"bob","save":false}' https://wandbox.org/api/compile.json; echo
curl -sS --max-time 60 -H "Content-Type: application/json" -d '{"code":"print(1/0)","compiler":"cpython-head","save":false}' https://wandbox.org/api/compile.json; echo
echo "=== GODBOLT LANGUAGES"
curl -sS --max-time 60 -H "Accept: application/json" "https://godbolt.org/api/languages?fields=id,name,extensions,defaultCompiler" -o gl.json
python3 -c 'import json; d=json.load(open("gl.json")); print(len(d)); print("\n".join(f"{x[\"id\"]}\t{x[\"name\"]}\t{\",\".join(x.get(\"extensions\",[]))}\t{x.get(\"defaultCompiler\")}" for x in d))'
echo "=== GODBOLT COMPILERS"
curl -sS --max-time 90 -H "Accept: application/json" "https://godbolt.org/api/compilers?fields=id,name,lang,supportsExecute,instructionSet" -o gc.json
python3 - <<'EOF'
import json, collections
d = json.load(open("gc.json"))
print("compilers", len(d), "keys", sorted(d[0].keys()))
by = collections.OrderedDict()
for c in d:
    if c.get("supportsExecute"):
        by.setdefault(c["lang"], []).append(c["id"] + "=" + c["name"])
print("languages with execution", len(by))
for lang, names in by.items():
    print(f"{lang}\t{len(names)}\t{' | '.join(names[:3])}")
EOF
echo "=== GODBOLT RUN"
cat > req.json <<'EOF'
{"source":"#include <stdio.h>\nint main(){char b[32];scanf(\"%31s\",b);printf(\"hi %s\\n\",b);fprintf(stderr,\"warn\\n\");return 3;}","compiler":"cg142","options":{"userArguments":"-O1","executeParameters":{"args":["a1"],"stdin":"bob"},"compilerOptions":{"executorRequest":true},"filters":{"execute":true},"tools":[],"libraries":[]},"lang":"c","allowStoreCodeDebug":true}
EOF
curl -sS --max-time 60 -H "Content-Type: application/json" -H "Accept: application/json" -d @req.json https://godbolt.org/api/compiler/cg142/compile | head -c 3000; echo
cat > req2.json <<'EOF'
{"source":"int main(){ return undefined_thing; }","compiler":"cg142","options":{"userArguments":"","executeParameters":{"args":[],"stdin":""},"compilerOptions":{"executorRequest":true},"filters":{"execute":true},"tools":[],"libraries":[]},"lang":"c","allowStoreCodeDebug":true}
EOF
curl -sS --max-time 60 -H "Content-Type: application/json" -H "Accept: application/json" -d @req2.json https://godbolt.org/api/compiler/cg142/compile | head -c 3000; echo
