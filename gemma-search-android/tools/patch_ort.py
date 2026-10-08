"""Makes the ONNX Runtime Java sources dexable by the legacy `dx` tool.

The published onnxruntime-android classes are Java 17 bytecode (needs D8). We rebuild them
from the -sources.jar as Java 8 bytecode instead; this replaces the few lambdas with plain
code and drops the optional telemetry ContentProvider.

usage: python3 patch_ort.py <unpacked sources dir>
"""
import os
import shutil
import sys

root = sys.argv[1]
shutil.rmtree(os.path.join(root, "ai/onnxruntime/telemetry"), ignore_errors=True)
for f in ("ai/onnxruntime/TelemetryInitializer.java",):
    p = os.path.join(root, f)
    if os.path.exists(p):
        os.remove(p)
shutil.rmtree(os.path.join(root, "META-INF"), ignore_errors=True)


def sub(path, old, new):
    p = os.path.join(root, path)
    s = open(p, encoding="utf-8").read()
    if old not in s:
        sys.exit(f"patch_ort: pattern not found in {path}: {old[:50]!r}")
    open(p, "w", encoding="utf-8").write(s.replace(old, new))


for name in ("OrtCUDAProviderOptions", "OrtTensorRTProviderOptions"):
    sub(f"ai/onnxruntime/providers/{name}.java",
        "super(loadLibraryAndCreate(PROVIDER, () -> create(getApiHandle())));",
        "super(loadLibraryAndCreate(PROVIDER, new OrtProviderSupplier() { @Override public long create() "
        f"throws OrtException {{ return {name}.create(getApiHandle()); }} }}));")
sub("ai/onnxruntime/providers/StringConfigProviderOptions.java",
    """    return options.entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining(";", "", ";"));""",
    """    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : options.entrySet()) {
      sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
    }
    return sb.toString();""")
sub("ai/onnxruntime/TensorInfo.java",
    """              + Arrays.stream(dimensionNames)
                  .map(
                      a -> {
                        if (a.isEmpty()) {
                          return "\\"\\"";
                        } else {
                          return a;
                        }
                      })
                  .collect(Collectors.joining(","))""",
    """              + String.join(",", dimensionNames)""")
# SemSearch's NPU process runs another build of the same ONNX Runtime (with the Qualcomm QNN provider),
# downloaded at runtime: there the libraries come from a directory instead of the APK.
sub("ai/onnxruntime/OnnxRuntime.java",
    """    if (isAndroid()) {
      System.loadLibrary(library);
      return;
    }""",
    """    if (isAndroid()) {
      String dir = System.getProperty("onnxruntime.native.dir");
      if (dir != null) {
        System.load(new File(dir, System.mapLibraryName(library)).getAbsolutePath());
      } else {
        System.loadLibrary(library);
      }
      return;
    }""")
# On Android ONNX Runtime loads only its JNI library and lets the system resolve libonnxruntime.so — from the
# APK. With another build in a directory that would pair its JNI with the APK's core library (symbol versions
# differ: VERS_1.29.0 vs VERS_1.30.0), so the core library is loaded from the directory first.
sub("ai/onnxruntime/OnnxRuntime.java",
    """      if (!isAndroid()) {
        load(ONNXRUNTIME_LIBRARY_NAME);
      }""",
    """      if (!isAndroid() || System.getProperty("onnxruntime.native.dir") != null) {
        load(ONNXRUNTIME_LIBRARY_NAME);
      }""")
print("patched", root)
