import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.teoplaydor.semsearch.core.LiteRtRuntime;

/**
 * Getting LiteRT-LM onto the phone, against a local stand-in for Google Maven and the Hugging Face Hub:
 * the newest runtime whose JNI matches ours (not older, not a newer minor, not a pre-release), its
 * arm64 libraries only (also from a LiteRT dependency), the text + vision bundle rather than the full or
 * text-only one, the generic file rather than a chip-specific NPU build, resumable model download, and
 * "installed" only when everything is in place.
 */
public class LiteRtInstallTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static byte[] aar(String... entries) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(b);
        for (String e : entries) {
            z.putNextEntry(new ZipEntry(e));
            z.write(("contents of " + e).getBytes("UTF-8"));
            z.closeEntry();
        }
        z.close();
        return b.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        final Map<String, byte[]> files = new HashMap<String, byte[]>();
        final String g = "/maven/com/google/ai/edge/litertlm/litertlm-android";
        files.put(g + "/maven-metadata.xml", ("<metadata><versioning><versions><version>0.16.1</version>"
                + "<version>0.17.1</version><version>0.18.0</version><version>0.19.1</version><version>0.19.2-rc1</version>"
                + "<version>0.20.0</version></versions></versioning></metadata>").getBytes("UTF-8"));
        files.put(g + "/0.19.1/litertlm-android-0.19.1.pom", ("<project><dependencies>"
                + "<dependency><groupId>com.google.ai.edge.litert</groupId><artifactId>litert</artifactId><version>2.1.0</version></dependency>"
                + "<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>2.0.0</version></dependency>"
                + "</dependencies></project>").getBytes("UTF-8"));
        files.put(g + "/0.19.1/litertlm-android-0.19.1.aar", aar("classes.jar", "jni/arm64-v8a/liblitertlm_jni.so",
                "jni/arm64-v8a/libLiteRtGpuAccelerator.so", "jni/x86_64/liblitertlm_jni.so", "jni/arm64-v8a/sub/ignored.so"));
        files.put("/maven/com/google/ai/edge/litert/litert/2.1.0/litert-2.1.0.aar", aar("jni/arm64-v8a/libLiteRt.so"));
        files.put("/hf/api/models?search=embeddinggemma-2&author=litert-community&sort=downloads&direction=-1&limit=30",
                ("[{\"id\":\"litert-community/embeddinggemma-2-740m-litert-lm\"},{\"id\":\"someone/embeddinggemma-2-440m-litert-lm\"},"
                        + "{\"id\":\"litert-community/embeddinggemma-2-440m-litert-lm\"},{\"id\":\"litert-community/embeddinggemma-2-270m-litert-lm\"}]")
                        .getBytes("UTF-8"));
        final byte[] model = new byte[300_000];
        for (int i = 0; i < model.length; i++) model[i] = (byte) (i * 31);
        files.put("/hf/api/models/litert-community/embeddinggemma-2-440m-litert-lm?blobs=true", ("{\"siblings\":["
                + "{\"rfilename\":\"README.md\",\"size\":10},"
                + "{\"rfilename\":\"embeddinggemma-2-440m_qualcomm_sm8750.litertlm\",\"size\":100},"
                + "{\"rfilename\":\"embeddinggemma-2-440m.litertlm\",\"size\":" + model.length + "}]}").getBytes("UTF-8"));
        files.put("/hf/litert-community/embeddinggemma-2-440m-litert-lm/resolve/main/embeddinggemma-2-440m.litertlm", model);
        final List<String> served = new ArrayList<String>();
        // the first runtime download and the first model download break off midway
        final java.util.Set<String> cutOnce = new java.util.HashSet<String>(java.util.Arrays.asList(
                g + "/0.19.1/litertlm-android-0.19.1.aar",
                "/hf/litert-community/embeddinggemma-2-440m-litert-lm/resolve/main/embeddinggemma-2-440m.litertlm"));

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange x) throws java.io.IOException {
                String key = x.getRequestURI().toString();
                served.add(key);
                byte[] body = files.get(key);
                if (body == null) {
                    x.sendResponseHeaders(404, -1);
                    x.close();
                    return;
                }
                String range = x.getRequestHeaders().getFirst("Range");
                int from = range != null ? Integer.parseInt(range.replaceAll("bytes=(\\d+)-.*", "$1")) : 0;
                if (from > 0) x.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (body.length - 1) + "/" + body.length);
                x.sendResponseHeaders(from > 0 ? 206 : 200, body.length - from);
                OutputStream o = x.getResponseBody();
                int n = body.length - from;
                if (cutOnce.remove(key)) n = n / 3;
                try {
                    o.write(body, from, n);
                } finally {
                    x.close();
                }
            }
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        File dir = new File(System.getProperty("java.io.tmpdir"), "litert-install-" + System.nanoTime());
        LiteRtRuntime rt = new LiteRtRuntime(base + "/maven", base + "/hf", dir, null);
        check(rt.installed() == null, "nothing installed yet");

        try {
            rt.install(null);
            check(false, "a broken runtime download is reported");
        } catch (java.io.IOException e) {
            check(rt.installed() == null && e.getMessage().contains("LiteRT-LM 0.19.1"),
                    "a cut runtime download (size known only from the server) is not taken as complete: " + e.getMessage());
        }
        final long[] last = {-1};
        final boolean[] monotonic = {true};
        try {
            rt.install(new io.github.teoplaydor.semsearch.core.HfRepo.Progress() {
                @Override
                public boolean onProgress(String file, long fd, long ft, long all, long allTotal) {
                    if (all < last[0]) monotonic[0] = false;
                    last[0] = all;
                    return true;
                }
            });
            check(false, "the broken download is reported");
        } catch (java.io.IOException e) {
            check(rt.installed() == null, "a broken model download leaves nothing \"installed\": " + e.getMessage());
        }
        LiteRtRuntime.Installed i = rt.install(null);
        check(i != null, "installed after the second attempt");
        check(served.contains("/hf/litert-community/embeddinggemma-2-440m-litert-lm/resolve/main/embeddinggemma-2-440m.litertlm")
                && new File(dir, "embeddinggemma-2-440m.litertlm.part").exists() == false, "model resumed and completed");
        check("0.19.1".equals(i.version), "runtime version: " + i.version + " (0.18–0.19, no pre-release, not 0.20)");
        check("litert-community/embeddinggemma-2-440m-litert-lm".equals(i.repo), "text + vision bundle: " + i.repo);
        check("embeddinggemma-2-440m.litertlm".equals(i.model) && rt.modelFile(i).length() == model.length,
                "generic file, not the chip-specific one: " + i.model);
        check(i.libs.size() == 3 && i.libs.contains("liblitertlm_jni.so") && i.libs.contains("libLiteRtGpuAccelerator.so")
                && i.libs.contains("libLiteRt.so"), "arm64 libraries, also from the LiteRT dependency: " + i.libs);
        check(!served.toString().contains("kotlin-stdlib"), "non-LiteRT dependencies are not fetched");
        check(new File(rt.libDir(), "liblitertlm_jni.so").length() > 0 && !new File(rt.libDir(), "ignored.so").exists(),
                "extracted into lib/ (nothing from sub-folders or other ABIs)");
        File[] left = dir.listFiles();
        boolean aarLeft = false;
        for (File f : left) aarLeft |= f.getName().endsWith(".aar");
        check(!aarLeft, "AARs removed after extraction");
        check(monotonic[0], "progress only grows");
        check(rt.bytesOnDisk() >= model.length, "size on disk: " + rt.bytesOnDisk());

        new File(rt.libDir(), "libLiteRt.so").delete();
        check(rt.installed() == null, "a missing library means not installed");

        // no bundle found by search: the known 740M one
        files.put("/hf/api/models?search=embeddinggemma-2&author=litert-community&sort=downloads&direction=-1&limit=30",
                "[]".getBytes("UTF-8"));
        files.put("/hf/api/models/litert-community/embeddinggemma-2-740m-litert-lm?blobs=true",
                "{\"siblings\":[{\"rfilename\":\"embeddinggemma-2-740m.litertlm\",\"size\":5}]}".getBytes("UTF-8"));
        files.put("/hf/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm", new byte[5]);
        LiteRtRuntime.Installed j = rt.install(null);
        check(j != null && "litert-community/embeddinggemma-2-740m-litert-lm".equals(j.repo), "fallback bundle: " + (j == null ? null : j.repo));

        // no compatible runtime version
        files.put(g + "/maven-metadata.xml", "<metadata><version>0.17.1</version><version>0.20.0</version></metadata>".getBytes("UTF-8"));
        try {
            rt.install(null);
            check(false, "no compatible version is an error");
        } catch (java.io.IOException e) {
            check(e.getMessage().contains("0.18–0.19"), "no compatible version: " + e.getMessage());
        }
        srv.stop(0);
        LiteRtRuntime.deleteTree(dir);
        System.out.println(bad == 0 ? "LITERT INSTALL OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
