#!/usr/bin/env bash
# Builds libsecllm.so — the secretary's own language model on the phone: llama.cpp from PrismML's fork (it runs the
# ternary Bonsai models, Q2_0/PQ2_0) with our JNI in native/secllm — for arm64 phones (ARMv8.2 + dot product: every
# Snapdragon since 855). The fork (a pinned commit) and the Android NDK are fetched once into build/deps.
#   usage: tools/build_secllm.sh [out.so]              (default: build/deps/secllm/libsecllm.so)
#          tools/build_secllm.sh --host DIR           for tests on this computer: DIR/libsecllm.so (+ JNI from the JDK)
#                                                      and DIR/llama-quantize; no NDK needed
#   ANDROID_NDK=<dir>  use an NDK already on disk;  LLAMA_SRC=<dir>  use a checkout of the fork at that commit
# Needs: git, cmake, curl, unzip (symlinks in the NDK zip matter), a C++ compiler is not needed — the NDK has one.
set -euo pipefail
cd "$(dirname "$0")/.."
LLAMA_REPO=https://github.com/PrismML-Eng/llama.cpp
LLAMA_COMMIT=6447906bf96c1a023980d3453fb59daa6af3bb23
NDK_VERSION=r29
D=build/deps
HOST=
if [ "${1:-}" = --host ]; then HOST=${2:?usage: --host DIR}; OUT=$HOST/libsecllm.so; else OUT=${1:-$D/secllm/libsecllm.so}; fi
mkdir -p "$D" "$(dirname "$OUT")"
GEN=(); command -v ninja >/dev/null && GEN=(-G Ninja)

# --- the fork at the pinned commit
SRC=${LLAMA_SRC:-$D/llama-prism}
if [ -z "${LLAMA_SRC:-}" ] && [ "$(cat "$SRC/.commit" 2>/dev/null)" != "$LLAMA_COMMIT" ]; then
  rm -rf "$SRC.tmp" && mkdir -p "$SRC.tmp"
  git -C "$SRC.tmp" init -q
  for i in 1 2 3 4; do
    git -C "$SRC.tmp" fetch -q --depth 1 "$LLAMA_REPO" "$LLAMA_COMMIT" && break
    [ $i = 4 ] && { echo "fetch failed: $LLAMA_REPO $LLAMA_COMMIT" >&2; exit 1; }
    sleep $((i * 5))
  done
  git -C "$SRC.tmp" -c advice.detachedHead=false checkout -q FETCH_HEAD
  echo "$LLAMA_COMMIT" > "$SRC.tmp/.commit"
  rm -rf "$SRC" && mv "$SRC.tmp" "$SRC"
fi
[ "$(git -C "$SRC" rev-parse HEAD)" = "$LLAMA_COMMIT" ] || { echo "$SRC is not at $LLAMA_COMMIT" >&2; exit 1; }
SRC_ABS=$(cd "$SRC" && pwd)

if [ -n "$HOST" ]; then
  cmake -S native/secllm -B "$HOST/build" "${GEN[@]}" -DCMAKE_BUILD_TYPE=Release -DLLAMA_DIR="$SRC_ABS" >/dev/null
  cmake --build "$HOST/build" --target secllm -j"$(nproc)" >/dev/null
  cp "$HOST/build/libsecllm.so" "$OUT"
  cmake -S "$SRC_ABS" -B "$HOST/llama" "${GEN[@]}" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DLLAMA_CURL=OFF \
    -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF -DGGML_NATIVE=OFF >/dev/null
  cmake --build "$HOST/llama" --target llama-quantize -j"$(nproc)" >/dev/null
  cp "$HOST/llama/bin/llama-quantize" "$HOST/llama-quantize"
  echo "$HOST: libsecllm.so, llama-quantize; gguf-py in $SRC_ABS/gguf-py"
  echo "$SRC_ABS" > "$HOST/llama-src"
  exit 0
fi

# --- the NDK
NDK=${ANDROID_NDK:-$D/android-ndk-$NDK_VERSION}
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
if [ ! -x "$CLANG" ]; then
  [ -z "${ANDROID_NDK:-}" ] || { echo "no NDK at $ANDROID_NDK" >&2; exit 1; }
  Z="$D/android-ndk-$NDK_VERSION-linux.zip"
  if [ ! -s "$Z" ]; then
    curl -fL --retry 5 --retry-delay 10 -o "$Z.tmp" "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip"
    mv "$Z.tmp" "$Z"
  fi
  (cd "$D" && unzip -q -o "android-ndk-$NDK_VERSION-linux.zip")
  rm -f "$Z"
fi

# --- build
W="$D/secllm-build"
cmake -S native/secllm -B "$W" "${GEN[@]}" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DLLAMA_DIR="$SRC_ABS" >/dev/null
cmake --build "$W" --target secllm -j"$(nproc)"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded -o "$OUT" "$W/libsecllm.so"
echo "$OUT: $(stat -c %s "$OUT") bytes"
