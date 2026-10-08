import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.MainActivity;

/**
 * The fp16 and LiteRT-LM variants in the app: a crash inside LiteRT-LM disables it on the next start,
 * variants that are not on the phone fall back to the CPU, the settings offer the download, the deletion
 * of unused variants and — when the speed check found LiteRT-LM faster but with other vectors — the move
 * to it with a re-index, and the way back. (The native runtime itself is covered by LiteRtJniTest.)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SpeedupsTest {
    static void write(File f, int bytes) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(new byte[bytes]);
        }
    }

    static void writeText(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes("UTF-8"));
        }
    }

    /** EmbeddingGemma 2 on disk as the manifest describes it (4-bit graphs, optionally the fp16 vision graph). */
    static void gemma(File model, boolean fp16) throws Exception {
        String[] files = fp16 ? new String[]{"config.json", "onnx/model_q4.onnx", "onnx/vision_encoder_q4.onnx",
                "onnx/vision_encoder_fp16.onnx", "onnx/vision_encoder_fp16.onnx_data"}
                : new String[]{"config.json", "onnx/model_q4.onnx", "onnx/vision_encoder_q4.onnx"};
        StringBuilder m = new StringBuilder("{\"repo\":\"test/gemma\",\"text\":\"onnx/model_q4.onnx\",\"vision\":"
                + "\"onnx/vision_encoder_q4.onnx\"" + (fp16 ? ",\"fp16_vision\":\"onnx/vision_encoder_fp16.onnx\"" : "") + ",\"files\":[");
        for (int i = 0; i < files.length; i++) {
            File f = new File(model, files[i]);
            write(f, files[i].contains("fp16") ? 2 << 20 : 1000);
            m.append(i > 0 ? "," : "").append("{\"path\":\"").append(files[i]).append("\",\"size\":").append(f.length()).append('}');
        }
        writeText(new File(model, "manifest.json"), m.append("]}").toString());
    }

    /** LiteRT-LM as LiteRtRuntime.install leaves it (the library is junk: it cannot load in this JVM). */
    static File liteRt(Context c) throws Exception {
        File dir = new File(c.getFilesDir(), "litertlm");
        write(new File(dir, "lib/liblitertlm_jni.so"), 3 << 20);
        write(new File(dir, "embeddinggemma-2-440m.litertlm"), 1 << 20);
        writeText(new File(dir, "manifest.json"), "{\"version\":\"0.18.0\",\"repo\":\"litert-community/embeddinggemma-2-440m-litert-lm\","
                + "\"model\":\"embeddinggemma-2-440m.litertlm\",\"model_size\":" + (1 << 20) + ",\"libs\":[\"liblitertlm_jni.so\"]}");
        return dir;
    }

    /** The settings panel follows the engine; nudge it after changing prefs behind its back. */
    static void refresh(View root) throws Exception {
        Robo.call(Robo.byName(root, "SettingsPanel"), "onEngineChanged");
        Robo.settle(300);
    }

    static String text(MainActivity a) {
        return Robo.allText(a.getWindow().getDecorView());
    }

    static void click(View root, String label) {
        TextView hit = null;
        for (View v : Robo.views(root, new java.util.ArrayList<View>())) {
            if (v instanceof TextView && v.isShown() && String.valueOf(((TextView) v).getText()).equals(label)) hit = (TextView) v;
        }
        assertNotNull("no visible \"" + label + "\"", hit);
        hit.performClick();
    }

    @Test
    public void fp16AndLiteRt() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        // The previous run died inside LiteRT-LM's GPU backend: it must not be tried again.
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("litert_probe", "7")
                .putInt("accel", Engine.ACCEL_LITERT_GPU).putInt("photo_model", 0).apply();
        FakeMediaStore.install();
        final MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        final Engine e = Engine.get(a);
        Robo.waitFor("store", () -> e.store() != null);
        assertTrue(e.liteRtBroken(Engine.ACCEL_LITERT_GPU));
        assertEquals(Engine.ACCEL_CPU, e.prefs().getInt("accel", -1));
        e.prefs().edit().remove("litert_broken_7").apply();

        // Nothing extra on the phone: fp16 / LiteRT-LM choices fall back to the CPU, the settings offer the download.
        File model = new File(a.getFilesDir(), "model");
        gemma(model, false);
        e.attachModelForTest(new Robo.FakeEmbedder());
        Robo.waitFor("ready", e::ready);
        for (int acc : new int[]{Engine.ACCEL_GPU_FP16, Engine.ACCEL_LITERT_GPU, Engine.ACCEL_LITERT_CPU}) {
            e.prefs().edit().putInt("accel", acc).apply();
            assertEquals("accel " + acc, Engine.ACCEL_CPU, e.accel());
        }
        e.prefs().edit().putInt("accel", Engine.ACCEL_CPU).apply();
        assertTrue(e.speedupsMissing());
        Robo.call(a, "openSettings");
        Robo.settle(500);
        View root = a.getWindow().getDecorView();
        assertTrue(text(a), text(a).contains("Проверить LiteRT-LM и fp16"));
        click(root, "Проверить LiteRT-LM и fp16");
        Robo.settle(400);
        assertTrue(text(a), text(a).contains("fp16-версия визуальной части для видеокарты"));
        assertTrue(text(a), text(a).contains("LiteRT-LM — движок Google"));
        a.onBackPressed(); // closes the sheet without downloading
        Robo.settle(400);
        a.onBackPressed();
        Robo.settle(500);

        // Both downloaded: usable, the download button goes, unused ones can be deleted.
        gemma(model, true);
        File lrt = liteRt(a);
        assertFalse(e.speedupsMissing());
        assertTrue(e.liteRtInstalled());
        e.prefs().edit().putInt("accel", Engine.ACCEL_GPU_FP16).apply();
        assertEquals(Engine.ACCEL_GPU_FP16, e.accel());
        e.prefs().edit().putInt("accel", Engine.ACCEL_LITERT_CPU).apply();
        assertEquals(Engine.ACCEL_LITERT_CPU, e.accel());
        e.prefs().edit().putInt("accel", Engine.ACCEL_GPU_INT8).apply();
        Robo.call(a, "openSettings");
        Robo.settle(500);
        root = a.getWindow().getDecorView();
        assertFalse(text(a), text(a).contains("Проверить LiteRT-LM и fp16"));
        assertTrue(text(a), text(a).contains("Удалить fp16-версию (4 МБ)"));
        assertTrue(text(a), text(a).contains("Удалить LiteRT-LM (4 МБ)"));
        assertFalse(text(a), text(a).contains("Перейти на LiteRT-LM"));

        // The speed check found LiteRT-LM faster but with other vectors: the move is offered, with a re-index.
        e.prefs().edit().putInt("litert_offer", Engine.ACCEL_LITERT_GPU).putInt("litert_offer_ms", 300).apply();
        refresh(root);
        assertEquals(Engine.ACCEL_LITERT_GPU, e.liteRtOffer());
        click(root, "Перейти на LiteRT-LM");
        Robo.settle(400);
        assertTrue(text(a), text(a).contains("индекс будет построен заново"));
        click(root, "Перейти");
        Robo.settle(300);
        assertTrue(e.liteRtSpace());
        assertEquals(Engine.ACCEL_LITERT_GPU, e.prefs().getInt("accel", -1));
        assertTrue(e.prefs().getBoolean("reindex_pending", false));
        assertEquals(-1, e.liteRtOffer());
        // its library cannot load here: the load fails and points the way back (no silent fallback to ONNX)
        Robo.waitFor("load attempt", () -> e.state == Engine.State.ERROR);
        System.out.println("LiteRT-LM load here: " + e.status);
        assertTrue(e.status, e.status.contains("LiteRT-LM не запустился") && e.status.contains("Вернуться на ONNX Runtime"));
        assertEquals("no fallback to the ONNX model in LiteRT-LM's space", Engine.ACCEL_LITERT_GPU, e.prefs().getInt("accel", -1));
        assertNull("a load error is not a crash", e.prefs().getString("litert_probe", null));
        // in LiteRT-LM's space a broken GPU backend falls back to its CPU backend, never to the ONNX model
        e.prefs().edit().putBoolean("litert_broken_7", true).apply();
        assertEquals(Engine.ACCEL_LITERT_CPU, e.accel());
        e.prefs().edit().remove("litert_broken_7").apply();
        e.deleteLiteRt(); // refused while the index holds its vectors
        Robo.settle(300);
        assertTrue(lrt.exists());
        refresh(root);
        assertTrue(text(a), text(a).contains("Вернуться на ONNX Runtime"));
        click(root, "Вернуться на ONNX Runtime");
        Robo.settle(400);
        click(root, "Вернуться");
        Robo.settle(300);
        assertFalse(e.liteRtSpace());
        assertEquals(Engine.ACCEL_CPU, e.prefs().getInt("accel", -1));
        assertTrue(e.prefs().getBoolean("reindex_pending", false));
        assertTrue(e.prefs().getBoolean("speed_check_pending", false));
        Robo.settle(500);

        // Snapdragon: the NPU is reached through QNN (its own button, the NNAPI one is not offered there).
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8850");
        org.robolectric.shadows.ShadowBuild.setSystemOnChipManufacturer("QTI");
        assertTrue(Engine.isSnapdragon());
        assertTrue("no QNN, no fp32 graph", e.qnnMissing());
        e.prefs().edit().putInt("accel", Engine.ACCEL_NPU_QNN).apply();
        assertEquals(Engine.ACCEL_CPU, e.accel());
        e.prefs().edit().putInt("accel", Engine.ACCEL_CPU).apply();
        refresh(root);
        assertTrue(text(a), text(a).contains("Проверить NPU Snapdragon"));
        assertFalse(text(a), text(a).contains("Проверить NPU\n") || text(a).contains("| Проверить NPU |"));
        click(root, "Проверить NPU Snapdragon");
        Robo.settle(400);
        assertTrue(text(a), text(a).contains("через Qualcomm QNN") && text(a).contains("отдельном процессе"));
        a.onBackPressed();
        Robo.settle(400);
        // QNN and the full-precision vision graph in place: usable, the download goes, the delete link appears
        File q = new File(a.getFilesDir(), "qnn/lib");
        String[] qlibs = {"libonnxruntime.so", "libonnxruntime4j_jni.so", "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so",
                "libQnnHtpV81Stub.so", "libQnnHtpV81Skel.so"};
        StringBuilder ql = new StringBuilder();
        for (String l : qlibs) {
            write(new File(q, l), l.contains("Prepare") ? 2 << 20 : 1000);
            ql.append(ql.length() > 0 ? "," : "").append('"').append(l).append('"');
        }
        writeText(new File(a.getFilesDir(), "qnn/manifest.json"), "{\"ort\":\"1.29.0\",\"qnn\":\"2.42.0\",\"arch\":\"81\",\"libs\":[" + ql + "]}");
        String m2 = new String(java.nio.file.Files.readAllBytes(new File(model, "manifest.json").toPath()), "UTF-8");
        write(new File(model, "onnx/vision_encoder.onnx"), 1 << 20);
        writeText(new File(model, "manifest.json"), m2.replace("\"vision\":", "\"accel_vision\":\"onnx/vision_encoder.onnx\",\"vision\":")
                .replace("\"files\":[", "\"files\":[{\"path\":\"onnx/vision_encoder.onnx\",\"size\":" + (1 << 20) + "},"));
        assertTrue(e.qnnInstalled());
        assertTrue(e.qnnUsable());
        assertFalse(e.qnnMissing());
        e.prefs().edit().putInt("accel", Engine.ACCEL_NPU_QNN).apply();
        assertEquals(Engine.ACCEL_NPU_QNN, e.accel());
        e.prefs().edit().putBoolean("qnn_broken", true).apply(); // the NPU process crashed once
        assertEquals(Engine.ACCEL_CPU, e.accel());
        e.prefs().edit().putBoolean("qnn_broken", false).putInt("accel", Engine.ACCEL_CPU).apply();
        refresh(root);
        assertFalse(text(a), text(a).contains("Проверить NPU Snapdragon"));
        assertTrue(text(a), text(a).contains("Удалить NPU Snapdragon (2 МБ)"));
        // The NPU process round trip (bound service, interface token, status, error text): here the "libraries"
        // are junk, so starting fails — with the reason, not a hang or a crash.
        Class<?> svc = Class.forName("io.github.teoplaydor.semsearch.app.NpuService");
        @SuppressWarnings("unchecked")
        android.app.Service service = Robolectric.buildService((Class<android.app.Service>) svc).create().get();
        org.robolectric.Shadows.shadowOf(a.getApplication()).setComponentNameAndServiceForBindService(
                new android.content.ComponentName(a, svc), service.onBind(new android.content.Intent(a, svc)));
        final Object[] npu = new Object[1];
        Thread t = new Thread(() -> {
            try {
                java.lang.reflect.Constructor<?> k = Class.forName("io.github.teoplaydor.semsearch.app.NpuVision")
                        .getDeclaredConstructor(Context.class, File.class, File.class);
                k.setAccessible(true);
                npu[0] = k.newInstance(a, q, new File(model, "onnx/vision_encoder.qnn.onnx"));
            } catch (java.lang.reflect.InvocationTargetException x) {
                npu[0] = x.getCause();
            } catch (Exception x) {
                npu[0] = x;
            }
        });
        t.start();
        Robo.waitFor("npu start", () -> npu[0] != null);
        assertTrue(String.valueOf(npu[0]), npu[0] instanceof java.io.IOException
                && ((Exception) npu[0]).getMessage().startsWith("NPU: ") && ((Exception) npu[0]).getMessage().contains("libQnn"));
        System.out.println("NPU process with junk libraries: " + ((Exception) npu[0]).getMessage());
        // With a real ONNX Runtime (the desktop build, which has no QNN) the process starts and a run fails inside
        // ONNX Runtime, with its reason. After the last unbind Android makes a new service object in the same
        // process, where the libraries are already loaded: that one must reach the runtime too (0.9.1 did not).
        File fx = new File(System.getProperty("npu.fixture"));
        for (String l : new String[]{"libonnxruntime.so", "libonnxruntime4j_jni.so", "libQnnSystem.so", "libQnnHtpPrepare.so",
                "libQnnHtp.so", "libQnnHtpV81Stub.so"}) {
            java.nio.file.Files.copy(new File(fx, l).toPath(), new File(q, l).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        System.setProperty("onnxruntime.native.path", q.getAbsolutePath()); // where the desktop build looks
        final File vit = new File(model, "onnx/vit.qnn.onnx");
        java.nio.file.Files.copy(new File(fx, "vit.onnx").toPath(), vit.toPath());
        java.lang.reflect.Field env = svc.getDeclaredField("env");
        env.setAccessible(true);
        for (int round = 0; round < 2; round++) {
            android.app.Service s = service;
            if (round == 1) {
                @SuppressWarnings("unchecked")
                android.app.Service fresh = Robolectric.buildService((Class<android.app.Service>) svc).create().get();
                s = fresh;
                org.robolectric.Shadows.shadowOf(a.getApplication()).setComponentNameAndServiceForBindService(
                        new android.content.ComponentName(a, svc), s.onBind(new android.content.Intent(a, svc)));
            }
            final Object[] run = new Object[1];
            Thread rt = new Thread(() -> {
                io.github.teoplaydor.semsearch.core.VisionRunner v = null;
                try {
                    java.lang.reflect.Constructor<?> k = Class.forName("io.github.teoplaydor.semsearch.app.NpuVision")
                            .getDeclaredConstructor(Context.class, File.class, File.class);
                    k.setAccessible(true);
                    v = (io.github.teoplaydor.semsearch.core.VisionRunner) k.newInstance(a, q, vit);
                    run[0] = v.run(new float[24 * 32], new long[24 * 2], 1, 24, 32);
                } catch (java.lang.reflect.InvocationTargetException x) {
                    run[0] = x.getCause();
                } catch (Exception x) {
                    run[0] = x;
                } finally {
                    if (v != null) v.close();
                }
            });
            rt.start();
            Robo.waitFor("npu run " + round, () -> run[0] != null);
            assertTrue(round + ": " + run[0], run[0] instanceof java.io.IOException && ((Exception) run[0]).getMessage().startsWith("NPU: ")
                    && ((Exception) run[0]).getMessage().contains("QNN") && !((Exception) run[0]).getMessage().contains("null"));
            assertNotNull("service object " + round + " has no ONNX Runtime", env.get(s));
            System.out.println("NPU process, service object " + round + ": " + ((Exception) run[0]).getMessage().trim());
        }
        e.deleteQnn();
        Robo.waitFor("qnn deleted", () -> !new File(a.getFilesDir(), "qnn").exists());
        assertFalse(e.qnnInstalled());
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("");
        org.robolectric.shadows.ShadowBuild.setSystemOnChipManufacturer("");

        // A throttled phone measures several times slower (seen: 9% battery while charging): the check says so.
        android.content.Intent bat = new android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED);
        bat.putExtra(android.os.BatteryManager.EXTRA_LEVEL, 9).putExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                .putExtra(android.os.BatteryManager.EXTRA_PLUGGED, android.os.BatteryManager.BATTERY_PLUGGED_AC);
        a.getApplication().sendStickyBroadcast(bat);
        assertEquals("заряд 9%", Robo.callStatic(Engine.class, "slowdown", a));
        String cond = (String) Robo.callStatic(Engine.class, "conditions", a);
        assertTrue(cond, cond.startsWith("заряд 9% (заряжается), экономия батареи выключена"));
        bat.putExtra(android.os.BatteryManager.EXTRA_LEVEL, 80);
        a.getApplication().sendStickyBroadcast(bat);
        assertNull(Robo.callStatic(Engine.class, "slowdown", a));
        org.robolectric.Shadows.shadowOf((android.os.PowerManager) a.getSystemService(Context.POWER_SERVICE)).setIsPowerSaveMode(true);
        assertEquals("включена экономия батареи", Robo.callStatic(Engine.class, "slowdown", a));
        org.robolectric.Shadows.shadowOf((android.os.PowerManager) a.getSystemService(Context.POWER_SERVICE)).setIsPowerSaveMode(false);

        // Unused variants go on request; the manifest forgets the fp16 graph, the 4-bit model stays.
        e.deleteGemmaFp16();
        e.deleteLiteRt();
        Robo.waitFor("deleted", () -> !new File(model, "onnx/vision_encoder_fp16.onnx").exists() && !lrt.exists());
        assertTrue(new File(model, "onnx/vision_encoder_q4.onnx").exists());
        assertTrue(e.speedupsMissing());
        assertTrue(e.hasModelFiles());
        a.finish();
    }
}
