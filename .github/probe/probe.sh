#!/usr/bin/env bash
set -u
G=https://dl.google.com/android/maven2
mkdir -p /tmp/p && cd /tmp/p
aar() { # group/path artifact version
  curl -sS -L --max-time 60 -o "$2.aar" "$G/$1/$2/$3/$2-$3.aar" && mkdir -p "$2" && (cd "$2" && unzip -oq "../$2.aar" && ls) ; }
jp() { echo "=== javap $2"; javap -classpath "$1" "$2" 2>&1 | head -80; }
aar com/google/android/gms play-services-mlkit-text-recognition-common 19.1.0
aar com/google/android/gms play-services-mlkit-text-recognition 19.0.1
aar com/google/android/gms play-services-base 18.11.1
aar com/google/android/gms play-services-basement 18.9.0
aar com/google/android/gms play-services-tasks 18.4.1
aar com/google/android/gms play-services-mlkit-subject-segmentation 16.0.0-beta1
aar com/google/mlkit vision-common 17.3.0
for d in */; do echo "--- $d"; ls "$d"; done
CP=$(ls -d /tmp/p/*/classes.jar | tr '\n' ':')
unzip -l play-services-mlkit-text-recognition-common/classes.jar | grep -E 'mlkit/vision/text/[A-Za-z]+\.class' | head -40
jp "$CP" com.google.mlkit.vision.text.TextRecognizer
jp "$CP" com.google.mlkit.vision.text.TextRecognition
unzip -l play-services-base/classes.jar | grep -i moduleinstall | head -40
unzip -l play-services-basement/classes.jar | grep -i moduleinstall | head -40
for c in com.google.android.gms.common.moduleinstall.ModuleInstall com.google.android.gms.common.moduleinstall.ModuleInstallClient com.google.android.gms.common.moduleinstall.ModuleInstallRequest com.google.android.gms.common.moduleinstall.ModuleInstallRequest\$Builder com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate\$ProgressInfo com.google.android.gms.common.moduleinstall.InstallStatusListener com.google.android.gms.common.moduleinstall.ModuleAvailabilityResponse com.google.android.gms.common.moduleinstall.ModuleInstallResponse com.google.android.gms.common.api.OptionalModuleApi; do jp "$CP" "$c"; done
unzip -l play-services-mlkit-subject-segmentation/classes.jar | grep -E '\.class' | grep -v internal | head -30
for c in com.google.mlkit.vision.segmentation.subject.SubjectSegmentation com.google.mlkit.vision.segmentation.subject.SubjectSegmenter com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions\$Builder com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult com.google.mlkit.vision.segmentation.subject.Subject; do jp "$CP" "$c"; done
echo "=== godbolt executable compilers"
curl -sS --max-time 60 'https://godbolt.org/api/compilers?fields=id,lang,name,supportsExecute,semver' -H 'Accept: application/json' -o /tmp/gb.json
python3 - <<'PY'
import json, collections
d = json.load(open('/tmp/gb.json'))
ex = [c for c in d if c.get('supportsExecute')]
print(len(d), 'compilers,', len(ex), 'executable')
by = collections.OrderedDict()
for c in ex: by.setdefault(c['lang'], []).append((c['id'], c['name']))
for k, v in by.items(): print(k, len(v), '|', ' ; '.join(f"{i}={n}" for i, n in v[-3:]))
PY
curl -sS --max-time 60 -X POST https://godbolt.org/api/compiler/g132/compile -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{"source":"#include <cstdio>\nint main(){puts(\"hello godbolt\");}","options":{"userArguments":"-O1","executeParameters":{"args":[],"stdin":""},"compilerOptions":{"executorRequest":true},"filters":{"execute":true},"tools":[],"libraries":[]},"lang":"c++","allowStoreCodeDebug":true}' | head -c 1500; echo
curl -sS --max-time 60 https://wandbox.org/api/list.json -o /tmp/wb.json
python3 - <<'PY'
import json
d=json.load(open('/tmp/wb.json'))
for x in d: print(x['language'], '|', x['name'], '|', x['version'])
PY
