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
import io.github.teoplaydor.semsearch.core.QnnRuntime;
import io.github.teoplaydor.semsearch.core.QueryBridge;
import io.github.teoplaydor.semsearch.core.SigLip;
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
    /** Decodes the next photo while the current one is being embedded. */
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
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
                store = new IndexStore(Engine.this.ctx);
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
        });
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
        public List<IndexStore.Hit> hits;
        public long millis;
        public String label;
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
                    r.hits = store.search(qv.media, mediaDims(), qv.notes, notesDims(), photos, videos, withNotes,
                            photo == model, 90, -1);
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
                    r.hits = store.search(q, mediaDims(), q, mediaDims(), photos, videos, notes && same, true, 90, -1);
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
                r.hits = store.search(item.emb, dims, item.emb, dims, (photos && (!note || same)), (videos && (!note || same)),
                        notes && (note || same) && notesUsable, true, 90, item.id);
                r.millis = System.currentTimeMillis() - t0;
                r.label = "похожие";
                post(cb, r, null);
            }
        });
    }

    private void requireModel() {
        if (model == null || photo == null || state != State.READY) throw new IllegalStateException("модель ещё не загружена");
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
            if (budget == photo.defaultImageTokens()) throw e;
            prefs.edit().putInt("photo_detail", PHOTO_BUDGETS.length - 1).apply();
            return photo.embedImages(src, 0);
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
            OnnxPatcher.forQnn(fp32, graph);
        }
        File text = graphFile(plan.textModel, true);
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
     * ±10⁴, bounds ±65504).
     */
    private static File qnnGraph(File fp32) {
        return new File(fp32.getParentFile(), fp32.getName().replace(".onnx", ".qnn.r4.onnx"));
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
                return m;
            } catch (RuntimeException noSuchBudget) {
                liteRtBudgetFixed = true;
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
        float cos = 1f;
        float[] emb;
        String error;
    }

    /** Embeds synthetic photos (after a warm-up) and returns the best of two timed runs. */
    private Measure measure(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int accel, int nThreads, int batch,
                            int budget, float[] reference) {
        Measure r = new Measure();
        Embedder m = null;
        try {
            m = createModel(cfg, tok, plan, accel, nThreads, batch);
            if (m instanceof LiteRtEmbedder && ((LiteRtEmbedder) m).budgetFixed()) {
                // its numbers would be for the bundle's own (smaller) detail, not for the one asked
                r.error = "сборка не принимает " + maxBudget() + " токенов — работает только со своей детализацией";
                return r;
            }
            List<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source> imgs =
                    new ArrayList<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source>();
            for (int k = 0; k < batch; k++) imgs.add(new PatternSource(640, 480, 2 + k));
            m.embedImages(imgs, budget); // warm-up: allocations, GPU shader compilation
            for (int run = 0; run < 2; run++) {
                long t0 = System.currentTimeMillis();
                float[][] e = m.embedImages(imgs, budget);
                double per = (System.currentTimeMillis() - t0) / (double) batch;
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
        } finally {
            if (m != null) m.close();
        }
        return r;
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
        else rep.append(String.format(java.util.Locale.ROOT, "%.2f с (%.2f + %.2f)", o.perPhotoMs / 1000.0,
                    o.visionMs / 1000.0, o.textMs / 1000.0));
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
                        for (int[] c : cands) {
                            step++;
                            String name = ACCEL_NAMES[c[0]] + ", потоков " + c[1] + (c[2] > 1 ? ", пачка " + c[2] : "");
                            status = "Подбираю ускорение (" + step + "): " + name;
                            notifyChanged();
                            mark("подбор ускорения: " + name);
                            if (isQnn(c[0]) && phase == 0) {
                                status = "NPU Snapdragon: компилирую модель под NPU — в первый раз до нескольких минут, если QNN не справится — до 15 минут поиска";
                                notifyChanged();
                            }
                            probe(c[0], true);
                            Measure m = measure(cfg, tok, plan, c[0], c[1], c[2], budget, reference);
                            probe(c[0], false);
                            mark("");
                            if (isQnn(c[0]) && m.error != null && m.error.startsWith("NPU-процесс упал")) {
                                prefs.edit().putBoolean("qnn_broken", true).apply();
                            }
                            if (isQnn(c[0]) && m.error == null && phase == 0) qnnMeasured = true;
                            if (m.error != null) {
                                rep.append("• ").append(name).append(": не работает — ").append(m.error).append('\n');
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
                                            : String.format(java.util.Locale.ROOT, "%.2f с (%.2f + %.2f)", m.perPhotoMs / 1000.0,
                                            m.visionMs / 1000.0, m.textMs / 1000.0),
                                    reference == m.emb ? "" : String.format(java.util.Locale.ROOT, ", совпадение %.3f", m.cos),
                                    ok ? "" : ownSpace ? " — векторы отличаются от ONNX-версии" : " — отклонено, результат расходится"));
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
                    }
                    if (best == null) {
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
                    for (int round = 0; round < (duel ? 2 : 1); round++) {
                        Measure a = measureAt(cfg, tok, plan, best, budget, reference);
                        if (a.error == null && a.perPhotoMs < againBest) {
                            againBest = a.perPhotoMs;
                            lastBest = a;
                        }
                        if (duel) {
                            Measure b = measureAt(cfg, tok, plan, runner, budget, reference);
                            if (b.error == null && b.perPhotoMs < againRunner) {
                                againRunner = b.perPhotoMs;
                                lastRunner = b;
                            }
                        }
                    }
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
                    Measure bestOther = null, rivalOther = null;
                    if (other > 0) {
                        if (weigh) {
                            status = "Даю телефону остыть перед замером скриншотов…";
                            notifyChanged();
                            Thread.sleep(20000);
                        }
                        bestOther = measureAt(cfg, tok, plan, best, other);
                        // LiteRT-LM on screenshots, compared with the ONNX model at the same detail
                        if (rival != null) {
                            rivalOther = measureAt(cfg, tok, plan, rival, other,
                                    bestOther.error == null ? bestOther.emb : null);
                        }
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
                    boolean switched = false;
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
                        appendMeasure(rep, onnxWinner, bestOther);
                        if (rivalOther != null) {
                            appendMeasure(rep, rival, rivalOther);
                            if (rivalOther.error == null && bestOther.error == null) {
                                rep.append(String.format(java.util.Locale.ROOT, ", совпадение с ONNX на %d токенах %.3f", other, rivalOther.cos));
                            }
                        }
                        // does LiteRT-LM really change detail? (a bundle without the signature falls back to its own)
                        float[] lrtAtBudget = isLiteRt(onnxWinner[0]) ? bestEmb : rivalOther != null ? lrtBestEmb : null;
                        Measure lrtAtOther = isLiteRt(onnxWinner[0]) ? bestOther : rivalOther;
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
                        notifyChanged();
                        mark("подбор ускорения: профиль, " + ACCEL_NAMES[best[0]]);
                        if (isNpu(best[0])) prefs.edit().putString("npu_probe", String.valueOf(best[0])).commit();
                        rep.append("\n\n").append(profileVision(cfg, tok, plan, best, budget));
                        prefs.edit().remove("npu_probe").commit();
                        mark("");
                    }
                    if (qnnMeasured) {
                        status = "Смотрю, что NPU взял на себя…";
                        notifyChanged();
                        rep.append("\n\nNPU Snapdragon (").append(budget).append(" токенов):\n").append(profileQnn(cfg, plan, budget));
                        if (qnnWrong) {
                            status = "Ищу, где NPU портит результат (сборка с проверками, до нескольких минут)…";
                            notifyChanged();
                            rep.append("\nГде NPU портит результат: ").append(scanQnn(cfg, plan, budget));
                        }
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
                    prefs.edit().putString("g_report", rep.toString()).apply();
                    post(cb, rep.toString(), null);
                } catch (Throwable e) {
                    rep.append("\nОшибка: ").append(e.getMessage() != null ? e.getMessage() : e.toString());
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
                return Media.decodeForIndex(ctx.getContentResolver(), e.uri, e.orientation, target);
            }
        });
    }

    /** "How many recent photos/videos" choices in the settings. */
    public static final int[] PHOTO_LIMITS = {100, 300, 1000, 3000, Integer.MAX_VALUE};
    public static final int[] VIDEO_LIMITS = {0, 10, 30, 100};

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
            List<Media.Entry> batch = new ArrayList<Media.Entry>();
            synchronized (queue) {
                if (cancelIndex || queue.isEmpty() || model == null) {
                    for (Future<Bitmap> f : prefetched.values()) f.cancel(false);
                    prefetched.clear();
                    String err = timingSplit() + (idxFirstError != null ? "\nПервая ошибка: " + idxFirstError : "");
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
                indexVideo(batch.get(0));
            } else {
                indexPhotos(batch);
            }
            idxProcessed += batch.size();
            long spent = System.currentTimeMillis() - idxStarted;
            double per = spent / 1000.0 / Math.max(1, idxProcessed);
            int left = idxTotal - idxDone;
            idxStatus = String.format(java.util.Locale.ROOT, "%d из %d · %.2f с на файл · осталось ~%s",
                    idxDone, idxTotal, per, eta((long) (per * left))) + timingSplit();
            notifyChanged();
            ml.submit(this);
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
                bitmaps.add(futures.get(i).get());
                ok.add(batch.get(i));
            } catch (Throwable t) {
                fail(batch.get(i), t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t);
            }
        }
        long waited = System.currentTimeMillis() - w0;
        if (ok.isEmpty()) return;
        try {
            float[][] embs;
            try {
                embs = embedPhotos(bitmaps, budgetFor(ok.get(0)));
            } catch (Throwable batchError) {
                if (bitmaps.size() == 1) throw batchError;
                // One bad photo (or a batch the encoder rejects) must not sink the others.
                embs = new float[bitmaps.size()][];
                for (int i = 0; i < bitmaps.size(); i++) {
                    try {
                        embs[i] = embedPhotos(java.util.Collections.singletonList(bitmaps.get(i)), budgetFor(ok.get(i)))[0];
                    } catch (Throwable t) {
                        fail(ok.get(i), t);
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
                store.add(e.kind, e.id, e.uri.toString(), e.name, null, e.date, embs[i]);
                idxDone++;
            }
        } catch (Throwable t) {
            for (Media.Entry e : ok) fail(e, t);
        } finally {
            for (Bitmap b : bitmaps) b.recycle();
        }
    }

    private void indexVideo(Media.Entry e) {
        try {
            List<Bitmap> frames = Media.videoFrames(ctx, e.uri, VIDEO_FRAMES, 640);
            try {
                List<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source> src =
                        new ArrayList<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source>();
                for (Bitmap f : frames) src.add(new Media.BitmapSource(f));
                float[] emb = photo.embedVideo(src, 0);
                store.add(e.kind, e.id, e.uri.toString(), e.name, null, e.date, emb);
                idxDone++;
            } finally {
                for (Bitmap f : frames) f.recycle();
            }
        } catch (Throwable t) {
            fail(e, t);
        }
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
        return String.format(java.util.Locale.ROOT, "\nна фото: чтение %.2f с · картинка %.2f с · текст %.2f с · потоков %d",
                sumWaitMs / 1000.0 / timedPhotos, sumVisionMs / 1000.0 / timedPhotos,
                sumTextMs / 1000.0 / timedPhotos, threads);
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
    }

    public void stopIndex() {
        cancelIndex = true;
    }
}
