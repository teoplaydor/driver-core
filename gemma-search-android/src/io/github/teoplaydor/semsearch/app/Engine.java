package io.github.teoplaydor.semsearch.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.teoplaydor.semsearch.core.AdultFilter;
import io.github.teoplaydor.semsearch.core.Embedder;
import io.github.teoplaydor.semsearch.core.EmbeddingGemma2;
import io.github.teoplaydor.semsearch.core.HfRepo;
import io.github.teoplaydor.semsearch.core.HfTokenizer;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.LiteRtEmbedder;
import io.github.teoplaydor.semsearch.core.LiteRtRuntime;
import io.github.teoplaydor.semsearch.core.ModelConfig;
import io.github.teoplaydor.semsearch.core.OnnxPatcher;
import io.github.teoplaydor.semsearch.core.OrtProfile;
import io.github.teoplaydor.semsearch.core.PatternSource;
import io.github.teoplaydor.semsearch.core.People;
import io.github.teoplaydor.semsearch.core.PhotoTags;
import io.github.teoplaydor.semsearch.core.QnnRuntime;
import io.github.teoplaydor.semsearch.core.QueryBridge;
import io.github.teoplaydor.semsearch.core.SigLip;
import io.github.teoplaydor.semsearch.core.StageProgress;
import io.github.teoplaydor.semsearch.core.VectorMath;

/**
 * App-wide state: model download/loading, the vector index and the indexing loop.
 * All model calls run on one background thread; indexing is chunked into one task per
 * media item so searches stay responsive while the gallery is being indexed.
 */
public final class Engine {
    public enum State { NO_MODEL, DOWNLOADING, LOADING, READY, ERROR }

    public interface Listener {
        void onEngineChanged();
    }

    public interface Callback<T> {
        void done(T result, Exception error);
    }

    /** Photo detail presets (soft tokens per image): fast / balanced / max. */
    public static final int[] PHOTO_BUDGETS = {70, 140, 280};
    public static final int VIDEO_FRAMES = 4;

    private static Engine instance;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService ml = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    /**
     * Decodes the next photo while the current one is being embedded. Replaced when a decode hangs (a file that never
     * opens: its thread stays stuck, the next files go to a new one).
     */
    private volatile ExecutorService decoder = Executors.newSingleThreadExecutor();
    /** How long a photo's decoding or a video's frames may take before the file counts as unreadable. */
    static volatile int openTimeoutS = 60;
    /** Test hook: files of these names hang when opened (a photo's decoding, a video's frames) until interrupted. */
    public static volatile java.util.Set<String> hangForTest;

    private static void hangIfTest(Media.Entry e) throws InterruptedException {
        java.util.Set<String> h = hangForTest;
        if (h != null && e.name != null && h.contains(e.name)) Thread.sleep(Long.MAX_VALUE);
    }
    /**
     * The indexing pipeline's text stage: the text model (CPU) of one batch runs here while the vision encoder (the
     * NPU, the GPU) takes the next batch on {@link #ml}.
     */
    private final ExecutorService textStage = Executors.newSingleThreadExecutor();
    private long sumWaitMs, sumVisionMs, sumTextMs;
    private int timedPhotos;
    public volatile int threads;
    public volatile int loadedAccel;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    final SharedPreferences prefs;
    final File modelDir;
    private final File manifest;

    private IndexStore store;
    /** Notes and other text: EmbeddingGemma 2, or the photo model's text tower when it is the only model. */
    private Embedder model;
    /** Photos and videos: EmbeddingGemma 2 or SigLIP 2 (pref "photo_model"). May be the same object as {@link #model}. */
    private Embedder photo;
    private QueryBridge bridge;
    /** The notes model is loaded too (not only the photo model, as for a background run). */
    private volatile boolean loadedFull;
    /** Notes vectors match the loaded notes model (false in a background run that skipped it). */
    private volatile boolean notesUsable;
    public volatile String accelLabel = "";
    /** Last failed download of the fast model's extra files, shown in the settings. */
    public volatile String dlError;

    /** How Russian queries reach photos/videos (pref "bridge_mode"). */
    public static final String[] BRIDGE_MODES = {
            "Русский + английский перевод (рекомендуется)", "Только английский перевод", "Без перевода"};

    public volatile State state = State.NO_MODEL;
    public volatile String status = "";
    /** Full stack trace of the last model error, for "copy details". */
    public volatile String errorDetails;
    public volatile long dlDone, dlTotal;
    private volatile boolean cancelDownload;

    public volatile boolean indexing;
    public volatile int idxDone, idxTotal, idxErrors;
    private volatile String idxFirstError;
    public volatile String idxStatus = "";
    private volatile boolean cancelIndex;
    private final List<Media.Entry> queue = new ArrayList<Media.Entry>();
    private long idxStarted;
    private int idxProcessed;
    /**
     * Files the model failed on one after another, with no success between: after FAIL_STREAK_STOP of them the
     * model is broken, not the files — the run stops, and they are not remembered as failed.
     */
    private final List<Media.Entry> failStreak = new ArrayList<Media.Entry>();
    static final int FAIL_STREAK_STOP = 8;
    /** Why the run stops before its end (the model broke); the files left stay as they are. */
    private volatile String stopError;
    /** The NPU process died during the run: the model is reloaded after it (a new process). */
    private boolean reloadAfterIndex;
    /**
     * The NPU process died while compiling a large graph: its photos go back into the queue and the model is
     * reloaded within the run (a new process compiles the next way); restarts so far.
     */
    private boolean npuRestart;
    private int npuRestarts;
    private final List<Media.Entry> requeue = new ArrayList<Media.Entry>();
    /** Budgets the NPU compiled for in this run (its first photo of a budget waits for the compilation). */
    private final java.util.Set<Integer> qnnSeen = new java.util.HashSet<Integer>();
    /** The QNN vision graph in use and the pooling kernel (patches = budget × pool²), set by openQnn. */
    private volatile File qnnGraphFile;
    private volatile int qnnPool = 1;

