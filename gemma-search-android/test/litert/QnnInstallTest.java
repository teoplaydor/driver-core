import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.teoplaydor.semsearch.core.LiteRtRuntime;
import io.github.teoplaydor.semsearch.core.QnnRuntime;

/**
 * Getting Qualcomm QNN onto a Snapdragon from a local stand-in for Maven Central: the ONNX Runtime build with
 * the QNN provider (both of its libraries), and from qnn-runtime only the HTP backend, its graph compiler,
 * QnnSystem and this chip's Hexagon stub/skel (V81 for SM8850; every version for an unknown chip); a chip
 * whose version the package lacks is refused; "installed" only when everything is in place.
 */
public class QnnInstallTest {
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
        files.put("/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/1.29.0/onnxruntime-android-qnn-1.29.0.aar",
                aar("classes.jar", "jni/arm64-v8a/libonnxruntime.so", "jni/arm64-v8a/libonnxruntime4j_jni.so",
                        "jni/x86_64/libonnxruntime.so", "headers/onnxruntime_c_api.h"));
        String[] qnn = {"libQnnDsp.so", "libQnnDspV66Skel.so", "libQnnGpu.so", "libQnnHtp.so", "libQnnHtpPrepare.so",
                "libQnnSystem.so", "libQnnHtpV73Skel.so", "libQnnHtpV73Stub.so", "libQnnHtpV79Skel.so", "libQnnHtpV79Stub.so",
                "libQnnHtpV81Skel.so", "libQnnHtpV81Stub.so"};
        String[] entries = new String[qnn.length + 1];
        for (int i = 0; i < qnn.length; i++) entries[i] = "jni/arm64-v8a/" + qnn[i];
        entries[qnn.length] = "LICENSE.pdf";
        files.put("/maven2/com/qualcomm/qti/qnn-runtime/2.42.0/qnn-runtime-2.42.0.aar", aar(entries));

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange x) throws java.io.IOException {
                byte[] body = files.get(x.getRequestURI().toString());
                if (body == null) {
                    x.sendResponseHeaders(404, -1);
                    x.close();
                    return;
                }
                x.sendResponseHeaders(200, body.length);
                OutputStream o = x.getResponseBody();
                o.write(body);
                x.close();
            }
        });
        srv.start();
        String maven = "http://127.0.0.1:" + srv.getAddress().getPort() + "/maven2";

        check("81".equals(QnnRuntime.htpArch("SM8850")) && "79".equals(QnnRuntime.htpArch("SM8750"))
                && "75".equals(QnnRuntime.htpArch("SM8650")) && "73".equals(QnnRuntime.htpArch("SM8550"))
                && QnnRuntime.htpArch("Tensor G4") == null && QnnRuntime.htpArch(null) == null, "Hexagon version by chip");

        File dir = new File(System.getProperty("java.io.tmpdir"), "qnn-install-" + System.nanoTime());
        QnnRuntime rt = new QnnRuntime(maven, dir);
        check(rt.installed() == null, "nothing installed yet");
        QnnRuntime.Installed i = rt.install("SM8850", null);
        check(i != null && "81".equals(i.arch) && "1.29.0".equals(i.ort) && "2.42.0".equals(i.qnn), "installed for V81");
        java.util.Set<String> want = new java.util.HashSet<String>(java.util.Arrays.asList("libonnxruntime.so",
                "libonnxruntime4j_jni.so", "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so", "libQnnHtpV81Stub.so",
                "libQnnHtpV81Skel.so"));
        check(i != null && new java.util.HashSet<String>(i.libs).equals(want), "only what the NPU needs: " + (i == null ? null : i.libs));
        String[] onDisk = rt.libDir().list();
        check(onDisk != null && onDisk.length == want.size(), "nothing else extracted (DSP, GPU, other Hexagon versions, x86)");
        boolean aarLeft = false;
        for (File f : dir.listFiles()) aarLeft |= f.getName().endsWith(".aar");
        check(!aarLeft, "packages removed after extraction");
        new File(rt.libDir(), "libQnnHtpV81Stub.so").delete();
        check(rt.installed() == null, "a missing library means not installed");

        LiteRtRuntime.deleteTree(dir);
        QnnRuntime any = new QnnRuntime(maven, dir);
        QnnRuntime.Installed all = any.install("SM0000", null);
        check(all != null && "all".equals(all.arch) && all.libs.contains("libQnnHtpV73Stub.so") && all.libs.contains("libQnnHtpV81Skel.so")
                && !all.libs.contains("libQnnDsp.so"), "unknown chip: every Hexagon version kept");

        LiteRtRuntime.deleteTree(dir);
        files.put("/maven2/com/qualcomm/qti/qnn-runtime/2.42.0/qnn-runtime-2.42.0.aar",
                aar("jni/arm64-v8a/libQnnHtp.so", "jni/arm64-v8a/libQnnSystem.so", "jni/arm64-v8a/libQnnHtpV73Stub.so"));
        try {
            new QnnRuntime(maven, dir).install("SM8850", null);
            check(false, "a chip without its Hexagon libraries is refused");
        } catch (java.io.IOException e) {
            check(e.getMessage().contains("V81"), "a chip without its Hexagon libraries is refused: " + e.getMessage());
        }
        srv.stop(0);
        LiteRtRuntime.deleteTree(dir);
        System.out.println(bad == 0 ? "QNN INSTALL OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
