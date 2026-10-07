#!/usr/bin/env bash
# Off-device verification of the app (run after ./build.sh):
#   1. tokenizer parity  — HfTokenizer vs Rust `tokenizers` on the real Gemma 3 vocabulary (+ variants)
#   2. pipeline parity   — EmbeddingGemma2 (Java + ONNX Runtime) vs transformers.js EmbeddingGemma2Model
#                          on a dummy model with the same ONNX inputs/outputs (text, images, video)
#                          and SigLIP 2 (the fast photo model) vs transformers.js SiglipText/VisionModel
#   2d. LiteRT-LM        — our Java side against a stand-in library with LiteRT-LM 0.18's own JNI glue
#                          (engine settings, prompts, budgets, batches, errors), install from mock
#                          Google Maven + Hub (version, arm64 libraries, bundle choice, resume)
#   3. Hub download      — file selection, chunked external data, resume after disconnect, cancel
#   4. app tests         — the real Activity/Engine/IndexStore/background jobs on Robolectric
#                          (Android 14 runtime), plus screenshots of the screens in build/shots/
# Needs: python3 (+pip), node/npm, JDK 21, Maven, g++.
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
  src/io/github/teoplaydor/semsearch/core/*.java src/com/google/ai/edge/litertlm/*.java test/*.java
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

echo "== 2c. int8 compute (accuracy_level), GPU fallback, NPU shapes, profile of CPU fallbacks"
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
python3 -m pip install -q onnxconverter-common
python3 test/accel/make_fp16_vision.py "$T/models/dummy/onnx/vision_encoder.onnx" "$T/models/dummy/onnx/vision_encoder_fp16.onnx" >/dev/null
javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 -cp "$T/cls:$T/ort-desktop.jar" -d "$T/cls" test/accel/Fp16Test.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$T/cls:build/deps/ort-classes:$T/ort-desktop.jar" Fp16Test "$T/models/dummy"
javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 -cp "$T/cls:$T/ort-desktop.jar" -d "$T/cls" test/accel/ProfileTest.java
python3 test/accel/make_mha_graph.py "$T/accel/mha.onnx"
javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 -cp "$T/cls:$T/ort-desktop.jar" -d "$T/cls" test/accel/Fp16AttentionTest.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$T/cls:build/deps/ort-classes:$T/ort-desktop.jar" Fp16AttentionTest \
  "$T/accel/mha.onnx" "$T/accel" "$T/models/dummy/onnx/vision_encoder.onnx"
python3 test/accel/make_attention_graph.py "$T/accel/attention.onnx"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$T/cls:build/deps/ort-classes:$T/ort-desktop.jar" ProfileTest "$T/models/dummy" \
  "$T/accel/attention.onnx"

echo "== 2d. LiteRT-LM: JNI contract, install"
L="$T/litert"
rm -rf "$L/lib" && mkdir -p "$L/lib" "$L/cls"
JH=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
g++ -std=c++17 -O1 -shared -fPIC -I"$JH/include" -I"$JH/include/linux" test/litert/fake_litertlm_jni.cc -o "$L/lib/liblitertlm_jni.so"
echo 'int litert_gpu_accelerator_stub = 1;' > "$L/gpu.c" && gcc -shared -fPIC "$L/gpu.c" -o "$L/lib/libLiteRtGpuAccelerator.so"
printf 'not an elf' > "$L/lib/libLiteRtOpenClAccelerator.so"
javac --release 8 -XDstringConcat=inline -nowarn -encoding UTF-8 -cp "$T/cls" -d "$T/cls" \
  test/litert/LiteRtJniTest.java test/litert/LiteRtInstallTest.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$T/cls" LiteRtJniTest "$L/lib" 2>&1 | grep -v "stack guard\|execstack"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dhttp.nonProxyHosts=127.0.0.1 -cp "$T/cls" LiteRtInstallTest

echo "== 3. Hub download"
python3 tools/mock_hub.py 18765 & HUB=$!
trap 'kill $HUB 2>/dev/null || true' EXIT
sleep 1
rm -rf "$T/dl" "$T/dl-cancel"
java -Dhttp.nonProxyHosts=127.0.0.1 -cp "$T/cls" HfRepoTest http://127.0.0.1:18765 "$T/dl"

echo "== 4. app on Robolectric 4.14 (Android 14): screens, flows, indexing, background jobs"
test/robolectric4/run.sh
echo "ALL TESTS PASSED"
