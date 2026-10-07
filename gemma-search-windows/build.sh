#!/usr/bin/env bash
# Builds out/SemSearch.exe (Windows x64, single file, no installer, no .NET needed on the target PC).
# Works on Windows (Git Bash) and on Linux with any .NET 8 SDK; uses only nuget.org and PyPI:
#   nuget.org: Microsoft.ML.OnnxRuntime 1.27.0 (managed API), .NET 8 runtime packs
#   PyPI:      onnxruntime-webgpu 1.27.0 (onnxruntime.dll with the CPU and WebGPU execution providers),
#              msvc-runtime (Microsoft-signed Visual C++ runtime DLLs, bundled app-locally for onnxruntime.dll)
# usage: ./build.sh            -> out/SemSearch.exe
#        ./build.sh test       -> also runs the parity tests (needs gemma-search-android/build/test from its run_tests.sh)
set -euo pipefail
cd "$(dirname "$0")"

VCRT_VERSION=14.44.35112
VCRT_SHA256=32f9c706009e16ccc319d6947ce3bffe20e5192bee52b18cf48313f9e7bedfbe
ORT_VERSION=1.27.0
ORT_WHEEL_SHA256=7ef99275b13e8cb9584bd0db7a6f00ebf76095601eeccf7d34749b89ee991c19
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1

need() { command -v "$1" >/dev/null || { echo "missing tool: $1" >&2; exit 1; }; }
need dotnet; need python3

# --- Visual C++ runtime (onnxruntime.dll imports msvcp140/vcruntime140; not every PC has the redistributable)
if [ ! -s redist/msvcp140_atomic_wait.dll ]; then
  tmp=$(mktemp -d)
  python3 -m pip download -q --no-deps --only-binary=:all: --platform win_amd64 --python-version 3.12 \
    "msvc-runtime==$VCRT_VERSION" -d "$tmp"
  whl=$(ls "$tmp"/msvc_runtime-*.whl)
  echo "$VCRT_SHA256  $whl" | sha256sum -c - >/dev/null || { echo "msvc-runtime wheel checksum mismatch" >&2; exit 1; }
  mkdir -p redist
  python3 -I - "$whl" redist <<'PY'
import sys, zipfile, os
want = {"vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll", "msvcp140_atomic_wait.dll"}
with zipfile.ZipFile(sys.argv[1]) as z:
    for n in z.namelist():
        base = n.rsplit("/", 1)[-1]
        if base.lower() in want and "/Scripts/" in n:
            with open(os.path.join(sys.argv[2], base.lower()), "wb") as f:
                f.write(z.read(n))
PY
  rm -rf "$tmp"
  [ "$(ls redist/*.dll | wc -l)" = 5 ] || { echo "VC++ runtime DLLs not found in the wheel" >&2; exit 1; }
fi

# --- ONNX Runtime with WebGPU (Dawn on D3D12) instead of the NuGet CPU-only native; the GPU shader compiler
#     (dxcompiler/dxil, ~8 MB) is not bundled: the app fetches it from the same wheel on first GPU use.
if [ ! -s redist-ort/onnxruntime.dll ]; then
  tmp=$(mktemp -d)
  python3 -m pip download -q --no-deps --only-binary=:all: --platform win_amd64 --python-version 3.12 \
    "onnxruntime-webgpu==$ORT_VERSION" -d "$tmp"
  whl=$(ls "$tmp"/onnxruntime_webgpu-*.whl)
  echo "$ORT_WHEEL_SHA256  $whl" | sha256sum -c - >/dev/null || { echo "onnxruntime-webgpu wheel checksum mismatch" >&2; exit 1; }
  mkdir -p redist-ort
  python3 -I - "$whl" redist-ort <<'PY'
import sys, zipfile, os
want = {"onnxruntime.dll", "onnxruntime_providers_shared.dll"}
with zipfile.ZipFile(sys.argv[1]) as z:
    for n in z.namelist():
        base = n.rsplit("/", 1)[-1]
        if base in want and n.startswith("onnxruntime/capi/"):
            with open(os.path.join(sys.argv[2], base), "wb") as f:
                f.write(z.read(n))
PY
  rm -rf "$tmp"
  [ -s redist-ort/onnxruntime.dll ] || { echo "onnxruntime.dll not found in the wheel" >&2; exit 1; }
fi

# --- tests (C# core vs the Android app's references: tokenizer, pipeline vs transformers.js, int8 patch, stemmer, index)
if [ "${1:-}" = test ]; then
  dotnet run -c Release --project SemSearch.Tests -- ../gemma-search-android/build/test
fi

# --- exe
rm -rf out
dotnet publish SemSearch/SemSearch.csproj -c Release -o out -nologo
rm -f out/*.pdb out/*.lib
ls -la out/SemSearch.exe
sha256sum out/SemSearch.exe
