#!/usr/bin/env bash
# App tests on Robolectric 4.14 (Android 14 runtime) with native graphics, after ./build.sh:
#   UiShots         — renders the main screens to build/shots/*.png (real fonts, Skia)
#   AppFlowTest     — gallery, search, viewer, settings and notes driven through the real Activity
#   HiddenTest      — hiding 18+: gallery, search, albums, the hidden folder, hiding and showing by hand
#   PeopleAppTest   — faces looked for after indexing, people named and corrected, the unnamed, pets by examples
#   NavTest         — «Назад» in the order things were opened: viewer, search, albums, settings
#   AppIndexingTest — MediaStore → decode → embed → SQLite with a stand-in model, Russian bridge
#   IndexStopTest   — a broken model (the same error file after file, the NPU process gone) stops the run, files unmarked
#   AutoIndexTest   — background jobs: content triggers on MediaStore, the periodic safety net
#   IdleIndexTest   — indexing while the phone rests: screen off/on, battery, wake lock, self-stop
#   SpeedupsTest    — fp16 / LiteRT-LM / QNN: crash guard, fallbacks, download offer, move to LiteRT-LM and
#                     back, the NPU process with a real ONNX Runtime
# Needs JDK 21 and Maven. androidx.test (Google Maven) is not used: a tiny API shim in shim/
# stands in for the few classes Robolectric touches.
# usage: test/robolectric4/run.sh [TestClass...]
set -euo pipefail
cd "$(dirname "$0")/../.."
J21=${J21:-/usr/lib/jvm/java-21-openjdk-amd64/bin}
R=build/robo4
T=test/robolectric4
mkdir -p "$R/deps" "$R/cls"
[ -f build/resources.ap_ ] && [ -d build/classes ] || { echo "run ./build.sh first" >&2; exit 1; }

if [ ! -d "$R/libs" ]; then
  cat > "$R/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>t</groupId><artifactId>robo4</artifactId><version>1</version>
  <dependencies>
    <dependency><groupId>org.robolectric</groupId><artifactId>robolectric</artifactId><version>4.14.1</version>
      <exclusions>
        <exclusion><groupId>androidx.test</groupId><artifactId>*</artifactId></exclusion>
        <exclusion><groupId>androidx.test.espresso</groupId><artifactId>*</artifactId></exclusion>
      </exclusions>
    </dependency>
    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
  </dependencies>
</project>
POM
  (cd "$R" && mvn -q -B -Daether.connector.http.retryHandler.count=8 \
    -Daether.connector.http.retryHandler.serviceUnavailable=429,503 dependency:copy-dependencies -DoutputDirectory=libs.tmp \
    && mv libs.tmp libs)
fi
# Android runtimes Robolectric loads at test time (offline mode): 14 runs the tests, 15 is its resource table.
MC=https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented
for v in 14-robolectric-10818077-i7 15-robolectric-12650502-i7; do
  f="$R/deps/android-all-instrumented-$v.jar"
  [ -s "$f" ] || curl -fsSL --retry 8 --retry-delay 10 -o "$f" "$MC/$v/android-all-instrumented-$v.jar"
done
AA="$R/deps/android-all-instrumented-14-robolectric-10818077-i7.jar"
CP=$(ls "$R"/libs/*.jar | tr '\n' ':')

mkdir -p "$R/cls/com/android/tools"
cat > "$R/cls/com/android/tools/test_config.properties" <<EOF
android_merged_manifest=$PWD/AndroidManifest.xml
android_merged_assets=$PWD/assets
android_resource_apk=$PWD/build/resources.ap_
android_custom_package=io.github.teoplaydor.semsearch
EOF
# Subclasses of framework classes compile against the plain SDK stubs (the instrumented runtime adds
# abstract hooks to them); the tests against the Android 14 runtime for its newer APIs.
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
"$J21/javac" -nowarn -encoding UTF-8 -cp "$ANDROID_JAR:${CP}" -d "$R/cls" \
  $(find "$T/shim" -name '*.java') "$T/src/FakeMediaStore.java"
"$J21/javac" -nowarn -encoding UTF-8 -cp "$R/cls:$AA:${CP}build/classes:build/deps/ort-classes" -d "$R/cls" \
  $(find "$T/src" -name '*.java' ! -name FakeMediaStore.java)

# The NPU process round trip with a real ONNX Runtime: the desktop build (no QNN in it) and stand-in QNN host
# libraries, plus a small vision graph
N="$R/npu"
if [ ! -s "$N/vit.onnx" ] || ! grep -q pixel_position_ids "$N/vit.onnx"; then
  mkdir -p "$N"
  OJ=build/test/ort-desktop.jar
  [ -s "$OJ" ] || { mkdir -p build/test && curl -fsSL --retry 8 --retry-delay 10 -o "$OJ" \
    https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.30.0/onnxruntime-1.30.0.jar; }
  unzip -q -j -o "$OJ" 'ai/onnxruntime/native/linux-x64/*.so' -d "$N"
  echo 'int qnn_stand_in = 1;' > "$N/stub.c"
  for l in libQnnSystem libQnnHtpPrepare libQnnHtp libQnnHtpV81Stub; do gcc -shared -fPIC "$N/stub.c" -o "$N/$l.so"; done
  python3 -c 'import onnx, numpy' 2>/dev/null || python3 -m pip install -q onnx numpy
  python3 test/robolectric4/make_npu_graph.py "$N/vit.onnx"
fi

rm -rf build/shots
TESTS=("$@")
[ ${#TESTS[@]} -gt 0 ] || TESTS=(UiShots AppFlowTest AppIndexingTest IndexStopTest PipelineTest ViewerTest HiddenTest PeopleAppTest NavTest OpenTimeoutTest AutoIndexTest IdleIndexTest SpeedupsTest)
# One JVM per class: Engine is an app-wide singleton.
for t in "${TESTS[@]}"; do
  echo "-- $t"
  "$J21/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 --add-opens=java.base/java.io=ALL-UNNAMED -Drobolectric.offline=true -Drobolectric.dependency.dir="$R/deps" \
    -Dshot.dir=build/shots -Dnpu.fixture="$PWD/$N" -cp "$R/cls:${CP}build/classes:build/deps/ort-classes:$AA" org.junit.runner.JUnitCore "$t"
done
