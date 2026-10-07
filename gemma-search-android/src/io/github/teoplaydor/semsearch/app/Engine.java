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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.teoplaydor.semsearch.core.EmbeddingGemma2;
import io.github.teoplaydor.semsearch.core.HfRepo;

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
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    final SharedPreferences prefs;
    final File modelDir;
    private final File manifest;

    private IndexStore store;
    private EmbeddingGemma2 model;

    public volatile State state = State.NO_MODEL;
    public volatile String status = "";
    public volatile long dlDone, dlTotal;
    private volatile boolean cancelDownload;

    public volatile boolean indexing;
    public volatile int idxDone, idxTotal, idxErrors;
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
        modelDir = new File(ctx.getFilesDir(), "model");
        manifest = new File(modelDir, "manifest.json");
        ml.submit(new Runnable() {
            @Override
            public void run() {
                store = new IndexStore(Engine.this.ctx);
                notifyChanged();
            }
        });
        if (manifest.exists()) loadModel();
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

    public void loadModel() {
        state = State.LOADING;
        status = "Загружаю модель в память…";
        notifyChanged();
        ml.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    HfRepo.Plan plan = HfRepo.loadManifest(manifest);
                    if (plan == null || !HfRepo.isComplete(plan, modelDir)) {
                        state = State.NO_MODEL;
                        status = "Модель не скачана полностью";
                        notifyChanged();
                        return;
                    }
                    long t0 = System.currentTimeMillis();
                    int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
                    File vision = plan.visionModel == null ? null : new File(modelDir, plan.visionModel);
                    model = new EmbeddingGemma2(modelDir, new File(modelDir, plan.textModel), vision, threads);
                    // Warm-up run (first inference allocates buffers) and embedding size.
                    model.embedQuery("привет");
                    String sig = HfRepo.manifestRepo(manifest) + "|" + plan.textModel + "|" + plan.visionModel;
                    if (!sig.equals(prefs.getString("model_sig", ""))) {
                        // Different weights: old vectors are not comparable.
                        store.clearMedia();
                        for (IndexStore.Item n : store.notes()) store.updateEmbedding(n, model.embedDocument(n.body));
                        prefs.edit().putString("model_sig", sig).apply();
                    }
                    state = State.READY;
                    status = "Модель готова (" + (System.currentTimeMillis() - t0) / 100 / 10.0 + " с), "
                            + model.embeddingDim() + " изм., потоков: " + threads
                            + (model.supportsImages() ? "" : " · только текст");
                } catch (Throwable e) {
                    model = null;
                    state = State.ERROR;
                    status = "Не удалось загрузить модель: " + e;
                }
                notifyChanged();
            }
        });
    }

    private void unloadModel() {
        final EmbeddingGemma2 m = model;
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
                    float[] q = model.embedQuery(query);
                    SearchResult r = new SearchResult();
                    r.hits = store.search(q, searchDims(), photos, videos, notes, 90, -1);
                    r.millis = System.currentTimeMillis() - t0;
                    r.label = "«" + query + "»";
                    post(cb, r, null);
                } catch (Exception e) {
                    post(cb, null, e);
                }
            }
        });
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
            if (budget == model.config().image.maxSoftTokens) throw e;
            prefs.edit().putInt("photo_detail", PHOTO_BUDGETS.length - 1).apply();
            return model.embedImage(new Media.BitmapSource(b), 0);
        }
    }

    public void startIndex(final int photoLimit, final int videoLimit) {
        if (indexing) return;
        indexing = true;
        cancelIndex = false;
        idxDone = 0;
        idxErrors = 0;
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
                    finishIndex(cancelIndex ? "Остановлено: " + idxDone + " из " + idxTotal
                            : "Готово: " + idxDone + " файлов" + (idxErrors > 0 ? ", пропущено " + idxErrors : ""));
                    return;
                }
                e = queue.remove(0);
            }
            try {
                float[] emb;
                if (e.kind == IndexStore.KIND_PHOTO) {
                    Bitmap b = Media.decode(ctx.getContentResolver(), e.uri, e.orientation,
                            (long) photoBudget() * 9 * 256);
                    try {
                        emb = embedPhoto(b);
                    } finally {
                        b.recycle();
                    }
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
            }
            idxProcessed++;
            long spent = System.currentTimeMillis() - idxStarted;
            double per = spent / 1000.0 / Math.max(1, idxProcessed);
            int left = idxTotal - idxDone;
            idxStatus = String.format(java.util.Locale.ROOT, "%d из %d · %.1f с на файл · осталось ~%s",
                    idxDone, idxTotal, per, eta((long) (per * left)));
            notifyChanged();
            ml.submit(this);
        }
    };

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
