package io.github.teoplaydor.semsearch.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Google's LiteRT-LM runtime and EmbeddingGemma 2 in its format, fetched on the phone: the native
 * libraries come from the {@code litertlm-android} AAR on Google Maven (a version whose JNI matches
 * {@code com.google.ai.edge.litertlm.LiteRtLmJni}), the {@code .litertlm} bundle from the LiteRT Community
 * on Hugging Face. Everything lives in one directory with a small manifest.
 */
public final class LiteRtRuntime {
    public static final String MAVEN = "https://dl.google.com/android/maven2";
    public static final String ARTIFACT = "com/google/ai/edge/litertlm/litertlm-android";
    /** Embedding JNI (LiteRtLmJni.kt) is the same from 0.18.0 (first with EmbeddingGemma 2) through 0.19. */
    static final int[] MIN_VERSION = {0, 18}, MAX_VERSION = {0, 19};
    public static final String DEFAULT_MODEL_REPO = "litert-community/embeddinggemma-2-740m-litert-lm";
    public static final String ABI = "arm64-v8a";
    public static final String JNI_LIB = "liblitertlm_jni.so";

    private final String maven;
    private final String hfHost;
    private final String token;
    private final File dir;

    public LiteRtRuntime(File dir, String token) {
        this(MAVEN, null, dir, token);
    }

    /** @param hfHost Hugging Face host, null for the real one (tests point both at a mock server) */
    public LiteRtRuntime(String maven, String hfHost, File dir, String token) {
        this.maven = maven;
        this.hfHost = hfHost;
        this.dir = dir;
        this.token = token;
    }

    public File dir() { return dir; }

    public File manifest() { return new File(dir, "manifest.json"); }

    public File libDir() { return new File(dir, "lib"); }

    // ------------------------------------------------------------------ state

    public static final class Installed {
        public String version, repo, model;
        public long modelSize;
        public final List<String> libs = new ArrayList<String>();
    }

