#!/usr/bin/env bash
# Off-device verification of the app (run after ./build.sh):
#   1. tokenizer parity  — HfTokenizer vs Rust `tokenizers` on the real Gemma 3 vocabulary (+ variants)
#   2. pipeline parity   — EmbeddingGemma2 (Java + ONNX Runtime) vs transformers.js EmbeddingGemma2Model
#                          on a dummy model with the same ONNX inputs/outputs (text, images, video)
#   3. Hub download      — file selection, chunked external data, resume after disconnect, cancel
#   4. app smoke test    — the real Activity/Engine/IndexStore on Robolectric (Android 8.1 runtime)
# Needs: python3 (+pip), node/npm, JDK 21 and JDK 8 (/usr/lib/jvm/java-8-openjdk-amd64), Maven.
set -euo pipefail
cd "$(dirname "$0")/.."
T=build/test
mkdir -p "$T"
ORT_VERSION=1.30.0

python3 -m pip install -q tokenizers onnx numpy
fetch() { [ -s "$2" ] || curl -fsSL --retry 10 --retry-delay 15 -o "$2" "$1"; }
fetch "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/$ORT_VERSION/onnxruntime-$ORT_VERSION.jar" "$T/ort-desktop.jar"

# Gemma 3 tokenizer.json (262k vocab) from npm
if [ ! -f "$T/gemma3/tokenizer.json" ]; then
  (cd "$T" && npm pack --silent @lenml/tokenizer-gemma3 >/dev/null && mkdir -p g3 && tar xzf lenml-tokenizer-gemma3-*.tgz -C g3)
  mkdir -p "$T/gemma3" && cp "$T/g3/package/models/tokenizer.json" "$T/gemma3/tokenizer.json"
fi

echo "== 1. tokenizer parity"
mkdir -p "$T/cls"
javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 -cp "$T/ort-desktop.jar" -d "$T/cls" \
  src/io/github/teoplaydor/semsearch/core/*.java test/*.java
python3 tools/tokenizer_reference.py "$T/gemma3/tokenizer.json" "$T/tok-cases.jsonl"
java -Xmx2g -cp "$T/cls" TokenizerParityTest "$T/gemma3/tokenizer.json" "$T/tok-cases.jsonl"

echo "== 2. pipeline parity vs transformers.js"
python3 test/parity/make_dummy_model.py "$T/gemma3/tokenizer.json" "$T/models/dummy"
if [ ! -d "$T/node_modules/@huggingface/transformers" ]; then
  (cd "$T" && { [ -f package.json ] || npm init -y >/dev/null; } \
    && npm install --silent --ignore-scripts @huggingface/transformers@4.3.1)
fi
cp test/parity/reference.mjs "$T/reference.mjs"
(cd "$T" && node reference.mjs "$PWD/models" dummy > reference.json)
java -Xmx3g -cp "$T/cls:$T/ort-desktop.jar" PipelineParityTest "$T/models/dummy" "$T/reference.json"
# Same check with the ONNX Runtime classes rebuilt for the APK.
java -Xmx3g -cp "$T/cls:build/deps/ort-classes:$T/ort-desktop.jar" PipelineParityTest "$T/models/dummy" "$T/reference.json"

echo "== 2b. Russian stemmer vs Snowball, query bridge"
python3 -m pip install -q snowballstemmer
python3 tools/stemmer_reference.py "$T/gemma3/tokenizer.json" "$T/stem-cases.tsv"
java -Dfile.encoding=UTF-8 -cp "$T/cls" StemmerParityTest "$T/stem-cases.tsv"
java -Dfile.encoding=UTF-8 -cp "$T/cls" QueryBridgeTest assets/ru_en_lexicon.txt

echo "== 2c. int8 compute (accuracy_level) and GPU fallback"
python3 -m pip install -q onnxruntime onnx_ir
python3 test/accel/make_q4_model.py "$T/accel" >/dev/null 2>&1
javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 -cp "$T/cls:$T/ort-desktop.jar" -d "$T/cls" \
  test/accel/AccuracyLevelTest.java test/accel/AccelPipelineTest.java test/accel/BatchParityTest.java
java -cp "$T/cls:$T/ort-desktop.jar" AccuracyLevelTest "$T/accel"
python3 test/accel/quantize_dummy.py "$T/models/dummy" >/dev/null 2>&1
java -cp "$T/cls:build/deps/ort-classes:$T/ort-desktop.jar" AccelPipelineTest "$T/models/dummy"
java -cp "$T/cls:build/deps/ort-classes:$T/ort-desktop.jar" BatchParityTest "$T/models/dummy"

echo "== 3. Hub download"
python3 tools/mock_hub.py 18765 & HUB=$!
trap 'kill $HUB 2>/dev/null || true' EXIT
sleep 1
rm -rf "$T/dl" "$T/dl-cancel"
java -Dhttp.nonProxyHosts=127.0.0.1 -cp "$T/cls" HfRepoTest http://127.0.0.1:18765 "$T/dl"

echo "== 4. Robolectric smoke test"
J8=/usr/lib/jvm/java-8-openjdk-amd64/bin/java
R="$T/robo"
mkdir -p "$R"
cat > "$R/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>t</groupId><artifactId>robo</artifactId><version>1</version>
  <dependencies>
    <dependency><groupId>org.robolectric</groupId><artifactId>robolectric</artifactId><version>3.8</version></dependency>
    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.12</version></dependency>
    <dependency><groupId>org.robolectric</groupId><artifactId>android-all</artifactId><version>8.1.0-robolectric-4611349</version></dependency>
  </dependencies>
</project>
POM
[ -d "$R/libs" ] || (cd "$R" && mvn -q -B -Daether.connector.http.retryHandler.count=8 \
  -Daether.connector.http.retryHandler.serviceUnavailable=429,503 dependency:copy-dependencies -DoutputDirectory=libs)
mkdir -p "$R/deps" "$R/cls"
cp "$R"/libs/android-all-*.jar "$R/deps/"
CP=$(ls "$R"/libs/*.jar | tr '\n' ':')
javac --release 8 -nowarn -encoding UTF-8 -cp "${CP}build/classes:build/deps/ort-classes" -d "$R/cls" test/robolectric/*.java
# One JVM per class: Engine is an app-wide singleton.
for t in AppSmokeTest AppIndexingTest; do
  "$J8" -Dfile.encoding=UTF-8 -Drobolectric.offline=true -Drobolectric.dependency.dir="$R/deps" \
    -cp "$R/cls:${CP}build/classes:build/deps/ort-classes" org.junit.runner.JUnitCore $t
done
echo "ALL TESTS PASSED"
