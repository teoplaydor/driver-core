package io.github.teoplaydor.semsearch.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;

import io.github.teoplaydor.semsearch.core.GemmaChat;
import io.github.teoplaydor.semsearch.core.HfRepo;
import io.github.teoplaydor.semsearch.core.LiteRtEmbedder;
import io.github.teoplaydor.semsearch.core.LiteRtRuntime;

/**
 * Gemma 4 E2B for answers in words (optional, ~2.6 GB, for phones with memory to spare): downloaded with its LiteRT-LM
 * runtime, loaded on the first question (on the GPU, else the CPU), let go after a few idle minutes. A native crash
 * while loading turns it off on this phone until it is tried again by hand.
 */
final class Chat {
    static final String PREF_ON = "chat_on", PREF_PROBE = "chat_probe", PREF_BROKEN = "chat_broken";
    private static final long IDLE_MS = 3 * 60_000L, MAX_ANSWER_MS = 90_000L;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final LiteRtRuntime rt;
    private GemmaChat gemma;
    private final Handler timer;
    /** Downloading: bytes so far and in all (0: not downloading); the last error. */
    volatile long dlDone, dlTotal;
    volatile boolean downloading;
    volatile String error;
    private final Runnable idle = new Runnable() {
        @Override
        public void run() {
            release();
        }
    };

    Chat(Context c, SharedPreferences prefs, String token) {
        ctx = c.getApplicationContext();
        this.prefs = prefs;
        rt = LiteRtRuntime.forChat(null, null, new File(ctx.getFilesDir(), "gemma"), token);
        HandlerThread t = new HandlerThread("chat-timer");
        t.start();
        timer = new Handler(t.getLooper());
        String probe = prefs.getString(PREF_PROBE, null);
        if (probe != null) {
            // the last run died while loading Gemma (a native crash): not again by itself
            prefs.edit().remove(PREF_PROBE).putBoolean(PREF_BROKEN, true).apply();
        }
    }

    boolean installed() {
        return rt.installed() != null;
    }

    /** On: downloaded, turned on, and not known to crash here. */
    boolean on() {
        return installed() && prefs.getBoolean(PREF_ON, true) && !prefs.getBoolean(PREF_BROKEN, false);
    }

    boolean broken() {
        return prefs.getBoolean(PREF_BROKEN, false);
    }

    void setOn(boolean on) {
        prefs.edit().putBoolean(PREF_ON, on).putBoolean(PREF_BROKEN, false).apply();
        if (!on) release();
    }

    long bytesOnDisk() {
        File d = rt.dir();
        return d.exists() ? rt.bytesOnDisk() : 0;
    }

    /** The phone's memory in GB (Gemma 4 E2B wants about 1.7 GB of it free on the CPU, less on the GPU). */
    static double memoryGb(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 0;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem / 1073741824.0;
    }

    /** Download (resuming what is there) on a thread of its own; {@code changed} on progress and at the end. */
    synchronized void download(final Runnable changed) {
        if (downloading) return;
        downloading = true;
        error = null;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    rt.install(new HfRepo.Progress() {
                        long last;

                        @Override
                        public boolean onProgress(String file, long fileDone, long fileTotal, long allDone, long allTotal) {
                            dlDone = allDone;
                            dlTotal = allTotal;
                            long now = System.currentTimeMillis();
                            if (now - last > 400) {
                                last = now;
                                changed.run();
                            }
                            return downloading;
                        }
                    });
                    prefs.edit().putBoolean(PREF_ON, true).putBoolean(PREF_BROKEN, false).apply();
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : e.toString();
                } finally {
                    downloading = false;
                    dlDone = dlTotal = 0;
                    changed.run();
                }
            }
        }, "chat-download").start();
    }

    void stopDownload() {
        downloading = false;
    }

    synchronized void delete() {
        release();
        LiteRtRuntime.deleteTree(rt.dir());
        prefs.edit().remove(PREF_BROKEN).apply();
    }

    /** Gemma's answer to the prompt (loading it first if needed); stopped after a minute and a half. */
    String generate(String prompt) throws Exception {
        final GemmaChat g = load();
        timer.removeCallbacks(idle);
        Runnable stop = new Runnable() {
            @Override
            public void run() {
                g.cancel();
            }
        };
        timer.postDelayed(stop, MAX_ANSWER_MS);
        try {
            return g.generate(prompt);
        } finally {
            timer.removeCallbacks(stop);
            timer.postDelayed(idle, IDLE_MS);
        }
    }

    private synchronized GemmaChat load() throws Exception {
        if (gemma != null) return gemma;
        LiteRtRuntime.Installed i = rt.installed();
        if (i == null) throw new IllegalStateException("Gemma не скачана");
        LiteRtRuntime.load(rt.libDir(), i.libs);
        File cache = new File(rt.dir(), "cache");
        cache.mkdirs();
        Exception first = null;
        for (String backend : new String[]{LiteRtEmbedder.GPU, LiteRtEmbedder.CPU}) {
            prefs.edit().putString(PREF_PROBE, backend).commit();
            try {
                gemma = new GemmaChat(rt.modelFile(i), backend, 2048, Math.max(2, Runtime.getRuntime().availableProcessors() / 2), cache);
                prefs.edit().remove(PREF_PROBE).commit();
                return gemma;
            } catch (Exception e) {
                if (first == null) first = e;
            }
        }
        prefs.edit().remove(PREF_PROBE).commit();
        throw first;
    }

    /** Gemma's memory given back. */
    synchronized void release() {
        timer.removeCallbacks(idle);
        if (gemma != null) {
            gemma.close();
            gemma = null;
        }
    }
}
