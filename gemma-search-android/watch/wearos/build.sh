#!/usr/bin/env bash
# Builds «Пульт кистью» for Wear OS (watch/wearos → build/watch/WristRemote-wearos.apk) the same way as the phone app:
# aapt2, javac, dx, zipalign, apksigner — no Gradle. The protocol is the phone's own code (core/WristGesture.java).
# Run ../../build.sh once first (it makes the Android 14 resource table this links against).
# usage: watch/wearos/build.sh
set -euo pipefail
cd "$(dirname "$0")"
ROOT=../..
VERSION_CODE=4
VERSION_NAME=1.3.0
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
AAPT2=${AAPT2:-$(ls /usr/lib/android-sdk/build-tools/*/aapt2 2>/dev/null | head -1)}
DX=${DX:-dalvik-exchange}
KEYSTORE=${KEYSTORE:-$HOME/.android/semsearch-debug.keystore}
FW_RES=$ROOT/build/deps/android-34-res.apk
B=$ROOT/build/watch/wearos
[ -s "$FW_RES" ] || { echo "run ./build.sh first (Android 14 resource table)" >&2; exit 1; }
[ -f "$KEYSTORE" ] || { echo "run ./build.sh first (signing key)" >&2; exit 1; }

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/apk"
"$AAPT2" compile --dir res -o "$B/res.zip"
"$AAPT2" link -o "$B/base.apk" -I "$FW_RES" --manifest AndroidManifest.xml \
  --min-sdk-version 30 --target-sdk-version 34 \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  --java "$B/gen" "$B/res.zip"
javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 -cp "$ANDROID_JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java') $ROOT/src/io/github/teoplaydor/semsearch/core/WristGesture.java
"$DX" --dex --min-sdk-version=26 --output="$B/apk/classes.dex" "$B/classes"
python3 - "$B/base.apk" "$B/unsigned.apk" "$B/apk/classes.dex" <<'PY'
import shutil, sys, zipfile
base, out, dex = sys.argv[1:]
shutil.copy(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY
zipalign -p -f 4 "$B/unsigned.apk" "$B/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/build/watch/WristRemote-wearos.apk" "$B/aligned.apk"
apksigner verify "$ROOT/build/watch/WristRemote-wearos.apk"
rm -f "$B/unsigned.apk" "$B/aligned.apk" "$ROOT/build/watch/WristRemote-wearos.apk.idsig"
ls -l "$ROOT/build/watch/WristRemote-wearos.apk"
