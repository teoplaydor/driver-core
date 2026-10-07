#!/usr/bin/env bash
# Off-device verification of the app (run after ./build.sh):
#   1. tokenizer parity  — HfTokenizer vs Rust `tokenizers` on the real Gemma 3 vocabulary (+ variants)
#   2. pipeline parity   — EmbeddingGemma2 (Java + ONNX Runtime) vs transformers.js EmbeddingGemma2Model
#                          on a dummy model with the same ONNX inputs/outputs (text, images, video)
#                          and SigLIP 2 (the fast photo model) vs transformers.js SiglipText/VisionModel
#   3. Hub download      — file selection, chunked external data, resume after disconnect, cancel
#   4. app tests         — the real Activity/Engine/IndexStore/background jobs on Robolectric
#                          (Android 14 runtime), plus screenshots of the screens in build/shots/
# Needs: python3 (+pip), node/npm, JDK 21, Maven.
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

echo "== 2b'. SigLIP 2 (fast photo model) vs transformers.js"
python3 test/siglip/make_dummy_siglip.py "$T/gemma3/tokenizer.json" "$T/models/siglip-dummy" >/dev/null
cp test/siglip/reference_siglip.mjs "$T/reference_siglip.mjs"
(cd "$T" && node reference_siglip.mjs "$PWD/models" siglip-dummy > reference_siglip.json)
javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 -cp "$T/cls:$T/ort-desktop.jar" -d "$T/cls" test/siglip/SigLipParityTest.java
java -Dfile.encoding=UTF-8 -cp "$T/cls:$T/ort-desktop.jar" SigLipParityTest "$T/models/siglip-dummy" "$T/reference_siglip.json"

echo "== 2c. int8 compute (accuracy_level) and GPU fallback"
python3 -m pip install -q onnxruntime onnx_ir
python3 test/accel/make_q4_model.py "$T/accel" >/dev/null 2>&1
javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 -cp "$T/cls:$T/ort-desktop.jar" -d "$T/cls" \
  test/accel/AccuracyLevelTest.java test/accel/AccelPipelineTest.java test/accel/BatchParityTest.java
java -cp "$T/cls:$T/ort-desktop.jar" AccuracyLevelTest "$T/accel"
python3 test/accel/quantize_dummy.py "$T/models/dummy" >/dev/null 2>&1
java -cp "$T/cls:build/deps/ort-classes:$T/ort-desktop.jar" AccelPipelineTest "$T/models/dummy"
java -cp "$T/cls:build/deps/ort-classes:$T/ort-desktop.jar" BatchParityTest "$T/models/dummy"
javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 -cp "$T/cls:$T/ort-desktop.jar" -d "$T/cls" test/accel/NpuShapesTest.java
java -cp "$T/cls:build/deps/ort-classes:$T/ort-desktop.jar" NpuShapesTest "$T/models/dummy"

echo "== 3. Hub download"
python3 tools/mock_hub.py 18765 & HUB=$!
trap 'kill $HUB 2>/dev/null || true' EXIT
sleep 1
rm -rf "$T/dl" "$T/dl-cancel"
java -Dhttp.nonProxyHosts=127.0.0.1 -cp "$T/cls" HfRepoTest http://127.0.0.1:18765 "$T/dl"

echo "== 4. app on Robolectric 4.14 (Android 14): screens, flows, indexing, background jobs"
test/robolectric4/run.sh
echo "ALL TESTS PASSED"
