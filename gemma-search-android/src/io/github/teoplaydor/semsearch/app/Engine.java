package io.github.teoplaydor.semsearch.app;

import android.content.ContentResolver;
import android.content.Context;
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
import io.github.teoplaydor.semsearch.core.ModelConfig;
import io.github.teoplaydor.semsearch.core.OnnxPatcher;
import io.github.teoplaydor.semsearch.core.PatternSource;
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
            prefs.edit().putInt("photo_model", manifest.exists() ? FastModel.GEMMA : FastModel.B16).apply();
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

    /** For the background job: the photo model is enough, the notes model stays on disk. */
    public void ensureLoadedForIndexing() {
        ensureLoaded(false);
    }

    private void ensureLoaded(boolean full) {
        if (!hasModelFiles()) return;
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
        return Math.max(0, Math.min(FastModel.NAMES.length - 1, prefs.getInt("photo_model", FastModel.B16)));
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
                    HfRepo.Plan plan = HfRepo.plan(r.listFiles(), vision);
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
                        model = loadGemma(plan, true);
                        photo = model;
                        mediaSig = notesSig = gemmaSig(plan);
                    } else {
                        HfRepo.Plan plan = FastModel.plan(ctx, pm);
                        if (plan == null || !HfRepo.isComplete(plan, FastModel.dir(ctx, pm))) {
                            noModel();
                            return;
                        }
                        step = "инициализация " + FastModel.NAMES[pm];
                        photo = openFast(pm, plan);
                        mediaSig = "s|" + HfRepo.manifestRepo(FastModel.manifest(ctx, pm));
                        HfRepo.Plan g = gemmaPlan();
                        String gSig = g != null ? gemmaSig(g) : null;
                        if (g != null && full) {
                            step = "EmbeddingGemma 2 для заметок";
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
                    photo.embedQuery("привет");
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
        return HfRepo.manifestRepo(manifest) + "|" + plan.textModel + "|" + plan.visionModel;
    }

    /** EmbeddingGemma 2 with the chosen accelerator (falling back to the CPU); text only when it serves notes. */
    private EmbeddingGemma2 loadGemma(HfRepo.Plan plan, boolean withVision) throws Exception {
        ModelConfig cfg = EmbeddingGemma2.loadConfig(modelDir);
        HfTokenizer tok = EmbeddingGemma2.loadTokenizer(modelDir);
        HfRepo.Plan use = plan;
        if (!withVision) {
            use = new HfRepo.Plan();
            use.textModel = plan.textModel;
        }
        int accel = accel();
        EmbeddingGemma2 m;
        try {
            m = createModel(cfg, tok, use, accel, threads);
        } catch (Exception gpuOrInt8Failure) {
            if (accel == ACCEL_CPU) throw gpuOrInt8Failure;
            android.util.Log.w("SemSearch", "accel " + accel + " failed, using CPU", gpuOrInt8Failure);
            prefs.edit().putInt("accel", ACCEL_CPU).apply();
            accel = ACCEL_CPU;
            m = createModel(cfg, tok, use, ACCEL_CPU, threads);
        }
        if (withVision) {
            loadedAccel = accel;
            accelLabel = ACCEL_NAMES[accel];
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
        return model instanceof EmbeddingGemma2 || (model != null && model == photo && photoModel() == FastModel.GEMMA)
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
                            .append(photoModel() == FastModel.GEMMA ? ", детализация фото: " + photoBudget()
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
                        q = embedPhoto(b);
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

    public int photoBudget() {
        int i = prefs.getInt("photo_detail", 0);
        return PHOTO_BUDGETS[Math.max(0, Math.min(PHOTO_BUDGETS.length - 1, i))];
    }

    /**
     * Embeds a photo with the chosen detail level. If the exported vision encoder only accepts
     * its default token budget, falls back to it and remembers that.
     */
    private float[] embedPhoto(Bitmap b) throws Exception {
        return embedPhotos(java.util.Collections.singletonList(b))[0];
    }

    private float[][] embedPhotos(List<Bitmap> bitmaps) throws Exception {
        List<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source> src =
                new ArrayList<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source>();
        for (Bitmap b : bitmaps) src.add(new Media.BitmapSource(b));
        if (!(photo instanceof EmbeddingGemma2)) return photo.embedImages(src, 0);
        int budget = photoBudget();
        try {
            return photo.embedImages(src, budget);
        } catch (Exception e) {
            if (budget == photo.defaultImageTokens()) throw e;
            prefs.edit().putInt("photo_detail", PHOTO_BUDGETS.length - 1).apply();
            return photo.embedImages(src, 0);
        }
    }

    // ------------------------------------------------------------------ acceleration

    public static final int ACCEL_CPU = 0, ACCEL_CPU_INT8 = 1, ACCEL_GPU = 2, ACCEL_GPU_INT8 = 3;
    public static final String[] ACCEL_NAMES = {"Процессор", "Процессор, int8", "Видеокарта (WebGPU)",
            "Видеокарта (WebGPU), int8"};

    public int accel() {
        int a = prefs.getInt("accel", ACCEL_CPU);
        if (a >= ACCEL_GPU && prefs.getBoolean("gpu_broken", false)) a = ACCEL_CPU;
        return Math.max(0, Math.min(ACCEL_NAMES.length - 1, a));
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

    private EmbeddingGemma2 createModel(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int accel, int nThreads)
            throws Exception {
        boolean int8 = accel == ACCEL_CPU_INT8 || accel == ACCEL_GPU_INT8;
        boolean gpu = accel >= ACCEL_GPU;
        File text = graphFile(plan.textModel, int8);
        File vision = plan.visionModel == null ? null : graphFile(plan.visionModel, int8);
        if (gpu) prefs.edit().putBoolean("gpu_probe", true).commit();
        try {
            return new EmbeddingGemma2(cfg, tok, text, vision, nThreads, gpu);
        } finally {
            if (gpu) prefs.edit().putBoolean("gpu_probe", false).commit();
        }
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
        EmbeddingGemma2 m = null;
        try {
            m = createModel(cfg, tok, plan, accel, nThreads);
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
                    if (model != null) {
                        model.close();
                        model = null;
                    }
                    HfRepo.Plan plan = HfRepo.loadManifest(manifest);
                    if (plan == null || plan.visionModel == null) throw new IllegalStateException("нет визуального энкодера");
                    ModelConfig cfg = EmbeddingGemma2.loadConfig(modelDir);
                    HfTokenizer tok = EmbeddingGemma2.loadTokenizer(modelDir);
                    int budget = photoBudget();
                    int auto = autoThreads(), cores = Runtime.getRuntime().availableProcessors();
                    rep.append("Детализация ").append(budget).append(" токенов, ядер ").append(cores)
                            .append(", быстрых ").append(auto).append("\n(картинка + текст на одно фото)\n\n");

                    List<int[]> plan1 = new ArrayList<int[]>(); // {accel, threads, batch}
                    for (int a = 0; a < ACCEL_NAMES.length; a++) {
                        if (a >= ACCEL_GPU && gpuBroken()) continue;
                        plan1.add(new int[]{a, auto, 1});
                    }
                    float[] reference = null;
                    int[] best = null;
                    double bestMs = Double.MAX_VALUE;
                    int step = 0;
                    for (int phase = 0; phase < 3; phase++) {
                        List<int[]> cands = new ArrayList<int[]>();
                        if (phase == 0) {
                            cands.addAll(plan1);
                        } else if (phase == 1 && best != null) {
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
                            Measure m = measure(cfg, tok, plan, c[0], c[1], c[2], budget, reference);
                            if (m.error != null) {
                                rep.append("• ").append(name).append(": не работает — ").append(m.error).append('\n');
                                continue;
                            }
                            if (reference == null) reference = m.emb;
                            boolean ok = m.cos >= 0.98f;
                            rep.append(String.format(java.util.Locale.ROOT, "• %s: %.2f с (%.2f + %.2f)%s%s\n", name,
                                    m.perPhotoMs / 1000.0, m.visionMs / 1000.0, m.textMs / 1000.0,
                                    reference == m.emb ? "" : String.format(java.util.Locale.ROOT, ", совпадение %.3f", m.cos),
                                    ok ? "" : " — отклонено, результат расходится"));
                            if (ok && m.perPhotoMs < bestMs) {
                                bestMs = m.perPhotoMs;
                                best = c;
                            }
                        }
                    }
                    if (best == null) throw new IllegalStateException("ни один вариант не сработал");
                    prefs.edit().putInt("accel", best[0]).putInt("threads", best[1] == auto ? 0 : best[1])
                            .putInt("batch", best[2]).putBoolean("accel_chosen", true).apply();
                    rep.append(String.format(java.util.Locale.ROOT, "\nВыбрано: %s, потоков %d%s — %.2f с на фото",
                            ACCEL_NAMES[best[0]], best[1], best[2] > 1 ? ", пачка " + best[2] : "", bestMs / 1000.0));
                    post(cb, rep.toString(), null);
                } catch (Throwable e) {
                    rep.append("\nОшибка: ").append(e.getMessage() != null ? e.getMessage() : e.toString());
                    post(cb, rep.toString(), null);
                }
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
                        if (prefs.getBoolean("s_broken_" + a.name(), false)) {
                            rep.append("• ").append(name).append(": пропущено — в прошлый раз уронило драйвер\n");
                            continue;
                        }
                        status = "Проверяю ускорители (" + (i + 1) + " из " + cands.size() + "): " + name;
                        notifyChanged();
                        boolean risky = a == SigLip.Accel.NPU || a == SigLip.Accel.NPU_FP32 || a == SigLip.Accel.GPU;
                        if (risky) prefs.edit().putString("s_probe", a.name()).commit();
                        FastModel.Measure m = FastModel.measure(dir, a.needsFp32() ? fp32 : int8, a, t, b, reference);
                        if (risky) prefs.edit().remove("s_probe").commit();
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
                    } else if (photo instanceof EmbeddingGemma2) {
                        c.slowModel = photo;
                    } else {
                        HfRepo.Plan g = gemmaPlan();
                        if (g == null) throw new IllegalStateException("EmbeddingGemma 2 не скачана");
                        EmbeddingGemma2 full = loadGemma(g, true);
                        if (model instanceof EmbeddingGemma2 && model != photo) {
                            model.close();
                            model = full; // same text model, now with pictures: serves notes too
                        } else {
                            c.ownSlow = true;
                        }
                        c.slowModel = full;
                    }
                    c.slowName = FastModel.NAMES[FastModel.GEMMA];
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
                    if (c.cancelled) return;
                    c.fastMs = fastNs / 1e6 / Math.max(1, done);
                    c.slowMs = slowNs / 1e6 / Math.max(1, done);
                    double agree = 0;
                    for (String q : COMPARE_QUERIES) {
                        CompareQuery r = rankBoth(c, q);
                        c.queries.add(r);
                        agree += r.shared / (double) COMPARE_TOP;
                    }
                    c.agreement = agree / COMPARE_QUERIES.length;
                    post(cb, c, null);
                } catch (Exception e) {
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
                : (long) photoBudget() * 9 * 256;
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
                    while (batch.size() < n && !queue.isEmpty() && queue.get(0).kind == IndexStore.KIND_PHOTO) {
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
                embs = embedPhotos(bitmaps);
            } catch (Throwable batchError) {
                if (bitmaps.size() == 1) throw batchError;
                // One bad photo (or a batch the encoder rejects) must not sink the others.
                embs = new float[bitmaps.size()][];
                for (int i = 0; i < bitmaps.size(); i++) {
                    try {
                        embs[i] = embedPhotos(java.util.Collections.singletonList(bitmaps.get(i)))[0];
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
