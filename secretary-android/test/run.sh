#!/usr/bin/env bash
# The standalone «Секретарь» on Robolectric 4.14 (Android 14), after build.sh. Robolectric and its Android runtime are
# the ones SemSearch's tests fetch (../gemma-search-android/build/robo4: run its test/robolectric4/run.sh once).
set -euo pipefail
cd "$(dirname "$0")/.."
R=../gemma-search-android/build/robo4
[ -d "$R/libs" ] && [ -s "$R/deps/android-all-instrumented-14-robolectric-10818077-i7.jar" ] || { echo "run ../gemma-search-android/test/robolectric4/run.sh first" >&2; exit 1; }
[ -f build/app.unaligned.apk ] || { echo "run build.sh first" >&2; exit 1; }
AA="$R/deps/android-all-instrumented-14-robolectric-10818077-i7.jar"
CP=$(ls "$R"/libs/*.jar | tr '\n' ':')
T=build/robo && rm -rf "$T" && mkdir -p "$T/cls/com/android/tools"
cat > "$T/cls/com/android/tools/test_config.properties" <<P
android_merged_manifest=$PWD/AndroidManifest.xml
android_merged_assets=$PWD/assets
android_resource_apk=$PWD/build/app.unaligned.apk
android_custom_package=local.secretary
P
# androidx.test is not used: SemSearch's tiny shim stands in for the few classes Robolectric touches
javac -nowarn -encoding UTF-8 -cp "/usr/lib/android-sdk/platforms/android-23/android.jar:${CP}" -d "$T/cls" \
  $(find ../gemma-search-android/test/robolectric4/shim -name '*.java')
javac -nowarn -encoding UTF-8 -cp "$T/cls:$AA:${CP}build/classes" -d "$T/cls" test/AppTest.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dhttp.nonProxyHosts=127.0.0.1 --add-opens=java.base/java.io=ALL-UNNAMED \
  -Drobolectric.offline=true -Drobolectric.dependency.dir="$R/deps" -cp "$T/cls:${CP}build/classes:$AA" org.junit.runner.JUnitCore local.secretary.AppTest