    public static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    private Engine(Context ctx) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (prefs.getBoolean("gpu_probe", false)) {
            // The previous run died while creating a GPU session (a driver crash cannot be caught):
            // never try the GPU again on this phone and fall back to the CPU.
            prefs.edit().putBoolean("gpu_broken", true).putBoolean("gpu_probe", false)
                    .putInt("accel", Math.min(prefs.getInt("accel", ACCEL_CPU), ACCEL_CPU_INT8)).apply();
        }
        modelDir = new File(ctx.getFilesDir(), "model");
        manifest = new File(modelDir, "manifest.json");
        if (!prefs.contains("photo_model")) {
            // Existing installs keep EmbeddingGemma 2 for photos; new ones start with the fast model.
            prefs.edit().putInt("photo_model", FastModel.GEMMA).apply();
        }
        CrashLog.install(ctx.getApplicationContext());
        String died = prefs.getString("last_step", "");
        if (!died.isEmpty()) {
            // The previous run ended inside a risky step (model load, warm-up, accelerator check).
            prefs.edit().putString("died_during", died).putString("last_step", "")
                    .putInt("crash_streak", prefs.getInt("crash_streak", 0) + 1).apply();
        }
        String checkProbe = prefs.getString("s_probe_check", null);
        if (checkProbe != null) {
            // It died measuring this accelerator + batch: the auto-check skips it from now on.
            prefs.edit().putBoolean("s_broken_" + checkProbe, true).remove("s_probe_check").apply();
        }
        String npuProbe = prefs.getString("npu_probe", null);
        if (npuProbe != null) {
            // It died starting EmbeddingGemma's vision encoder on the NPU (driver crash): not again.
            prefs.edit().putBoolean("npu_broken_" + npuProbe, true).remove("npu_probe").putInt("accel", 0).apply();
        }
        String liteRtProbe = prefs.getString("litert_probe", null);
        if (liteRtProbe != null) {
            // It died loading LiteRT-LM or starting it on this backend (native crash): not again.
            SharedPreferences.Editor ed = prefs.edit().putBoolean("litert_broken_" + liteRtProbe, true).remove("litert_probe");
            if (!prefs.getBoolean("litert_space", false)) ed.putInt("accel", ACCEL_CPU);
            ed.apply();
        }
        if (!prefs.getBoolean("migrated_070", false)) {
            // 0.7: EmbeddingGemma 2 is the photo model again (SigLIP 2 did not hold up on real photos).
            prefs.edit().putInt("photo_model", FastModel.GEMMA).putBoolean("migrated_070", true).apply();
        }
        String probe = prefs.getString("s_probe", null);
        if (probe != null) {
            // The previous run died while setting up an NPU/GPU session (driver crash): never use that one again.
            prefs.edit().putBoolean("s_broken_" + probe, true).remove("s_probe").apply();
        }
        if (!prefs.contains("media_sig") && prefs.contains("model_sig")) {
            String old = prefs.getString("model_sig", "");
            prefs.edit().putString("media_sig", old).putString("notes_sig", old).apply();
        }
        ml.submit(new Runnable() {
            @Override
            public void run() {
                IndexStore s = new IndexStore(Engine.this.ctx);
                // what is hidden is hidden from the first frame on, before any model is loaded
                applyHidden(s);
                store = s;
                faceStore = new FaceStore(Engine.this.ctx);
                scanFaces();
                try {
                    bridge = QueryBridge.load(Engine.this.ctx.getAssets().open("ru_en_lexicon.txt"));
                } catch (Exception e) {
                    android.util.Log.e("SemSearch", "bridge lexicon", e);
                }
                notifyChanged();
            }
        });
    }

    /** Loads the downloaded model unless it is already loaded or loading (screens and the background job call this). */
    public void ensureLoaded() {
        ensureLoaded(true);
    }

    /**
     * Records the risky native step the app is in (synchronously: a native crash gives no second
     * chance); empty when done. The next start reads it to report and to avoid a crash loop.
     */
    private void mark(String step) {
        prefs.edit().putString("last_step", step).commit();
    }

    /** "Retry" after an error, including after crashes during loading: try once more. */
    public void retryLoad() {
        prefs.edit().putInt("crash_streak", 0).apply();
        loadModel(true);
    }

    /** For the background job: the photo model is enough, the notes model stays on disk. */
    public void ensureLoadedForIndexing() {
        ensureLoaded(false);
    }

    private void ensureLoaded(boolean full) {
        if (!hasModelFiles()) return;
        if (state == State.NO_MODEL && prefs.getInt("crash_streak", 0) >= 2) {
            // Closed twice in a row while loading: don't load automatically into a third crash.
            state = State.ERROR;
            status = "Приложение закрывалось при загрузке модели (" + prefs.getString("died_during", "") + "). "
                    + "Нажмите «Повторить» или выберите другую модель для фото.";
            errorDetails = CrashLog.report(ctx);
            notifyChanged();
            return;
        }
        if (state == State.NO_MODEL) loadModel(full);
        else if (state == State.READY && full && !loadedFull) loadModel(true);
    }

    /** True while a screen of the app is visible; the background job unloads the model only when it is not. */
    public volatile boolean uiVisible;
    /** The current indexing run was started by the background job. */
    public volatile boolean backgroundRun;

    /** Frees the model's memory after a background run if nobody is looking at the app. */
    public void releaseIfBackground() {
        if (uiVisible || indexing || state != State.READY) return;
        unloadModel();
        state = State.NO_MODEL;
        status = "";
        notifyChanged();
    }

    /** Test hook: use a stand-in model (Robolectric can't run ONNX Runtime's native code). */
    public void attachModelForTest(final Embedder m) {
        attachModelsForTest(m, m);
    }

    /** Test hook: separate photo and notes models (SigLIP for photos, EmbeddingGemma for notes). */
    public void attachModelsForTest(final Embedder photoModel, final Embedder notesModel) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                photo = photoModel;
                model = notesModel;
                loadedFull = true;
                notesUsable = true;
                state = State.READY;
                status = "test model";
                notifyChanged();
                refreshAdult();
            }
        });
    }

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    void notifyChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onEngineChanged();
            }
        });
    }

    private <T> void post(final Callback<T> cb, final T r, final Exception e) {
        if (cb == null) return; // e.g. the automatic first accelerator check: nobody waits for its report
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.done(r, e);
            }
        });
    }

    public IndexStore store() { return store; }

    public SharedPreferences prefs() { return prefs; }

    public boolean ready() { return state == State.READY && model != null && photo != null; }

    public boolean supportsImages() { return ready() && photo.supportsImages(); }

    public boolean supportsVideo() { return ready() && photo.supportsVideo(); }

    /** Which model embeds photos and videos: {@link FastModel#GEMMA}, {@link FastModel#B16} or {@link FastModel#B32}. */
    public int photoModel() {
        return Math.max(0, Math.min(FastModel.NAMES.length - 1, prefs.getInt("photo_model", FastModel.GEMMA)));
    }

    public boolean gemmaDownloaded() { return manifest.exists(); }

    public boolean fastDownloaded(int pm) { return FastModel.manifest(ctx, pm).exists(); }

    /** The fast model of this phone can run on an NPU/GPU but its full-precision graph is not downloaded yet. */
    public boolean fastNeedsFp32() {
        int pm = photoModel();
        return pm != FastModel.GEMMA && fastDownloaded(pm) && FastModel.fp32Vision(ctx, pm) == null;
    }

    /** Size of the full-precision picture graph (about 4× the int8 one) for the NPU/GPU check. */
    public long fp32EstimateBytes() {
        int pm = photoModel();
        HfRepo.Plan p = pm == FastModel.GEMMA ? null : FastModel.plan(ctx, pm);
        return p == null ? 0 : 4 * new File(FastModel.dir(ctx, pm), p.visionModel).length();
    }

    public String fastReport() {
        return prefs.getString("s_report_" + photoModel(), null);
    }

    /** Switches the photo model; the photo index starts over in the new model's space. */
    public void setPhotoModel(int pm) {
        if (pm == photoModel()) return;
        stopIndex();
        prefs.edit().putInt("photo_model", pm).apply();
        unloadModel();
        state = State.NO_MODEL;
        status = "";
        notifyChanged();
        if (hasModelFiles()) loadModel(true);
    }

    public int searchDims() { return prefs.getInt("dims", 768); }

    public String repo() { return prefs.getString("repo", HfRepo.DEFAULT_REPO); }

    // ------------------------------------------------------------------ model download / load

    public void checkRepo(final String repo, final String token, final boolean vision, final Callback<HfRepo.Plan> cb) {
        net.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    HfRepo r = new HfRepo(repo, token);
                    post(cb, HfRepo.plan(r.listFiles(), vision), null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    public void download(final String repo, final String token, final boolean vision) {
        download(repo, token, vision, false);
    }

    /** Downloads EmbeddingGemma's full-precision vision encoder for the NPU, then checks every accelerator. */
    public void downloadGemmaFp32() {
        prefs.edit().putBoolean("npu_check_pending", true).apply();
        download(repo(), prefs.getString("token", ""), true, true);
    }

    public String gemmaReport() {
        return prefs.getString("g_report", null);
    }

    private void download(final String repo, final String token, final boolean vision, final boolean fp32) {
        if (state == State.DOWNLOADING || state == State.LOADING) return;
        prefs.edit().putString("repo", repo).putString("token", token).putBoolean("vision", vision).apply();
        cancelDownload = false;
        state = State.DOWNLOADING;
        status = "Получаю список файлов…";
        dlDone = 0;
        dlTotal = 0;
        notifyChanged();
        net.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    HfRepo r = new HfRepo(repo, token);
                    HfRepo.Plan plan = HfRepo.plan(r.listFiles(), vision, fp32 || gemmaFp32Vision() != null,
                            vision && gemmaFp16Vision() != null);
                    dlTotal = plan.totalBytes;
                    if (!modelDir.exists() && !modelDir.mkdirs()) throw new Exception("нет доступа к памяти");
                    r.download(plan, modelDir, new HfRepo.Progress() {
                        @Override
                        public boolean onProgress(String file, long fd, long ft, long all, long allTotal) {
                            dlDone = all;
                            dlTotal = allTotal;
                            status = "Скачиваю " + file;
                            notifyChanged();
                            return !cancelDownload;
                        }
                    });
                    HfRepo.saveManifest(plan, repo, manifest);
                    dlDone = dlTotal;
                    unloadModel();
                    loadModel();
                } catch (Exception e) {
                    String msg = cancelDownload ? "Загрузка остановлена — её можно продолжить" : "Ошибка: " + e.getMessage();
                    if (photoModel() != FastModel.GEMMA && hasModelFiles()) {
                        dlError = msg; // EmbeddingGemma for notes failed; the photo model keeps working
                        loadModel(true);
                        return;
                    }
                    state = manifest.exists() ? State.ERROR : State.NO_MODEL;
                    status = msg;
                    notifyChanged();
                }
            }
        });
    }

    public void cancelDownload() { cancelDownload = true; }

    public boolean hasModelFiles() {
        int pm = photoModel();
        return pm == FastModel.GEMMA ? manifest.exists() : fastDownloaded(pm);
    }

    public void loadModel() {
        loadModel(true);
    }

    /** @param full also load the notes model (the app is open); a background run needs only the photo model */
    public void loadModel(final boolean full) {
        state = State.LOADING;
        status = "Загружаю модель в память…";
        notifyChanged();
        ml.submit(new Runnable() {
            @Override
            public void run() {
                loadNow(full);
            }
        });
    }

    /** Test hook: what a reload loads instead of the model files (a stand-in model). */
    public static volatile java.util.concurrent.Callable<Embedder> reloadForTest;

    /** Loads the models on the {@code ml} thread (a run of indexing calls it directly after the NPU process died). */
    private void loadNow(final boolean full) {
        state = State.LOADING;
        if (reloadForTest != null) {
            closeModels();
            try {
                photo = model = reloadForTest.call();
                state = State.READY;
            } catch (Exception e) {
                state = State.ERROR;
                status = String.valueOf(e.getMessage());
            }
            notifyChanged();
            return;
        }
        String step = "чтение списка файлов";
        errorDetails = null;
        boolean autoCheck = false;
        closeModels(); // reload (e.g. new thread count): free the old sessions first
        try {
            long t0 = System.currentTimeMillis();
            threads = threadCount();
            int pm = photoModel();
            String mediaSig, notesSig;
            if (pm == FastModel.GEMMA) {
                HfRepo.Plan plan = HfRepo.loadManifest(manifest);
                if (plan == null || !HfRepo.isComplete(plan, modelDir)) {
                    noModel();
                    return;
                }
                step = "инициализация";
                mark("загрузка EmbeddingGemma 2 (" + ACCEL_NAMES[accel()] + ")");
                model = loadGemma(plan, true);
                photo = model;
                mediaSig = notesSig = gemmaSig(plan);
                if (isNpu(loadedAccel) || isLiteRt(loadedAccel)) {
                    // the NPU / LiteRT-LM compile on the first run: a driver crash there must not loop
                    String probe = isNpu(loadedAccel) ? "npu_probe" : "litert_probe";
                    step = "первый запуск (" + ACCEL_NAMES[loadedAccel] + ")";
                    prefs.edit().putString(probe, String.valueOf(loadedAccel)).commit();
                    photo.embedImage(new PatternSource(640, 480, 1), photoBudget());
                    prefs.edit().remove(probe).commit();
                }
            } else {
                HfRepo.Plan plan = FastModel.plan(ctx, pm);
                if (plan == null || !HfRepo.isComplete(plan, FastModel.dir(ctx, pm))) {
                    noModel();
                    return;
                }
                step = "инициализация " + FastModel.NAMES[pm];
                mark("загрузка " + FastModel.NAMES[pm] + " (" + FastModel.accel(prefs, pm).label + ")");
                photo = openFast(pm, plan);
                mediaSig = "s|" + HfRepo.manifestRepo(FastModel.manifest(ctx, pm));
                HfRepo.Plan g = gemmaPlan();
                String gSig = g != null ? gemmaSig(g) : null;
                if (g != null && full) {
                    step = "EmbeddingGemma 2 для заметок";
                    mark("загрузка EmbeddingGemma 2 для заметок");
                    try {
                        model = loadGemma(g, false);
                        notesSig = gSig;
                    } catch (Throwable e) {
                        android.util.Log.w("SemSearch", "notes model", e);
                        model = photo;
                        notesSig = mediaSig;
                    }
                } else {
                    model = photo;
                    // A background run leaves notes with their EmbeddingGemma vectors alone.
                    notesSig = gSig != null && gSig.equals(prefs.getString("notes_sig", "")) ? null : mediaSig;
                }
            }
            step = "пробный запуск";
            mark("пробный запуск " + FastModel.NAMES[pm]);
            photo.embedQuery("привет");
            mark("");
            prefs.edit().putInt("crash_streak", 0).apply();
            if (!mediaSig.equals(prefs.getString("media_sig", ""))) {
                // Different weights: old photo vectors are not comparable.
                store.clearMedia();
                prefs.edit().putString("media_sig", mediaSig).apply();
            }
            if (notesSig != null && !notesSig.equals(prefs.getString("notes_sig", ""))) {
                step = "переиндексация заметок";
                for (IndexStore.Item n : store.notes()) store.updateEmbedding(n, model.embedDocument(n.body));
                prefs.edit().putString("notes_sig", notesSig).apply();
            }
            notesUsable = notesSig != null;
            loadedFull = full;
            state = State.READY;
            status = "Модель готова (" + (System.currentTimeMillis() - t0) / 100 / 10.0 + " с): "
                    + FastModel.NAMES[pm] + ", " + accelLabel
                    + (pm == FastModel.GEMMA ? ", потоков: " + threads : "")
                    + (photo.supportsImages() ? "" : " · только текст");
            autoCheck = pm != FastModel.GEMMA && full && !FastModel.checked(prefs, pm);
        } catch (Throwable e) {
            mark("");
            closeModels();
            state = State.ERROR;
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            status = "Не удалось загрузить модель (" + step + "): " + msg;
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            errorDetails = "SemSearch " + BuildInfo.version(ctx) + ", Android " + android.os.Build.VERSION.SDK_INT
                    + ", " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n" + status + "\n\n" + sw;
            android.util.Log.e("SemSearch", status, e);
        }
        notifyChanged();
        if (indexing) return; // reloaded within a run of indexing: what follows a load waits for its end
        refreshAdult();
        if (autoCheck) checkFast(null); // first start of a fast model: find its best accelerator once
        if (state == State.READY && full && photoModel() == FastModel.GEMMA) {
            boolean npuCheck = prefs.getBoolean("npu_check_pending", false) && gemmaFp32Vision() != null;
            if (npuCheck || prefs.getBoolean("speed_check_pending", false)) {
                // new accelerator files: compare everything once (the check reloads the model at its end)
                prefs.edit().putBoolean("npu_check_pending", false).putBoolean("speed_check_pending", false).apply();
                benchmark(new Callback<String>() {
                    @Override
                    public void done(String report, Exception e) {
                        prefs.edit().putBoolean("g_report_unseen", true).apply();
                        notifyChanged();
                    }
                });
            } else if (prefs.getBoolean("reindex_pending", false) && AutoIndex.hasMediaAccess(ctx)) {
                // the index was cleared for a model with other vectors: rebuild it right away
                prefs.edit().putBoolean("reindex_pending", false).apply();
                startIndexFromPrefs(false);
            }
        }
    }

    private void noModel() {
        state = State.NO_MODEL;
        status = "Модель не скачана полностью";
        notifyChanged();
    }

    private HfRepo.Plan gemmaPlan() {
        try {
            HfRepo.Plan p = manifest.exists() ? HfRepo.loadManifest(manifest) : null;
            return p != null && HfRepo.isComplete(p, modelDir) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String gemmaSig(HfRepo.Plan plan) throws java.io.IOException {
        return HfRepo.manifestRepo(manifest) + "|" + plan.textModel + "|" + plan.visionModel + (liteRtSpace() ? "|litert" : "");
    }

    /** EmbeddingGemma 2 with the chosen accelerator (falling back to the CPU); text only when it serves notes. */
    private Embedder loadGemma(HfRepo.Plan plan, boolean withVision) throws Exception {
        ModelConfig cfg = null;
        HfTokenizer tok = null;
        HfRepo.Plan use = plan;
        if (!withVision) {
            use = new HfRepo.Plan();
            use.textModel = plan.textModel;
        }
        int accel = accel();
        if (!withVision && (isLiteRt(accel) || isQnn(accel))) accel = ACCEL_CPU; // notes beside SigLIP: the ONNX text model
        if (withVision && liteRtSpace() && !isLiteRt(accel)) {
            throw new java.io.IOException("LiteRT-LM на этом телефоне больше не запускается, а индекс построен им — "
                    + "«Вернуться на ONNX Runtime» в настройках переиндексирует галерею.");
        }
        if (!isLiteRt(accel)) { // LiteRT-LM's bundle carries its own configs and tokenizer
            cfg = EmbeddingGemma2.loadConfig(modelDir);
            tok = EmbeddingGemma2.loadTokenizer(modelDir);
        }
        Embedder m;
        try {
            m = createModel(cfg, tok, use, accel, threads);
        } catch (Exception gpuOrInt8Failure) {
            if (accel == ACCEL_CPU) throw gpuOrInt8Failure;
            if (isLiteRt(accel) && liteRtSpace()) {
                // the index holds LiteRT-LM's vectors: the ONNX model cannot stand in for it
                throw new java.io.IOException("LiteRT-LM не запустился (" + gpuOrInt8Failure.getMessage() + "). Индекс "
                        + "построен им — «Вернуться на ONNX Runtime» в настройках переиндексирует галерею.", gpuOrInt8Failure);
            }
            android.util.Log.w("SemSearch", "accel " + accel + " failed, using CPU", gpuOrInt8Failure);
            prefs.edit().putInt("accel", ACCEL_CPU).apply();
            accel = ACCEL_CPU;
            if (cfg == null) {
                cfg = EmbeddingGemma2.loadConfig(modelDir);
                tok = EmbeddingGemma2.loadTokenizer(modelDir);
            }
            m = createModel(cfg, tok, use, ACCEL_CPU, threads);
        }
        if (withVision) {
            loadedAccel = accel;
            accelLabel = ACCEL_NAMES[accel];
            if (m instanceof LiteRtEmbedder && ((LiteRtEmbedder) m).budgetFixed()) {
                accelLabel += " · детализация сборки, не " + maxBudget();
            }
        }
        return m;
    }

    /** SigLIP 2 with the accelerator the auto-check chose; an NPU/GPU that fails falls back to the CPU. */
    private SigLip openFast(int pm, HfRepo.Plan plan) throws Exception {
        File dir = FastModel.dir(ctx, pm);
        File text = new File(dir, plan.textModel), int8 = new File(dir, plan.visionModel);
        File fp32 = FastModel.fp32Vision(ctx, pm);
        SigLip.Accel a = FastModel.accel(prefs, pm);
        if (a.needsFp32() && fp32 == null) a = SigLip.Accel.CPU;
        int t = FastModel.threads(prefs, pm), batch = FastModel.batch(prefs, pm);
        boolean risky = a != SigLip.Accel.CPU && a != SigLip.Accel.XNNPACK && a != SigLip.Accel.CPU_FP32;
        if (risky) prefs.edit().putString("s_probe", a.name()).commit();
        try {
            SigLip m = new SigLip(dir, text, a.needsFp32() ? fp32 : int8, a, t, batch);
            accelLabel = a.label;
            return m;
        } catch (Exception e) {
            if (a == SigLip.Accel.CPU) throw e;
            android.util.Log.w("SemSearch", "fast model on " + a + " failed, using CPU", e);
            prefs.edit().putInt("s_accel_" + pm, SigLip.Accel.CPU.ordinal()).apply();
            accelLabel = SigLip.Accel.CPU.label;
            return new SigLip(dir, text, int8, SigLip.Accel.CPU, t, 1);
        } finally {
            if (risky) prefs.edit().remove("s_probe").commit();
        }
    }

    private void closeModels() {
        Embedder a = model, b = photo;
        model = null;
        photo = null;
        if (a != null) a.close();
        if (b != null && b != a) b.close();
    }

    private void unloadModel() {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                closeModels();
            }
        });
    }

    public void deleteModel() {
        final int pm = photoModel();
        stopIndex();
        cancelDownload = true;
        state = State.NO_MODEL;
        unloadModel();
        ml.submit(new Runnable() {
            @Override
            public void run() {
                deleteTree(pm == FastModel.GEMMA ? modelDir : FastModel.dir(ctx, pm));
                if (pm != FastModel.GEMMA) {
                    prefs.edit().remove("s_checked_" + pm).remove("s_report_" + pm).remove("s_accel_" + pm).apply();
                }
                state = State.NO_MODEL;
                status = "Модель удалена";
                notifyChanged();
            }
        });
    }

    /**
     * Deletes EmbeddingGemma's full-precision vision encoder (the NPU's graph, ≈0.7 GB) when the NPU is not
     * the chosen accelerator. The manifest forgets it first, so an interruption leaves at worst stray files.
     */
    public void deleteGemmaFp32() {
        deleteVisionVariant(true);
    }

    /** Deletes the half-precision vision encoder (WebGPU fp16) when it is not the chosen accelerator. */
    public void deleteGemmaFp16() {
        deleteVisionVariant(false);
    }

    private void deleteVisionVariant(final boolean fp32) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    HfRepo.Plan p = HfRepo.loadManifest(manifest);
                    String variant = p == null ? null : fp32 ? p.accelVision : p.fp16Vision;
                    if (variant == null) return;
                    // a model still running on that graph (accelerator changed, not reloaded yet) lets go of it
                    boolean reload = photo != null && (fp32 ? isNpu(loadedAccel) || isQnn(loadedAccel) : loadedAccel == ACCEL_GPU_FP16);
                    if (reload) closeModels();
                    String repo = HfRepo.manifestRepo(manifest);
                    List<String> gone = new ArrayList<String>();
                    for (java.util.Iterator<HfRepo.RemoteFile> it = p.files.iterator(); it.hasNext(); ) {
                        HfRepo.RemoteFile f = it.next();
                        if (!f.path.startsWith(variant)) continue;
                        gone.add(f.path);
                        p.totalBytes -= Math.max(0, f.size);
                        it.remove();
                    }
                    if (fp32) p.accelVision = null;
                    else p.fp16Vision = null;
                    File tmp = new File(manifest.getPath() + ".tmp");
                    HfRepo.saveManifest(p, repo, tmp);
                    if (!tmp.renameTo(manifest)) throw new java.io.IOException("не удалось обновить манифест");
                    for (String g : gone) new File(modelDir, g).delete();
                    int a = prefs.getInt("accel", ACCEL_CPU);
                    if (fp32) {
                        // the NPU's QNN graph and its compiled contexts are made from the fp32 graph
                        File[] ctxs = new File(modelDir, "onnx").listFiles();
                        if (ctxs != null) for (File f : ctxs) if (f.getName().contains(".qnn.")) f.delete();
                        prefs.edit().remove("npu_check_pending").apply();
                        if (isNpu(a) || isQnn(a)) prefs.edit().putInt("accel", ACCEL_CPU).apply();
                    } else if (a == ACCEL_GPU_FP16) {
                        prefs.edit().putInt("accel", ACCEL_CPU).apply();
                    }
                    if (reload) loadModel();
                } catch (Exception e) {
                    android.util.Log.w("SemSearch", "vision variant not deleted", e);
                }
                notifyChanged();
            }
        });
    }

    /** Size of the fp16 vision encoder on disk (0 when absent). */
    public long gemmaFp16Bytes() {
        HfRepo.Plan p = gemmaFp16Vision() != null ? gemmaPlan() : null;
        if (p == null) return 0;
        long n = 0;
        for (HfRepo.RemoteFile f : p.files) if (f.path.startsWith(p.fp16Vision)) n += new File(modelDir, f.path).length();
        return n;
    }

    public long liteRtBytes() {
        return liteRt().bytesOnDisk();
    }

    /** Deletes LiteRT-LM and its model (not while the index holds its vectors). */
    public void deleteLiteRt() {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                if (liteRtSpace()) return;
                boolean reload = photo != null && isLiteRt(loadedAccel);
                if (reload) closeModels();
                LiteRtRuntime.deleteTree(liteRt().dir());
                prefs.edit().remove("litert_offer").apply();
                if (isLiteRt(prefs.getInt("accel", ACCEL_CPU))) prefs.edit().putInt("accel", ACCEL_CPU).apply();
                if (reload) loadModel();
                notifyChanged();
            }
        });
    }

    /**
     * Downloads the two faster variants of EmbeddingGemma's picture side, then lets the speed check compare
     * them with the rest: the half-precision vision encoder for ONNX Runtime's WebGPU provider (same ONNX
     * repo) and Google's LiteRT-LM runtime with its own build of the model. A failure of one is reported, the
     * other is still checked.
     */
    public void downloadSpeedups() {
        if (state == State.DOWNLOADING || state == State.LOADING || photoModel() != FastModel.GEMMA) return;
        final String repo = repo(), token = prefs.getString("token", "");
        stopIndex();
        cancelDownload = false;
        dlError = null;
        state = State.DOWNLOADING;
        status = "Получаю список файлов…";
        dlDone = 0;
        dlTotal = 0;
        notifyChanged();
        net.submit(new Runnable() {
            @Override
            public void run() {
                HfRepo.Progress progress = new HfRepo.Progress() {
                    @Override
                    public boolean onProgress(String file, long fd, long ft, long all, long allTotal) {
                        dlDone = all;
                        dlTotal = allTotal;
                        status = "Скачиваю " + file;
                        notifyChanged();
                        return !cancelDownload;
                    }
                };
                StringBuilder errors = new StringBuilder();
                if (gemmaFp16Vision() == null) {
                    try {
                        HfRepo r = new HfRepo(repo, token);
                        HfRepo.Plan plan = HfRepo.plan(r.listFiles(), true, gemmaFp32Vision() != null, true);
                        dlTotal = plan.totalBytes;
                        r.download(plan, modelDir, progress);
                        HfRepo.saveManifest(plan, repo, manifest);
                    } catch (Exception e) {
                        if (!cancelDownload) errors.append("fp16: ").append(e.getMessage());
                    }
                }
                if (!cancelDownload && !liteRtInstalled()) {
                    try {
                        dlDone = 0;
                        dlTotal = 0;
                        status = "LiteRT-LM: ищу подходящую версию…";
                        notifyChanged();
                        liteRt().install(progress);
                    } catch (Exception e) {
                        if (!cancelDownload) errors.append(errors.length() > 0 ? "\n" : "").append("LiteRT-LM: ").append(e.getMessage());
                    }
                }
                dlError = cancelDownload ? "Загрузка остановлена — её можно продолжить" : errors.length() > 0 ? errors.toString() : null;
                if (!cancelDownload && (gemmaFp16Vision() != null || liteRtInstalled())) {
                    prefs.edit().putBoolean("speed_check_pending", true).apply();
                }
                unloadModel();
                loadModel();
            }
        });
    }

    /**
     * Downloads what the Snapdragon NPU needs: the full-precision vision encoder (if missing) and Qualcomm's QNN
     * runtime with the ONNX Runtime build for it (Maven Central, ≈72 MB); then the speed check compares the NPU
     * with the rest.
     */
    public void downloadQnn() {
        if (state == State.DOWNLOADING || state == State.LOADING || photoModel() != FastModel.GEMMA) return;
        final String repo = repo(), token = prefs.getString("token", "");
        stopIndex();
        cancelDownload = false;
        dlError = null;
        state = State.DOWNLOADING;
        status = "Получаю список файлов…";
        dlDone = 0;
        dlTotal = 0;
        notifyChanged();
        net.submit(new Runnable() {
            @Override
            public void run() {
                HfRepo.Progress progress = new HfRepo.Progress() {
                    @Override
                    public boolean onProgress(String file, long fd, long ft, long all, long allTotal) {
                        dlDone = all;
                        dlTotal = allTotal;
                        status = "Скачиваю " + file;
                        notifyChanged();
                        return !cancelDownload;
                    }
                };
                StringBuilder errors = new StringBuilder();
                if (gemmaFp32Vision() == null) {
                    try {
                        HfRepo r = new HfRepo(repo, token);
                        HfRepo.Plan plan = HfRepo.plan(r.listFiles(), true, true, gemmaFp16Vision() != null);
                        dlTotal = plan.totalBytes;
                        r.download(plan, modelDir, progress);
                        HfRepo.saveManifest(plan, repo, manifest);
                    } catch (Exception e) {
                        if (!cancelDownload) errors.append("полная версия визуального энкодера: ").append(e.getMessage());
                    }
                }
                if (!cancelDownload && !qnnInstalled()) {
                    try {
                        dlDone = 0;
                        dlTotal = 0;
                        qnn().install(socModel(), progress);
                    } catch (Exception e) {
                        if (!cancelDownload) errors.append(errors.length() > 0 ? "\n" : "").append("QNN: ").append(e.getMessage());
                    }
                }
                dlError = cancelDownload ? "Загрузка остановлена — её можно продолжить" : errors.length() > 0 ? errors.toString() : null;
                if (!cancelDownload && qnnInstalled() && gemmaFp32Vision() != null) {
                    prefs.edit().putBoolean("qnn_broken", false).putBoolean("speed_check_pending", true).apply();
                }
                unloadModel();
                loadModel();
            }
        });
    }

    /** Deletes QNN and the compiled NPU graphs (not while the NPU is the chosen accelerator). */
    public void deleteQnn() {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                boolean reload = photo != null && isQnn(loadedAccel);
                if (reload) closeModels();
                LiteRtRuntime.deleteTree(qnn().dir());
                File[] ctxs = new File(modelDir, "onnx").listFiles();
                if (ctxs != null) for (File f : ctxs) if (f.getName().contains(".qnn.")) f.delete();
                if (isQnn(prefs.getInt("accel", ACCEL_CPU))) prefs.edit().putInt("accel", ACCEL_CPU).apply();
                if (reload) loadModel();
                notifyChanged();
            }
        });
    }

    /** Rebuilds the index with LiteRT-LM's vectors: photos, videos and notes are embedded again by it. */
    public void switchToLiteRt() {
        int a = liteRtOffer();
        if (a < 0) return;
        stopIndex();
        prefs.edit().putBoolean("litert_space", true).putInt("accel", a).putInt("threads", 0).putInt("batch", 1)
                .remove("litert_offer").putBoolean("reindex_pending", true).apply();
        loadModel();
    }

    /** Back to the ONNX model: the index is rebuilt with its vectors, the speed check picks its accelerator again. */
    public void leaveLiteRt() {
        stopIndex();
        prefs.edit().putBoolean("litert_space", false).putInt("accel", ACCEL_CPU).putInt("threads", 0).putInt("batch", 1)
                .putBoolean("reindex_pending", true).putBoolean("speed_check_pending", true).apply();
        loadModel();
    }

    /** Removes EmbeddingGemma 2 when it only serves notes (notes then use the fast model's text side). */
    public void deleteGemma() {
        if (photoModel() == FastModel.GEMMA) {
            deleteModel();
            return;
        }
        ml.submit(new Runnable() {
            @Override
            public void run() {
                deleteTree(modelDir);
            }
        });
        if (hasModelFiles()) loadModel(true);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // ------------------------------------------------------------------ search

    public static final class SearchResult {
        /** What clearly matches (IndexStore.Tiers), or the nearest few when nothing does ({@link #nearestOnly}). */
        public List<IndexStore.Hit> hits;
        /** The less sure ones, shown on request. */
        public List<IndexStore.Hit> more = new ArrayList<IndexStore.Hit>();
        public boolean nearestOnly;
        public long millis;
        public String label;

        void set(IndexStore.Tiers t) {
            hits = t.sure;
            more = t.more;
            nearestOnly = t.nearestOnly;
        }
    }

    public void search(final String query, final boolean photos, final boolean videos, final boolean notes,
                       final Callback<SearchResult> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    requireModel();
                    long t0 = System.currentTimeMillis();
                    boolean withNotes = notes && notesUsable;
                    QueryVectors qv = queryVectors(query, photos || videos, withNotes, bridgeMode());
                    SearchResult r = new SearchResult();
                    r.set(store.searchTiers(qv.media, mediaDims(), qv.notes, notesDims(), photos, videos, withNotes,
                            photo == model, -1));
                    r.millis = System.currentTimeMillis() - t0;
                    r.label = "«" + query + "»" + (qv.english != null ? " → для фото «" + qv.english + "»" : "");
                    post(cb, r, null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    public int bridgeMode() {
        return Math.max(0, Math.min(BRIDGE_MODES.length - 1, prefs.getInt("bridge_mode", 0)));
    }

    static final class QueryVectors {
        float[] media, notes;
        String english;
    }

    /** Matryoshka truncation applies to EmbeddingGemma vectors only; SigLIP needs all of them. */
    int mediaDims() {
        return photoModel() == FastModel.GEMMA ? searchDims() : Integer.MAX_VALUE;
    }

    int notesDims() {
        return isGemma(model) || (model != null && model == photo && photoModel() == FastModel.GEMMA)
                ? searchDims() : Integer.MAX_VALUE;
    }

    QueryVectors queryVectors(String query, boolean forMedia, int mode) throws Exception {
        return queryVectors(query, forMedia, true, mode);
    }

    /**
     * Notes are matched with the original query. For photos/videos a Russian query is also
     * rendered in English (QueryBridge) because the model's text↔image alignment is strongest
     * for English; mode 0 searches with the sum of both vectors, mode 1 with English only.
     */
    QueryVectors queryVectors(String query, boolean forMedia, boolean forNotes, int mode) throws Exception {
        return queryVectors(photo, query, forMedia, forNotes, mode);
    }

    /** Query vectors with a given photo model (the comparison runs the other one through the same path). */
    QueryVectors queryVectors(Embedder photoModel, String query, boolean forMedia, boolean forNotes, int mode) throws Exception {
        QueryVectors v = new QueryVectors();
        if (forNotes) v.notes = model.embedQuery(query);
        if (!forMedia) return v;
        float[] ru = photoModel == model && v.notes != null ? v.notes : photoModel.embedQuery(query);
        v.media = ru;
        if (mode == 2 || bridge == null || !QueryBridge.hasCyrillic(query)) return v;
        QueryBridge.Result br = bridge.translate(query);
        if (br == null) return v;
        float[] en = photoModel.embedQuery(br.english);
        v.english = br.english;
        if (mode == 1) {
            v.media = en;
        } else {
            float[] sum = new float[en.length];
            for (int i = 0; i < sum.length; i++) sum[i] = ru[i] + en[i];
            VectorMath.normalize(sum);
            v.media = sum;
        }
        return v;
    }

    /** Text-only quality probe: RU↔EN similarity and, with an indexed gallery, top-10 agreement. */
    public void diagnose(final Callback<String> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    requireModel();
                    String[][] pairs = {
                            {"кот на диване", "a cat on a sofa"}, {"собака на улице", "a dog on the street"},
                            {"закат над морем", "sunset over the sea"}, {"еда на тарелке", "food on a plate"},
                            {"скриншот с текстом", "a screenshot with text"}, {"машина на дороге", "a car on the road"},
                            {"цветы", "flowers"}, {"люди", "people"}};
                    StringBuilder sb = new StringBuilder();
                    sb.append("SemSearch ").append(BuildInfo.version(ctx)).append(" · ").append(status).append('\n');
                    int photosN = store.count(IndexStore.KIND_PHOTO) + store.count(IndexStore.KIND_VIDEO);
                    sb.append("Модель фото: ").append(FastModel.NAMES[photoModel()]).append(", ").append(accelLabel)
                            .append("\nВ индексе фото/видео: ").append(photosN)
                            .append(photoModel() == FastModel.GEMMA ? ", детализация фото: "
                                    + (autoDetail() ? "авто (" + photoBudget() + "/" + maxBudget() + ")" : String.valueOf(photoBudget()))
                                    + " токенов, длина вектора поиска: " + searchDims() : "")
                            .append("\n\nRU↔EN — косинус запросов; топ-10 — сколько фото совпало с выдачей по EN\n");
                    long tq = 0;
                    int nq = 0;
                    for (String[] p : pairs) {
                        long t0 = System.currentTimeMillis();
                        float[] en = photo.embedQuery(p[1]);
                        tq += System.currentTimeMillis() - t0;
                        nq++;
                        QueryVectors ru = queryVectors(p[0], true, false, 2), mix = queryVectors(p[0], true, false, 0),
                                br = queryVectors(p[0], true, false, 1);
                        sb.append(String.format(java.util.Locale.ROOT, "• %s ↔ %s: %.2f", p[0], p[1], dot(ru.media, en)));
                        if (mix.english != null) {
                            sb.append(String.format(java.util.Locale.ROOT, " | мост «%s»: %.2f", mix.english, dot(br.media, en)));
                        }
                        if (photosN >= 10) {
                            List<IndexStore.Hit> hEn = store.search(en, mediaDims(), true, true, false, 10, -1);
                            List<IndexStore.Hit> hRu = store.search(ru.media, mediaDims(), true, true, false, 10, -1);
                            List<IndexStore.Hit> hMix = store.search(mix.media, mediaDims(), true, true, false, 10, -1);
                            List<IndexStore.Hit> hBr = store.search(br.media, mediaDims(), true, true, false, 10, -1);
                            sb.append(String.format(java.util.Locale.ROOT,
                                    "\n   топ-10 = EN: RU %d/10, RU+мост %d/10, мост %d/10; лучший балл EN %.2f RU %.2f RU+мост %.2f",
                                    overlap(hEn, hRu), overlap(hEn, hMix), overlap(hEn, hBr),
                                    top(hEn), top(hRu), top(hMix)));
                        }
                        sb.append('\n');
                    }
                    sb.append("\nВремя на текстовый запрос: ").append(tq / Math.max(1, nq)).append(" мс");
                    post(cb, sb.toString(), null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    private static float dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) s += a[i] * b[i];
        return (float) s;
    }

    private static int overlap(List<IndexStore.Hit> a, List<IndexStore.Hit> b) {
        java.util.HashSet<Long> ids = new java.util.HashSet<Long>();
        for (IndexStore.Hit h : a) ids.add(h.item.id);
        int n = 0;
        for (IndexStore.Hit h : b) if (ids.contains(h.item.id)) n++;
        return n;
    }

    private static float top(List<IndexStore.Hit> h) {
        return h.isEmpty() ? 0f : h.get(0).score;
    }

    public void searchByImage(final Uri uri, final boolean photos, final boolean videos, final boolean notes,
                              final Callback<SearchResult> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    requireModel();
                    if (!photo.supportsImages()) throw new IllegalStateException("визуальный энкодер не загружен");
                    long t0 = System.currentTimeMillis();
                    Bitmap b = Media.decode(ctx.getContentResolver(), uri, 0, 900_000L);
                    float[] q;
                    try {
                        q = embedPhoto(b, maxBudget()); // one picture: the most detail
                    } finally {
                        b.recycle();
                    }
                    SearchResult r = new SearchResult();
                    boolean same = photo == model && notesUsable;
                    r.set(store.searchTiers(q, mediaDims(), q, mediaDims(), photos, videos, notes && same, true, -1));
                    r.millis = System.currentTimeMillis() - t0;
                    r.label = "похожие на выбранное фото";
                    post(cb, r, null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    public void similar(final IndexStore.Item item, final boolean photos, final boolean videos, final boolean notes,
                        final Callback<SearchResult> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                SearchResult r = new SearchResult();
                // Notes and pictures are only comparable when one model embedded both.
                boolean same = photo != null && photo == model && notesUsable;
                boolean note = item.kind == IndexStore.KIND_NOTE;
                int dims = note ? notesDims() : mediaDims();
                r.set(store.searchTiers(item.emb, dims, item.emb, dims, (photos && (!note || same)), (videos && (!note || same)),
                        notes && (note || same) && notesUsable, true, item.id));
                r.millis = System.currentTimeMillis() - t0;
                r.label = "похожие";
                post(cb, r, null);
            }
        });
    }

    private void requireModel() {
        if (model == null || photo == null || state != State.READY) throw new IllegalStateException("модель ещё не загружена");
    }

    // ------------------------------------------------------------------ what a picture shows

    /** The vocabulary's words embedded by the photo model (PhotoTags), and for which model and gallery size. */
    private PhotoTags tags;
    private Embedder tagsModel;
    private int tagsCalibratedAt = -1;

    /**
     * Words for what a photo or video shows (assets/photo_tags.txt, PhotoTags): best first, possibly none. The first
     * call embeds the vocabulary (some 350 short queries) and saves it; later ones only compare vectors.
     */
    public void describe(final IndexStore.Item item, final Callback<List<String>> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    requireModel();
                    if (item.emb == null || item.kind == IndexStore.KIND_NOTE) throw new IllegalStateException("у этого файла нет вектора картинки");
                    PhotoTags t = photoTags();
                    if (t.vecs.length > 0 && t.vecs[0].length != item.emb.length) {
                        throw new IllegalStateException("индекс построен другой моделью — переиндексируйте галерею");
                    }
                    post(cb, t.rank(item.emb), null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    /** An album — by meaning (core.Albums), a person, someone unnamed or a pet/thing — and its pictures, best first. */
    public static final class Album {
        public static final int MEANING = 0, PERSON = 1, UNNAMED = 2, THING = 3;
        public final String name;
        public final List<IndexStore.Item> items;
        public int kind = MEANING;
        /** A person's or a thing's id (FaceStore). */
        public long id;
        /** Someone unnamed: their faces (FaceStore ids), clearest first. */
        long[] faces = new long[0];
        /** A thing: the less sure photos. */
        public List<IndexStore.Item> more = new ArrayList<IndexStore.Item>();
        /** People: the photo of the face shown, and the face's box in it (fractions of the photo). */
        public IndexStore.Item face;
        public float[] box;

        Album(String name, List<IndexStore.Item> items) {
            this.name = name;
            this.items = items;
        }

        /** The same album with only these pictures. */
        Album with(List<IndexStore.Item> items, List<IndexStore.Item> more) {
            Album a = new Album(name, items);
            a.kind = kind;
            a.id = id;
            a.faces = faces;
            a.more = more;
            a.face = face;
            a.box = box;
            return a;
        }
    }

    private List<Album> albums;
    private int albumsAt = -1;
    private Embedder albumsModel;

    /**
     * Albums by meaning (assets/albums.txt, core.Albums) of the photos and videos in the index, biggest first; made
     * again when the index has grown or shrunk by a tenth, or the model changed.
     */
    public void albums(final Callback<List<Album>> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    post(cb, visible(albumsNow()), null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    /** The albums by meaning, made again when needed (on the ml thread). */
    private List<Album> albumsNow() throws Exception {
        requireModel();
        List<IndexStore.Item> media = store.media();
        if (albums == null || albumsModel != photo || Math.abs(media.size() - albumsAt) > Math.max(10, albumsAt / 10)) {
            PhotoTags t = photoTags();
            int dim = t.vecs.length > 0 ? t.vecs[0].length : 0;
            List<IndexStore.Item> usable = new ArrayList<IndexStore.Item>();
            List<float[]> vecs = new ArrayList<float[]>();
            for (IndexStore.Item it : media) {
                if (it.emb == null || it.emb.length != dim) continue;
                usable.add(it);
                vecs.add(it.emb);
            }
            if (usable.isEmpty() && !media.isEmpty()) throw new IllegalStateException("индекс построен другой моделью — переиндексируйте галерею");
            List<io.github.teoplaydor.semsearch.core.Albums.Def> defs =
                    io.github.teoplaydor.semsearch.core.Albums.parse(ctx.getAssets().open("albums.txt"));
            List<Album> out = new ArrayList<Album>();
            for (io.github.teoplaydor.semsearch.core.Albums.Album a : io.github.teoplaydor.semsearch.core.Albums.build(defs, t, vecs)) {
                List<IndexStore.Item> items = new ArrayList<IndexStore.Item>(a.pictures.size());
                for (int i : a.pictures) items.add(usable.get(i));
                out.add(new Album(a.name, items));
            }
            albums = out;
            albumsAt = media.size();
            albumsModel = photo;
        }
        return albums;
    }

    /** What is hidden (18+) is in no album; an album by meaning left with too few pictures goes. */
    private List<Album> visible(List<Album> all) {
        List<Album> out = new ArrayList<Album>();
        for (Album a : all) {
            List<IndexStore.Item> items = new ArrayList<IndexStore.Item>(a.items.size()), more = new ArrayList<IndexStore.Item>();
            for (IndexStore.Item it : a.items) if (!store.isHidden(it)) items.add(it);
            for (IndexStore.Item it : a.more) if (!store.isHidden(it)) more.add(it);
            if (a.kind == Album.MEANING ? items.size() >= io.github.teoplaydor.semsearch.core.Albums.MIN_SIZE : !items.isEmpty()) {
                out.add(a.with(items, more));
            }
        }
        return out;
    }

    private PhotoTags photoTags() throws Exception {
        if (tags == null || tagsModel != photo) {
            java.io.InputStream in = ctx.getAssets().open("photo_tags.txt");
            List<String[]> words = PhotoTags.parse(in);
            File cache = new File(ctx.getFilesDir(), "photo_tags.bin");
            float[] probe = photo.embedQuery(words.get(0)[0]);
            float[][] vecs = PhotoTags.readCache(cache, words, probe);
            if (vecs == null) {
                long t0 = System.currentTimeMillis();
                vecs = new float[words.size()][];
                vecs[0] = probe;
                for (int i = 1; i < vecs.length; i++) vecs[i] = photo.embedQuery(words.get(i)[0]);
                PhotoTags.writeCache(cache, words, vecs);
                android.util.Log.i("SemSearch", "photo tags: " + vecs.length + " words in " + (System.currentTimeMillis() - t0) + " ms");
            }
            tags = new PhotoTags(words, vecs);
            tagsModel = photo;
            tagsCalibratedAt = -1;
        }
        List<IndexStore.Item> media = store.media();
        if (tagsCalibratedAt < 0 || Math.abs(media.size() - tagsCalibratedAt) > Math.max(20, tagsCalibratedAt / 10)) {
            // the gallery's mean per word: up to 500 pictures spread over the index
            List<float[]> sample = new ArrayList<float[]>();
            int dim = tags.vecs.length > 0 ? tags.vecs[0].length : 0;
            int step = Math.max(1, media.size() / 500);
            for (int i = 0; i < media.size(); i += step) {
                float[] e = media.get(i).emb;
                if (e != null && e.length == dim) sample.add(e);
            }
            tags.calibrate(sample);
            tagsCalibratedAt = media.size();
        }
        return tags;
    }

    // ------------------------------------------------------------------ people and pets

    /** Test hook: a stand-in for the face models (Robolectric runs no ONNX Runtime). */
    public static volatile io.github.teoplaydor.semsearch.core.FaceFinder facesForTest;
    static final String YUNET = "face_detection_yunet_2023mar.onnx", SFACE = "face_recognition_sface_2021dec.onnx";
    /** The face models (opencv_zoo: YuNet, MIT; SFace, Apache 2.0): GitHub first, OpenCV's copies on the Hub next. */
    static final String[][] FACE_FILES = {
            {YUNET, "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/" + YUNET,
                    "https://huggingface.co/opencv/face_detection_yunet/resolve/main/" + YUNET},
            {SFACE, "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/" + SFACE,
                    "https://huggingface.co/opencv/face_recognition_sface/resolve/main/" + SFACE}};
    /** Faces kept from this many pixels (the shorter side, in the photo as scanned); unnamed groups start from bigger ones. */
    static final int MIN_FACE = 24, GROUP_FACE = 40;
    /** About this many pixels of a photo are looked at for faces. */
    static final long FACE_PIXELS = 700_000L;
    /** Unnamed people offered, at most. */
    static final int UNNAMED_MAX = 30;

    private final ExecutorService faceScan = Executors.newSingleThreadExecutor();
    private volatile FaceStore faceStore;
    private io.github.teoplaydor.semsearch.core.FaceFinder finder;
    public volatile boolean faceScanning, faceDownloading;
    public volatile int faceDone, faceTotal;
    public volatile long faceDlDone, faceDlTotal;
    public volatile String faceDlError;
    private volatile boolean stopFaces;

    File facesDir() {
        return new File(ctx.getFilesDir(), "faces");
    }

    /** Whether a file is an ONNX graph (a protobuf starting with ir_version), not an error page or a Git LFS pointer. */
    private static boolean onnxFile(File f) {
        if (f.length() < 50_000) return false;
        try {
            java.io.InputStream in = new java.io.FileInputStream(f);
            try {
                return in.read() == 0x08;
            } finally {
                in.close();
            }
        } catch (java.io.IOException e) {
            return false;
        }
    }

    public boolean facesInstalled() {
        return facesForTest != null || (onnxFile(new File(facesDir(), YUNET)) && onnxFile(new File(facesDir(), SFACE)));
    }

    public long facesBytes() {
        return new File(facesDir(), YUNET).length() + new File(facesDir(), SFACE).length();
    }

    public int facesFound() {
        FaceStore fs = faceStore;
        return fs == null ? 0 : fs.faceCount();
    }

    public int photosScanned() {
        FaceStore fs = faceStore;
        return fs == null ? 0 : fs.scannedCount();
    }

    /** Downloads the face models (≈40 MB), then looks for faces in the photos. */
    public void downloadFaces() {
        if (faceDownloading || facesInstalled()) return;
        faceDownloading = true;
        faceDlError = null;
        faceDlDone = 0;
        faceDlTotal = 0;
        notifyChanged();
        net.submit(new Runnable() {
            @Override
            public void run() {
                HfRepo.Progress progress = new HfRepo.Progress() {
                    @Override
                    public boolean onProgress(String file, long fd, long ft, long all, long allTotal) {
                        faceDlDone = fd;
                        faceDlTotal = ft;
                        notifyChanged();
                        return true;
                    }
                };
                try {
                    File dir = facesDir();
                    if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("не создаётся папка " + dir);
                    for (String[] f : FACE_FILES) {
                        File dst = new File(dir, f[0]);
                        if (onnxFile(dst)) continue;
                        String errors = "";
                        for (int u = 1; u < f.length && !onnxFile(dst); u++) {
                            try {
                                HfRepo.fetch(f[u], null, f[0], -1, dst, 0, -1, progress);
                                if (!onnxFile(dst)) throw new java.io.IOException("пришёл не файл модели (" + dst.length() + " байт)");
                            } catch (java.io.IOException e) {
                                dst.delete();
                                new File(dst.getPath() + ".part").delete(); // the next source starts over
                                errors += (errors.isEmpty() ? "" : "; ") + Uri.parse(f[u]).getHost() + ": " + e.getMessage();
                            }
                        }
                        if (!onnxFile(dst)) throw new java.io.IOException(f[0] + " — " + errors);
                    }
                    // the graphs as expected (YuNet 2023mar's outputs): else the files go
                    io.github.teoplaydor.semsearch.core.FaceModel m = new io.github.teoplaydor.semsearch.core.FaceModel(
                            new File(dir, YUNET), new File(dir, SFACE), 1);
                    Journal.add(ctx, "app", "лица: модели скачаны — " + m.describe());
                    m.close();
                } catch (Throwable e) {
                    faceDlError = "Модели лиц не скачались: " + e.getMessage();
                    Journal.add(ctx, "app", faceDlError);
                    new File(facesDir(), YUNET).delete();
                    new File(facesDir(), SFACE).delete();
                } finally {
                    faceDownloading = false;
                    notifyChanged();
                }
                scanFaces();
            }
        });
    }

    /** Deletes the face models; the faces found and the names given stay. */
    public void deleteFaces() {
        stopFaces = true;
        faceScan.submit(new Runnable() {
            @Override
            public void run() {
                synchronized (Engine.this) {
                    if (finder != null) finder.close();
                    finder = null;
                }
                deleteTree(facesDir());
                notifyChanged();
            }
        });
    }

    private synchronized io.github.teoplaydor.semsearch.core.FaceFinder finder() throws Exception {
        if (facesForTest != null) return facesForTest;
        if (finder == null) {
            finder = new io.github.teoplaydor.semsearch.core.FaceModel(new File(facesDir(), YUNET), new File(facesDir(), SFACE), 2);
        }
        return finder;
    }

    /** The faces of one photo, looked for now (decoded at about FACE_PIXELS, upright). */
    private List<io.github.teoplaydor.semsearch.core.FaceModel.Face> findFaces(IndexStore.Item it, int[] size) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        Bitmap b = Media.decode(cr, Uri.parse(it.uri), Media.orientation(cr, it.mediaId), FACE_PIXELS);
        try {
            if ((long) b.getWidth() * b.getHeight() > 2 * FACE_PIXELS) {
                double k = Math.sqrt((double) FACE_PIXELS / ((long) b.getWidth() * b.getHeight()));
                Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)), Math.max(1, (int) (b.getHeight() * k)), true);
                b.recycle();
                b = s;
            }
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            size[0] = w;
            size[1] = h;
            return finder().faces(px, w, h, MIN_FACE);
        } finally {
            b.recycle();
        }
    }

    /** Looks for faces in the photos not looked at yet (newest first), in the background; waits while indexing runs. */
    public void scanFaces() {
        if (!facesInstalled() || faceStore == null || store == null) return;
        synchronized (this) {
            if (faceScanning) return;
            faceScanning = true;
        }
        stopFaces = false;
        faceScan.submit(new Runnable() {
            @Override
            public void run() {
                int found = 0, read = 0, failed = 0;
                long t0 = System.currentTimeMillis(), last = 0;
                try {
                    List<IndexStore.Item> todo = new ArrayList<IndexStore.Item>();
                    for (IndexStore.Item it : store.media()) {
                        if (it.kind == IndexStore.KIND_PHOTO && !faceStore.scanned(IndexStore.key(it))) todo.add(it);
                    }
                    java.util.Collections.sort(todo, new java.util.Comparator<IndexStore.Item>() {
                        @Override
                        public int compare(IndexStore.Item a, IndexStore.Item b) {
                            return Long.compare(b.date, a.date);
                        }
                    });
                    faceTotal = todo.size();
                    faceDone = 0;
                    if (todo.isEmpty()) return;
                    notifyChanged();
                    for (IndexStore.Item it : todo) {
                        if (stopFaces || indexing) break; // indexing first: the scan goes on after it
                        if (faceStore.scanned(IndexStore.key(it))) continue;
                        int[] size = new int[2];
                        List<io.github.teoplaydor.semsearch.core.FaceModel.Face> fs = null;
                        try {
                            fs = findFaces(it, size);
                            found += fs.size();
                            read++;
                        } catch (Throwable e) {
                            if (failed++ == 0) Journal.add(ctx, "app", "лица: " + it.title + " не прочитан — " + e);
                        }
                        faceStore.addScan(IndexStore.key(it), fs, size[0], size[1]);
                        faceDone++;
                        long now = System.currentTimeMillis();
                        if (now - last > 1000) {
                            last = now;
                            notifyChanged();
                        }
                    }
                } finally {
                    faceScanning = false;
                    if (read > 0 || failed > 0) {
                        Journal.add(ctx, "app", "лица: просмотрено " + read + " фото, найдено " + found + " лиц за "
                                + (System.currentTimeMillis() - t0) / 1000 + " с" + (read > 0 ? " (" + (System.currentTimeMillis() - t0) / read
                                + " мс на фото)" : "") + (failed > 0 ? ", не прочитано " + failed : ""));
                    }
                    notifyChanged();
                }
            }
        });
    }

    /** A face of a photo, for the screen: its box (fractions), and who it is (0 and null: nobody named). */
    public static final class FaceTag {
        public final long faceId, personId;
        public final float x, y, w, h;
        public final String name;

        FaceTag(People.Face f, long personId, String name) {
            faceId = f.id;
            x = f.x;
            y = f.y;
            w = f.w;
            h = f.h;
            this.personId = personId;
            this.name = name;
        }
    }

    /** People and pets as they stand: the faces, who each is, the albums. */
    private static final class PeopleView {
        int version, mediaSize, level;
        List<People.Face> faces;
        int[] who;
        List<FaceStore.Group> persons = new ArrayList<FaceStore.Group>();
        List<Album> albums = new ArrayList<Album>();
    }

    private final Object peopleLock = new Object();
    private PeopleView peopleCache;

    /** Made again when faces, names or marks, or the index changed (any thread but faceScan, which it would wait for). */
    private PeopleView people() {
        synchronized (peopleLock) {
            List<IndexStore.Item> media = store.media();
            int v = faceStore.version(), level = faceLevel();
            if (peopleCache != null && peopleCache.version == v && peopleCache.mediaSize == media.size() && peopleCache.level == level) {
                return peopleCache;
            }
            PeopleView pv = new PeopleView();
            pv.version = v;
            pv.mediaSize = media.size();
            pv.level = level;
            java.util.Map<Long, IndexStore.Item> byKey = new java.util.HashMap<Long, IndexStore.Item>();
            for (IndexStore.Item it : media) byKey.put(IndexStore.key(it), it);
            pv.faces = new ArrayList<People.Face>();
            for (People.Face f : faceStore.faces()) if (byKey.containsKey(f.photo) && !faceStore.ignored(f.id)) pv.faces.add(f);
            List<People.Person> persons = new ArrayList<People.Person>();
            List<FaceStore.Group> things = new ArrayList<FaceStore.Group>();
            for (FaceStore.Group g : faceStore.groups()) {
                if (g.kind == FaceStore.PERSON) {
                    People.Person p = new People.Person();
                    p.yes.addAll(g.yesFaces.values());
                    p.no.addAll(g.noFaces.values());
                    persons.add(p);
                    pv.persons.add(g);
                } else {
                    things.add(g);
                }
            }
            pv.who = People.assign(pv.faces, persons, level);
            for (int p = 0; p < pv.persons.size(); p++) {
                List<Integer> idx = new ArrayList<Integer>();
                for (int i = 0; i < pv.who.length; i++) if (pv.who[i] == p) idx.add(i);
                Album a = personAlbum(pv.persons.get(p).name, idx, pv.faces, byKey);
                a.kind = Album.PERSON;
                a.id = pv.persons.get(p).id;
                pv.albums.add(a);
            }
            List<People.Cluster> clusters = People.clusters(pv.faces, pv.who, GROUP_FACE, level);
            for (int c = 0; c < Math.min(UNNAMED_MAX, clusters.size()); c++) {
                Album a = personAlbum("Кто это?", clusters.get(c).faces, pv.faces, byKey);
                a.kind = Album.UNNAMED;
                a.faces = new long[clusters.get(c).faces.size()];
                for (int k = 0; k < a.faces.length; k++) a.faces[k] = pv.faces.get(clusters.get(c).faces.get(k)).id;
                pv.albums.add(a);
            }
            if (!things.isEmpty()) {
                // the photos with the model's vectors of one length (another model's leftovers left out)
                int dim = photo != null ? photo.embeddingDim() : 0;
                List<IndexStore.Item> usable = new ArrayList<IndexStore.Item>();
                List<float[]> vecs = new ArrayList<float[]>();
                for (IndexStore.Item it : media) {
                    if (it.emb == null || it.emb.length == 0 || (dim > 0 && it.emb.length != dim)) continue;
                    usable.add(it);
                    vecs.add(it.emb);
                }
                java.util.Map<Long, Integer> at = new java.util.HashMap<Long, Integer>();
                for (int i = 0; i < usable.size(); i++) at.put(IndexStore.key(usable.get(i)), i);
                for (FaceStore.Group g : things) {
                    java.util.Set<Integer> yes = new java.util.HashSet<Integer>(), no = new java.util.HashSet<Integer>();
                    for (long k : g.yesPhotos) if (at.containsKey(k)) yes.add(at.get(k));
                    for (long k : g.noPhotos) if (at.containsKey(k)) no.add(at.get(k));
                    People.Members m = People.byExamples(vecs, yes, no);
                    List<IndexStore.Item> items = new ArrayList<IndexStore.Item>(), more = new ArrayList<IndexStore.Item>();
                    for (int i : m.sure) items.add(usable.get(i));
                    for (int i : m.more) more.add(usable.get(i));
                    Album a = new Album(g.name, items);
                    a.kind = Album.THING;
                    a.id = g.id;
                    a.more = more;
                    pv.albums.add(a);
                }
            }
            peopleCache = pv;
            return pv;
        }
    }

    /** A person's photos (newest first) from their faces, and the clearest face shown. */
    private static Album personAlbum(String name, List<Integer> idx, List<People.Face> faces, java.util.Map<Long, IndexStore.Item> byKey) {
        java.util.LinkedHashSet<IndexStore.Item> items = new java.util.LinkedHashSet<IndexStore.Item>();
        People.Face best = null;
        for (int i : idx) {
            People.Face f = faces.get(i);
            items.add(byKey.get(f.photo));
            if (best == null || f.size * (double) f.score > best.size * (double) best.score) best = f;
        }
        List<IndexStore.Item> list = new ArrayList<IndexStore.Item>(items);
        java.util.Collections.sort(list, new java.util.Comparator<IndexStore.Item>() {
            @Override
            public int compare(IndexStore.Item a, IndexStore.Item b) {
                return Long.compare(b.date, a.date);
            }
        });
        Album a = new Album(name, list);
        if (best != null) {
            a.face = byKey.get(best.photo);
            a.box = new float[]{best.x, best.y, best.w, best.h};
        }
        return a;
    }

    /** People (named, then unnamed) and pets/things, for the albums sheet; what is hidden (18+) left out. */
    public void people(final Callback<List<Album>> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if (faceStore == null) throw new IllegalStateException("индекс ещё открывается");
                    post(cb, visible(people().albums), null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    /** The faces of a photo and who they are; the photo is looked at now when the scan has not reached it. */
    public void facesOf(final IndexStore.Item it, final Callback<List<FaceTag>> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    long key = IndexStore.key(it);
                    if (!faceStore.scanned(key)) {
                        if (!facesInstalled()) throw new IllegalStateException("модели лиц не скачаны");
                        int[] size = new int[2];
                        faceStore.addScan(key, findFaces(it, size), size[0], size[1]);
                    }
                    PeopleView pv = people();
                    List<FaceTag> out = new ArrayList<FaceTag>();
                    for (int i = 0; i < pv.faces.size(); i++) {
                        People.Face f = pv.faces.get(i);
                        if (f.photo != key) continue;
                        FaceStore.Group g = pv.who[i] >= 0 ? pv.persons.get(pv.who[i]) : null;
                        out.add(new FaceTag(f, g == null ? 0 : g.id, g == null ? null : g.name));
                    }
                    java.util.Collections.sort(out, new java.util.Comparator<FaceTag>() {
                        @Override
                        public int compare(FaceTag a, FaceTag b) {
                            return Float.compare(a.x, b.x); // left to right, as they stand
                        }
                    });
                    post(cb, out, null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    /** Every album this photo is in: by meaning (when made already, or now), people, pets and things. */
    public void albumsOf(final IndexStore.Item it, final Callback<List<Album>> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Album> out = new ArrayList<Album>();
                    if (faceStore != null) for (Album a : visible(people().albums)) if (a.kind != Album.UNNAMED && a.items.contains(it)) out.add(a);
                    try {
                        for (Album a : visible(albumsNow())) if (a.items.contains(it)) out.add(a);
                    } catch (Exception e) {
                        // no albums by meaning yet (no model): the rest
                    }
                    post(cb, out, null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    public static final String[] FACE_LEVELS = {"мягко", "обычно", "строго"};

    /** How strict faces are told apart (People levels: mild, normal, strict). */
    public int faceLevel() {
        return Math.max(0, Math.min(2, prefs.getInt("face_level", People.NORMAL)));
    }

    public void setFaceLevel(int level) {
        prefs.edit().putInt("face_level", level).apply();
        Journal.add(ctx, "app", "люди: строгость узнавания — " + FACE_LEVELS[faceLevel()]);
        notifyChanged();
    }

    public int facesHidden() {
        FaceStore fs = faceStore;
        return fs == null ? 0 : fs.ignoredCount();
    }

    /** This face is nobody to name (a passer-by): it leaves the photo's faces, people and the unnamed. */
    public void hideFace(final long faceId, final Runnable done) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                faceStore.ignore(faceId);
                changed(done, "люди: лицо скрыто");
            }
        });
    }

    public void showHiddenFaces(final Runnable done) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                faceStore.unignoreAll();
                changed(done, "люди: скрытые лица возвращены");
            }
        });
    }

    /** Names of the people (PERSON) or the pets/things (THING) there are, in the order made. */
    public List<String> groupNames(boolean persons) {
        List<String> out = new ArrayList<String>();
        FaceStore fs = faceStore;
        if (fs == null) return out;
        for (FaceStore.Group g : fs.groups()) if ((g.kind == FaceStore.PERSON) == persons) out.add(g.name);
        return out;
    }

    private void changed(final Runnable done, final String journal) {
        if (journal != null) Journal.add(ctx, "app", journal);
        notifyChanged();
        if (done != null) main.post(done);
    }

    /** This face is the person of this name (made when new). */
    public void nameFace(final long faceId, final String name, final Runnable done) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                People.Face f = faceStore.face(faceId);
                if (f != null && !name.trim().isEmpty()) faceStore.markFace(faceStore.named(name, FaceStore.PERSON), f, true);
                changed(done, "люди: лицо отмечено как «" + name.trim() + "»");
            }
        });
    }

    /** This face is not this person. */
    public void notPerson(final long faceId, final long personId, final Runnable done) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                People.Face f = faceStore.face(faceId);
                FaceStore.Group g = faceStore.group(personId);
                if (f != null && g != null) faceStore.markFace(g, f, false);
                changed(done, g == null ? null : "люди: лицо отмечено как не «" + g.name + "»");
            }
        });
    }

    /**
     * Someone unnamed gets a name: their most typical faces (up to 8) become the person's — not all of them: a face that
     * got into the group by mistake would bring its own lookalikes along.
     */
    public void nameUnnamed(final Album a, final String name, final Runnable done) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                if (name.trim().isEmpty()) return;
                FaceStore.Group g = faceStore.named(name, FaceStore.PERSON);
                for (int k = 0; k < Math.min(People.REPS, a.faces.length); k++) {
                    People.Face f = faceStore.face(a.faces[k]);
                    if (f != null) faceStore.markFace(g, f, true);
                }
                changed(done, "люди: «" + name.trim() + "» — " + a.items.size() + " фото");
            }
        });
    }

    /** This photo is (or, {@code yes} false, is not) the pet or thing of this name (made when new). */
    public void markThing(final IndexStore.Item it, final String name, final boolean yes, final Runnable done) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                if (name.trim().isEmpty()) return;
                faceStore.markPhoto(faceStore.named(name, FaceStore.THING), IndexStore.key(it), yes);
                changed(done, "питомцы и другое: фото " + (yes ? "отмечено как" : "убрано из") + " «" + name.trim() + "»");
            }
        });
    }

    public void renameGroup(final long id, final String name, final Runnable done) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                if (!name.trim().isEmpty()) faceStore.rename(id, name);
                changed(done, null);
            }
        });
    }

    public void deleteGroup(final long id, final Runnable done) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                faceStore.delete(id);
                changed(done, null);
            }
        });
    }

    // ------------------------------------------------------------------ 18+

    public static final String[] ADULT_LEVELS = {"мягко", "обычно", "строго"};
    /** The phrases of assets/adult.txt embedded by the photo model (AdultFilter), and for which model and gallery size. */
    private AdultFilter adult;
    private Embedder adultModel;
    private int adultCalibratedAt = -1;
    /** IndexStore.key's: what the filter found, what was hidden by hand and what was shown by hand (it wins over the filter). */
    private final Object hideLock = new Object();
    private java.util.Set<Long> adultAuto, adultManual, adultShown;

    public boolean hideAdult() {
        return prefs.getBoolean("hide_adult", false);
    }

    public int adultLevel() {
        return Math.max(0, Math.min(ADULT_LEVELS.length - 1, prefs.getInt("adult_level", AdultFilter.NORMAL)));
    }

    private java.util.Set<Long> keys(String name) {
        java.util.Set<Long> out = new java.util.HashSet<Long>();
        for (String k : prefs.getStringSet(name, new java.util.HashSet<String>())) {
            try {
                out.add(Long.parseLong(k));
            } catch (NumberFormatException ignored) {
                // not ours
            }
        }
        return out;
    }

    private void saveKeys(String name, java.util.Set<Long> keys) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (long k : keys) out.add(String.valueOf(k));
        prefs.edit().putStringSet(name, out).apply();
    }

    /** What the store keeps out of sight: the filter's finds and the ones hidden by hand, not those shown by hand. */
    private void applyHidden(IndexStore s) {
        java.util.Set<Long> h = new java.util.HashSet<Long>();
        synchronized (hideLock) {
            if (adultAuto == null) {
                adultAuto = keys("adult_auto");
                adultManual = keys("adult_manual");
                adultShown = keys("adult_shown");
            }
            if (hideAdult()) {
                h.addAll(adultAuto);
                h.addAll(adultManual);
                h.removeAll(adultShown);
            }
        }
        s.setHidden(h);
    }

    /**
     * Turns hiding of 18+ on or off. On: what was found before is hidden at once, then the index is checked (with the
     * model loaded; else once it is); {@code cb} gets the number hidden.
     */
    public void setHideAdult(boolean on, final Callback<Integer> cb) {
        prefs.edit().putBoolean("hide_adult", on).apply();
        if (store != null) applyHidden(store);
        notifyChanged();
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if (state == State.READY) classifyAdult(false);
                    post(cb, hiddenItems().size(), null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
                notifyChanged();
            }
        });
    }

    /** How strict the filter is (AdultFilter levels); the index is checked again. */
    public void setAdultLevel(int level, final Callback<Integer> cb) {
        prefs.edit().putInt("adult_level", level).apply();
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if (state == State.READY) classifyAdult(true);
                    post(cb, hiddenItems().size(), null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
                notifyChanged();
            }
        });
    }

    /** Hides or shows one photo or video by hand; it stays so whatever the filter finds later. */
    public void setHidden(IndexStore.Item it, boolean hide) {
        long k = IndexStore.key(it);
        synchronized (hideLock) {
            if (adultAuto == null) applyHidden(store);
            if (hide) {
                adultManual.add(k);
                adultShown.remove(k);
            } else {
                adultManual.remove(k);
                if (adultAuto.contains(k)) adultShown.add(k);
            }
            saveKeys("adult_manual", adultManual);
            saveKeys("adult_shown", adultShown);
        }
        applyHidden(store);
        Journal.add(ctx, "app", "18+: " + (it.title != null ? it.title : "файл") + (hide ? " скрыт вручную" : " возвращён вручную"));
        notifyChanged();
    }

    public boolean isHidden(IndexStore.Item it) {
        IndexStore s = store;
        return s != null && s.isHidden(it);
    }

    public List<IndexStore.Item> hiddenItems() {
        IndexStore s = store;
        return s == null ? new ArrayList<IndexStore.Item>() : s.hiddenItems();
    }

    private AdultFilter adultFilter() throws Exception {
        if (adult == null || adultModel != photo) {
            List<String> words = AdultFilter.parse(ctx.getAssets().open("adult.txt"));
            float[][] vecs = new float[words.size()][];
            for (int i = 0; i < vecs.length; i++) vecs[i] = photo.embedQuery(words.get(i));
            adult = new AdultFilter(words, vecs);
            adultModel = photo;
            adultCalibratedAt = -1;
        }
        List<IndexStore.Item> media = store.media();
        if (adultCalibratedAt < 0 || Math.abs(media.size() - adultCalibratedAt) > Math.max(20, adultCalibratedAt / 10)) {
            // the gallery's typical best similarity: up to 500 pictures spread over the index
            List<float[]> sample = new ArrayList<float[]>();
            int dim = adult.vecs.length > 0 ? adult.vecs[0].length : 0;
            int step = Math.max(1, media.size() / 500);
            for (int i = 0; i < media.size(); i += step) {
                float[] e = media.get(i).emb;
                if (e != null && e.length == dim) sample.add(e);
            }
            adult.calibrate(sample);
            adultCalibratedAt = media.size();
        }
        return adult;
    }

    /**
     * Runs the filter over every photo and video (hiding on, the photo model loaded); unless {@code force}, only when
     * the index, the level or the model changed since the last run.
     */
    private void classifyAdult(boolean force) throws Exception {
        if (!hideAdult() || photo == null || !photo.supportsImages()) return;
        List<IndexStore.Item> media = store.media();
        int level = adultLevel();
        String sig = level + ":" + media.size() + ":" + prefs.getString("media_sig", "") + ":" + photoModel();
        if (!force && sig.equals(prefs.getString("adult_sig", null))) return;
        long t0 = System.currentTimeMillis();
        PhotoTags t = photoTags();
        AdultFilter f = adultFilter();
        int dim = f.vecs.length > 0 ? f.vecs[0].length : 0;
        java.util.Set<Long> found = new java.util.HashSet<Long>();
        for (IndexStore.Item it : media) {
            if (it.emb != null && it.emb.length == dim && f.adult(it.emb, t, level)) found.add(IndexStore.key(it));
        }
        synchronized (hideLock) {
            if (adultAuto == null) applyHidden(store);
            adultAuto.clear();
            adultAuto.addAll(found);
            saveKeys("adult_auto", found);
        }
        prefs.edit().putString("adult_sig", sig).apply();
        applyHidden(store);
        Journal.add(ctx, "app", "18+ (" + ADULT_LEVELS[level] + "): найдено " + found.size() + " из " + media.size() + " за "
                + (System.currentTimeMillis() - t0) + " мс");
    }

    /** Checks the index again on the ml thread, when hiding is on and the model is there. */
    private void refreshAdult() {
        if (!hideAdult()) return;
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if (state != State.READY) return;
                    classifyAdult(false);
                    notifyChanged();
                } catch (Throwable e) {
                    android.util.Log.e("SemSearch", "18+ check", e);
                }
            }
        });
    }

    /** A photo or video just indexed: hidden at once when it is 18+ (the whole index is checked again at the end). */
    private void checkAdult(IndexStore.Item it) {
        if (!hideAdult() || it == null) return;
        try {
            AdultFilter f = adultFilter();
            if (it.emb.length == f.vecs[0].length && f.adult(it.emb, photoTags(), adultLevel())) {
                synchronized (hideLock) {
                    adultAuto.add(IndexStore.key(it));
                }
                applyHidden(store);
            }
        } catch (Throwable e) {
            android.util.Log.e("SemSearch", "18+ check", e);
        }
    }

    // ------------------------------------------------------------------ notes

    public void addNote(final String text, final Callback<IndexStore.Item> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    requireModel();
                    float[] e = model.embedDocument(text);
                    IndexStore.Item it = store.add(IndexStore.KIND_NOTE, -1, null, null, text, System.currentTimeMillis(), e);
                    post(cb, it, null);
                    notifyChanged();
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    public void deleteItem(final IndexStore.Item it) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                store.delete(it);
                notifyChanged();
            }
        });
    }

    public void clearMediaIndex() {
        stopIndex();
        ml.submit(new Runnable() {
            @Override
            public void run() {
                store.clearMedia();
                notifyChanged();
            }
        });
    }

    // ------------------------------------------------------------------ gallery indexing

    /** pref "photo_detail": 0..2 = PHOTO_BUDGETS, 3 = auto (the default). */
    public static final int DETAIL_AUTO = 3;

    public boolean autoDetail() {
        return prefs.getInt("photo_detail", DETAIL_AUTO) == DETAIL_AUTO;
    }

    /** Budget for an ordinary photo (auto: the fast one). */
    public int photoBudget() {
        int i = prefs.getInt("photo_detail", DETAIL_AUTO);
        if (i == DETAIL_AUTO) return PHOTO_BUDGETS[0];
        return PHOTO_BUDGETS[Math.max(0, Math.min(PHOTO_BUDGETS.length - 1, i))];
    }

    /** The largest budget in use (auto: screenshots get the most detailed one). */
    public int maxBudget() {
        return autoDetail() ? PHOTO_BUDGETS[PHOTO_BUDGETS.length - 1] : photoBudget();
    }

    /**
     * Auto detail: 70 soft tokens for ordinary photos (4× fewer patches for the vision encoder), 280 for
     * screenshots and scans, where small text decides what a picture is about.
     */
    int budgetFor(Media.Entry e) {
        return autoDetail() && e.textHeavy ? maxBudget() : photoBudget();
    }

    /**
     * Embeds a photo with the chosen detail level. If the exported vision encoder only accepts
     * its default token budget, falls back to it and remembers that.
     */
    private float[] embedPhoto(Bitmap b, int budget) throws Exception {
        return embedPhotos(java.util.Collections.singletonList(b), budget)[0];
    }

    private float[][] embedPhotos(List<Bitmap> bitmaps, int budget) throws Exception {
        List<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source> src =
                new ArrayList<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source>();
        for (Bitmap b : bitmaps) src.add(new Media.BitmapSource(b));
        if (!(photo instanceof EmbeddingGemma2)) return photo.embedImages(src, budget); // SigLIP: one resolution, ignores it
        try {
            return photo.embedImages(src, budget);
        } catch (Exception e) {
            // only an encoder exported for one fixed budget rejects the others (by the input's size); the NPU graph
            // is compiled for the budget it gets, and its errors are something else — they must not change the detail
            if (budget == photo.defaultImageTokens() || isQnn(loadedAccel) || npuProcessGone(e)) throw e;
            float[][] r = photo.embedImages(src, 0);
            prefs.edit().putInt("photo_detail", PHOTO_BUDGETS.length - 1).apply();
            return r;
        }
    }

    // ------------------------------------------------------------------ acceleration

    public static final int ACCEL_CPU = 0, ACCEL_CPU_INT8 = 1, ACCEL_GPU = 2, ACCEL_GPU_INT8 = 3, ACCEL_NPU = 4,
            ACCEL_NPU_FP32 = 5, ACCEL_GPU_FP16 = 6, ACCEL_LITERT_GPU = 7, ACCEL_LITERT_CPU = 8, ACCEL_GPU_FP16_ATTN = 9,
            ACCEL_NPU_QNN = 10;
    /**
     * For the NPU and fp16 variants the vision encoder (most of the work per photo) runs there, the text model on
     * the CPU in int8. The LiteRT-LM variants run Google's own build of the model with its own kernels.
     */
    public static final String[] ACCEL_NAMES = {"Процессор", "Процессор, int8", "Видеокарта (WebGPU)",
            "Видеокарта (WebGPU), int8", "NPU (NNAPI)", "NPU (NNAPI, fp32)", "Видеокарта (WebGPU), fp16",
            "LiteRT-LM, видеокарта", "LiteRT-LM, процессор", "Видеокарта (WebGPU), int8 + fp16-внимание",
            "NPU Snapdragon (QNN)"};

    /** ONNX Runtime's WebGPU provider (the "gpu_broken" guard covers these). */
    public static boolean isGpu(int a) {
        return a == ACCEL_GPU || a == ACCEL_GPU_INT8 || a == ACCEL_GPU_FP16 || a == ACCEL_GPU_FP16_ATTN;
    }

    /** The vision encoder on the Snapdragon NPU through Qualcomm QNN, in the separate NPU process. */
    public static boolean isQnn(int a) {
        return a == ACCEL_NPU_QNN;
    }

    public static boolean isLiteRt(int a) {
        return a == ACCEL_LITERT_GPU || a == ACCEL_LITERT_CPU;
    }

    public static boolean isNpu(int a) {
        return a == ACCEL_NPU || a == ACCEL_NPU_FP32;
    }

    public int accel() {
        int a = Math.max(0, Math.min(ACCEL_NAMES.length - 1, prefs.getInt("accel", ACCEL_CPU)));
        if (isGpu(a) && prefs.getBoolean("gpu_broken", false)) a = ACCEL_CPU;
        if (isNpu(a) && (npuBroken(a) || gemmaFp32Vision() == null)) a = ACCEL_CPU;
        if (a == ACCEL_GPU_FP16 && gemmaFp16Vision() == null) a = ACCEL_CPU;
        if (isQnn(a) && !qnnUsable()) a = ACCEL_CPU;
        if (isLiteRt(a) && (liteRtBroken(a) || !liteRtInstalled())) {
            // in LiteRT-LM's vector space only its other backend keeps the index usable
            int other = a == ACCEL_LITERT_GPU ? ACCEL_LITERT_CPU : ACCEL_LITERT_GPU;
            a = liteRtSpace() && liteRtInstalled() && !liteRtBroken(other) ? other : ACCEL_CPU;
        }
        return a;
    }

    public boolean liteRtBroken(int a) {
        return prefs.getBoolean("litert_broken_" + a, false);
    }

    QnnRuntime qnn() {
        return new QnnRuntime(new File(ctx.getFilesDir(), "qnn"));
    }

    /** Build.SOC_MODEL (API 31+), e.g. "SM8850", or null. */
    static String socModel() {
        if (android.os.Build.VERSION.SDK_INT < 31) return null;
        try {
            String s = (String) android.os.Build.class.getField("SOC_MODEL").get(null);
            return s == null || s.isEmpty() || "unknown".equalsIgnoreCase(s) ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** A Qualcomm Snapdragon (its NPU is reachable through QNN). */
    public static boolean isSnapdragon() {
        String soc = socModel();
        if (soc != null && soc.toUpperCase(java.util.Locale.ROOT).startsWith("SM")) return true;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                String m = (String) android.os.Build.class.getField("SOC_MANUFACTURER").get(null);
                if (m != null && (m.equalsIgnoreCase("QTI") || m.toLowerCase(java.util.Locale.ROOT).contains("qualcomm"))) return true;
            } catch (Exception ignored) {
                // not available
            }
        }
        return android.os.Build.HARDWARE != null && android.os.Build.HARDWARE.toLowerCase(java.util.Locale.ROOT).startsWith("qcom");
    }

    public boolean qnnInstalled() {
        return qnn().installed() != null;
    }

    /** QNN downloaded, the full-precision vision graph present, and the NPU process has not crashed before. */
    public boolean qnnUsable() {
        return qnnInstalled() && gemmaFp32Vision() != null && !prefs.getBoolean("qnn_broken", false);
    }

    /** On a Snapdragon with EmbeddingGemma for photos, something the NPU needs is not on the phone yet. */
    public boolean qnnMissing() {
        if (photoModel() != FastModel.GEMMA || gemmaPlan() == null || !isSnapdragon()) return false;
        return !qnnInstalled() || gemmaFp32Vision() == null;
    }

    public long qnnBytes() {
        long n = qnn().bytesOnDisk();
        File[] ctxs = new File(modelDir, "onnx").listFiles();
        if (ctxs != null) for (File f : ctxs) if (f.getName().contains(".qnn.")) n += f.length();
        return n;
    }

    LiteRtRuntime liteRt() {
        return new LiteRtRuntime(new File(ctx.getFilesDir(), "litertlm"), prefs.getString("token", ""));
    }

    /** LiteRT-LM's libraries and EmbeddingGemma 2 in its format are on the phone. */
    public boolean liteRtInstalled() {
        return liteRt().installed() != null;
    }

    /**
     * The index holds LiteRT-LM's vectors (it was rebuilt with it because they differ from the ONNX model's):
     * only LiteRT-LM may embed photos and queries until the person goes back.
     */
    public boolean liteRtSpace() {
        return prefs.getBoolean("litert_space", false) && photoModel() == FastModel.GEMMA;
    }

    /** A LiteRT-LM variant the speed check found clearly faster, but with vectors that need a new index (or -1). */
    public int liteRtOffer() {
        return liteRtSpace() || !liteRtInstalled() ? -1 : prefs.getInt("litert_offer", -1);
    }

    /** EmbeddingGemma's half-precision vision encoder (for the GPU), when downloaded. */
    File gemmaFp16Vision() {
        HfRepo.Plan p = gemmaPlan();
        if (p == null || p.fp16Vision == null) return null;
        File f = new File(modelDir, p.fp16Vision);
        return f.exists() ? f : null;
    }

    /** Neither the fp16 graph nor LiteRT-LM is on the phone yet (EmbeddingGemma serves photos). */
    public boolean speedupsMissing() {
        if (photoModel() != FastModel.GEMMA || gemmaPlan() == null) return false;
        return gemmaFp16Vision() == null || !liteRtInstalled();
    }

    public boolean npuBroken(int a) {
        return prefs.getBoolean("npu_broken_" + a, false);
    }

    /** EmbeddingGemma's full-precision vision encoder (needed by the NPU), when downloaded. */
    File gemmaFp32Vision() {
        HfRepo.Plan p = gemmaPlan();
        if (p == null || p.accelVision == null) return null;
        File f = new File(modelDir, p.accelVision);
        return f.exists() ? f : null;
    }

    /** Size of the downloaded NPU graph (0 when absent). */
    public long gemmaFp32Bytes() {
        HfRepo.Plan p = gemmaFp32Vision() != null ? gemmaPlan() : null;
        if (p == null) return 0;
        long n = 0;
        for (HfRepo.RemoteFile f : p.files) if (f.path.startsWith(p.accelVision)) n += new File(modelDir, f.path).length();
        return n;
    }

    /** EmbeddingGemma serves photos and its NPU graph is not on the phone yet. */
    public boolean gemmaNeedsFp32() {
        if (photoModel() != FastModel.GEMMA) return false;
        HfRepo.Plan p = gemmaPlan();
        return p != null && p.visionModel != null && gemmaFp32Vision() == null;
    }

    /** About 7× the 4-bit vision encoder (fp32 weights). */
    public long gemmaFp32EstimateBytes() {
        HfRepo.Plan p = gemmaPlan();
        if (p == null || p.visionModel == null) return 0;
        long q4 = 0;
        for (HfRepo.RemoteFile f : p.files) if (f.path.startsWith(p.visionModel)) q4 += Math.max(0, f.size);
        return 7 * q4;
    }

    public boolean gpuBroken() { return prefs.getBoolean("gpu_broken", false); }

    /**
     * Graph file for a component: the original, or a copy whose 4-bit MatMuls compute in int8
     * (accuracy_level 4, see OnnxPatcher). The copy shares the original's external weights.
     */
    private File graphFile(String rel, boolean int8) throws java.io.IOException {
        File orig = new File(modelDir, rel);
        if (!int8) return orig;
        File patched = new File(modelDir, rel.replace(".onnx", ".int8.onnx"));
        if (!patched.exists() || patched.lastModified() < orig.lastModified()) {
            if (OnnxPatcher.setMatMulNBitsAccuracy(orig, patched, 4) == 0) {
                patched.delete();
                return orig;
            }
        }
        return patched;
    }

    private Embedder createModel(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int accel, int nThreads)
            throws Exception {
        return createModel(cfg, tok, plan, accel, nThreads, batchSize());
    }

    private Embedder createModel(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int accel, int nThreads,
                                 int batch) throws Exception {
        if (isLiteRt(accel)) return openLiteRt(accel, nThreads, batch);
        if (isQnn(accel) && plan.visionModel != null) return openQnn(cfg, tok, plan, nThreads);
        boolean npu = isNpu(accel) && plan.visionModel != null;
        boolean int8 = accel == ACCEL_CPU_INT8 || accel == ACCEL_GPU_INT8 || isNpu(accel) || accel == ACCEL_GPU_FP16
                || accel == ACCEL_GPU_FP16_ATTN;
        boolean gpu = isGpu(accel);
        File text = graphFile(plan.textModel, int8);
        if (npu) {
            File vision = plan.accelVision == null ? null : new File(modelDir, plan.accelVision);
            if (vision == null || !vision.exists()) {
                throw new java.io.IOException("для NPU нужна полная версия визуального энкодера — «Проверить NPU» в настройках");
            }
            // a caller that also runs the model (benchmark) holds the probe itself, past creation
            boolean outer = prefs.contains("npu_probe");
            if (!outer) prefs.edit().putString("npu_probe", String.valueOf(accel)).commit();
            try {
                return new EmbeddingGemma2(cfg, tok, text, vision, nThreads, accel == ACCEL_NPU
                        ? EmbeddingGemma2.VisionAccel.NPU : EmbeddingGemma2.VisionAccel.NPU_FP32, batch, maxBudget());
            } finally {
                if (!outer) prefs.edit().remove("npu_probe").commit();
            }
        }
        File vision = plan.visionModel == null ? null : graphFile(plan.visionModel, int8);
        if (accel == ACCEL_GPU_FP16_ATTN && vision != null) vision = fp16AttentionGraph(vision);
        if (accel == ACCEL_GPU_FP16 && plan.visionModel != null) {
            vision = plan.fp16Vision == null ? null : new File(modelDir, plan.fp16Vision);
            if (vision == null || !vision.exists()) {
                throw new java.io.IOException("нет fp16-версии визуального энкодера — «Проверить LiteRT-LM и fp16» в настройках");
            }
        }
        if (gpu) prefs.edit().putBoolean("gpu_probe", true).commit();
        try {
            return new EmbeddingGemma2(cfg, tok, text, vision, nThreads, gpu);
        } finally {
            if (gpu) prefs.edit().putBoolean("gpu_probe", false).commit();
        }
    }

    /**
     * The int8 vision graph with its fused attention computed in fp16 (OnnxPatcher.fp16Attention), next to it so
     * the external weights resolve; rebuilt when the int8 copy changes.
     */
    private File fp16AttentionGraph(File int8) throws java.io.IOException {
        File patched = new File(int8.getPath().replace(".onnx", ".fp16attn.onnx"));
        if (!patched.exists() || patched.lastModified() < int8.lastModified()) {
            if (OnnxPatcher.fp16Attention(int8, patched) == 0) {
                throw new java.io.IOException("в визуальном энкодере нет слитого внимания (MultiHeadAttention)");
            }
        }
        return patched;
    }

    /**
     * The text model here (int8 on the CPU), the vision encoder on the Snapdragon NPU in the NPU process: the
     * full-precision graph rewritten into ops QNN can run (OnnxPatcher.forQnn), compiled there on first use.
     */
    private Embedder openQnn(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int nThreads) throws Exception {
        File fp32 = plan.accelVision == null ? null : new File(modelDir, plan.accelVision);
        if (fp32 == null || !fp32.exists()) {
            throw new java.io.IOException("для NPU Snapdragon нужна полная версия визуального энкодера — «Проверить NPU Snapdragon»");
        }
        if (!qnnInstalled()) throw new java.io.IOException("QNN не скачан — «Проверить NPU Snapdragon» в настройках");
        File graph = qnnGraph(fp32);
        if (!graph.exists() || graph.lastModified() < fp32.lastModified()) {
            // a graph from an older rewrite (and what was compiled from it) goes
            String base = fp32.getName().replace(".onnx", ".qnn."), now = graph.getName().replace(".onnx", "");
            File[] old = fp32.getParentFile().listFiles();
            if (old != null) {
                for (File f : old) {
                    if (!f.getName().startsWith(base)) continue;
                    // the nodes an older graph kept on the CPU are worth trying first (QnnBuild matches them by name)
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\.(p\\d+_cpu\\.txt)$").matcher(f.getName());
                    if (m.find() && !f.getName().startsWith(now + ".")) f.renameTo(new File(f.getParentFile(), now + "." + m.group(1)));
                    else f.delete();
                }
            }
            OnnxPatcher.forQnn(fp32, graph, true);
        }
        // the same rewrite with the norms spelled out: the NPU process falls back to it where QNN's own norm fails
        File split = NpuService.decomposedGraph(graph);
        if (!split.exists() || split.lastModified() < fp32.lastModified()) OnnxPatcher.forQnn(fp32, split, false);
        File text = graphFile(plan.textModel, true);
        qnnGraphFile = graph;
        qnnPool = Math.max(1, cfg.image.poolingKernelSize);
        NpuVision vision = new NpuVision(ctx, qnn().libDir(), graph);
        try {
            return new EmbeddingGemma2(cfg, tok, text, vision, nThreads);
        } catch (Exception e) {
            vision.close();
            throw e;
        }
    }

    /**
     * The vision graph rewritten for QNN; the "r" number changes with the rewrite (r2: unique node names; r3:
     * RMS norm and constants safe in fp16; r4: sentinels used as data — the mask carried in the keys — become
     * ±10⁴, bounds ±65504; r5: Gathers with constant indices as Slices, divisions by a per-vector value as
     * multiplications by its reciprocal, no scaling of scores by 1; r6: the reciprocal as Div(1, x), not Reciprocal,
     * which QNN's provider took although kept on the CPU; r7: GELU as x·σ(x·(a + b·x²)), QNN's Gelu was 40% of the
     * NPU's time, and √ε / s as a multiplication; r8: RMS norms as QNN's own RmsNorm, opset 23 — with the r7 form
     * next to it, NpuService.decomposedGraph, for a patch count where QNN's norm does not work).
     */
    private static File qnnGraph(File fp32) {
        return new File(fp32.getParentFile(), fp32.getName().replace(".onnx", ".qnn.r8.onnx"));
    }

    /** A checked NPU compilation for this many patches: of the graph, or of its sibling with the norms spelled out. */
    static boolean qnnBuilt(File graph, int patches) {
        for (File g : new File[]{graph, NpuService.decomposedGraph(graph)}) {
            if (new File(g.getParentFile(), g.getName().replace(".onnx", "") + ".p" + patches + "_precision.txt").exists()) return true;
        }
        return false;
    }

    /** Where the NPU's result differs from the CPU's (NpuService.scan), for the last image it ran. */
    private String scanQnn(ModelConfig cfg, HfRepo.Plan plan, int budget) {
        NpuVision v = null;
        try {
            v = new NpuVision(ctx, qnn().libDir(), qnnGraph(new File(modelDir, plan.accelVision)));
            int pool = Math.max(1, cfg.image.poolingKernelSize);
            return v.scan(budget * pool * pool, cfg.image.patchSize * cfg.image.patchSize * 3);
        } catch (Exception e) {
            return "не вышло: " + e.getMessage();
        } finally {
            if (v != null) v.close();
        }
    }

    /** Where the NPU graph's nodes run for {@code budget} tokens (profiled in the NPU process). */
    private String profileQnn(ModelConfig cfg, HfRepo.Plan plan, int budget) {
        NpuVision v = null;
        try {
            File graph = qnnGraph(new File(modelDir, plan.accelVision));
            v = new NpuVision(ctx, qnn().libDir(), graph);
            int pool = Math.max(1, cfg.image.poolingKernelSize);
            return v.profile(budget * pool * pool, cfg.image.patchSize * cfg.image.patchSize * 3);
        } catch (Exception e) {
            return "профиль не снят: " + e.getMessage();
        } finally {
            if (v != null) v.close();
        }
    }

    private static volatile boolean liteRtLoaded;
    /** Libraries of the runtime that did not load (e.g. an OpenCL accelerator on a phone without OpenCL). */
    static volatile String liteRtLoadIssues;
    /** The last LiteRT-LM engine had to be created with the bundle's own detail (no signature for maxBudget()). */
    static volatile boolean liteRtBudgetFixed;

    /** The installed LiteRT-LM bundle and the detail asked of it, when that bundle did not take the detail. */
    private static final String LITERT_FIXED = "litert_fixed";

    /** "bundle file:size:detail" of the installed LiteRT-LM bundle; null when none is installed. */
    private String liteRtKey() {
        try {
            LiteRtRuntime rt = liteRt();
            LiteRtRuntime.Installed i = rt.installed();
            if (i == null) return null;
            File f = rt.modelFile(i);
            return f.getName() + ":" + f.length() + ":" + maxBudget();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * This bundle is known not to take the detail asked (a check found it): the check does not load it again. In
     * 0.10.9 the CPU slowed several times over right after LiteRT-LM was loaded on the CPU only to say so.
     */
    private boolean liteRtKnownFixed() {
        String key = liteRtKey();
        return key != null && key.equals(prefs.getString(LITERT_FIXED, null));
    }

    /** EmbeddingGemma 2 on LiteRT-LM: loads its native libraries once per process, under the crash probe. */
    private Embedder openLiteRt(int accel, int nThreads, int batch) throws Exception {
        LiteRtRuntime rt = liteRt();
        LiteRtRuntime.Installed i = rt.installed();
        if (i == null) throw new java.io.IOException("LiteRT-LM не скачан — «Проверить LiteRT-LM и fp16» в настройках");
        boolean outer = prefs.contains("litert_probe");
        if (!outer) prefs.edit().putString("litert_probe", String.valueOf(accel)).commit();
        try {
            synchronized (Engine.class) {
                if (!liteRtLoaded) {
                    List<String> issues = LiteRtRuntime.load(rt.libDir(), i.libs);
                    liteRtLoadIssues = issues.isEmpty() ? null : issues.toString();
                    liteRtLoaded = true;
                }
            }
            File cache = new File(ctx.getCacheDir(), "litertlm");
            if (!cache.exists()) cache.mkdirs();
            String backend = accel == ACCEL_LITERT_GPU ? LiteRtEmbedder.GPU : LiteRtEmbedder.CPU;
            try {
                LiteRtEmbedder m = new LiteRtEmbedder(rt.modelFile(i), backend, nThreads, maxBudget(), cache, batch, JPEG);
                liteRtBudgetFixed = false;
                prefs.edit().remove(LITERT_FIXED).apply();
                return m;
            } catch (RuntimeException noSuchBudget) {
                liteRtBudgetFixed = true;
                String key = liteRtKey();
                if (key != null) prefs.edit().putString(LITERT_FIXED, key).apply();
                // a bundle without a signature for this many picture tokens: its own default for every picture
                android.util.Log.w("SemSearch", "LiteRT-LM with " + maxBudget() + " tokens", noSuchBudget);
                return new LiteRtEmbedder(rt.modelFile(i), backend, nThreads, 0, cache, batch, JPEG);
            }
        } catch (UnsatisfiedLinkError e) {
            throw new java.io.IOException(e.getMessage(), e);
        } finally {
            if (!outer) prefs.edit().remove("litert_probe").commit();
        }
    }

    /** LiteRT-LM takes pictures as PNG/JPEG bytes and resizes them itself. */
    static final LiteRtEmbedder.ImageEncoder JPEG = new LiteRtEmbedder.ImageEncoder() {
        @Override
        public byte[] encode(ImagePreprocessor.Source s) {
            Bitmap b;
            boolean own = false;
            if (s instanceof Media.BitmapSource) {
                b = ((Media.BitmapSource) s).bitmap();
            } else {
                float k = Math.min(1f, 1024f / Math.max(s.width(), s.height()));
                int w = Math.max(1, Math.round(s.width() * k)), h = Math.max(1, Math.round(s.height() * k));
                b = Bitmap.createBitmap(s.argb(w, h), w, h, Bitmap.Config.ARGB_8888);
                own = true;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(256 * 1024);
            b.compress(Bitmap.CompressFormat.JPEG, 95, out);
            if (own) b.recycle();
            return out.toByteArray();
        }
    };

    static boolean isGemma(Embedder e) {
        return e instanceof EmbeddingGemma2 || e instanceof LiteRtEmbedder;
    }

    private static final class Measure {
        double perPhotoMs = Double.MAX_VALUE, visionMs, textMs;
        /** Measured in the pipeline: the time per photo is the interval between batches, the stages overlap. */
        boolean pipelined;
        float cos = 1f;
        float[] emb;
        String error;
        /** The NPU process crashes on the way (a large graph's compilation, tried again another way). */
        String crashes = "";

        /** "1.21 с (1.19 + 0.18)", in the pipeline "1.21 с (1.19 + 0.18, конвейер)". */
        String time() {
            return String.format(java.util.Locale.ROOT, "%.2f с (%.2f + %.2f%s)", perPhotoMs / 1000.0, visionMs / 1000.0,
                    textMs / 1000.0, pipelined ? ", конвейер" : "");
        }
    }

    /**
     * The vision encoder runs off the CPU (the GPU, the NPU), so the text model of one batch can run on the CPU at
     * the same time as the vision encoder takes the next one. On the CPU both would share its cores.
     */
    static boolean pipelines(int accel) {
        return isGpu(accel) || isQnn(accel) || isNpu(accel);
    }

    /** Times the NPU process may crash while compiling a large graph before the NPU gives up (each time another way). */
    static final int MAX_NPU_RESTARTS = 3;

    /** The running (or last) accelerator check by stages, for the progress on screen; null before the first. */
    public volatile StageProgress bench;
    /** The stage being measured, what the measurement does now, and whether it runs on the NPU (its process's stage is shown). */
    private volatile StageProgress.Stage benchStage;
    private volatile String benchDoing = "";
    private volatile boolean benchNpu;
    private volatile long benchStageMs;
    private java.util.concurrent.ScheduledExecutorService ticker;

    private static long now() {
        return System.currentTimeMillis();
    }

    /** The stage measured from now on (its sub-steps go to its detail). */
    private void benchAt(StageProgress.Stage s, int accel) {
        benchStage = s;
        benchNpu = isQnn(accel);
        benchStageMs = now();
        benchDoing = "";
    }

    /** What the measurement of the current stage does now. */
    private void benchDoing(String what) {
        benchDoing = what;
        Journal.add(ctx, "app", "  " + what);
        StageProgress pr = bench;
        StageProgress.Stage s = benchStage;
        if (pr != null && s != null) pr.detail(s, what);
        notifyChanged();
    }

    /**
     * Once a second while the check runs: the progress moves on screen, and on the NPU the NPU process's own stage
     * (compiling, checking, its memory) joins the detail.
     */
    private void startTicker() {
        stopTicker();
        ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                StageProgress pr = bench;
                if (pr == null || pr.finished()) return;
                StageProgress.Stage s = benchStage;
                if (s != null && benchNpu) {
                    File f = NpuService.stageFile(ctx);
                    if (f.exists() && f.lastModified() >= benchStageMs - 1000) {
                        String npu = readSmall(f).trim();
                        if (!npu.isEmpty()) pr.detail(s, (benchDoing.isEmpty() ? "" : benchDoing + " · ") + "NPU: " + npu);
                    }
                }
                notifyChanged();
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** The check's journal (both processes, by time) at the end of its report. */
    private void appendJournal(StringBuilder rep) {
        String j = Journal.tail(ctx, 120000).trim();
        if (!j.isEmpty()) rep.append("\n\nЖурнал подбора (по времени; app — приложение, npu — NPU-процесс):\n").append(j);
    }

    private void finishBench() {
        StageProgress pr = bench;
        if (pr != null) {
            pr.finish(now());
            Journal.add(ctx, "app", "подбор закончен за " + StageProgress.clock(pr.elapsedMs(now())));
        }
        benchStage = null;
        stopTicker();
        notifyChanged();
    }

    private void stopTicker() {
        if (ticker != null) ticker.shutdownNow();
        ticker = null;
    }

    private static String readSmall(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Expected time of a measurement: the last one of the same kind on this phone, else a guess. */
    private double expectMs(int accel, int budget, int batch, boolean cold) {
        long seen = prefs.getLong(expectKey(accel, budget, batch, cold), 0);
        if (seen > 0) return seen;
        double scale = Math.max(1, budget / 70.0);
        if (isQnn(accel) && cold) return budget * 9 > NpuService.BIG_PATCHES ? 480000 : 180000;
        if (isLiteRt(accel)) return 15000;
        return Math.min(90000, 15000 * scale * Math.max(1, batch / 2.0));
    }

    private static String expectKey(int accel, int budget, int batch, boolean cold) {
        return "bench_ms_" + accel + "_" + budget + "_" + batch + (cold ? "_cold" : "");
    }

    /** The NPU has no checked compilation for this budget yet: its first run compiles one (minutes). */
    private boolean qnnCold(ModelConfig cfg, HfRepo.Plan plan, int budget) {
        if (plan.accelVision == null || cfg == null) return true;
        File g = qnnGraph(new File(modelDir, plan.accelVision));
        int pool = Math.max(1, cfg.image.poolingKernelSize);
        return !qnnBuilt(g, budget * pool * pool);
    }

    /** "0.36 с, совпадение 0.994" for a stage's row. */
    private static String shortResult(Measure m, boolean withCos) {
        if (m.error != null) return "не работает";
        String r = String.format(java.util.Locale.ROOT, "%.2f с", m.perPhotoMs / 1000.0);
        if (withCos) r += String.format(java.util.Locale.ROOT, ", совпадение %.3f", m.cos);
        return r;
    }

    /** The NPU with a graph so large that it compiles in lighter ways, each tried in a new process after a crash. */
    private static boolean bigNpu(int accel, int budget, int pool) {
        return isQnn(accel) && budget * pool * pool > NpuService.BIG_PATCHES;
    }

    /**
     * Embeds synthetic photos (after a warm-up) and returns the best of two timed runs. A large graph on the NPU
     * whose compilation crashed the NPU process is measured again (a new process compiles it the next way).
     */
    private Measure measure(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int accel, int nThreads, int batch,
                            int budget, float[] reference) {
        int pool = cfg != null ? Math.max(1, cfg.image.poolingKernelSize) : 1;
        boolean cold = isQnn(accel) && plan != null && qnnCold(cfg, plan, budget);
        long t0 = now();
        StringBuilder crashes = new StringBuilder();
        for (int attempt = 0; ; attempt++) {
            Measure r = measureOnce(cfg, tok, plan, accel, nThreads, batch, budget, reference, cold);
            boolean crashed = r.error != null && r.error.startsWith("NPU-процесс упал");
            if (!crashed || !bigNpu(accel, budget, pool) || attempt >= MAX_NPU_RESTARTS) {
                if (crashes.length() > 0) r.crashes = "\n  до этого NPU-процесс падал при сборке:" + crashes;
                // how long this kind takes on this phone: the next check's progress expects it
                if (r.error == null) prefs.edit().putLong(expectKey(accel, budget, batch, cold), now() - t0).apply();
                return r;
            }
            crashes.append("\n    ").append(attempt + 1).append(") ").append(r.error.replace("\n", "\n       "));
            Journal.add(ctx, "app", "NPU-процесс упал — запускаю новый (перезапуск " + (attempt + 1) + " из " + MAX_NPU_RESTARTS + "): " + r.error);
            status = "NPU-процесс упал при сборке — новый процесс соберёт другим способом (попытка " + (attempt + 2) + ")…";
            benchDoing("NPU-процесс упал при сборке — перезапуск " + (attempt + 1) + " из " + MAX_NPU_RESTARTS + ", следующий способ");
        }
    }

    private Measure measureOnce(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int accel, int nThreads, int batch,
                                int budget, float[] reference, boolean cold) {
        Measure r = new Measure();
        Embedder m = null;
        try {
            if (isLiteRt(accel) && liteRtKnownFixed()) {
                liteRtBudgetFixed = true;
                r.error = "сборка не принимает " + maxBudget() + " токенов — работает только со своей детализацией (известно с прошлой "
                        + "проверки, не загружаю)";
                Journal.add(ctx, "app", "  LiteRT-LM не загружаю: эта сборка уже не приняла " + maxBudget() + " токенов");
                return r;
            }
            benchDoing(isLiteRt(accel) ? "загружаю модель LiteRT-LM" : isQnn(accel) ? "запускаю NPU-процесс" : "загружаю модель");
            m = createModel(cfg, tok, plan, accel, nThreads, batch);
            if (m instanceof LiteRtEmbedder && ((LiteRtEmbedder) m).budgetFixed()) {
                // its numbers would be for the bundle's own (smaller) detail, not for the one asked
                r.error = "сборка не принимает " + maxBudget() + " токенов — работает только со своей детализацией";
                return r;
            }
            List<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source> imgs =
                    new ArrayList<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source>();
            for (int k = 0; k < batch; k++) imgs.add(new PatternSource(640, 480, 2 + k));
            benchDoing(cold ? "первый запуск: сборка модели под NPU (минуты)" : isGpu(accel) ? "прогрев: шейдеры видеокарты" : "прогрев");
            m.embedImages(imgs, budget); // warm-up: allocations, GPU shader compilation
            if (m instanceof Embedder.Staged && pipelines(accel)) {
                measurePipeline((Embedder.Staged) m, imgs, batch, budget, r);
            } else for (int run = 0; run < 2; run++) {
                benchDoing("замер " + (run + 1) + " из 2");
                long t0 = System.currentTimeMillis();
                float[][] e = m.embedImages(imgs, budget);
                double per = (System.currentTimeMillis() - t0) / (double) batch;
                long[] tmj = m.lastTimingsMs();
                Journal.add(ctx, "app", String.format(java.util.Locale.ROOT, "  замер %d: %.0f мс на фото (картинка %d мс, текст %d мс на пачку из %d)",
                        run + 1, per, tmj[0], tmj[1], batch));
                if (per < r.perPhotoMs) {
                    r.perPhotoMs = per;
                    long[] tm = m.lastTimingsMs();
                    r.visionMs = tm[0] / (double) batch;
                    r.textMs = tm[1] / (double) batch;
                    r.emb = e[0];
                }
            }
            if (reference != null) {
                float c = 0;
                for (int j = 0; j < r.emb.length; j++) c += r.emb[j] * reference[j];
                r.cos = c;
            }
        } catch (Throwable e) {
            r.error = e.getMessage() != null ? e.getMessage() : e.toString();
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            String trace = sw.toString();
            Journal.add(ctx, "app", "  ошибка: " + (trace.length() > 3000 ? trace.substring(0, 3000) + "…" : trace));
        } finally {
            if (m != null) m.close();
        }
        return r;
    }

    /**
     * As indexing runs it: the text stage of a batch on {@link #textStage} while the vision stage takes the next
     * batch. The time per photo is the interval between two batches done once the pipeline is full (two of them:
     * 4 vision runs, the first fills the pipeline, the last one's text is not waited for).
     */
    private void measurePipeline(final Embedder.Staged st, List<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source> imgs,
                                 int batch, int budget, Measure r) throws Exception {
        benchDoing("конвейер: заполняю");
        Object cur = st.startImages(imgs, budget);
        long last = 0;
        for (int k = 0; k < 3; k++) {
            if (k > 0) benchDoing("замер " + k + " из 2 (конвейер)");
            final Object started = cur;
            Future<float[][]> text = textStage.submit(new java.util.concurrent.Callable<float[][]>() {
                @Override
                public float[][] call() throws Exception {
                    return st.finishImages(started);
                }
            });
            Object next = st.startImages(imgs, budget);
            float[][] e;
            try {
                e = text.get();
            } catch (ExecutionException ee) {
                throw ee.getCause() instanceof Exception ? (Exception) ee.getCause() : ee;
            }
            long done = System.currentTimeMillis();
            if (k > 0) {
                double per = (done - last) / (double) batch;
                long[] tm = st.timingsMs(started);
                Journal.add(ctx, "app", String.format(java.util.Locale.ROOT, "  замер %d: %.0f мс на фото в конвейере (картинка %d мс, "
                        + "текст %d мс на пачку из %d — одновременно с картинкой следующей)", k, per, tm[0], tm[1], batch));
                if (per < r.perPhotoMs) {
                    r.perPhotoMs = per;
                    r.visionMs = tm[0] / (double) batch;
                    r.textMs = tm[1] / (double) batch;
                    r.emb = e[0];
                    r.pipelined = true;
                }
            }
            last = done;
            cur = next;
        }
    }

    /** One variant at another detail level, under its crash probe. */
    private Measure measureAt(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int[] c, int budget) {
        return measureAt(cfg, tok, plan, c, budget, null);
    }

    private Measure measureAt(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int[] c, int budget, float[] reference) {
        status = "Подбираю ускорение: " + ACCEL_NAMES[c[0]] + ", детализация " + budget;
        notifyChanged();
        mark("подбор ускорения: " + ACCEL_NAMES[c[0]] + ", детализация " + budget);
        probe(c[0], true);
        Measure m = measure(cfg, tok, plan, c[0], c[1], c[2], budget, reference);
        probe(c[0], false);
        mark("");
        return m;
    }

    private static void appendMeasure(StringBuilder rep, int[] c, Measure o) {
        rep.append("\n• ").append(ACCEL_NAMES[c[0]]).append(": ");
        if (o.error != null) rep.append("не работает — ").append(o.error);
        else if (isLiteRt(c[0])) rep.append(String.format(java.util.Locale.ROOT, "%.2f с", o.perPhotoMs / 1000.0));
        else rep.append(o.time());
        rep.append(o.crashes);
    }

    /** {screenshots, all} of the last {@link #screenshotShare()}. */
    private final int[] shareCounts = new int[2];

    /** Share of screenshots and scans among the photos to index (auto detail gives them more tokens), or -1. */
    double screenshotShare() {
        if (!AutoIndex.hasMediaAccess(ctx)) return -1;
        try {
            List<Media.Entry> all = Media.recentImages(ctx.getContentResolver(), photoLimit());
            if (all.isEmpty()) return -1;
            int n = 0;
            for (Media.Entry e : all) if (e.textHeavy) n++;
            shareCounts[0] = n;
            shareCounts[1] = all.size();
            return n / (double) all.size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Marks a risky accelerator run (NPU driver, LiteRT-LM native code): a crash inside disables that variant. */
    private void probe(int accel, boolean on) {
        String key = isNpu(accel) ? "npu_probe" : isLiteRt(accel) ? "litert_probe" : null;
        if (key == null) return;
        if (on) prefs.edit().putString(key, String.valueOf(accel)).commit();
        else prefs.edit().remove(key).commit();
    }

    /** Battery, power saving and heat, as the speed report shows them. */
    static String conditions(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        StringBuilder sb = new StringBuilder();
        if (b != null) {
            int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            boolean charging = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (level >= 0 && scale > 0) sb.append("заряд ").append(level * 100 / scale).append('%').append(charging ? " (заряжается)" : "");
        }
        android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            sb.append(sb.length() > 0 ? ", " : "").append("экономия батареи ").append(pm.isPowerSaveMode() ? "включена" : "выключена");
            int t = thermalStatus(pm);
            if (t >= 0) sb.append(", нагрев: ").append(new String[]{"нет", "лёгкий", "умеренный", "сильный", "критический",
                    "критический", "критический"}[Math.min(6, t)]);
        }
        return sb.toString();
    }

    /** Why the phone is slowed down right now (low battery, power saving, heat), or null. */
    static String slowdown(Context c) {
        android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isPowerSaveMode()) return "включена экономия батареи";
        if (pm != null && thermalStatus(pm) >= 2) return "телефон нагрелся";
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0 && level * 100 / scale < 20) return "заряд " + level * 100 / scale + "%";
        }
        return null;
    }

    /** PowerManager.getCurrentThermalStatus() (API 29), -1 when unknown. */
    static int thermalStatus(android.os.PowerManager pm) {
        if (android.os.Build.VERSION.SDK_INT < 29) return -1;
        try {
            return (Integer) android.os.PowerManager.class.getMethod("getCurrentThermalStatus").invoke(pm);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Phone and chip, for the speed report (Build.SOC_MODEL is API 31+). */
    static String device() {
        String soc = null;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                soc = (String) android.os.Build.class.getField("SOC_MODEL").get(null);
            } catch (Exception ignored) {
                // not available
            }
        }
        return android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + (soc != null && !soc.isEmpty() && !"unknown".equalsIgnoreCase(soc) ? ", чип " + soc : "")
                + ", Android " + android.os.Build.VERSION.SDK_INT;
    }

    /** One profiled run of the vision encoder with variant {@code c}: which ops its accelerator left to the CPU. */
    private String profileVision(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int[] c, int budget) {
        File dir = ctx.getCacheDir();
        EmbeddingGemma2 m = null;
        try {
            EmbeddingGemma2.profileVision = new File(dir, "vision-profile").getPath();
            try {
                m = (EmbeddingGemma2) createModel(cfg, tok, plan, c[0], c[1], c[2]);
            } finally {
                EmbeddingGemma2.profileVision = null;
            }
            List<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source> imgs =
                    new ArrayList<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source>();
            for (int k = 0; k < c[2]; k++) imgs.add(new PatternSource(640, 480, 2 + k));
            m.embedImages(imgs, budget); // warm-up; the summary covers the last run only
            m.embedImages(imgs, budget);
            return OrtProfile.parse(m.endVisionProfiling()).summary(6);
        } catch (Throwable e) {
            return "Профиль не снят: " + (e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (m != null) m.close();
            File[] left = dir.listFiles();
            if (left != null) for (File f : left) if (f.getName().startsWith("vision-profile")) f.delete();
        }
    }

    /**
     * Measures photo embedding speed on this phone: every accelerator, then thread counts and
     * batch sizes for the best one. Variants whose result drifts from the plain CPU one are
     * rejected (so the existing index stays comparable); the fastest is saved.
     */
    public void benchmark(final Callback<String> cb) {
        if (photoModel() != FastModel.GEMMA) {
            checkFast(cb);
            return;
        }
        if (indexing) {
            post(cb, null, new IllegalStateException("дождитесь конца индексации"));
            return;
        }
        state = State.LOADING;
        status = "Подбираю ускорение…";
        notifyChanged();
        ml.submit(new Runnable() {
            @Override
            public void run() {
                StringBuilder rep = new StringBuilder();
                try {
                    closeModels();
                    HfRepo.Plan plan = HfRepo.loadManifest(manifest);
                    if (plan == null || plan.visionModel == null) throw new IllegalStateException("нет визуального энкодера");
                    ModelConfig cfg = EmbeddingGemma2.loadConfig(modelDir);
                    HfTokenizer tok = EmbeddingGemma2.loadTokenizer(modelDir);
                    int budget = photoBudget();
                    int auto = autoThreads(), cores = Runtime.getRuntime().availableProcessors();
                    rep.append(device()).append('\n');
                    String slowAtStart = slowdown(ctx);
                    rep.append("Условия: ").append(conditions(ctx)).append('\n');
                    if (slowAtStart != null) {
                        rep.append("⚠ Телефон сейчас замедлен (").append(slowAtStart).append("): цифры будут хуже обычных, "
                                + "сравнивать стоит только варианты между собой\n");
                    }
                    rep.append(autoDetail() ? "Детализация авто: подбор на " + budget + " токенах (обычные фото)"
                            : "Детализация " + budget + " токенов").append(", ядер ").append(cores)
                            .append(", быстрых ").append(auto).append("\n(картинка + текст на одно фото)\n\n");

                    List<int[]> plan1 = new ArrayList<int[]>(); // {accel, threads, batch}
                    boolean fp32 = plan.accelVision != null && new File(modelDir, plan.accelVision).exists();
                    boolean fp16 = plan.fp16Vision != null && new File(modelDir, plan.fp16Vision).exists();
                    boolean lrt = liteRtInstalled();
                    // With an index of LiteRT-LM vectors only its variants are comparable; its CPU run is the reference.
                    final boolean space = liteRtSpace();
                    // the exact CPU run first (the reference), then the likely winners while the phone is still cool,
                    // the NPU last (slow on most phones, and its driver may crash)
                    int[] order = space ? new int[]{ACCEL_LITERT_CPU, ACCEL_LITERT_GPU}
                            : new int[]{ACCEL_CPU, ACCEL_NPU_QNN, ACCEL_GPU_FP16_ATTN, ACCEL_GPU_INT8, ACCEL_GPU_FP16, ACCEL_GPU,
                            ACCEL_CPU_INT8, ACCEL_LITERT_GPU, ACCEL_LITERT_CPU, ACCEL_NPU, ACCEL_NPU_FP32};
                    for (int a : order) {
                        if (isGpu(a) && gpuBroken()) continue;
                        if (a == ACCEL_GPU_FP16 && !fp16) continue;
                        if (isQnn(a) && !(qnnInstalled() && fp32)) continue;
                        if (isQnn(a) && prefs.getBoolean("qnn_broken", false)) {
                            rep.append("• ").append(ACCEL_NAMES[a]).append(": пропущено — в прошлый раз NPU-процесс упал\n");
                            continue;
                        }
                        // with QNN the NPU is reached directly; NNAPI (slow here, deprecated) would only heat the phone
                        if (isNpu(a) && fp32 && qnnInstalled()) continue;
                        if (isNpu(a) && (!fp32 || npuBroken(a))) {
                            if (fp32) rep.append("• ").append(ACCEL_NAMES[a]).append(": пропущено — в прошлый раз уронило драйвер\n");
                            continue;
                        }
                        if (isLiteRt(a) && (!lrt || liteRtBroken(a))) {
                            if (lrt) rep.append("• ").append(ACCEL_NAMES[a]).append(": пропущено — в прошлый раз приложение упало\n");
                            continue;
                        }
                        plan1.add(new int[]{a, auto, 1});
                    }
                    // the stages on screen, weighted by their expected time (the last one of the same kind on this phone)
                    final StageProgress pr = new StageProgress("Подбор ускорения", now());
                    List<StageProgress.Stage> first = new ArrayList<StageProgress.Stage>();
                    for (int[] c : plan1) {
                        first.add(pr.add(ACCEL_NAMES[c[0]], expectMs(c[0], budget, 1, isQnn(c[0]) && qnnCold(cfg, plan, budget))));
                    }
                    StageProgress.Stage stThreads = pr.add("Число потоков для лучшего", 45000);
                    StageProgress.Stage stBatch = pr.add("Пачки по 2 и 4 фото", 30000);
                    StageProgress.Stage stDuel = pr.add("Перемер двух лучших вперемешку", 48000);
                    int otherBudget = budget == PHOTO_BUDGETS[0] ? PHOTO_BUDGETS[PHOTO_BUDGETS.length - 1] : PHOTO_BUDGETS[0];
                    StageProgress.Stage stCool = autoDetail() && otherBudget > budget ? pr.add("Пауза: телефон остывает", 20000) : null;
                    StageProgress.Stage stOther = pr.add("Замер на " + otherBudget + " токенах", 40000);
                    StageProgress.Stage stProfile = pr.add("Профиль: где работает ускоритель", 15000);
                    boolean qnnPlanned = false;
                    for (int[] c : plan1) qnnPlanned |= isQnn(c[0]);
                    StageProgress.Stage stNpu = qnnPlanned ? pr.add("NPU изнутри: что он взял и профиль QNN", 25000) : null;
                    Journal.clear(ctx);
                    StringBuilder planned = new StringBuilder();
                    for (int[] c : plan1) planned.append(planned.length() > 0 ? ", " : "").append(ACCEL_NAMES[c[0]]);
                    Journal.add(ctx, "app", "подбор: SemSearch " + BuildInfo.version(ctx) + ", " + device() + "; " + conditions(ctx)
                            + "; детализация " + budget + " токенов, ядер " + cores + ", потоков " + auto + "; варианты: " + planned);
                    pr.setListener(new StageProgress.Listener() {
                        @Override
                        public void event(StageProgress.Stage s, String what) {
                            Journal.add(ctx, "app", what);
                        }
                    });
                    bench = pr;
                    startTicker();
                    notifyChanged();
                    float[] reference = null;
                    float[] bestEmb = null, lrtBestEmb = null;
                    int[] best = null, offer = null, lrtBest = null;
                    double bestMs = Double.MAX_VALUE, offerMs = Double.MAX_VALUE, lrtBestMs = Double.MAX_VALUE;
                    float offerCos = 0, lrtBestCos = 0;
                    List<int[]> okCands = new ArrayList<int[]>();
                    boolean qnnMeasured = false, qnnWrong = false;
                    List<Double> okMs = new ArrayList<Double>();
                    int step = 0;
                    for (int phase = 0; phase < 3; phase++) {
                        List<int[]> cands = new ArrayList<int[]>();
                        if (phase == 0) {
                            cands.addAll(plan1);
                        } else if (phase == 1 && best != null && best[0] != ACCEL_LITERT_GPU) { // threads: CPU work only
                            java.util.LinkedHashSet<Integer> ts = new java.util.LinkedHashSet<Integer>();
                            ts.add(Math.min(cores, auto + 2));
                            ts.add(Math.max(2, cores / 2));
                            ts.add(cores);
                            ts.remove(auto);
                            for (int t : ts) cands.add(new int[]{best[0], t, 1});
                        } else if (phase == 2 && best != null) {
                            for (int b : new int[]{2, 4}) cands.add(new int[]{best[0], best[1], b});
                        }
                        StageProgress.Stage ps = phase == 1 ? stThreads : phase == 2 ? stBatch : null;
                        if (ps != null) {
                            if (cands.isEmpty()) {
                                pr.skip(ps, best == null ? "нет работающего варианта" : "не нужно для этого варианта");
                            } else {
                                pr.start(ps, now());
                                pr.parts(ps, cands.size());
                            }
                        }
                        int idx = -1;
                        for (int[] c : cands) {
                            idx++;
                            step++;
                            String name = ACCEL_NAMES[c[0]] + ", потоков " + c[1] + (c[2] > 1 ? ", пачка " + c[2] : "");
                            status = "Подбираю ускорение (" + step + "): " + name;
                            notifyChanged();
                            mark("подбор ускорения: " + name);
                            if (isQnn(c[0]) && phase == 0) {
                                status = "NPU Snapdragon: компилирую модель под NPU — в первый раз до нескольких минут, если QNN не справится — до 15 минут поиска";
                                notifyChanged();
                            }
                            StageProgress.Stage st = phase == 0 ? first.get(idx) : ps;
                            if (phase == 0) pr.start(st, now());
                            else pr.part(st, idx, name.replace(ACCEL_NAMES[c[0]] + ", ", ""), now());
                            benchAt(st, c[0]);
                            probe(c[0], true);
                            Measure m = measure(cfg, tok, plan, c[0], c[1], c[2], budget, reference);
                            probe(c[0], false);
                            mark("");
                            if (isQnn(c[0]) && m.error != null && m.error.startsWith("NPU-процесс упал")) {
                                prefs.edit().putBoolean("qnn_broken", true).apply();
                            }
                            if (isQnn(c[0]) && m.error == null && phase == 0) qnnMeasured = true;
                            if (phase == 0) {
                                if (m.error != null) pr.failed(st, "не работает", now());
                                else pr.done(st, shortResult(m, reference != null && reference != m.emb)
                                        + (reference != null && reference != m.emb && m.cos < 0.98f ? " — расходится" : ""), now());
                            }
                            if (m.error != null) {
                                rep.append("• ").append(name).append(": не работает — ").append(m.error).append(m.crashes).append('\n');
                                continue;
                            }
                            if (reference == null) reference = m.emb;
                            boolean ok = m.cos >= 0.98f;
                            if (isQnn(c[0]) && phase == 0 && !ok) qnnWrong = true;
                            // LiteRT-LM is Google's own quantisation of the model: close, but maybe not close enough
                            // to share an index with the ONNX vectors — then it is offered with a re-index instead
                            boolean ownSpace = !space && isLiteRt(c[0]);
                            rep.append(String.format(java.util.Locale.ROOT, "• %s: %s%s%s\n", name, isLiteRt(c[0])
                                            ? String.format(java.util.Locale.ROOT, "%.2f с", m.perPhotoMs / 1000.0)
                                            : m.time(),
                                    reference == m.emb ? "" : String.format(java.util.Locale.ROOT, ", совпадение %.3f", m.cos),
                                    ok ? "" : ownSpace ? " — векторы отличаются от ONNX-версии" : " — отклонено, результат расходится"));
                            if (!m.crashes.isEmpty()) rep.setLength(rep.length() - 1);
                            rep.append(m.crashes).append(m.crashes.isEmpty() ? "" : "\n");
                            if (ok) {
                                okCands.add(c);
                                okMs.add(m.perPhotoMs);
                            }
                            if (ok && m.perPhotoMs < bestMs) {
                                bestMs = m.perPhotoMs;
                                best = c;
                                bestEmb = m.emb;
                            }
                            if (ownSpace) prefs.edit().putFloat("litert_cos_" + c[0], m.cos).apply();
                            if (ownSpace && m.perPhotoMs < lrtBestMs) {
                                lrtBestMs = m.perPhotoMs;
                                lrtBest = c;
                                lrtBestCos = m.cos;
                                lrtBestEmb = m.emb;
                            }
                            if (ownSpace && !ok && m.perPhotoMs < offerMs) {
                                offerMs = m.perPhotoMs;
                                offer = c;
                                offerCos = m.cos;
                            }
                        }
                        if (ps != null && !cands.isEmpty() && best != null) {
                            pr.done(ps, phase == 1 ? "лучше всего потоков " + best[1]
                                    : best[2] > 1 ? "лучше пачкой по " + best[2] : "лучше по одному фото", now());
                        }
                    }
                    if (best == null) {
                        pr.finish(now());
                        throw new IllegalStateException(space ? "LiteRT-LM не подходит для этой детализации — «Вернуться на ONNX "
                                + "Runtime» в настройках" : "ни один вариант не сработал");
                    }
                    // Measured one after another, later variants run on a warmer phone. The two best (when close) are
                    // measured again alternately, and the winner's repeat shows whether the phone slowed down meanwhile.
                    int[] runner = null;
                    double runnerMs = Double.MAX_VALUE;
                    for (int i = 0; i < okCands.size(); i++) {
                        if (okCands.get(i)[0] != best[0] && okMs.get(i) < runnerMs) {
                            runner = okCands.get(i);
                            runnerMs = okMs.get(i);
                        }
                    }
                    int[] phaseBest = best;
                    double firstBestMs = bestMs, againBest = Double.MAX_VALUE, againRunner = Double.MAX_VALUE;
                    Measure lastBest = null, lastRunner = null;
                    boolean duel = runner != null && runnerMs < bestMs * 1.25;
                    pr.start(stDuel, now());
                    pr.parts(stDuel, duel ? 4 : 1);
                    int duelPart = 0;
                    for (int round = 0; round < (duel ? 2 : 1); round++) {
                        pr.part(stDuel, duelPart++, ACCEL_NAMES[best[0]], now());
                        benchAt(stDuel, best[0]);
                        Measure a = measureAt(cfg, tok, plan, best, budget, reference);
                        if (a.error == null && a.perPhotoMs < againBest) {
                            againBest = a.perPhotoMs;
                            lastBest = a;
                        }
                        if (duel) {
                            pr.part(stDuel, duelPart++, ACCEL_NAMES[runner[0]], now());
                            benchAt(stDuel, runner[0]);
                            Measure b = measureAt(cfg, tok, plan, runner, budget, reference);
                            if (b.error == null && b.perPhotoMs < againRunner) {
                                againRunner = b.perPhotoMs;
                                lastRunner = b;
                            }
                        }
                    }
                    pr.done(stDuel, lastBest == null ? "не вышло" : duel && lastRunner != null
                            ? String.format(java.util.Locale.ROOT, "%.2f с против %.2f с", againBest / 1000.0, againRunner / 1000.0)
                            : String.format(java.util.Locale.ROOT, "%.2f с", againBest / 1000.0), now());
                    if (duel && lastBest != null && lastRunner != null) {
                        rep.append(String.format(java.util.Locale.ROOT, "\nПеремер двух лучших вперемешку: %s %.2f с, %s %.2f с",
                                ACCEL_NAMES[best[0]], againBest / 1000.0, ACCEL_NAMES[runner[0]], againRunner / 1000.0));
                        if (againRunner < againBest) {
                            best = runner;
                            bestEmb = lastRunner.emb;
                        }
                        bestMs = Math.min(againBest, againRunner);
                    }
                    if (lastBest != null && againBest > firstBestMs * 1.25) {
                        rep.append(String.format(java.util.Locale.ROOT, "\n⚠ За время замера телефон замедлился: %s сначала %.2f с, "
                                        + "в конце %.2f с (нагрев или экономия батареи) — поздние варианты в списке выглядят хуже, чем есть",
                                ACCEL_NAMES[phaseBest[0]], firstBestMs / 1000.0, againBest / 1000.0));
                    }
                    // The other end of the detail scale. With auto detail screenshots get it: the winner and the
                    // fastest LiteRT-LM variant are measured there too (after a pause — the phone is warm by now)
                    // and the choice weighs both by the gallery's real share of screenshots.
                    int other = budget == PHOTO_BUDGETS[0] ? PHOTO_BUDGETS[PHOTO_BUDGETS.length - 1] : PHOTO_BUDGETS[0];
                    if (isNpu(best[0]) && other > maxBudget()) other = 0; // the NPU graph is compiled for maxBudget()
                    boolean weigh = autoDetail() && other > budget;
                    // the fastest LiteRT-LM variant (when not the winner) is measured there as well: its detail is verified
                    int[] rival = lrtBest != null && !isLiteRt(best[0]) ? lrtBest : null;
                    // the NPU, when it worked but did not win here, is measured there too: at the other detail it may
                    // be the fastest (and its compilation for that size is checked on the way)
                    int[] npuRival = null;
                    double npuMs = Double.MAX_VALUE;
                    for (int i = 0; i < okCands.size(); i++) {
                        if (!isQnn(best[0]) && isQnn(okCands.get(i)[0]) && okMs.get(i) < npuMs) {
                            npuRival = okCands.get(i);
                            npuMs = okMs.get(i);
                        }
                    }
                    // one that did not work at this detail is tried at the other (it compiles for each size anew)
                    if (npuRival == null && !isQnn(best[0]) && !prefs.getBoolean("qnn_broken", false)) {
                        for (int[] c : plan1) if (isQnn(c[0])) npuRival = c;
                    }
                    Measure bestOther = null, rivalOther = null, npuOther = null;
                    if (stCool != null && !weigh) pr.skip(stCool, "");
                    if (other > 0) {
                        if (weigh) {
                            status = "Даю телефону остыть перед замером скриншотов…";
                            if (stCool != null) pr.start(stCool, now());
                            notifyChanged();
                            Thread.sleep(20000);
                            if (stCool != null) pr.done(stCool, "", now());
                        }
                        pr.expect(stOther, expectMs(best[0], other, best[2], isQnn(best[0]) && qnnCold(cfg, plan, other))
                                + (rival != null ? expectMs(rival[0], other, 1, false) : 0)
                                + (npuRival != null ? expectMs(npuRival[0], other, 1, qnnCold(cfg, plan, other)) : 0));
                        pr.start(stOther, now());
                        pr.parts(stOther, 1 + (rival != null ? 1 : 0) + (npuRival != null ? 1 : 0));
                        pr.part(stOther, 0, ACCEL_NAMES[best[0]], now());
                        benchAt(stOther, best[0]);
                        bestOther = measureAt(cfg, tok, plan, best, other);
                        if (npuRival != null) {
                            pr.part(stOther, 1, ACCEL_NAMES[npuRival[0]], now());
                            benchAt(stOther, npuRival[0]);
                            npuOther = measureAt(cfg, tok, plan, npuRival, other, bestOther.error == null ? bestOther.emb : null);
                        }
                        // LiteRT-LM on screenshots, compared with the ONNX model at the same detail
                        if (rival != null) {
                            pr.part(stOther, npuRival != null ? 2 : 1, ACCEL_NAMES[rival[0]], now());
                            benchAt(stOther, rival[0]);
                            rivalOther = measureAt(cfg, tok, plan, rival, other,
                                    bestOther.error == null ? bestOther.emb : null);
                        }
                        String r = ACCEL_NAMES[best[0]].replaceFirst(" \\(.*", "") + ": " + shortResult(bestOther, false)
                                + (npuOther != null ? "; NPU: " + shortResult(npuOther, false) : "")
                                + (rivalOther != null ? "; " + ACCEL_NAMES[rival[0]] + ": " + shortResult(rivalOther, false) : "");
                        if (bestOther.error != null) pr.failed(stOther, r, now());
                        else pr.done(stOther, r, now());
                    } else {
                        pr.skip(stOther, "");
                    }
                    double share = weigh ? screenshotShare() : 0;
                    boolean shareKnown = share >= 0;
                    if (!shareKnown) share = 0.25;
                    double bestScore = bestMs, rivalScore = Double.MAX_VALUE;
                    if (bestOther != null && bestOther.error == null && weigh) {
                        bestScore = (1 - share) * bestMs + share * bestOther.perPhotoMs;
                        if (rivalOther != null && rivalOther.error == null) {
                            rivalScore = (1 - share) * lrtBestMs + share * rivalOther.perPhotoMs;
                        }
                    }
                    int[] onnxWinner = best;
                    // the variant bestOther was measured with (the winner before any switch below)
                    final int[] measuredOther = best;
                    boolean switched = false;
                    // the NPU weighed the same way: with the screenshots on average faster (and its vectors the same)
                    double npuScore = Double.MAX_VALUE;
                    boolean npuSame = npuOther != null && npuOther.error == null && (bestOther == null || bestOther.error != null || npuOther.cos >= 0.98f);
                    if (npuSame && weigh && bestOther != null && bestOther.error == null) {
                        npuScore = (1 - share) * npuMs + share * npuOther.perPhotoMs;
                        if (npuScore < bestScore) {
                            rep.append(String.format(java.util.Locale.ROOT, "\n(%s с учётом скриншотов в среднем %.2f с на снимок против %.2f с у %s)",
                                    ACCEL_NAMES[npuRival[0]], npuScore / 1000.0, bestScore / 1000.0, ACCEL_NAMES[best[0]]));
                            best = npuRival;
                            bestMs = npuMs;
                            bestScore = npuScore;
                            onnxWinner = best;
                        }
                    }
                    // weighed: both measured on screenshots too — then only the average per snapshot decides
                    boolean weighed = rivalScore < Double.MAX_VALUE;
                    if (weighed) {
                        offer = null;
                        offerMs = Double.MAX_VALUE;
                    }
                    if (weighed && rivalScore < bestScore / 1.15) {
                        if (lrtBestCos >= 0.98f) {
                            // same vectors and faster on this gallery: LiteRT-LM becomes the accelerator
                            best = rival;
                            bestMs = lrtBestMs;
                            switched = true;
                        } else {
                            offer = rival;
                            offerMs = rivalScore;
                            offerCos = lrtBestCos;
                        }
                    }
                    prefs.edit().putInt("accel", best[0]).putInt("threads", best[1] == auto ? 0 : best[1])
                            .putInt("batch", best[2]).putBoolean("accel_chosen", true).apply();
                    rep.append(String.format(java.util.Locale.ROOT, "\nВыбрано: %s, потоков %d%s — %.2f с на фото (%d токенов)",
                            ACCEL_NAMES[best[0]], best[1], best[2] > 1 ? ", пачка " + best[2] : "", bestMs / 1000.0, budget));
                    if (switched) {
                        rep.append(String.format(java.util.Locale.ROOT, "\n(вместо %s: с учётом скриншотов в среднем %.2f с на "
                                + "снимок против %.2f с)", ACCEL_NAMES[onnxWinner[0]], rivalScore / 1000.0, bestScore / 1000.0));
                    }
                    if (bestOther != null) {
                        rep.append(weigh ? "\n\nСкриншоты и документы (" + other + " токенов, после паузы на остывание):"
                                : other < budget ? "\n\nС детализацией «Авто» обычные фото (" + other + " токенов):"
                                : "\n\nПри " + other + " токенах:");
                        appendMeasure(rep, measuredOther, bestOther);
                        if (npuOther != null) {
                            appendMeasure(rep, npuRival, npuOther);
                            if (npuOther.error == null && bestOther.error == null) {
                                rep.append(String.format(java.util.Locale.ROOT, ", совпадение %.3f", npuOther.cos));
                            }
                        }
                        if (rivalOther != null) {
                            appendMeasure(rep, rival, rivalOther);
                            if (rivalOther.error == null && bestOther.error == null) {
                                rep.append(String.format(java.util.Locale.ROOT, ", совпадение с ONNX на %d токенах %.3f", other, rivalOther.cos));
                            }
                        }
                        // does LiteRT-LM really change detail? (a bundle without the signature falls back to its own)
                        float[] lrtAtBudget = isLiteRt(measuredOther[0]) ? bestEmb : rivalOther != null ? lrtBestEmb : null;
                        Measure lrtAtOther = isLiteRt(measuredOther[0]) ? bestOther : rivalOther;
                        if (lrtAtBudget != null && lrtAtOther != null && lrtAtOther.error == null) {
                            float same = 0;
                            for (int j = 0; j < lrtAtBudget.length; j++) same += lrtAtBudget[j] * lrtAtOther.emb[j];
                            rep.append(same >= 0.9995f
                                    ? String.format(java.util.Locale.ROOT, "\n⚠ LiteRT-LM выдаёт одно и то же при %d и %d токенах — детализация "
                                    + "в этой сборке не меняется", budget, other)
                                    : String.format(java.util.Locale.ROOT, "\nLiteRT-LM: %d и %d токенов дают разные векторы (%.3f) — "
                                    + "детализация применяется", budget, other, same));
                        }
                        if (liteRtBudgetFixed) {
                            rep.append("\n⚠ Сборка LiteRT-LM не принимает " + maxBudget() + " токенов: работает со своей детализацией");
                        }
                        if (weigh && bestOther.error == null) {
                            rep.append(shareKnown ? String.format(java.util.Locale.ROOT, "\nВ галерее скриншотов и документов %d%% "
                                    + "(%d из %d)", Math.round(share * 100), shareCounts[0], shareCounts[1])
                                    : "\nДоля скриншотов неизвестна (нет доступа к галерее), считаю 25%");
                            rep.append(String.format(java.util.Locale.ROOT, ". В среднем на снимок: %s %.2f с", ACCEL_NAMES[onnxWinner[0]],
                                    bestScore / 1000.0));
                            if (rivalScore < Double.MAX_VALUE) {
                                rep.append(String.format(java.util.Locale.ROOT, ", %s %.2f с", ACCEL_NAMES[rival[0]], rivalScore / 1000.0));
                            }
                        }
                    }
                    double vs = weighed ? bestScore : bestMs;
                    if (offer != null && offerMs < vs / 1.15) {
                        prefs.edit().putInt("litert_offer", offer[0]).putInt("litert_offer_ms", (int) offerMs).apply();
                        rep.append(String.format(java.util.Locale.ROOT, "\n\n%s быстрее в %.1f раза (%.2f с на снимок против %.2f с), "
                                        + "но его векторы немного отличаются от ONNX-версии (совпадение %.3f), и смешивать их в одном "
                                        + "индексе нельзя. Перейти можно с переиндексацией всей галереи: «Перейти на LiteRT-LM» в настройках.",
                                ACCEL_NAMES[offer[0]], vs / offerMs, offerMs / 1000.0, vs / 1000.0, offerCos));
                    } else {
                        prefs.edit().remove("litert_offer").apply();
                    }
                    // where the accelerator's graph actually runs
                    if (isGpu(best[0]) || isNpu(best[0])) {
                        status = "Смотрю, какие операции остаются на процессоре…";
                        pr.start(stProfile, now());
                        benchAt(stProfile, best[0]);
                        notifyChanged();
                        mark("подбор ускорения: профиль, " + ACCEL_NAMES[best[0]]);
                        if (isNpu(best[0])) prefs.edit().putString("npu_probe", String.valueOf(best[0])).commit();
                        rep.append("\n\n").append(profileVision(cfg, tok, plan, best, budget));
                        prefs.edit().remove("npu_probe").commit();
                        mark("");
                        pr.done(stProfile, "", now());
                    } else {
                        pr.skip(stProfile, isQnn(best[0]) ? "у NPU — следующим этапом" : "на процессоре не нужен");
                    }
                    if (qnnMeasured) {
                        status = "Смотрю, что NPU взял на себя…";
                        pr.start(stNpu, now());
                        benchAt(stNpu, ACCEL_NPU_QNN);
                        if (qnnWrong) {
                            pr.parts(stNpu, 2);
                            pr.expect(stNpu, 25000 + 180000);
                        }
                        notifyChanged();
                        rep.append("\n\nNPU Snapdragon (").append(budget).append(" токенов):\n").append(profileQnn(cfg, plan, budget));
                        if (qnnWrong) {
                            status = "Ищу, где NPU портит результат (сборка с проверками, до нескольких минут)…";
                            pr.part(stNpu, 1, "ищу, где NPU портит результат", now());
                            notifyChanged();
                            rep.append("\nГде NPU портит результат: ").append(scanQnn(cfg, plan, budget));
                        }
                        pr.done(stNpu, "", now());
                    } else if (stNpu != null) {
                        pr.skip(stNpu, "NPU не сработал");
                    }
                    try {
                        // how the vision encoder computes attention (the cost that grows quadratically with detail)
                        rep.append("\n\nУстройство визуального энкодера:\n")
                                .append(OnnxPatcher.graphSummary(new File(modelDir, plan.visionModel)));
                    } catch (Exception ex) {
                        rep.append("\n\nУстройство визуального энкодера не прочитано: ").append(ex.getMessage());
                    }
                    if (!space && (!fp16 || !lrt)) {
                        rep.append("\n\n").append(!fp16 && !lrt ? "fp16-версия и LiteRT-LM не проверены"
                                : !fp16 ? "fp16-версия не проверена" : "LiteRT-LM не проверен")
                                .append(": кнопка «Проверить LiteRT-LM и fp16» в настройках.");
                    }
                    String slowAtEnd = slowdown(ctx);
                    if (slowAtEnd != null && slowAtStart == null) {
                        rep.append("\n\n⚠ К концу замера телефон замедлился (").append(slowAtEnd).append(")");
                    }
                    if (liteRtLoadIssues != null && lrt) {
                        rep.append("\n\nLiteRT-LM: не загрузились необязательные библиотеки — ").append(liteRtLoadIssues);
                    }
                    if (!fp32 && FastModel.acceleratorLikely() && !space) {
                        rep.append(String.format(java.util.Locale.ROOT, "\n\nNPU не проверен: ему нужна полная версия визуального "
                                + "энкодера (≈%d МБ) — кнопка «Проверить NPU» в настройках.", gemmaFp32EstimateBytes() >> 20));
                    }
                    finishBench();
                    appendJournal(rep);
                    prefs.edit().putString("g_report", rep.toString()).apply();
                    post(cb, rep.toString(), null);
                } catch (Throwable e) {
                    rep.append("\nОшибка: ").append(e.getMessage() != null ? e.getMessage() : e.toString());
                    Journal.add(ctx, "app", "подбор прерван: " + e);
                    finishBench();
                    appendJournal(rep);
                    prefs.edit().putString("g_report", rep.toString()).apply();
                    post(cb, rep.toString(), null);
                }
                mark("");
                loadModel();
            }
        });
    }

    // ------------------------------------------------------------------ fast model: download, auto-check

    /** Downloads the fast model (and with {@code withFp32} the full-precision picture graph for NPU/GPU). */
    public void downloadFast(final boolean withFp32) {
        if (state == State.DOWNLOADING) return;
        final int pm = photoModel();
        if (pm == FastModel.GEMMA) return;
        final String token = prefs.getString("token", "");
        stopIndex();
        cancelDownload = false;
        dlError = null;
        state = State.DOWNLOADING;
        status = "Получаю список файлов…";
        dlDone = 0;
        dlTotal = 0;
        notifyChanged();
        net.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean fp32 = withFp32 || FastModel.fp32Vision(ctx, pm) != null;
                    Object[] rp = FastModel.resolve(FastModel.repo(prefs, pm), FastModel.SEARCH[pm], token, fp32);
                    String repo = (String) rp[0];
                    HfRepo.Plan plan = (HfRepo.Plan) rp[1];
                    prefs.edit().putString("s_repo_" + pm, repo).apply();
                    dlTotal = plan.totalBytes;
                    File dir = FastModel.dir(ctx, pm);
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("нет доступа к памяти");
                    new HfRepo(repo, token).download(plan, dir, new HfRepo.Progress() {
                        @Override
                        public boolean onProgress(String file, long fd, long ft, long all, long allTotal) {
                            dlDone = all;
                            dlTotal = allTotal;
                            status = "Скачиваю " + file;
                            notifyChanged();
                            return !cancelDownload;
                        }
                    });
                    HfRepo.saveManifest(plan, repo, FastModel.manifest(ctx, pm));
                    dlDone = dlTotal;
                    if (withFp32) prefs.edit().putBoolean("s_checked_" + pm, false).apply(); // check NPU/GPU next
                    loadModel(true);
                } catch (Exception e) {
                    dlError = cancelDownload ? "Загрузка остановлена — её можно продолжить" : "Ошибка: " + e.getMessage();
                    if (hasModelFiles()) {
                        loadModel(true);
                    } else {
                        state = State.NO_MODEL;
                        status = dlError;
                        notifyChanged();
                    }
                }
            }
        });
    }

    /**
     * Auto-check of the fast model on this phone: every way to run its picture tower (CPU int8 and fp32,
     * XNNPACK, NPU through NNAPI in fp16 and fp32, GPU through WebGPU) with a few batch sizes. Each run is
     * compared with the reference one (fp32 on the CPU when available): a variant whose vectors drift is
     * rejected, so an accelerator can never quietly spoil the index. The fastest correct one is kept.
     */
    public void checkFast(final Callback<String> cb) {
        final int pm = photoModel();
        if (pm == FastModel.GEMMA || !fastDownloaded(pm)) {
            post(cb, null, new IllegalStateException("быстрая модель не скачана"));
            return;
        }
        final boolean resume = indexing;
        stopIndex();
        state = State.LOADING;
        status = "Проверяю ускорители…";
        notifyChanged();
        ml.submit(new Runnable() {
            @Override
            public void run() {
                StringBuilder rep = new StringBuilder();
                closeModels();
                try {
                    HfRepo.Plan plan = FastModel.plan(ctx, pm);
                    File dir = FastModel.dir(ctx, pm);
                    File int8 = new File(dir, plan.visionModel), fp32 = FastModel.fp32Vision(ctx, pm);
                    int auto = autoThreads(), cores = Runtime.getRuntime().availableProcessors();
                    rep.append(FastModel.NAMES[pm]).append(", ядер ").append(cores).append(", быстрых ").append(auto)
                            .append("\nвремя на одно фото (без чтения файла), совпадение с эталоном\n\n");
                    List<Object[]> cands = new ArrayList<Object[]>(); // {accel, threads, batch}
                    if (fp32 != null) cands.add(new Object[]{SigLip.Accel.CPU_FP32, auto, 1}); // the reference
                    cands.add(new Object[]{SigLip.Accel.CPU, auto, 1});
                    cands.add(new Object[]{SigLip.Accel.CPU, auto, 4});
                    if (cores > auto) cands.add(new Object[]{SigLip.Accel.CPU, cores, 4});
                    if (fp32 != null) {
                        cands.add(new Object[]{SigLip.Accel.XNNPACK, auto, 4});
                        cands.add(new Object[]{SigLip.Accel.NPU, auto, 1});
                        cands.add(new Object[]{SigLip.Accel.NPU, auto, 4});
                        cands.add(new Object[]{SigLip.Accel.NPU_FP32, auto, 1});
                        cands.add(new Object[]{SigLip.Accel.GPU, auto, 4});
                    }
                    float[][] reference = null;
                    Object[] best = null;
                    double bestMs = Double.MAX_VALUE;
                    for (int i = 0; i < cands.size(); i++) {
                        Object[] c = cands.get(i);
                        SigLip.Accel a = (SigLip.Accel) c[0];
                        int t = (Integer) c[1], b = (Integer) c[2];
                        String name = a.label + (a == SigLip.Accel.CPU || a == SigLip.Accel.XNNPACK ? ", потоков " + t : "")
                                + (b > 1 ? ", пачка " + b : "");
                        String key = a.name() + "_" + b;
                        if (prefs.getBoolean("s_broken_" + a.name(), false) || prefs.getBoolean("s_broken_" + key, false)) {
                            rep.append("• ").append(name).append(": пропущено — в прошлый раз приложение на нём закрылось\n");
                            continue;
                        }
                        status = "Проверяю ускорители (" + (i + 1) + " из " + cands.size() + "): " + name;
                        notifyChanged();
                        prefs.edit().putString("s_probe_check", key).putString("last_step", "автопроверка: " + name).commit();
                        FastModel.Measure m = FastModel.measure(dir, a.needsFp32() ? fp32 : int8, a, t, b, reference);
                        prefs.edit().remove("s_probe_check").putString("last_step", "").commit();
                        if (m.error != null) {
                            rep.append("• ").append(name).append(": не работает — ").append(m.error).append('\n');
                            continue;
                        }
                        if (reference == null) reference = m.embs;
                        boolean ok = m.minCos >= 0.97f;
                        rep.append(String.format(java.util.Locale.ROOT, "• %s: %.3f с, совпадение %.3f%s%s\n", name,
                                m.perPhotoMs / 1000.0, m.minCos, m.loadMs > 3000 ? String.format(java.util.Locale.ROOT,
                                        " (подготовка %.0f с)", m.loadMs / 1000.0) : "",
                                ok ? "" : " — отклонено, результат расходится"));
                        if (ok && m.perPhotoMs < bestMs) {
                            bestMs = m.perPhotoMs;
                            best = c;
                        }
                    }
                    if (best == null) throw new IllegalStateException("ни один вариант не сработал");
                    SigLip.Accel ba = (SigLip.Accel) best[0];
                    rep.append(String.format(java.util.Locale.ROOT, "\nВыбрано: %s%s — %.3f с на фото",
                            ba.label, (Integer) best[2] > 1 ? ", пачка " + best[2] : "", bestMs / 1000.0));
                    if (fp32 == null && FastModel.acceleratorLikely()) {
                        long extra = 4 * new File(dir, plan.visionModel).length();
                        rep.append(String.format(java.util.Locale.ROOT, "\n\nNPU и видеокарта не проверены: им нужна полная "
                                + "версия модели (≈%d МБ). «Проверить NPU и видеокарту» в настройках докачает её.", extra >> 20));
                    }
                    prefs.edit().putInt("s_accel_" + pm, ba.ordinal()).putInt("s_batch_" + pm, (Integer) best[2])
                            .putInt("s_threads_" + pm, (Integer) best[1] == auto ? 0 : (Integer) best[1])
                            .putBoolean("s_checked_" + pm, true).putString("s_report_" + pm, rep.toString()).apply();
                    post(cb, rep.toString(), null);
                } catch (Throwable e) {
                    rep.append("\nОшибка: ").append(e.getMessage() != null ? e.getMessage() : e.toString());
                    prefs.edit().putBoolean("s_checked_" + pm, true).putString("s_report_" + pm, rep.toString()).apply();
                    post(cb, rep.toString(), null);
                }
                loadModel(true);
                if (resume) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            addListener(new Listener() {
                                @Override
                                public void onEngineChanged() {
                                    if (state == State.LOADING) return;
                                    removeListener(this);
                                    if (ready()) startIndexFromPrefs(false);
                                }
                            });
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------------ quality comparison

    /** Side-by-side run of the fast model and EmbeddingGemma 2 on the same recent photos. */
    public static final class Comparison {
        public final List<IndexStore.Item> photos = new ArrayList<IndexStore.Item>();
        public float[][] fast, slow;
        public double fastMs, slowMs;
        public String fastName, slowName;
        public final List<CompareQuery> queries = new ArrayList<CompareQuery>();
        /** Average share of the top results both models agree on. */
        public double agreement;
        public volatile boolean cancelled;
        Embedder fastModel, slowModel;
        boolean ownFast, ownSlow;
    }

    public static final class CompareQuery {
        public String text;
        /** Indexes into {@link Comparison#photos}, best first. */
        public int[] fast, slow;
        public int shared;
    }

    /** Test hook: {fast, slow} stand-ins for the comparison (no ONNX Runtime on Robolectric). */
    public static volatile Embedder[] testCompare;

    public static final String[] COMPARE_QUERIES = {"кот", "собака", "еда", "закат", "море", "снег", "машина",
            "документ", "скриншот", "люди", "цветы", "дом"};
    public static final int COMPARE_TOP = 6;
    public volatile int cmpDone, cmpTotal;

    /** Both models are on the phone and there are photos to compare on. */
    public boolean canCompare() {
        return ready() && gemmaDownloaded() && (fastDownloaded(FastModel.B16) || fastDownloaded(FastModel.B32))
                && store != null && store.count(IndexStore.KIND_PHOTO) >= COMPARE_TOP * 2;
    }

    public void compare(final int n, final Callback<Comparison> cb) {
        final Comparison c = new Comparison();
        cmpDone = 0;
        cmpTotal = n;
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    requireModel();
                    int pm = photoModel();
                    // fast side: the loaded SigLIP, or one opened on the CPU for the comparison
                    if (testCompare != null) {
                        c.fastModel = testCompare[0];
                        c.fastName = FastModel.NAMES[FastModel.B16];
                    } else if (photo instanceof SigLip) {
                        c.fastModel = photo;
                        c.fastName = FastModel.NAMES[pm];
                    } else {
                        int fpm = fastDownloaded(FastModel.B16) ? FastModel.B16 : FastModel.B32;
                        HfRepo.Plan fp = FastModel.plan(ctx, fpm);
                        File dir = FastModel.dir(ctx, fpm);
                        c.fastModel = new SigLip(dir, new File(dir, fp.textModel), new File(dir, fp.visionModel),
                                SigLip.Accel.CPU, threadCount(), 1);
                        c.ownFast = true;
                        c.fastName = FastModel.NAMES[fpm];
                    }
                    // slow side: EmbeddingGemma 2 with its vision encoder; it also takes over notes meanwhile
                    if (testCompare != null) {
                        c.slowModel = testCompare[1];
                    } else if (isGemma(photo)) {
                        c.slowModel = photo;
                    } else {
                        HfRepo.Plan g = gemmaPlan();
                        if (g == null) throw new IllegalStateException("EmbeddingGemma 2 не скачана");
                        Embedder full = loadGemma(g, true);
                        if (isGemma(model) && model != photo) {
                            model.close();
                            model = full; // same text model, now with pictures: serves notes too
                        } else {
                            c.ownSlow = true;
                        }
                        c.slowModel = full;
                    }
                    c.slowName = FastModel.NAMES[FastModel.GEMMA];
                    mark("сравнение моделей");
                    for (IndexStore.Item it : store.recent(true, false, false, n)) c.photos.add(it);
                    cmpTotal = c.photos.size();
                    c.fast = new float[c.photos.size()][];
                    c.slow = new float[c.photos.size()][];
                    int budget = photoBudget();
                    long target = Math.max((long) budget * 9 * 256, 4L * 256 * 256);
                    long fastNs = 0, slowNs = 0;
                    int done = 0;
                    ContentResolver cr = ctx.getContentResolver();
                    for (int i = 0; i < c.photos.size() && !c.cancelled; i++) {
                        IndexStore.Item it = c.photos.get(i);
                        Bitmap b;
                        try {
                            b = Media.decodeForIndex(cr, Uri.parse(it.uri), Media.orientation(cr, it.mediaId), target);
                        } catch (Exception unreadable) {
                            b = null;
                        }
                        if (b != null) {
                            Media.BitmapSource src = new Media.BitmapSource(b);
                            List<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source> one =
                                    new ArrayList<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source>();
                            one.add(src);
                            long t0 = System.nanoTime();
                            c.fast[i] = c.fastModel.embedImages(one, 0)[0];
                            long t1 = System.nanoTime();
                            c.slow[i] = c.slowModel.embedImages(one, budget)[0];
                            long t2 = System.nanoTime();
                            b.recycle();
                            fastNs += t1 - t0;
                            slowNs += t2 - t1;
                            done++;
                        }
                        cmpDone = i + 1;
                        notifyChanged();
                    }
                    if (c.cancelled) {
                        mark("");
                        return;
                    }
                    c.fastMs = fastNs / 1e6 / Math.max(1, done);
                    c.slowMs = slowNs / 1e6 / Math.max(1, done);
                    double agree = 0;
                    for (String q : COMPARE_QUERIES) {
                        CompareQuery r = rankBoth(c, q);
                        c.queries.add(r);
                        agree += r.shared / (double) COMPARE_TOP;
                    }
                    c.agreement = agree / COMPARE_QUERIES.length;
                    mark("");
                    post(cb, c, null);
                } catch (Exception e) {
                    mark("");
                    endCompare(c);
                    post(cb, null, e);
                }
            }
        });
    }

    /** One more query against a finished comparison (its models stay loaded until {@link #endCompare}). */
    public void compareQuery(final Comparison c, final String q, final Callback<CompareQuery> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    post(cb, rankBoth(c, q), null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    private CompareQuery rankBoth(Comparison c, String q) throws Exception {
        CompareQuery r = new CompareQuery();
        r.text = q;
        r.fast = top(c.fast, queryVectors(c.fastModel, q, true, false, bridgeMode()).media);
        r.slow = top(c.slow, queryVectors(c.slowModel, q, true, false, bridgeMode()).media);
        java.util.HashSet<Integer> f = new java.util.HashSet<Integer>();
        for (int i : r.fast) f.add(i);
        for (int i : r.slow) if (f.contains(i)) r.shared++;
        return r;
    }

    private static int[] top(final float[][] embs, float[] q) {
        List<Integer> idx = new ArrayList<Integer>();
        final float[] score = new float[embs.length];
        for (int i = 0; i < embs.length; i++) {
            if (embs[i] == null) continue;
            score[i] = dot(embs[i], q);
            idx.add(i);
        }
        java.util.Collections.sort(idx, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Float.compare(score[b], score[a]);
            }
        });
        int k = Math.min(COMPARE_TOP, idx.size());
        int[] out = new int[k];
        for (int i = 0; i < k; i++) out[i] = idx.get(i);
        return out;
    }

    /** Frees the models a comparison opened just for itself. */
    public void endCompare(final Comparison c) {
        c.cancelled = true;
        ml.submit(new Runnable() {
            @Override
            public void run() {
                if (c.ownFast && c.fastModel != null) c.fastModel.close();
                if (c.ownSlow && c.slowModel != null) c.slowModel.close();
                c.fastModel = null;
                c.slowModel = null;
            }
        });
    }

    /** Number of "big" CPU cores (max frequency ≥ 80% of the fastest one), clamped to 2..6. */
    public static int autoThreads() {
        int n = Runtime.getRuntime().availableProcessors();
        long[] f = new long[n];
        long max = 0;
        for (int i = 0; i < n; i++) {
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                        "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"));
                try {
                    f[i] = Long.parseLong(r.readLine().trim());
                } finally {
                    r.close();
                }
            } catch (Exception ignored) {
            }
            max = Math.max(max, f[i]);
        }
        int big = 0;
        for (long x : f) if (max > 0 && x >= max * 0.8) big++;
        if (max <= 0) big = n / 2;
        return Math.max(2, Math.min(6, big));
    }

    /** User-chosen thread count, or {@link #autoThreads()} for 0. */
    public int threadCount() {
        int t = prefs.getInt("threads", 0);
        return t > 0 ? t : autoThreads();
    }

    private Future<Bitmap> decodeAsync(final Media.Entry e) {
        // EmbeddingGemma looks at up to 9 × budget patches of 16×16; SigLIP at one small square (decoded at ~2× its side).
        Embedder p = photo;
        final long target = p instanceof SigLip ? 4L * ((SigLip) p).imageSize() * ((SigLip) p).imageSize()
                : (long) budgetFor(e) * 9 * 256;
        return decoder.submit(new Callable<Bitmap>() {
            @Override
            public Bitmap call() throws Exception {
                hangIfTest(e);
                return Media.decodeForIndex(ctx.getContentResolver(), e.uri, e.orientation, target);
            }
        });
    }

    /** "How many recent photos/videos" choices in the settings. */
    public static final int[] PHOTO_LIMITS = {100, 300, 1000, 3000, Integer.MAX_VALUE};
    public static final int[] VIDEO_LIMITS = {0, 10, 30, 100, 300, 1000, Integer.MAX_VALUE};

    public int photoLimit() {
        return PHOTO_LIMITS[Math.max(0, Math.min(PHOTO_LIMITS.length - 1, prefs.getInt("photo_limit", 4)))];
    }

    public int videoLimit() {
        return VIDEO_LIMITS[Math.max(0, Math.min(VIDEO_LIMITS.length - 1, prefs.getInt("video_limit", 1)))];
    }

    public void startIndexFromPrefs(boolean background) {
        if (indexing) return;
        backgroundRun = background;
        startIndex(photoLimit(), videoLimit());
    }

    /** New photos/videos (not in the index yet) within the configured limits, counted without the model. */
    public void countPending(final Callback<Integer> cb) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentResolver cr = ctx.getContentResolver();
                    int n = 0;
                    for (Media.Entry e : Media.recentImages(cr, photoLimit())) if (isNew(e, true)) n++;
                    if (videoLimit() > 0) {
                        for (Media.Entry e : Media.recentVideos(cr, videoLimit())) if (isNew(e, true)) n++;
                    }
                    post(cb, n, null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
    }

    /** Drops index entries of photos/videos that are gone from the phone (only with access to the whole gallery). */
    private int pruneDeleted(ContentResolver cr) {
        if (!AutoIndex.hasFullMediaAccess(ctx)) return 0;
        java.util.Set<Long> photos = Media.allIds(cr, IndexStore.KIND_PHOTO), videos = Media.allIds(cr, IndexStore.KIND_VIDEO);
        if (photos == null || videos == null) return 0;
        int n = 0;
        for (IndexStore.Item it : store.media()) {
            java.util.Set<Long> live = it.kind == IndexStore.KIND_PHOTO ? photos : videos;
            if (!live.contains(it.mediaId)) {
                store.delete(it);
                n++;
            }
        }
        return n;
    }

    public void startIndex(final int photoLimit, final int videoLimit) {
        if (indexing) return;
        indexing = true;
        cancelIndex = false;
        idxDone = 0;
        idxErrors = 0;
        idxFirstError = null;
        failStreak.clear();
        stopError = null;
        reloadAfterIndex = false;
        npuRestart = false;
        npuRestarts = 0;
        requeue.clear();
        qnnSeen.clear();
        pipelinedRun = false;
        videoHangs = 0;
        videoSkipNote = null;
        sumWaitMs = sumVisionMs = sumTextMs = 0;
        timedPhotos = 0;
        idxTotal = 0;
        idxStatus = "Ищу фото и видео…";
        notifyChanged();
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    requireModel();
                    ContentResolver cr = ctx.getContentResolver();
                    if (pruneDeleted(cr) > 0) notifyChanged();
                    boolean background = backgroundRun;
                    if (background && isQnn(loadedAccel) && prefs.contains("qnn_crash")) {
                        // the NPU process died in the last run: no new crash in the background — a run the user starts tries again
                        finishIndex("Фоновая индексация пропущена: в прошлый раз упал NPU-процесс — " + prefs.getString("qnn_crash", ""));
                        return;
                    }
                    if (!background) prefs.edit().remove("qnn_crash").apply();
                    if (!background) clearFailed(); // a run the user started retries what failed before
                    synchronized (queue) {
                        queue.clear();
                        if (photoLimit > 0 && photo.supportsImages()) {
                            for (Media.Entry e : Media.recentImages(cr, photoLimit)) {
                                if (isNew(e, background)) queue.add(e);
                            }
                        }
                        if (videoLimit > 0 && photo.supportsVideo()) {
                            for (Media.Entry e : Media.recentVideos(cr, videoLimit)) {
                                if (isNew(e, background)) queue.add(e);
                            }
                        }
                        idxTotal = queue.size();
                    }
                    idxStarted = System.currentTimeMillis();
                    idxProcessed = 0;
                    if (idxTotal == 0) {
                        finishIndex("Новых файлов нет — всё уже в индексе");
                        return;
                    }
                    idxStatus = "Индексирую…";
                    notifyChanged();
                    ml.submit(indexStep);
                } catch (Exception e) {
                    finishIndex("Ошибка: " + e.getMessage());
                }
            }
        });
    }

    /** Photos per vision-encoder run while indexing (pref "batch", chosen by the benchmark). */
    public int batchSize() {
        int pm = photoModel();
        if (pm != FastModel.GEMMA) return Math.max(FastModel.batch(prefs, pm), 4); // small model: batches are cheap
        return Math.max(1, Math.min(8, prefs.getInt("batch", 1)));
    }

    private final Runnable indexStep = new Runnable() {
        @Override
        public void run() {
            try {
                step();
            } catch (Throwable t) {
                // an executor swallows what escapes a task: the next step would never come and the run would stand
                // still, "Индексирую", with nothing working
                android.util.Log.e("SemSearch", "index step", t);
                Journal.add(ctx, "app", "индексация: ошибка шага — " + t);
                try {
                    collectText();
                } catch (Throwable ignored) {
                    // the run ends anyway
                }
                for (Future<Bitmap> f : prefetched.values()) f.cancel(false);
                prefetched.clear();
                finishIndex("Индексация остановлена из-за ошибки: " + (t.getMessage() != null ? t.getMessage() : t.toString())
                        + "\nВ индекс добавлено " + (idxDone - idxErrors) + " из " + idxTotal);
            }
        }

        private void step() {
            List<Media.Entry> batch = new ArrayList<Media.Entry>();
            boolean last;
            synchronized (queue) {
                last = cancelIndex || stopError != null || queue.isEmpty() || model == null;
            }
            if (last) collectText(); // the pipeline's last batch: its text stage, into the index
            synchronized (queue) {
                if (cancelIndex || stopError != null || queue.isEmpty() || model == null) {
                    for (Future<Bitmap> f : prefetched.values()) f.cancel(false);
                    prefetched.clear();
                    String err = timingSplit() + (idxFirstError != null ? "\nПервая ошибка: " + idxFirstError : "")
                            + (videoSkipNote != null ? "\n" + videoSkipNote : "");
                    if (stopError != null) {
                        finishIndex("Индексация остановлена: " + stopError + "\nВ индекс добавлено " + (idxDone - idxErrors) + " из "
                                + idxTotal + (idxErrors > 0 ? ", пропущено " + idxErrors : "")
                                + "; остальные файлы не помечены как ошибочные — следующий запуск возьмёт их снова" + err);
                        if (reloadAfterIndex) loadModel(loadedFull); // a new NPU process for the next run
                        return;
                    }
                    finishIndex((cancelIndex ? "Остановлено: " + idxDone + " из " + idxTotal
                            : "Готово: " + (idxDone - idxErrors) + " файлов" + (idxErrors > 0 ? ", пропущено " + idxErrors : ""))
                            + err);
                    return;
                }
                batch.add(queue.remove(0));
                if (batch.get(0).kind == IndexStore.KIND_PHOTO) {
                    int n = batchSize();
                    int budget = budgetFor(batch.get(0));
                    while (batch.size() < n && !queue.isEmpty() && queue.get(0).kind == IndexStore.KIND_PHOTO
                            && budgetFor(queue.get(0)) == budget) { // one vision run takes one budget
                        batch.add(queue.remove(0));
                    }
                }
            }
            if (batch.get(0).kind == IndexStore.KIND_VIDEO) {
                collectText();
                indexVideo(batch.get(0));
            } else {
                indexPhotos(batch);
            }
            idxProcessed += batch.size();
            long spent = System.currentTimeMillis() - idxStarted;
            double per = spent / 1000.0 / Math.max(1, idxProcessed);
            int left = idxTotal - idxDone;
            idxStatus = String.format(java.util.Locale.ROOT, "%d из %d%s · %.2f с на файл · осталось ~%s",
                    idxDone, idxTotal, idxErrors > 0 ? " (пропущено " + idxErrors + ")" : "", per, eta((long) (per * left))) + timingSplit();
            if (npuRestart) {
                // the NPU process died compiling a large graph: its photos again, with a new process (the next way)
                npuRestart = false;
                synchronized (queue) {
                    queue.addAll(0, requeue);
                }
                idxProcessed -= requeue.size();
                for (Media.Entry r : requeue) qnnSeen.remove(budgetFor(r));
                requeue.clear();
                idxStatus = idxDone + " из " + idxTotal + " · NPU-процесс упал при сборке — перезапускаю его, сборка пойдёт другим "
                        + "способом (перезапуск " + npuRestarts + " из " + MAX_NPU_RESTARTS + ")";
                notifyChanged();
                collectText();
                loadNow(loadedFull);
                if (state != State.READY || photo == null) stopError = "после падения NPU-процесса модель не загрузилась: " + status;
            }
            notifyChanged();
            ml.submit(indexStep);
        }
    };

    /** Decoding started ahead of time for upcoming photos (keyed by MediaStore id). */
    private final java.util.HashMap<Long, Future<Bitmap>> prefetched = new java.util.HashMap<Long, Future<Bitmap>>();

    private void indexPhotos(List<Media.Entry> batch) {
        long w0 = System.currentTimeMillis();
        List<Future<Bitmap>> futures = new ArrayList<Future<Bitmap>>();
        for (Media.Entry e : batch) {
            Future<Bitmap> f = prefetched.remove(e.id);
            futures.add(f != null ? f : decodeAsync(e));
        }
        // Start decoding the next batch while this one goes through the model.
        synchronized (queue) {
            int n = 0;
            for (Media.Entry e : queue) {
                if (e.kind != IndexStore.KIND_PHOTO || n >= batchSize()) break;
                if (!prefetched.containsKey(e.id)) prefetched.put(e.id, decodeAsync(e));
                n++;
            }
        }
        List<Media.Entry> ok = new ArrayList<Media.Entry>();
        List<Bitmap> bitmaps = new ArrayList<Bitmap>();
        for (int i = 0; i < batch.size(); i++) {
            try {
                // a file that never opens (seen: indexing stood still with no load) must not hold the run forever
                bitmaps.add(futures.get(i).get(openTimeoutS, java.util.concurrent.TimeUnit.SECONDS));
                ok.add(batch.get(i));
            } catch (java.util.concurrent.TimeoutException te) {
                futures.get(i).cancel(true);
                fail(batch.get(i), new IllegalStateException("файл не открылся за " + openTimeoutS + " с (недоступен или ещё в облаке?)"));
                // the decoding thread hangs in that file: the files queued after it go to a new one
                Journal.add(ctx, "app", "индексация: " + batch.get(i).name + " не открылся за " + openTimeoutS + " с — декодер заменён");
                decoder.shutdownNow();
                decoder = Executors.newSingleThreadExecutor();
                for (Future<Bitmap> f : prefetched.values()) f.cancel(true);
                prefetched.clear();
                for (int j = i + 1; j < batch.size(); j++) {
                    futures.get(j).cancel(true);
                    futures.set(j, decodeAsync(batch.get(j)));
                }
            } catch (Throwable t) {
                fail(batch.get(i), t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t);
            }
        }
        long waited = System.currentTimeMillis() - w0;
        if (ok.isEmpty()) return;
        int budget = budgetFor(ok.get(0));
        boolean compiles = qnnCompiles(budget);
        if (!compiles && photo instanceof Embedder.Staged && pipelines(loadedAccel) && startPipelined(ok, bitmaps, budget)) {
            sumWaitMs += waited;
            return;
        }
        collectText(); // the batch before goes into the index first, then this one the usual way
        if (compiles) {
            idxStatus = idxDone + " из " + idxTotal + " · NPU Snapdragon: первая сборка модели под " + budget
                    + " токенов — несколько минут, дальше быстро";
            notifyChanged();
        }
        long e0 = System.currentTimeMillis();
        try {
            float[][] embs;
            try {
                embs = embedPhotos(bitmaps, budget);
            } catch (Throwable batchError) {
                if (bitmaps.size() == 1 || npuProcessGone(batchError)) throw batchError;
                // One bad photo (or a batch the encoder rejects) must not sink the others.
                embs = new float[bitmaps.size()][];
                for (int i = 0; i < bitmaps.size() && stopError == null && !npuRestart; i++) {
                    try {
                        embs[i] = embedPhotos(java.util.Collections.singletonList(bitmaps.get(i)), budgetFor(ok.get(i)))[0];
                    } catch (Throwable t) {
                        modelFail(ok.get(i), t);
                    }
                }
            }
            long[] tm = photo.lastTimingsMs();
            sumWaitMs += waited;
            sumVisionMs += tm[0];
            sumTextMs += tm[1];
            timedPhotos += ok.size();
            for (int i = 0; i < ok.size(); i++) {
                if (embs[i] == null) continue;
                Media.Entry e = ok.get(i);
                checkAdult(store.add(e.kind, e.id, e.uri.toString(), e.name, null, e.date, embs[i]));
                idxDone++;
                failStreak.clear();
            }
        } catch (Throwable t) {
            for (Media.Entry e : ok) modelFail(e, t);
        } finally {
            for (Bitmap b : bitmaps) b.recycle();
            // the compilation is a one-off: it does not count in the time per file
            if (compiles) idxStarted += System.currentTimeMillis() - e0;
        }
    }

    /** The batch whose text stage runs on {@link #textStage} now (its vision stage done), and the model it runs on. */
    private Future<float[][]> textJob;
    private List<Media.Entry> textEntries;
    private Object textStarted;
    private Embedder.Staged textModel;
    /** Photos of this run went through the pipeline (for the status line). */
    private boolean pipelinedRun;

    /**
     * The vision stage of a batch here, its text stage on {@link #textStage}: while it runs, the next batch's vision
     * stage does (the NPU or GPU and the CPU busy at once). The batch before is collected after this vision stage.
     * False when the vision stage failed: the batch goes the usual way (one photo at a time, the failure rules).
     */
    private boolean startPipelined(final List<Media.Entry> ok, List<Bitmap> bitmaps, int budget) {
        final Embedder.Staged st = (Embedder.Staged) photo;
        List<ImagePreprocessor.Source> src = new ArrayList<ImagePreprocessor.Source>();
        for (Bitmap b : bitmaps) src.add(new Media.BitmapSource(b));
        final Object started;
        try {
            started = st.startImages(src, budget);
        } catch (Throwable t) {
            android.util.Log.w("SemSearch", "index: vision stage failed, the batch goes the usual way", t);
            collectText();
            return false;
        }
        for (Bitmap b : bitmaps) b.recycle();
        collectText();
        pipelinedRun = true;
        textEntries = ok;
        textStarted = started;
        textModel = st;
        textJob = textStage.submit(new Callable<float[][]>() {
            @Override
            public float[][] call() throws Exception {
                return st.finishImages(started);
            }
        });
        return true;
    }

    /** Waits for the pipeline's text stage and puts its photos into the index (on {@link #ml}, as everything else). */
    private void collectText() {
        Future<float[][]> job = textJob;
        if (job == null) return;
        List<Media.Entry> entries = textEntries;
        Object started = textStarted;
        Embedder.Staged st = textModel;
        textJob = null;
        textEntries = null;
        textStarted = null;
        textModel = null;
        float[][] embs;
        try {
            boolean interrupted = false;
            while (true) {
                try {
                    embs = job.get();
                    break;
                } catch (InterruptedException ie) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        } catch (ExecutionException ee) {
            Throwable t = ee.getCause() != null ? ee.getCause() : ee;
            if (t instanceof Embedder.Closed) {
                // the model was replaced before its text stage ran: the photos are fine, the next batches take them
                synchronized (queue) {
                    queue.addAll(0, entries);
                }
                idxProcessed -= entries.size();
                return;
            }
            for (Media.Entry e : entries) modelFail(e, t);
            return;
        }
        long[] tm = st.timingsMs(started);
        sumVisionMs += tm[0];
        sumTextMs += tm[1];
        timedPhotos += entries.size();
        for (int i = 0; i < entries.size(); i++) {
            Media.Entry e = entries.get(i);
            checkAdult(store.add(e.kind, e.id, e.uri.toString(), e.name, null, e.date, embs[i]));
            idxDone++;
            failStreak.clear();
        }
    }

    /** The NPU has no checked compilation for this budget yet: the next photo waits for one (minutes). */
    private boolean qnnCompiles(int budget) {
        File g = qnnGraphFile;
        if (!isQnn(loadedAccel) || g == null || !qnnSeen.add(budget)) return false;
        return !qnnBuilt(g, budget * qnnPool * qnnPool);
    }

    /** The NPU process is gone (crashed or stopped): every later photo would fail the same way. */
    static boolean npuProcessGone(Throwable t) {
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof NpuVision.Crashed) return true;
        }
        return false;
    }

    /**
     * The model failed on this file. The NPU process gone, or the same failure on file after file, means the
     * model is broken: the run stops, and the files are not remembered as failed (the next run takes them).
     */
    private void modelFail(Media.Entry e, Throwable t) {
        if (stopError != null) return; // stopping: the file stays as it is
        String msg = t.getMessage() != null ? t.getMessage() : t.toString();
        if (npuProcessGone(t) && e.kind == IndexStore.KIND_PHOTO && bigNpu(loadedAccel, budgetFor(e), qnnPool)
                && (npuRestart || npuRestarts < MAX_NPU_RESTARTS)) {
            // a large graph's compilation: a new process tries the next way
            if (!npuRestart) {
                npuRestart = true;
                npuRestarts++;
                android.util.Log.e("SemSearch", "index: NPU process gone, restart " + npuRestarts, t);
                Journal.add(ctx, "app", "индексация: NPU-процесс упал при сборке — перезапуск " + npuRestarts + ": " + msg);
            }
            requeue.add(e);
            return;
        }
        if (npuRestart) {
            requeue.add(e); // the rest of the batch goes back with it
            return;
        }
        if (npuProcessGone(t)) {
            Journal.add(ctx, "app", "индексация остановлена: " + msg);
            stopError = msg;
            reloadAfterIndex = true;
            prefs.edit().putString("qnn_crash", msg.length() > 300 ? msg.substring(0, 300) + "…" : msg).apply();
            android.util.Log.e("SemSearch", "index: NPU process gone", t);
            return;
        }
        fail(e, t);
        failStreak.add(e);
        if (failStreak.size() >= FAIL_STREAK_STOP) {
            for (Media.Entry f : failStreak) unmarkFailed(f);
            idxErrors -= failStreak.size();
            idxDone -= failStreak.size();
            if (idxErrors == 0) idxFirstError = null;
            stopError = "модель не обработала " + failStreak.size() + " файлов подряд — " + msg;
            failStreak.clear();
        }
    }

    private void indexVideo(final Media.Entry e) {
        idxStatus = idxDone + " из " + idxTotal + " · видео " + (e.name != null ? e.name : "") + ": кадры…" + videoCompileNote();
        notifyChanged();
        try {
            List<Bitmap> frames = videoFrames(e);
            try {
                List<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source> src =
                        new ArrayList<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source>();
                for (Bitmap f : frames) src.add(new Media.BitmapSource(f));
                float[] emb = photo.embedVideo(src, 0);
                checkAdult(store.add(e.kind, e.id, e.uri.toString(), e.name, null, e.date, emb));
                idxDone++;
                videoHangs = 0;
            } finally {
                for (Bitmap f : frames) f.recycle();
            }
        } catch (Throwable t) {
            if (npuProcessGone(t)) modelFail(e, t);
            else fail(e, t); // a video that cannot be read is the file's fault
            if (t instanceof OpenTimeout && ++videoHangs >= VIDEO_HANGS_STOP) {
                // video after video does not open (a minute each): the others wait for another run, unmarked
                int left = 0;
                synchronized (queue) {
                    for (java.util.Iterator<Media.Entry> it = queue.iterator(); it.hasNext(); ) {
                        if (it.next().kind == IndexStore.KIND_VIDEO) {
                            it.remove();
                            left++;
                        }
                    }
                }
                idxTotal -= left;
                videoSkipNote = VIDEO_HANGS_STOP + " видео подряд не открылись — остальные " + left + " в этот раз пропущены";
                Journal.add(ctx, "app", "индексация: " + videoSkipNote);
            }
        }
    }

    /** Why the rest of the videos were left for another run (for the end of the run's status). */
    private String videoSkipNote;

    /**
     * A video's frames, on a thread of their own: MediaMetadataRetriever can hang on a file (indexing stood still on
     * the first video with no load), and then the video counts as unreadable and its thread is left behind.
     */
    private List<Bitmap> videoFrames(final Media.Entry e) throws Exception {
        java.util.concurrent.FutureTask<List<Bitmap>> task = new java.util.concurrent.FutureTask<List<Bitmap>>(
                new Callable<List<Bitmap>>() {
                    @Override
                    public List<Bitmap> call() throws Exception {
                        hangIfTest(e);
                        return Media.videoFrames(ctx, e.uri, VIDEO_FRAMES, 640);
                    }
                });
        Thread t = new Thread(task, "video-frames");
        t.setDaemon(true);
        t.start();
        try {
            return task.get(openTimeoutS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            task.cancel(true);
            Journal.add(ctx, "app", "индексация: кадры видео " + e.name + " не получены за " + openTimeoutS + " с");
            throw new OpenTimeout("кадры видео не получены за " + openTimeoutS + " с (файл недоступен или ещё в облаке?)");
        } catch (ExecutionException ee) {
            throw ee.getCause() instanceof Exception ? (Exception) ee.getCause() : ee;
        }
    }

    /** A file did not open in the time allowed. */
    static final class OpenTimeout extends IllegalStateException {
        OpenTimeout(String m) {
            super(m);
        }
    }

    /** Videos in a row whose frames did not come; at VIDEO_HANGS_STOP the run leaves the rest of the videos alone. */
    private int videoHangs;
    static final int VIDEO_HANGS_STOP = 3;

    /** On the NPU, the first video of a run may need a compilation for its frames' size (minutes): said so. */
    private String videoCompileNote() {
        if (!isQnn(loadedAccel) || !(photo instanceof EmbeddingGemma2) || qnnGraphFile == null) return "";
        ModelConfig cfg = ((EmbeddingGemma2) photo).config();
        if (cfg.video == null) return "";
        int patches = cfg.video.maxPatches();
        return qnnBuilt(qnnGraphFile, patches) ? "" : " NPU собирает модель под кадры видео (" + patches + " фрагментов) — несколько минут";
    }

    /** Not in the index yet; files that failed before only count when the user started the run. */
    private boolean isNew(Media.Entry e, boolean skipFailed) {
        return !store.hasMedia(e.kind, e.id) && !(skipFailed && failedBefore(e));
    }

    // Files that could not be indexed (a broken video, an unsupported format): without this list the
    // background job would load the model for them again on every new photo.
    private java.util.Set<String> failed;

    private synchronized boolean failedBefore(Media.Entry e) {
        if (failed == null) failed = new java.util.HashSet<String>(prefs.getStringSet("failed_media", new java.util.HashSet<String>()));
        return failed.contains(e.kind + ":" + e.id);
    }

    private synchronized void markFailed(Media.Entry e) {
        failedBefore(e);
        if (failed.add(e.kind + ":" + e.id)) prefs.edit().putStringSet("failed_media", new java.util.HashSet<String>(failed)).apply();
    }

    private synchronized void unmarkFailed(Media.Entry e) {
        failedBefore(e);
        if (failed.remove(e.kind + ":" + e.id)) prefs.edit().putStringSet("failed_media", new java.util.HashSet<String>(failed)).apply();
    }

    private synchronized void clearFailed() {
        failed = new java.util.HashSet<String>();
        prefs.edit().remove("failed_media").apply();
    }

    private void fail(Media.Entry e, Throwable t) {
        markFailed(e);
        idxErrors++;
        idxDone++;
        if (idxFirstError == null) {
            idxFirstError = (e.name != null ? e.name + ": " : "") + (t.getMessage() != null ? t.getMessage() : t.toString());
            android.util.Log.e("SemSearch", "index " + e.uri, t);
        }
    }

    /** Where the time per photo goes: waiting for decode, vision encoder, text model. */
    private String timingSplit() {
        if (timedPhotos == 0) return "";
        return String.format(java.util.Locale.ROOT, "\nна фото: чтение %.2f с · картинка %.2f с · текст %.2f с%s · потоков %d",
                sumWaitMs / 1000.0 / timedPhotos, sumVisionMs / 1000.0 / timedPhotos,
                sumTextMs / 1000.0 / timedPhotos, pipelinedRun ? " (одновременно со следующей картинкой)" : "", threads);
    }

    private static String eta(long sec) {
        if (sec < 60) return sec + " с";
        if (sec < 3600) return (sec / 60) + " мин";
        return (sec / 3600) + " ч " + (sec % 3600 / 60) + " мин";
    }

    private void finishIndex(String msg) {
        indexing = false;
        backgroundRun = false;
        idxStatus = msg;
        notifyChanged();
        refreshAdult();
        scanFaces();
    }

    public void stopIndex() {
        cancelIndex = true;
    }
}
