#!/usr/bin/env bash
# Сборка APK без Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Сервер больше не нужен: секретарь (его логика — общий код с SemSearch в
# ../gemma-search-android/src/io/github/teoplaydor/semsearch/secretary) работает в самом приложении,
# нейросеть — Gemini напрямую с телефона, ключ вписывается в приложении («Настройка» → «Ключ Gemini»).
#
# Инструменты — в ~/.secretary-android (как раньше: sdk/build-tools/35.0.0, sdk/platforms/android-35, jdk, release.jks).
# Чего там нет, берётся из системы (aapt2, zipalign, apksigner — пакеты Ubuntu; android.jar и d8 скачиваются с Google).
# Ключ подписи — один на все версии ($T/release.jks): собранное своим ключом встаёт поверх прежней версии.
#   bash secretary-android/build.sh
set -euo pipefail
cd "$(dirname "$0")"
T="${SECRETARY_ANDROID_TOOLS:-$HOME/.secretary-android}"
mkdir -p "$T"
[ -d "$T/jdk" ] && export JAVA_HOME="$T/jdk" && export PATH="$JAVA_HOME/bin:$PATH"
CORE=../gemma-search-android/src/io/github/teoplaydor/semsearch

fetch() {
  [ -s "$2" ] && return 0
  curl -fL --retry 5 --retry-delay 5 -o "$2.tmp" "$1" && mv "$2.tmp" "$2"
}
tool() { # build-tools: свои (Windows — .bat), иначе системные
  local bt="$T/sdk/build-tools/35.0.0"
  if [ -x "$bt/$1" ]; then echo "$bt/$1"; elif [ -f "$bt/$1.bat" ]; then echo "$bt/$1.bat"; elif [ -f "$bt/$1.exe" ]; then echo "$bt/$1.exe"
  elif command -v "$1" >/dev/null; then command -v "$1"
  else ls /usr/lib/android-sdk/build-tools/*/"$1" 2>/dev/null | head -1 || true; fi
}
AAPT2=$(tool aapt2); ZIPALIGN=$(tool zipalign); APKSIGNER=$(tool apksigner); D8=$(tool d8)
[ -n "$AAPT2" ] && [ -n "$ZIPALIGN" ] && [ -n "$APKSIGNER" ] || { echo "нет aapt2/zipalign/apksigner (apt install android-sdk-build-tools zipalign apksigner)" >&2; exit 1; }
JAR="$T/sdk/platforms/android-35/android.jar"
if [ ! -f "$JAR" ]; then
  JAR="$T/android-34.jar"
  if [ ! -s "$JAR" ]; then
    fetch https://dl.google.com/android/repository/platform-34-ext7_r03.zip "$T/platform-34.zip"
    echo "1f2e9478d6a7601425ceaa553311dc43191f103d  $T/platform-34.zip" | sha1sum -c --quiet -
    python3 -c "import sys, zipfile; z = zipfile.ZipFile(sys.argv[1]); n = [x for x in z.namelist() if x.endswith('/android.jar')][0]; open(sys.argv[2], 'wb').write(z.read(n))" \
      "$T/platform-34.zip" "$JAR"
    rm -f "$T/platform-34.zip"
  fi
fi
if [ -z "$D8" ]; then # d8 — из R8 (Google Maven)
  fetch https://dl.google.com/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar "$T/r8.jar"
  D8="java -cp $T/r8.jar com.android.tools.r8.D8"
fi

VERSION_CODE=$(( $(cat version.txt 2>/dev/null || echo 0) + 1 ))
echo "$VERSION_CODE" > version.txt
VERSION_NAME="2.$VERSION_CODE"

rm -rf build && mkdir -p build/gen build/classes build/dex
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link -o build/app.unaligned.apk -I "$JAR" --manifest AndroidManifest.xml -R build/res.zip -A assets \
    --java build/gen --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --auto-add-overlay
# Логика секретаря — общая с SemSearch; без модели на телефоне (LocalLlm/LocalBrain): здесь думает Gemini.
javac --release 11 -encoding UTF-8 -Xlint:-options -nowarn -cp "$JAR" -d build/classes \
    $(find src build/gen -name '*.java') "$CORE/core/MiniJson.java" \
    $(ls "$CORE"/secretary/*.java | grep -v -e LocalLlm.java -e LocalBrain.java)
$D8 --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
python3 -c "import zipfile; z = zipfile.ZipFile('build/app.unaligned.apk', 'a'); z.write('build/dex/classes.dex', 'classes.dex'); z.close()"
"$ZIPALIGN" -f -p 4 build/app.unaligned.apk build/app.aligned.apk

KS="$T/release.jks"
if [ ! -f "$KS" ]; then
  python3 -c "import secrets; print(secrets.token_urlsafe(18))" > "$T/release.pass"
  keytool -genkeypair -keystore "$KS" -storepass "$(cat "$T/release.pass")" -alias secretary \
      -keyalg EC -groupname secp256r1 -validity 10000 -dname "CN=Secretary" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$(cat "$T/release.pass")" --out build/secretary.apk build/app.aligned.apk
"$APKSIGNER" verify build/secretary.apk
echo "Готово: secretary-android/build/secretary.apk (версия $VERSION_NAME)"