    /** What is installed, or null when the runtime or the model is missing or incomplete. */
    public Installed installed() {
        try {
            if (!manifest().exists()) return null;
            Map<String, Object> m = MiniJson.obj(ModelConfig.readJson(manifest()));
            Installed i = new Installed();
            i.version = MiniJson.str(m, "version", null);
            i.repo = MiniJson.str(m, "repo", null);
            i.model = MiniJson.str(m, "model", null);
            i.modelSize = MiniJson.num(m, "model_size", -1);
            for (Object o : MiniJson.arr(m.get("libs"))) i.libs.add(String.valueOf(o));
            if (i.version == null || i.model == null || !i.libs.contains(JNI_LIB)) return null;
            for (String l : i.libs) if (!new File(libDir(), l).exists()) return null;
            File mf = modelFile(i);
            if (!mf.exists() || (i.modelSize >= 0 && mf.length() != i.modelSize)) return null;
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    public File modelFile(Installed i) {
        return new File(dir, new File(i.model).getName());
    }

    /** Bytes on disk (libraries, AARs, the model), for "delete". */
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

    // ------------------------------------------------------------------ install

    /**
     * Downloads (resuming what is there) the runtime's native libraries and the model, then writes the
     * manifest. {@code progress} sees both as one download.
     */
    public Installed install(HfRepo.Progress progress) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir " + dir);
        // 1. which runtime and which model
        String version = pickVersion(httpText(maven + "/" + ARTIFACT + "/maven-metadata.xml"));
        if (version == null) {
            throw new IOException("в Google Maven нет версии LiteRT-LM " + MIN_VERSION[0] + "." + MIN_VERSION[1]
                    + "–" + MAX_VERSION[0] + "." + MAX_VERSION[1] + ".x");
        }
        String base = maven + "/" + ARTIFACT + "/" + version + "/litertlm-android-" + version;
        List<String[]> deps = nativeDeps(httpTextOrNull(base + ".pom"));
        String repo = DEFAULT_MODEL_REPO;
        try {
            repo = pickModelRepo(hub(DEFAULT_MODEL_REPO).search("embeddinggemma-2", "litert-community"), repo);
        } catch (IOException e) {
            // search is a nicety: the 740M bundle is known to exist
        }
        HfRepo hub = hub(repo);
        HfRepo.RemoteFile model = pickModelFile(hub.listFiles());
        if (model == null) throw new IOException("в " + repo + " нет файла .litertlm");

        // 2. sizes for one progress bar (the AARs' sizes are only known once they start)
        long total = Math.max(0, model.size);
        long[] done = {0};

        // 3. runtime: the AAR (and LiteRT AARs it depends on), native libraries for arm64
        File lib = libDir();
        deleteTree(lib);
        if (!lib.mkdirs()) throw new IOException("mkdir " + lib);
        List<String> libs = new ArrayList<String>();
        File aar = new File(dir, "litertlm-android-" + version + ".aar");
        done[0] = HfRepo.fetch(base + ".aar", null, "LiteRT-LM " + version, -1, aar, done[0], total, progress);
        extractNative(aar, lib, libs);
        for (String[] d : deps) {
            File da = new File(dir, d[1] + "-" + d[2] + ".aar");
            String url = maven + "/" + d[0].replace('.', '/') + "/" + d[1] + "/" + d[2] + "/" + d[1] + "-" + d[2] + ".aar";
            try {
                done[0] = HfRepo.fetch(url, null, d[1] + " " + d[2], -1, da, done[0], total, progress);
                extractNative(da, lib, libs);
            } catch (IOException e) {
                // a dependency without an AAR (plain jar) has no native code for us
            }
        }
        if (!libs.contains(JNI_LIB)) throw new IOException("в LiteRT-LM " + version + " нет " + ABI + "/" + JNI_LIB);

        // 4. the model
        File mf = new File(dir, new File(model.path).getName());
        hub.downloadFile(model, mf, offset(progress, done[0], total + done[0]));

        // 5. manifest last: its presence means "complete"
        StringBuilder sb = new StringBuilder("{\"version\":").append(MiniJson.write(version))
                .append(",\"repo\":").append(MiniJson.write(repo))
                .append(",\"model\":").append(MiniJson.write(model.path))
                .append(",\"model_size\":").append(model.size).append(",\"libs\":[");
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
        for (File f : listOrEmpty(dir)) if (f.getName().endsWith(".aar")) f.delete(); // libraries are extracted
        return installed();
    }

    private HfRepo hub(String repo) {
        return hfHost != null ? new HfRepo(hfHost, repo, token) : new HfRepo(repo, token);
    }

    private static HfRepo.Progress offset(final HfRepo.Progress p, final long before, final long total) {
        if (p == null) return null;
        return new HfRepo.Progress() {
            @Override
            public boolean onProgress(String file, long fileDone, long fileTotal, long allDone, long allTotal) {
                return p.onProgress(file, fileDone, fileTotal, before + allDone, total);
            }
        };
    }

    /** Copies {@code jni/arm64-v8a/*.so} out of an AAR. */
    static void extractNative(File aar, File lib, List<String> names) throws IOException {
        String prefix = "jni/" + ABI + "/";
        ZipInputStream z = new ZipInputStream(new FileInputStream(aar));
        try {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith(prefix) || !n.endsWith(".so") || n.indexOf('/', prefix.length()) >= 0) continue;
                String name = n.substring(prefix.length());
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

    // ------------------------------------------------------------------ choices (pure, tested)

    /** Highest released version in [MIN_VERSION, MAX_VERSION] (by major.minor), or null. */
    static String pickVersion(String metadataXml) {
        Matcher m = Pattern.compile("<version>\\s*([0-9]+)\\.([0-9]+)\\.([0-9]+)\\s*</version>").matcher(metadataXml);
        String best = null;
        long bestKey = -1;
        while (m.find()) {
            int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2)), c = Integer.parseInt(m.group(3));
            if (cmp(a, b, MIN_VERSION) < 0 || cmp(a, b, MAX_VERSION) > 0) continue;
            long key = ((long) a << 40) | ((long) b << 20) | c;
            if (key > bestKey) {
                bestKey = key;
                best = a + "." + b + "." + c;
            }
        }
        return best;
    }

    private static int cmp(int a, int b, int[] v) {
        return a != v[0] ? Integer.compare(a, v[0]) : Integer.compare(b, v[1]);
    }

