#!/usr/bin/env bash
# Builds SemSearch.apk without Gradle / Android Studio, using only:
#   Ubuntu/Debian packages: aapt2 (android-sdk-build-tools), dalvik-exchange (dx), zipalign,
#                           apksigner, android-sdk-platform-23 (android.jar), a JDK (javac/keytool)
#   Maven Central:          com.microsoft.onnxruntime:onnxruntime-android (AAR + sources)
# usage: ./build.sh            -> build/SemSearch.apk
set -euo pipefail
cd "$(dirname "$0")"

ORT_VERSION=1.30.0
VERSION_CODE=34
VERSION_NAME=0.10.9
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
AAPT2=${AAPT2:-$(ls /usr/lib/android-sdk/build-tools/*/aapt2 2>/dev/null | head -1)}
DX=${DX:-dalvik-exchange}
KEYSTORE=${KEYSTORE:-$HOME/.android/semsearch-debug.keystore}
B=build

need() { command -v "$1" >/dev/null || { echo "missing tool: $1" >&2; exit 1; }; }
need javac; need "$DX"; need zipalign; need apksigner; need python3; need curl
[ -x "$AAPT2" ] || { echo "aapt2 not found (apt install android-sdk-build-tools)" >&2; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "android.jar not found (apt install android-sdk-platform-23)" >&2; exit 1; }

rm -rf "$B/gen" "$B/classes" "$B/apk" "$B"/*.apk "$B/res.zip"
mkdir -p "$B/deps" "$B/gen" "$B/classes" "$B/apk/lib/arm64-v8a"

# --- ONNX Runtime: native libs from the AAR, Java API rebuilt from sources as Java 8 bytecode
fetch() {
  local url=$1 out=$2 i
  [ -s "$out" ] && return 0
  for i in 1 2 3 4 5; do
    if curl -fsSL -o "$out.tmp" "$url"; then mv "$out.tmp" "$out"; return 0; fi
    sleep $((i * 5))
  done
  echo "download failed: $url" >&2; exit 1
}
MC=https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VERSION
fetch "$MC/onnxruntime-android-$ORT_VERSION.aar" "$B/deps/ort.aar"
fetch "$MC/onnxruntime-android-$ORT_VERSION-sources.jar" "$B/deps/ort-sources.jar"
if [ ! -d "$B/deps/ort-classes" ]; then
  rm -rf "$B/deps/ort-src" && mkdir -p "$B/deps/ort-src" "$B/deps/ort-classes.tmp"
  (cd "$B/deps/ort-src" && python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall('.')" ../ort-sources.jar)
  python3 tools/patch_ort.py "$B/deps/ort-src"
  javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 -d "$B/deps/ort-classes.tmp" \
    $(find "$B/deps/ort-src" -name '*.java')
  mv "$B/deps/ort-classes.tmp" "$B/deps/ort-classes"
fi
python3 - "$B/deps/ort.aar" "$B/apk/lib/arm64-v8a" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
for n in ("libonnxruntime.so", "libonnxruntime4j_jni.so"):
    open(f"{sys.argv[2]}/{n}", "wb").write(z.read(f"jni/arm64-v8a/{n}"))
PY

# --- resources + manifest
# The manifest uses Android 14 attributes (foreground service type): link it against the Android 14
# framework resource table, taken from Robolectric's android-all on Maven Central (cached, 4 MB kept).
FW_RES="$B/deps/android-34-res.apk"
if [ ! -s "$FW_RES" ]; then
  AA=build/robo4/deps/android-all-instrumented-14-robolectric-10818077-i7.jar
  if [ ! -s "$AA" ]; then
    AA="$B/deps/android-all-14.jar"
    fetch "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/14-robolectric-10818077-i7/android-all-instrumented-14-robolectric-10818077-i7.jar" "$AA"
  fi
  python3 - "$AA" "$FW_RES" <<'PY2'
import sys, zipfile
src, out = zipfile.ZipFile(sys.argv[1]), zipfile.ZipFile(sys.argv[2] + ".tmp", "w", zipfile.ZIP_DEFLATED)
for n in ("AndroidManifest.xml", "resources.arsc"):
    out.writestr(n, src.read(n))
out.close()
PY2
  mv "$FW_RES.tmp" "$FW_RES"
  rm -f "$B/deps/android-all-14.jar"
fi
"$AAPT2" compile --dir res -o "$B/res.zip"
"$AAPT2" link -o "$B/base.apk" -I "$FW_RES" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  -A assets --java "$B/gen" "$B/res.zip"

# --- Java -> dex (no lambdas / indy string concat so legacy dx can handle it).
# java.* comes from the JDK's Java 8 API (minSdk 26 has it, e.g. java.util.Optional used by ORT);
# android.* from the platform jar.
javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 \
  -cp "$ANDROID_JAR:$B/deps/ort-classes" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$DX" --dex --min-sdk-version=26 --output="$B/apk/classes.dex" "$B/classes" "$B/deps/ort-classes"

# --- assemble, align, sign
python3 - "$B/base.apk" "$B/unsigned.apk" "$B/apk" <<'PY'
import os, shutil, sys, zipfile
base, out, extra = sys.argv[1:]
shutil.copy(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(extra):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, extra))
PY
zipalign -p -f 4 "$B/unsigned.apk" "$B/aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias semsearch \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=SemSearch prototype" >/dev/null
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$B/SemSearch.apk" "$B/aligned.apk"
apksigner verify "$B/SemSearch.apk"
mv "$B/base.apk" "$B/resources.ap_" # resources-only APK, used by the Robolectric UI tests
rm -f "$B/unsigned.apk" "$B/aligned.apk" "$B/SemSearch.apk.idsig"
ls -l "$B/SemSearch.apk"
