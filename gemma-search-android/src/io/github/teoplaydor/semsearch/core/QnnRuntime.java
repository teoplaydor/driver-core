package io.github.teoplaydor.semsearch.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Qualcomm's QNN runtime for the Snapdragon NPU (Hexagon HTP) and the ONNX Runtime build that drives it,
 * fetched on the phone from Maven Central: {@code onnxruntime-android-qnn} (CPU + QNN only — it runs in its own
 * process next to the app's ONNX Runtime with WebGPU; its JNI is the same as the app's 1.30 classes) and
 * {@code qnn-runtime} at the version that build was made against, of which only the HTP backend, the
 * on-device graph compiler and this chip's Hexagon libraries are kept.
 */
public final class QnnRuntime {
    public static final String MAVEN = "https://repo1.maven.org/maven2";
    public static final String ORT_VERSION = "1.29.0", QNN_VERSION = "2.42.0";
    static final String ORT_AAR = "com/microsoft/onnxruntime/onnxruntime-android-qnn/" + ORT_VERSION
            + "/onnxruntime-android-qnn-" + ORT_VERSION + ".aar";
    static final String QNN_AAR = "com/qualcomm/qti/qnn-runtime/" + QNN_VERSION + "/qnn-runtime-" + QNN_VERSION + ".aar";
    public static final String ABI = "arm64-v8a";
    /** Loaded into the NPU process before ONNX Runtime (the HTP backend opens them by name); skels go to the DSP. */
    public static final String[] HOST_LIBS = {"libQnnSystem.so", "libQnnHtpPrepare.so", "libQnnHtp.so"};

    private final String maven;
    private final File dir;

    public QnnRuntime(File dir) {
        this(MAVEN, dir);
    }

    public QnnRuntime(String maven, File dir) {
        this.maven = maven;
        this.dir = dir;
    }

    public File dir() { return dir; }

    public File libDir() { return new File(dir, "lib"); }

    File manifest() { return new File(dir, "manifest.json"); }

    /**
     * Hexagon HTP architecture of a Snapdragon by its model number (Build.SOC_MODEL), or null when unknown
     * (then every architecture's libraries are kept).
     */
    public static String htpArch(String soc) {
        if (soc == null) return null;
        String s = soc.toUpperCase(Locale.ROOT);
        String[][] map = {{"SM8850", "81"}, {"SM8845", "81"}, {"SM8750", "79"}, {"SM8735", "79"}, {"SM8650", "75"},
                {"SM8635", "73"}, {"SM7675", "73"}, {"SM8550", "73"}, {"SM7550", "73"}, {"SM8475", "69"}, {"SM8450", "69"},
                {"SM7475", "69"}, {"SM8350", "68"}, {"SM7325", "68"}};
        for (String[] m : map) if (s.startsWith(m[0])) return m[1];
        return null;
    }

    public static final class Installed {
        public String ort, qnn, arch;
        public final List<String> libs = new ArrayList<String>();
    }

    /** What is installed, or null when something is missing. */
    public Installed installed() {
        try {
            if (!manifest().exists()) return null;
            Map<String, Object> m = MiniJson.obj(ModelConfig.readJson(manifest()));
            Installed i = new Installed();
            i.ort = MiniJson.str(m, "ort", null);
            i.qnn = MiniJson.str(m, "qnn", null);
            i.arch = MiniJson.str(m, "arch", null);
            for (Object o : MiniJson.arr(m.get("libs"))) i.libs.add(String.valueOf(o));
            if (!i.libs.contains("libonnxruntime.so") || !i.libs.contains("libonnxruntime4j_jni.so") || !i.libs.contains("libQnnHtp.so")) {
                return null;
            }
            for (String l : i.libs) if (!new File(libDir(), l).exists()) return null;
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    public long bytesOnDisk() {
        return size(dir);
    }

    private static long size(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += size(k);
        return n;
    }

    /** Downloads both AARs (resuming), keeps the needed libraries, writes the manifest. */
    public Installed install(String socModel, HfRepo.Progress progress) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir " + dir);
        File lib = libDir();
        if (!lib.exists() && !lib.mkdirs()) throw new IOException("mkdir " + lib);
        String arch = htpArch(socModel);
        List<String> libs = new ArrayList<String>();
        File ortAar = new File(dir, "onnxruntime-android-qnn.aar"), qnnAar = new File(dir, "qnn-runtime.aar");
        long done = HfRepo.fetch(maven + "/" + ORT_AAR, null, "ONNX Runtime QNN " + ORT_VERSION, -1, ortAar, 0, -1, progress);
        done = HfRepo.fetch(maven + "/" + QNN_AAR, null, "Qualcomm QNN " + QNN_VERSION, -1, qnnAar, done, -1, progress);
        extract(ortAar, lib, libs, arch, true);
        extract(qnnAar, lib, libs, arch, false);
        if (!libs.contains("libonnxruntime.so") || !libs.contains("libQnnHtp.so")) {
            throw new IOException("в пакетах нет библиотек ONNX Runtime или QNN для " + ABI);
        }
        boolean stub = false;
        for (String l : libs) stub |= l.startsWith("libQnnHtpV") && l.endsWith("Stub.so");
        if (!stub) throw new IOException("в QNN " + QNN_VERSION + " нет библиотек для NPU этого чипа (Hexagon V" + arch + ")");
        StringBuilder sb = new StringBuilder("{\"ort\":").append(MiniJson.write(ORT_VERSION))
                .append(",\"qnn\":").append(MiniJson.write(QNN_VERSION))
                .append(",\"arch\":").append(MiniJson.write(arch == null ? "all" : arch)).append(",\"libs\":[");
        for (int i = 0; i < libs.size(); i++) sb.append(i > 0 ? "," : "").append(MiniJson.write(libs.get(i)));
        sb.append("]}");
        File tmp = new File(dir, "manifest.json.tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(sb.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(manifest())) throw new IOException("rename manifest");
        ortAar.delete();
        qnnAar.delete();
        return installed();
    }

    /**
     * Keeps {@code jni/arm64-v8a/}: from ONNX Runtime both libraries; from QNN the HTP backend, its graph
     * compiler, QnnSystem and the stub/skel pair of this chip's Hexagon version (all of them when unknown).
     */
    static void extract(File aar, File lib, List<String> names, String arch, boolean ort) throws IOException {
        String prefix = "jni/" + ABI + "/";
        ZipInputStream z = new ZipInputStream(new FileInputStream(aar));
        try {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith(prefix) || n.indexOf('/', prefix.length()) >= 0) continue;
                String name = n.substring(prefix.length());
                if (!wanted(name, arch, ort)) continue;
                OutputStream out = new FileOutputStream(new File(lib, name));
                try {
                    int r;
                    while ((r = z.read(buf)) > 0) out.write(buf, 0, r);
                } finally {
                    out.close();
                }
                if (!names.contains(name)) names.add(name);
            }
        } finally {
            z.close();
        }
    }

    static boolean wanted(String name, String arch, boolean ort) {
        if (ort) return name.equals("libonnxruntime.so") || name.equals("libonnxruntime4j_jni.so");
        if (name.equals("libQnnHtp.so") || name.equals("libQnnHtpPrepare.so") || name.equals("libQnnSystem.so")) return true;
        if (!name.startsWith("libQnnHtpV") || !(name.endsWith("Stub.so") || name.endsWith("Skel.so"))) return false;
        return arch == null || name.startsWith("libQnnHtpV" + arch);
    }
}