    /** {groupId, artifactId, version} of the POM's LiteRT dependencies (they may carry native libraries). */
    static List<String[]> nativeDeps(String pom) {
        List<String[]> out = new ArrayList<String[]>();
        if (pom == null) return out;
        Matcher m = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pom);
        while (m.find()) {
            String d = m.group(1);
            String g = tag(d, "groupId"), a = tag(d, "artifactId"), v = tag(d, "version");
            if (g == null || a == null || v == null || !g.startsWith("com.google.ai.edge")) continue;
            if (v.startsWith("[")) v = v.substring(1, v.contains(",") ? v.indexOf(',') : v.length() - 1);
            out.add(new String[]{g, a, v.trim()});
        }
        return out;
    }

    private static String tag(String xml, String t) {
        Matcher m = Pattern.compile("<" + t + ">\\s*([^<]+?)\\s*</" + t + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The LiteRT Community's EmbeddingGemma 2 bundle with pictures and as little else as possible:
     * text + vision (440M) before the full 740M one; text-only (270M) cannot index photos.
     */
    static String pickModelRepo(List<String> ids, String fallback) {
        String best = null;
        int bestScore = -1;
        for (String id : ids) {
            String l = id.toLowerCase(Locale.ROOT);
            if (!l.startsWith("litert-community/") || !l.contains("embeddinggemma-2") || l.contains("270m")) continue;
            int score = l.contains("440m") ? 3 : l.contains("740m") ? 2 : 1;
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return best != null ? best : fallback;
    }

    private static final String[] CHIP_MARKERS = {"sm8", "sm7", "sm6", "mt6", "qualcomm", "mediatek", "tensor", "exynos",
            "npu", "qnn", "_tpu", "-tpu"};

    /**
     * The generic {@code .litertlm} file (runs on CPU and GPU) — not a chip-specific NPU build, not text-only;
     * among several, the one named for text + vision, then the smallest.
     */
    static HfRepo.RemoteFile pickModelFile(List<HfRepo.RemoteFile> files) {
        List<HfRepo.RemoteFile> ok = new ArrayList<HfRepo.RemoteFile>();
        for (HfRepo.RemoteFile f : files) {
            String l = f.path.toLowerCase(Locale.ROOT);
            if (!l.endsWith(".litertlm") || l.contains("270m") || l.contains("text_only") || l.contains("text-only")) continue;
            boolean chip = false;
            for (String c : CHIP_MARKERS) chip |= l.contains(c);
            if (!chip) ok.add(f);
        }
        HfRepo.RemoteFile best = null;
        for (HfRepo.RemoteFile f : ok) {
            if (best == null || rank(f) > rank(best) || (rank(f) == rank(best) && size(f) < size(best))) best = f;
        }
        return best;
    }

    private static int rank(HfRepo.RemoteFile f) {
        String l = f.path.toLowerCase(Locale.ROOT);
        return (l.contains("440m") ? 2 : 0) + (l.contains("vision") ? 1 : 0) + (l.indexOf('/') < 0 ? 1 : 0);
    }

    private static long size(HfRepo.RemoteFile f) {
        return f.size >= 0 ? f.size : Long.MAX_VALUE;
    }

    // ------------------------------------------------------------------ native libraries

    /**
     * Loads the extracted libraries by path. The JNI library may need others first (and LiteRT later opens its
     * GPU accelerator by name, which finds an already-loaded library): load in passes until nothing more loads.
     * Returns the libraries that could not be loaded with the reason (OpenCL-only accelerators fail on phones
     * without OpenCL; that is fine as long as the JNI library loads).
     */
    public static List<String> load(File libDir, List<String> libs) {
        List<String> left = new ArrayList<String>(libs);
        // the JNI library last: its dependencies (if separate) are then in place
        if (left.remove(JNI_LIB)) left.add(JNI_LIB);
        List<String> errors = new ArrayList<String>();
        boolean progress = true;
        while (!left.isEmpty() && progress) {
            progress = false;
            errors.clear();
            for (java.util.Iterator<String> it = left.iterator(); it.hasNext(); ) {
                String l = it.next();
                try {
                    System.load(new File(libDir, l).getAbsolutePath());
                    it.remove();
                    progress = true;
                } catch (UnsatisfiedLinkError e) {
                    errors.add(l + ": " + e.getMessage());
                }
            }
        }
        if (left.contains(JNI_LIB)) {
            throw new UnsatisfiedLinkError("LiteRT-LM не загрузился: " + errors);
        }
        return errors;
    }

    // ------------------------------------------------------------------ http

    private String httpText(String url) throws IOException {
        String s = httpTextOrNull(url);
        if (s == null) throw new IOException("нет ответа от " + url);
        return s;
    }

    private static String httpTextOrNull(String url) throws IOException {
        HttpURLConnection c = HfRepo.open(url, null, 0);
        int code = c.getResponseCode();
        if (code == 404) return null;
        if (code != 200) throw new IOException("HTTP " + code + " для " + url);
        InputStream in = c.getInputStream();
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static List<File> listOrEmpty(File d) {
        File[] f = d.listFiles();
        List<File> l = new ArrayList<File>();
        if (f != null) Collections.addAll(l, f);
        return l;
    }

    public static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
