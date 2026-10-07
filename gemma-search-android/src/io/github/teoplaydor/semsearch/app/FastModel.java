package io.github.teoplaydor.semsearch.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.github.teoplaydor.semsearch.core.HfRepo;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.PatternSource;
import io.github.teoplaydor.semsearch.core.SigLip;

/**
 * The fast photo models (SigLIP 2 B/16 and B/32): where their files live, which Hub repo they come
 * from, which accelerator the auto-check chose, and the measurement it is based on.
 */
final class FastModel {
    /** Photo model choices (pref "photo_model"). */
    static final int GEMMA = 0, B16 = 1, B32 = 2;
    static final String[] NAMES = {"EmbeddingGemma 2", "SigLIP 2 B/16", "SigLIP 2 B/32"};
    static final String[] HINTS = {"подробно, но медленно", "быстро, понимает русский", "ещё быстрее, чуть грубее"};
    static final String[] REPOS = {null, "onnx-community/siglip2-base-patch16-224-ONNX",
            "onnx-community/siglip2-base-patch32-256-ONNX"};
    /** Hub search used when the default repo is gone or renamed. */
    static final String[] SEARCH = {null, "siglip2-base-patch16-224", "siglip2-base-patch32-256"};

    private FastModel() {}

    static File dir(Context c, int pm) {
        return new File(c.getFilesDir(), pm == B32 ? "siglip-b32" : "siglip-b16");
    }

    static File manifest(Context c, int pm) {
        return new File(dir(c, pm), "manifest.json");
    }

    static String repo(SharedPreferences p, int pm) {
        return p.getString("s_repo_" + pm, REPOS[pm]);
    }

    static HfRepo.Plan plan(Context c, int pm) {
        try {
            File m = manifest(c, pm);
            return m.exists() ? HfRepo.loadManifest(m) : null;
        } catch (IOException e) {
            return null;
        }
    }

    static boolean ready(Context c, int pm) {
        HfRepo.Plan p = plan(c, pm);
        return p != null && HfRepo.isComplete(p, dir(c, pm));
    }

    /** The full-precision vision graph NPU/GPU runs need, when it is downloaded. */
    static File fp32Vision(Context c, int pm) {
        HfRepo.Plan p = plan(c, pm);
        if (p == null || p.accelVision == null) return null;
        File f = new File(dir(c, pm), p.accelVision);
        return f.exists() ? f : null;
    }

    /**
     * The repo's file plan: the configured repo first; if it is missing or has another layout, the most
     * downloaded Hub repo whose name matches and that has the two ONNX towers.
     * @return {repo id, plan}
     */
    static Object[] resolve(String preferred, String search, String token, boolean fp32) throws IOException {
        IOException first;
        try {
            return new Object[]{preferred, HfRepo.planTowers(new HfRepo(preferred, token).listFiles(), fp32)};
        } catch (IOException e) {
            first = e;
        }
        List<String> ids;
        try {
            ids = new HfRepo("", token).search(search);
        } catch (IOException e) {
            throw first;
        }
        int tried = 0;
        for (String id : ids) {
            if (id.equals(preferred) || !id.toLowerCase(java.util.Locale.ROOT).contains("onnx")) continue;
            if (++tried > 6) break;
            try {
                return new Object[]{id, HfRepo.planTowers(new HfRepo(id, token).listFiles(), fp32)};
            } catch (IOException ignored) {
                // next candidate
            }
        }
        throw first;
    }

    // ------------------------------------------------------------------ accelerator choice

    static SigLip.Accel accel(SharedPreferences p, int pm) {
        int a = p.getInt("s_accel_" + pm, 0);
        SigLip.Accel[] all = SigLip.Accel.values();
        SigLip.Accel r = all[Math.max(0, Math.min(all.length - 1, a))];
        return p.getBoolean("s_broken_" + r.name(), false) ? SigLip.Accel.CPU : r;
    }

    static int batch(SharedPreferences p, int pm) {
        return Math.max(1, Math.min(8, p.getInt("s_batch_" + pm, 1)));
    }

    static int threads(SharedPreferences p, int pm) {
        int t = p.getInt("s_threads_" + pm, 0);
        return t > 0 ? t : Engine.autoThreads();
    }

    static boolean checked(SharedPreferences p, int pm) {
        return p.getBoolean("s_checked_" + pm, false);
    }

    /** True when the phone probably has an NPU or a usable GPU worth downloading the fp32 graph for. */
    static boolean acceleratorLikely() {
        if (Build.VERSION.SDK_INT < 27) return false; // NNAPI 1.1+
        try {
            String soc = (String) Build.class.getField("SOC_MANUFACTURER").get(null); // API 31
            if (soc != null) {
                String s = soc.toLowerCase(java.util.Locale.ROOT);
                return s.contains("qti") || s.contains("qualcomm") || s.contains("mediatek") || s.contains("samsung")
                        || s.contains("google") || s.contains("hisilicon") || s.contains("unisoc");
            }
        } catch (Exception ignored) {
            // older Android: try it anyway
        }
        return true;
    }

    // ------------------------------------------------------------------ measurement

    static final class Measure {
        double perPhotoMs = Double.MAX_VALUE, loadMs;
        float minCos = 1f;
        float[][] embs;
        String error;
    }

    /** Synthetic photos of different content, the same for every candidate. */
    static List<ImagePreprocessor.Source> probes(int n) {
        List<ImagePreprocessor.Source> imgs = new ArrayList<ImagePreprocessor.Source>();
        for (int k = 0; k < n; k++) imgs.add(new PatternSource(640, 480, 3 + k));
        return imgs;
    }

    /**
     * Loads the vision tower with one accelerator, embeds the probes (after a warm-up run, which also
     * compiles NNAPI/GPU programs) and compares each vector with the reference run.
     */
    static Measure measure(File dir, File vision, SigLip.Accel accel, int threads, int batch, float[][] reference) {
        Measure r = new Measure();
        SigLip m = null;
        try {
            long t0 = System.currentTimeMillis();
            m = new SigLip(dir, null, vision, accel, threads, batch);
            List<ImagePreprocessor.Source> imgs = probes(Math.max(batch, 4));
            List<ImagePreprocessor.Source> one = imgs.subList(0, batch);
            m.embedImages(one, 0); // warm-up
            r.loadMs = System.currentTimeMillis() - t0;
            for (int run = 0; run < 2; run++) {
                long s = System.nanoTime();
                m.embedImages(one, 0);
                r.perPhotoMs = Math.min(r.perPhotoMs, (System.nanoTime() - s) / 1e6 / batch);
            }
            r.embs = m.embedImages(imgs.subList(0, 4), 0);
            if (reference != null) {
                for (int i = 0; i < 4; i++) {
                    float c = 0;
                    for (int j = 0; j < reference[i].length; j++) c += reference[i][j] * r.embs[i][j];
                    r.minCos = Math.min(r.minCos, c);
                }
            }
        } catch (Throwable e) {
            r.error = e.getMessage() != null ? e.getMessage().split("\n")[0] : e.toString();
        } finally {
            if (m != null) m.close();
        }
        return r;
    }
}
