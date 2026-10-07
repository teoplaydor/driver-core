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
    private Future<Bitmap> prefetch;
    private Media.Entry prefetchEntry;
    private long sumWaitMs, sumVisionMs, sumTextMs;
    private int timedPhotos;
    public volatile int threads;
    public volatile int loadedAccel;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    final SharedPreferences prefs;
    final File modelDir;
    private final File manifest;

    private IndexStore store;
    private Embedder model;
    private QueryBridge bridge;

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
        if (manifest.exists()) loadModel();
    }

    /** Test hook: use a stand-in model (Robolectric can't run ONNX Runtime's native code). */
    public void attachModelForTest(final Embedder m) {
        ml.submit(new Runnable() {
            @Override
            public void run() {
                model = m;
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

    public boolean ready() { return state == State.READY && model != null; }

    public boolean supportsImages() { return ready() && model.supportsImages(); }

    public boolean supportsVideo() { return ready() && model.supportsVideo(); }

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
                    state = manifest.exists() ? State.ERROR : State.NO_MODEL;
                    status = cancelDownload ? "Загрузка остановлена — её можно продолжить" : "Ошибка: " + e.getMessage();
                    notifyChanged();
                }
            }
        });
    }

    public void cancelDownload() { cancelDownload = true; }

    public boolean hasModelFiles() { return manifest.exists(); }

    public void loadModel() {
        state = State.LOADING;
        status = "Загружаю модель в память…";
        notifyChanged();
        ml.submit(new Runnable() {
            @Override
            public void run() {
                String step = "чтение списка файлов";
                errorDetails = null;
                if (model != null) { // reload (e.g. new thread count): free the old sessions first
                    model.close();
                    model = null;
                }
                try {
                    HfRepo.Plan plan = HfRepo.loadManifest(manifest);
                    if (plan == null || !HfRepo.isComplete(plan, modelDir)) {
                        state = State.NO_MODEL;
                        status = "Модель не скачана полностью";
                        notifyChanged();
                        return;
                    }
                    long t0 = System.currentTimeMillis();
                    threads = threadCount();
                    step = "инициализация";
                    ModelConfig cfg = EmbeddingGemma2.loadConfig(modelDir);
                    HfTokenizer tok = EmbeddingGemma2.loadTokenizer(modelDir);
                    int accel = accel();
                    try {
                        model = createModel(cfg, tok, plan, accel, threads);
                    } catch (Exception gpuOrInt8Failure) {
                        if (accel == ACCEL_CPU) throw gpuOrInt8Failure;
                        android.util.Log.w("SemSearch", "accel " + accel + " failed, using CPU", gpuOrInt8Failure);
                        prefs.edit().putInt("accel", ACCEL_CPU).apply();
                        accel = ACCEL_CPU;
                        model = createModel(cfg, tok, plan, ACCEL_CPU, threads);
                    }
                    loadedAccel = accel;
                    // Warm-up run (first inference allocates buffers) and embedding size.
                    step = "пробный запуск";
                    model.embedQuery("привет");
                    step = "переиндексация заметок";
                    String sig = HfRepo.manifestRepo(manifest) + "|" + plan.textModel + "|" + plan.visionModel;
                    if (!sig.equals(prefs.getString("model_sig", ""))) {
                        // Different weights: old vectors are not comparable.
                        store.clearMedia();
                        for (IndexStore.Item n : store.notes()) store.updateEmbedding(n, model.embedDocument(n.body));
                        prefs.edit().putString("model_sig", sig).apply();
                    }
                    state = State.READY;
                    status = "Модель готова (" + (System.currentTimeMillis() - t0) / 100 / 10.0 + " с), "
                            + model.embeddingDim() + " изм., " + ACCEL_NAMES[loadedAccel] + ", потоков: " + threads
                            + (model.supportsImages() ? "" : " · только текст");
                } catch (Throwable e) {
                    if (model != null) model.close();
                    model = null;
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
            }
        });
    }

    private void unloadModel() {
        final Embedder m = model;
        model = null;
        if (m != null) {
            ml.submit(new Runnable() {
                @Override
                public void run() {
                    m.close();
                }
            });
        }
    }

    public void deleteModel() {
        stopIndex();
        cancelDownload = true;
        unloadModel();
        ml.submit(new Runnable() {
            @Override
            public void run() {
                deleteTree(modelDir);
                state = State.NO_MODEL;
                status = "Модель удалена";
                notifyChanged();
            }
        });
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
                    QueryVectors qv = queryVectors(query, photos || videos, bridgeMode());
                    SearchResult r = new SearchResult();
                    r.hits = store.search(qv.media, qv.notes, searchDims(), photos, videos, notes, 90, -1);
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

    /**
     * Notes are matched with the original query. For photos/videos a Russian query is also
     * rendered in English (QueryBridge) because the model's text↔image alignment is strongest
     * for English; mode 0 searches with the sum of both vectors, mode 1 with English only.
     */
    QueryVectors queryVectors(String query, boolean forMedia, int mode) throws Exception {
        QueryVectors v = new QueryVectors();
        v.notes = model.embedQuery(query);
        v.media = v.notes;
        if (!forMedia || mode == 2 || bridge == null || !QueryBridge.hasCyrillic(query)) return v;
        QueryBridge.Result br = bridge.translate(query);
        if (br == null) return v;
        float[] en = model.embedQuery(br.english);
        v.english = br.english;
        if (mode == 1) {
            v.media = en;
        } else {
            float[] sum = new float[en.length];
            for (int i = 0; i < sum.length; i++) sum[i] = v.notes[i] + en[i];
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
                    sb.append("В индексе фото/видео: ").append(photosN).append(", детализация фото: ")
                            .append(photoBudget()).append(" токенов, длина вектора поиска: ").append(searchDims())
                            .append("\n\nRU↔EN — косинус запросов; топ-10 — сколько фото совпало с выдачей по EN\n");
                    long tq = 0;
                    int nq = 0;
                    for (String[] p : pairs) {
                        long t0 = System.currentTimeMillis();
                        float[] en = model.embedQuery(p[1]);
                        tq += System.currentTimeMillis() - t0;
                        nq++;
                        QueryVectors ru = queryVectors(p[0], true, 2), mix = queryVectors(p[0], true, 0),
                                br = queryVectors(p[0], true, 1);
                        sb.append(String.format(java.util.Locale.ROOT, "• %s ↔ %s: %.2f", p[0], p[1], dot(ru.notes, en)));
                        if (mix.english != null) {
                            sb.append(String.format(java.util.Locale.ROOT, " | мост «%s»: %.2f", mix.english, dot(br.media, en)));
                        }
                        if (photosN >= 10) {
                            List<IndexStore.Hit> hEn = store.search(en, en, searchDims(), true, true, false, 10, -1);
                            List<IndexStore.Hit> hRu = store.search(ru.media, ru.media, searchDims(), true, true, false, 10, -1);
                            List<IndexStore.Hit> hMix = store.search(mix.media, mix.media, searchDims(), true, true, false, 10, -1);
                            List<IndexStore.Hit> hBr = store.search(br.media, br.media, searchDims(), true, true, false, 10, -1);
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
                    if (!model.supportsImages()) throw new IllegalStateException("визуальный энкодер не загружен");
                    long t0 = System.currentTimeMillis();
                    Bitmap b = Media.decode(ctx.getContentResolver(), uri, 0, 900_000L);
                    float[] q;
                    try {
                        q = embedPhoto(b);
                    } finally {
                        b.recycle();
                    }
                    SearchResult r = new SearchResult();
                    r.hits = store.search(q, searchDims(), photos, videos, notes, 90, -1);
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
                r.hits = store.search(item.emb, searchDims(), photos, videos, notes, 90, item.id);
                r.millis = System.currentTimeMillis() - t0;
                r.label = "похожие";
                post(cb, r, null);
            }
        });
    }

    private void requireModel() {
        if (model == null || state != State.READY) throw new IllegalStateException("модель ещё не загружена");
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
        int budget = photoBudget();
        try {
            return model.embedImage(new Media.BitmapSource(b), budget);
        } catch (Exception e) {
            if (budget == model.defaultImageTokens()) throw e;
            prefs.edit().putInt("photo_detail", PHOTO_BUDGETS.length - 1).apply();
            return model.embedImage(new Media.BitmapSource(b), 0);
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

    /**
     * Measures photo embedding speed for each accelerator (and a few thread counts) on this phone,
     * rejects variants whose result drifts from the plain CPU one, and keeps the fastest.
     */
    public void benchmark(final Callback<String> cb) {
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
                            .append(", быстрых ").append(auto).append("\n\n");
                    List<int[]> cands = new ArrayList<int[]>();
                    for (int a = 0; a < ACCEL_NAMES.length; a++) {
                        if (a >= ACCEL_GPU && gpuBroken()) continue;
                        cands.add(new int[]{a, auto});
                    }
                    float[] reference = null;
                    long bestMs = Long.MAX_VALUE;
                    int bestA = ACCEL_CPU, bestT = auto, bestCpuA = ACCEL_CPU;
                    long bestCpuMs = Long.MAX_VALUE;
                    for (int i = 0; i < cands.size(); i++) {
                        int a = cands.get(i)[0], t = cands.get(i)[1];
                        String name = ACCEL_NAMES[a] + ", потоков " + t;
                        status = "Подбираю ускорение " + (i + 1) + "/" + cands.size() + ": " + name;
                        notifyChanged();
                        EmbeddingGemma2 m = null;
                        try {
                            m = createModel(cfg, tok, plan, a, t);
                            m.embedImage(new PatternSource(640, 480, 1), budget); // warm-up (GPU shader compile)
                            long best = Long.MAX_VALUE;
                            float[] emb = null;
                            for (int k = 0; k < 2; k++) {
                                long t0 = System.currentTimeMillis();
                                emb = m.embedImage(new PatternSource(640, 480, 2), budget);
                                best = Math.min(best, System.currentTimeMillis() - t0);
                            }
                            String sim = "";
                            boolean ok = true;
                            if (reference == null) {
                                reference = emb;
                            } else {
                                float cos = 0;
                                for (int j = 0; j < emb.length; j++) cos += emb[j] * reference[j];
                                ok = cos >= 0.98f;
                                sim = String.format(java.util.Locale.ROOT, ", совпадение %.3f", cos);
                            }
                            rep.append(String.format(java.util.Locale.ROOT, "• %s: %.2f с на фото%s%s\n", name,
                                    best / 1000.0, sim, ok ? "" : " — отклонено, результат расходится"));
                            if (ok && best < bestMs) {
                                bestMs = best;
                                bestA = a;
                                bestT = t;
                            }
                            if (ok && a < ACCEL_GPU && best < bestCpuMs) {
                                bestCpuMs = best;
                                bestCpuA = a;
                            }
                        } catch (Throwable e) {
                            rep.append("• ").append(name).append(": не работает — ")
                                    .append(e.getMessage() != null ? e.getMessage() : e.toString()).append('\n');
                        } finally {
                            if (m != null) m.close();
                        }
                        // After the accelerators, try other thread counts for the best CPU variant.
                        if (i == cands.size() - 1 && cands.size() <= ACCEL_NAMES.length && bestCpuMs < Long.MAX_VALUE) {
                            java.util.LinkedHashSet<Integer> ts = new java.util.LinkedHashSet<Integer>();
                            if (auto > 2) ts.add(auto - 1);
                            ts.add(Math.min(cores, auto + 2));
                            ts.add(cores);
                            ts.remove(auto);
                            for (int tt : ts) cands.add(new int[]{bestCpuA, tt});
                        }
                    }
                    if (bestMs == Long.MAX_VALUE) throw new IllegalStateException("ни один вариант не сработал");
                    prefs.edit().putInt("accel", bestA).putInt("threads", bestT == auto ? 0 : bestT)
                            .putBoolean("accel_chosen", true).apply();
                    rep.append(String.format(java.util.Locale.ROOT, "\nВыбрано: %s, потоков %d — %.2f с на фото",
                            ACCEL_NAMES[bestA], bestT, bestMs / 1000.0));
                    post(cb, rep.toString(), null);
                } catch (Throwable e) {
                    rep.append("\nОшибка: ").append(e.getMessage() != null ? e.getMessage() : e.toString());
                    post(cb, rep.toString(), null);
                }
                loadModel();
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
        final long target = (long) photoBudget() * 9 * 256;
        return decoder.submit(new Callable<Bitmap>() {
            @Override
            public Bitmap call() throws Exception {
                return Media.decodeForIndex(ctx.getContentResolver(), e.uri, e.orientation, target);
            }
        });
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
                    synchronized (queue) {
                        queue.clear();
                        if (photoLimit > 0 && model.supportsImages()) {
                            for (Media.Entry e : Media.recentImages(cr, photoLimit)) {
                                if (!store.hasMedia(e.kind, e.id)) queue.add(e);
                            }
                        }
                        if (videoLimit > 0 && model.supportsVideo()) {
                            for (Media.Entry e : Media.recentVideos(cr, videoLimit)) {
                                if (!store.hasMedia(e.kind, e.id)) queue.add(e);
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

    private final Runnable indexStep = new Runnable() {
        @Override
        public void run() {
            Media.Entry e;
            synchronized (queue) {
                if (cancelIndex || queue.isEmpty() || model == null) {
                    if (prefetch != null) prefetch.cancel(false);
                    prefetch = null;
                    prefetchEntry = null;
                    String err = timingSplit() + (idxFirstError != null ? "\nПервая ошибка: " + idxFirstError : "");
                    finishIndex((cancelIndex ? "Остановлено: " + idxDone + " из " + idxTotal
                            : "Готово: " + (idxDone - idxErrors) + " файлов" + (idxErrors > 0 ? ", пропущено " + idxErrors : ""))
                            + err);
                    return;
                }
                e = queue.remove(0);
            }
            try {
                float[] emb;
                if (e.kind == IndexStore.KIND_PHOTO) {
                    long w0 = System.currentTimeMillis();
                    Future<Bitmap> mine = prefetchEntry == e && prefetch != null ? prefetch : decodeAsync(e);
                    prefetch = null;
                    prefetchEntry = null;
                    synchronized (queue) {
                        if (!queue.isEmpty() && queue.get(0).kind == IndexStore.KIND_PHOTO) {
                            prefetchEntry = queue.get(0);
                        }
                    }
                    Bitmap b;
                    try {
                        b = mine.get();
                    } catch (ExecutionException ex) {
                        if (prefetchEntry != null) prefetch = decodeAsync(prefetchEntry);
                        throw ex.getCause();
                    }
                    long waited = System.currentTimeMillis() - w0;
                    if (prefetchEntry != null) prefetch = decodeAsync(prefetchEntry);
                    try {
                        emb = embedPhoto(b);
                    } finally {
                        b.recycle();
                    }
                    long[] tm = model.lastTimingsMs();
                    sumWaitMs += waited;
                    sumVisionMs += tm[0];
                    sumTextMs += tm[1];
                    timedPhotos++;
                } else {
                    List<Bitmap> frames = Media.videoFrames(ctx, e.uri, VIDEO_FRAMES, 640);
                    try {
                        List<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source> src =
                                new ArrayList<io.github.teoplaydor.semsearch.core.ImagePreprocessor.Source>();
                        for (Bitmap f : frames) src.add(new Media.BitmapSource(f));
                        emb = model.embedVideo(src, 0);
                    } finally {
                        for (Bitmap f : frames) f.recycle();
                    }
                }
                store.add(e.kind, e.id, e.uri.toString(), e.name, null, e.date, emb);
                idxDone++;
            } catch (Throwable t) {
                idxErrors++;
                idxDone++;
                if (idxFirstError == null) {
                    idxFirstError = (e.name != null ? e.name + ": " : "") + (t.getMessage() != null ? t.getMessage() : t.toString());
                    android.util.Log.e("SemSearch", "index " + e.uri, t);
                }
            }
            idxProcessed++;
            long spent = System.currentTimeMillis() - idxStarted;
            double per = spent / 1000.0 / Math.max(1, idxProcessed);
            int left = idxTotal - idxDone;
            String split = timingSplit();
            idxStatus = String.format(java.util.Locale.ROOT, "%d из %d · %.1f с на файл · осталось ~%s",
                    idxDone, idxTotal, per, eta((long) (per * left))) + split;
            notifyChanged();
            ml.submit(this);
        }
    };

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
        idxStatus = msg;
        notifyChanged();
    }

    public void stopIndex() {
        cancelIndex = true;
    }
}
