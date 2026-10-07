package io.github.teoplaydor.semsearch.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app is a gallery: recent photos by default, the same staggered grid for search results.
 * Settings live in a panel, the viewer opens over the grid, notes are one tab of the gallery.
 */
public final class MainActivity extends Activity implements Engine.Listener, Viewer.Host, MasonryView.Host {
    private static final int REQ_PICK_IMAGE = 7, REQ_MEDIA = 8;
    private static final int RECENT_LIMIT = 3000;
    private static final String[] FILTERS = {"Все", "Фото", "Видео", "Заметки"};

    /** Test hook: thumbnails for items that have no MediaStore entry (screenshots on Robolectric). */
    public interface BitmapLoader {
        Bitmap load(IndexStore.Item it, int size);
    }

    public static volatile BitmapLoader testLoader;

    private Engine engine;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private EditText query;
    private ImageView clear;
    private Segments segments;
    private int filter;
    private ProgressLine progress;
    private TextView status, section;
    private MasonryView gallery;
    private FrameLayout empty;
    private View fab;
    private Viewer viewer;
    private SettingsPanel settings;
    private Sheet sheet;

    /** What the grid shows: the recent gallery, or results of the last search (label). */
    private String resultsLabel;
    private Intent pendingShare;
    private int lastIndexed = -1;
    private long lastRefreshMs;
    private Engine.State lastState;
    private final Runnable debounced = new Runnable() {
        @Override
        public void run() {
            String q = query.getText().toString().trim();
            if (q.length() >= 2) runSearch(false);
        }
    };

    /** Picture proportions from MediaStore (see Media.aspects); null until the first load. */
    private volatile Map<Long, Float> aspects;
    private boolean aspectsLoading, aspectsMissing, recentWaits, recentAnimate;
    private long aspectsLoadedMs;
    private final Runnable aspectsRefresh = new Runnable() {
        @Override
        public void run() {
            loadAspects();
        }
    };

    private final ExecutorService thumbPool = Executors.newFixedThreadPool(3);
    private final LruCache<Long, Bitmap> thumbs = new LruCache<Long, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 5, 160L << 20)) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        engine = Engine.get(this);
        setContentView(buildRoot());
        engine.addListener(this);
        engine.ensureLoaded();
        if (AutoIndex.hasMediaAccess(this)) AutoIndex.schedule(this);
        handleIntent(getIntent());
        onEngineChanged();
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine.uiVisible = true;
        engine.ensureLoaded();
    }

    @Override
    protected void onStop() {
        engine.uiVisible = false;
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        engine.removeListener(this);
        thumbPool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (sheet != null && sheet.getParent() != null && !sheet.isClosing()) {
            sheet.dismiss();
            return;
        }
        for (int i = root.getChildCount() - 1; i > 0; i--) {
            View top = root.getChildAt(i);
            if (top instanceof Sheet && !((Sheet) top).isClosing()) {
                ((Sheet) top).dismiss();
                return;
            }
        }
        if (viewer != null && !viewer.isClosing()) {
            viewer.close();
            return;
        }
        if (settings != null && !settings.isClosing()) {
            settings.close();
            return;
        }
        if (query.getText().length() > 0 || resultsLabel != null) {
            query.setText("");
            showRecent(true);
            return;
        }
        super.onBackPressed();
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        if (!engine.ready()) {
            pendingShare = intent;
            toast("Модель ещё загружается — поиск начнётся сам");
            return;
        }
        setIntent(new Intent());
        String type = intent.getType();
        if (type != null && type.startsWith("image/")) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) runImageSearch(uri);
        } else {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                query.setText(text);
                runSearch(true);
            }
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ------------------------------------------------------------------ layout

    private View buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.setFocusableInTouchMode(true); // keeps the search field from grabbing focus on start
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));

        // search pill + settings
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(12), dp(14), dp(8));
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.round(this, Ui.SURFACE2, 26));
        pill.setPadding(dp(14), 0, dp(6), 0);
        pill.addView(Ui.icon(this, Icon.SEARCH, Ui.TEXT3, 22), new LinearLayout.LayoutParams(dp(22), dp(22)));
        query = new EditText(this);
        query.setHint("Найти по смыслу");
        query.setSingleLine(true);
        query.setTextColor(Ui.TEXT);
        query.setHintTextColor(Ui.TEXT3);
        query.setTextSize(16);
        query.setTypeface(Ui.font(this, Ui.REGULAR));
        query.setBackground(null);
        query.setPadding(dp(10), 0, dp(4), 0);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
                runSearch(true);
                return true;
            }
        });
        query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                boolean has = s.length() > 0;
                if (has && clear.getVisibility() != View.VISIBLE) Ui.fadeIn(clear, 150);
                if (!has && clear.getVisibility() == View.VISIBLE) Ui.fadeOut(clear, 150);
                ui.removeCallbacks(debounced);
                if (has) ui.postDelayed(debounced, 550);
                else if (resultsLabel != null) showRecent(true);
            }
        });
        pill.addView(query, new LinearLayout.LayoutParams(0, dp(52), 1));
        clear = Ui.icon(this, Icon.CLOSE, Ui.TEXT2, 40);
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                query.setText("");
                hideKeyboard();
                showRecent(true);
            }
        });
        pill.addView(clear, new LinearLayout.LayoutParams(dp(40), dp(40)));
        ImageView byPhoto = Ui.icon(this, Icon.IMAGE, Ui.TEXT2, 40);
        byPhoto.setContentDescription("Найти по фото");
        byPhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Найти похожие на фото"), REQ_PICK_IMAGE);
                } catch (Exception e) {
                    toast("Нет приложения для выбора фото");
                }
            }
        });
        pill.addView(byPhoto, new LinearLayout.LayoutParams(dp(40), dp(40)));
        head.addView(pill, new LinearLayout.LayoutParams(0, dp(52), 1));
        ImageView gear = Ui.icon(this, Icon.TUNE, Ui.TEXT, 52);
        gear.setContentDescription("Настройки");
        gear.setBackground(Ui.round(this, Ui.SURFACE2, 26));
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        });
        Ui.pressable(gear);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(52), dp(52));
        gl.leftMargin = dp(10);
        head.addView(gear, gl);
        screen.addView(head);

        segments = new Segments(this, FILTERS);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(40));
        sl.setMargins(dp(14), dp(2), dp(14), dp(6));
        screen.addView(segments, sl);

        // status: thin progress + one quiet line, only while something runs
        progress = new ProgressLine(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(2));
        pl.setMargins(dp(18), dp(6), dp(18), 0);
        screen.addView(progress, pl);
        status = Ui.text(this, "", 12.5f, Ui.TEXT3, Ui.REGULAR);
        status.setPadding(dp(18), dp(6), dp(18), dp(8));
        status.setVisibility(View.GONE);
        screen.addView(status);

        section = Ui.text(this, "", 13, Ui.TEXT2, Ui.MEDIUM);
        section.setPadding(dp(18), dp(10), dp(18), dp(10));
        section.setSingleLine(true);
        section.setEllipsize(TextUtils.TruncateAt.END);
        screen.addView(section);

        FrameLayout area = new FrameLayout(this);
        gallery = new MasonryView(this, this);
        area.addView(gallery, new FrameLayout.LayoutParams(-1, -1));
        empty = new FrameLayout(this);
        area.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        fab = Ui.icon(this, Icon.PLUS, Ui.ON_ACCENT, 58);
        fab.setBackground(Ui.round(this, Ui.ACCENT, 29));
        fab.setElevation(dp(6));
        fab.setContentDescription("Новая заметка");
        fab.setVisibility(View.GONE);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                noteEditor();
            }
        });
        Ui.pressable(fab);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM | Gravity.END);
        fl.setMargins(0, 0, dp(22), dp(26));
        area.addView(fab, fl);
        screen.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        if (f != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
            f.clearFocus();
        }
    }

    /** Segmented filter with a highlight that glides to the chosen item. */
    final class Segments extends LinearLayout {
        final TextView[] labels;
        private final android.graphics.Paint hl = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private float pos;
        private android.animation.ValueAnimator glide;

        Segments(Context c, String[] names) {
            super(c);
            setWillNotDraw(false);
            setBackground(Ui.round(c, Ui.SURFACE, 20));
            setPadding(dp(4), dp(4), dp(4), dp(4));
            hl.setColor(Ui.ACCENT_SOFT);
            labels = new TextView[names.length];
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                TextView t = Ui.text(c, names[i], 13.5f, i == 0 ? Ui.TEXT : Ui.TEXT2, Ui.MEDIUM);
                t.setGravity(Gravity.CENTER);
                t.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectFilter(idx);
                    }
                });
                labels[i] = t;
                addView(t, new LinearLayout.LayoutParams(0, -1, 1));
            }
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas c) {
            float cell = (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) labels.length;
            float left = getPaddingLeft() + cell * pos;
            r.set(left, getPaddingTop(), left + cell, getHeight() - getPaddingBottom());
            float rad = r.height() / 2;
            c.drawRoundRect(r, rad, rad, hl);
            super.dispatchDraw(c);
        }

        void select(int idx, boolean animate) {
            if (glide != null) glide.cancel();
            if (animate) {
                glide = android.animation.ValueAnimator.ofFloat(pos, idx);
                glide.setDuration(280);
                glide.setInterpolator(Ui.EASE);
                glide.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        pos = (Float) a.getAnimatedValue();
                        invalidate();
                    }
                });
                glide.start();
            } else {
                pos = idx;
                invalidate();
            }
            for (int i = 0; i < labels.length; i++) labels[i].setTextColor(i == idx ? Ui.TEXT : Ui.TEXT2);
        }
    }

    private void selectFilter(int idx) {
        if (idx == filter) return;
        filter = idx;
        segments.select(idx, true);
        if (fab != null) {
            if (idx == 3) Ui.fadeIn(fab, 200);
            else Ui.fadeOut(fab, 150);
        }
        if (resultsLabel != null && query.getText().toString().trim().length() > 0) runSearch(false);
        else showRecent(true);
    }

    private boolean photos() {
        return filter == 0 || filter == 1;
    }

    private boolean videos() {
        return filter == 0 || filter == 2;
    }

    private boolean notes() {
        return filter == 0 || filter == 3;
    }

    // ------------------------------------------------------------------ content

    private void showRecent(boolean animate) {
        resultsLabel = null;
        if (aspects == null) {
            // lay the gallery out once picture proportions are known, so nothing jumps
            recentWaits = true;
            recentAnimate |= animate;
            loadAspects();
            return;
        }
        IndexStore s = engine.store();
        List<IndexStore.Item> list = s == null ? new ArrayList<IndexStore.Item>()
                : s.recent(filter != 2 && filter != 3, filter == 0 || filter == 2, filter == 3, RECENT_LIMIT);
        setItems(list, animate);
        section.setVisibility(View.GONE); // the gallery speaks for itself; the line is for search results
        updateEmpty();
    }

    /** The quiet line above search results. */
    private void sectionText(String text) {
        if (section.getVisibility() != View.VISIBLE) {
            section.setText(text);
            Ui.fadeIn(section, 180);
        } else {
            Ui.setTextSoft(section, text);
        }
    }

    private void setItems(List<IndexStore.Item> list, boolean animate) {
        aspectsMissing = false;
        gallery.setItems(list, filter == 3 ? 2 : 3, animate);
        if (aspectsMissing) {
            // new photos since the last MediaStore read: fetch their proportions (at most every few seconds)
            long wait = aspectsLoadedMs + 4000 - System.currentTimeMillis();
            ui.removeCallbacks(aspectsRefresh);
            if (wait <= 0) loadAspects();
            else ui.postDelayed(aspectsRefresh, wait);
        }
    }

    private void loadAspects() {
        if (aspectsLoading) return;
        aspectsLoading = true;
        final List<IndexStore.Item> current = gallery.items();
        thumbPool.submit(new Runnable() {
            @Override
            public void run() {
                final Map<Long, Float> m = Media.aspects(getContentResolver());
                // what MediaStore has no size for stays square; don't ask again for it
                for (IndexStore.Item it : current) {
                    long k = Media.aspectKey(it.kind, it.mediaId);
                    if (it.kind != IndexStore.KIND_NOTE && !m.containsKey(k)) m.put(k, 0f);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        aspects = m;
                        aspectsLoading = false;
                        aspectsLoadedMs = System.currentTimeMillis();
                        if (recentWaits) {
                            recentWaits = false;
                            boolean animate = recentAnimate;
                            recentAnimate = false;
                            showRecent(animate);
                        } else {
                            gallery.relayout();
                        }
                    }
                });
            }
        });
    }

    private void runSearch(boolean fromKeyboard) {
        final String q = query.getText().toString().trim();
        if (q.isEmpty()) return;
        if (!engine.ready()) {
            if (fromKeyboard) toast(engine.hasModelFiles() ? "Модель ещё загружается" : "Сначала скачайте модель");
            return;
        }
        if (fromKeyboard) hideKeyboard();
        ui.removeCallbacks(debounced);
        sectionText("Ищу…");
        engine.search(q, photos(), videos(), notes(), new Engine.Callback<Engine.SearchResult>() {
            @Override
            public void done(Engine.SearchResult r, Exception e) {
                if (!q.equals(query.getText().toString().trim())) return; // typed on: a newer search follows
                showResults(r, e, "«" + q + "»");
            }
        });
    }

    private void runImageSearch(Uri uri) {
        if (!engine.supportsImages()) {
            toast("Для поиска по фото нужен визуальный энкодер");
            return;
        }
        sectionText("Смотрю на фото…");
        engine.searchByImage(uri, photos(), videos(), notes(), new Engine.Callback<Engine.SearchResult>() {
            @Override
            public void done(Engine.SearchResult r, Exception e) {
                showResults(r, e, "Похожие на выбранное фото");
            }
        });
    }

    private void runSimilar(IndexStore.Item item) {
        sectionText("Ищу похожие…");
        engine.similar(item, photos(), videos(), notes(), new Engine.Callback<Engine.SearchResult>() {
            @Override
            public void done(Engine.SearchResult r, Exception e) {
                showResults(r, e, "Похожие");
            }
        });
    }

    private void showResults(Engine.SearchResult r, Exception e, String label) {
        if (e != null) {
            sectionText("Не получилось: " + e.getMessage());
            return;
        }
        List<IndexStore.Item> list = new ArrayList<IndexStore.Item>();
        for (IndexStore.Hit h : r.hits) list.add(h.item);
        resultsLabel = label;
        setItems(list, true);
        sectionText(list.isEmpty() ? label : label + " · " + list.size());
        updateEmpty();
    }

    // ------------------------------------------------------------------ empty states

    private void updateEmpty() {
        empty.removeAllViews();
        Engine.State st = engine.state;
        boolean noModel = !engine.hasModelFiles() || (st == Engine.State.NO_MODEL && !engine.hasModelFiles());
        if (st == Engine.State.DOWNLOADING || (noModel && st != Engine.State.LOADING)) {
            welcome();
        } else if (st == Engine.State.ERROR) {
            card(Icon.INFO, "Модель не загрузилась", engine.status, "Повторить", new Runnable() {
                @Override
                public void run() {
                    engine.loadModel();
                }
            }, "Скопировать подробности", new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("SemSearch error", String.valueOf(engine.errorDetails)));
                    toast("Скопировано — вставьте в чат");
                }
            });
        } else if (!gallery.items().isEmpty()) {
            empty.setVisibility(View.GONE);
            return;
        } else if (resultsLabel != null) {
            card(Icon.SEARCH, "Ничего не нашлось", "Попробуйте другими словами или уберите фильтр", null, null, null, null);
        } else if (filter == 3) {
            card(Icon.NOTE, "Заметок пока нет", "Запишите что угодно — найдётся по смыслу, не по словам", "Новая заметка",
                    new Runnable() {
                        @Override
                        public void run() {
                            noteEditor();
                        }
                    }, null, null);
        } else if (st == Engine.State.LOADING) {
            card(Icon.SIMILAR, "Загружаю модель…", null, null, null, null, null);
        } else if (!engine.indexing) {
            card(Icon.IMAGE, "Галерея ещё не проиндексирована", "Это нужно один раз — дальше новые фото добавляются сами",
                    "Начать", new Runnable() {
                        @Override
                        public void run() {
                            requestMediaAndIndex();
                        }
                    }, null, null);
        } else {
            card(Icon.IMAGE, "Индексирую галерею", "Фото появятся здесь по мере обработки", null, null, null, null);
        }
        if (empty.getVisibility() != View.VISIBLE) Ui.fadeIn(empty, 220);
    }

    private void welcome() {
        boolean dl = engine.state == Engine.State.DOWNLOADING;
        String sub = dl ? (engine.dlTotal > 0 ? String.format(Locale.ROOT, "Скачиваю модель · %.0f из %.0f МБ",
                engine.dlDone / 1048576.0, engine.dlTotal / 1048576.0) : "Подключаюсь…")
                : "Напишите «кот на диване» или «чек из кафе» — фото найдутся без тегов. Модель работает на телефоне, без интернета.";
        card(Icon.SIMILAR, "Поиск по смыслу", sub, dl ? "Остановить" : "Скачать модель", new Runnable() {
            @Override
            public void run() {
                if (engine.state == Engine.State.DOWNLOADING) engine.cancelDownload();
                else downloadModel();
            }
        }, dl ? null : "Источник и настройки", dl ? null : new Runnable() {
            @Override
            public void run() {
                openSettings();
            }
        });
    }

    private void card(int icon, String title, String text, String action, final Runnable onAction, String quiet, final Runnable onQuiet) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(32), 0, dp(32), dp(60));
        ImageView ic = Ui.icon(this, icon, Ui.ACCENT, 72, 2.2f);
        ic.setBackground(Ui.round(this, Ui.ACCENT_SOFT, 36));
        box.addView(ic, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView t = Ui.text(this, title, 21, Ui.TEXT, Ui.SEMIBOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(22), 0, 0);
        box.addView(t);
        if (text != null && !text.isEmpty()) {
            TextView s = Ui.text(this, text, 14.5f, Ui.TEXT2, Ui.REGULAR);
            s.setGravity(Gravity.CENTER);
            s.setLineSpacing(0, 1.3f);
            s.setPadding(0, dp(10), 0, 0);
            box.addView(s);
        }
        if (engine.state == Engine.State.DOWNLOADING && action != null) {
            ProgressLine p = new ProgressLine(this);
            p.setIndeterminate(engine.dlTotal <= 0);
            if (engine.dlTotal > 0) p.setProgress((float) engine.dlDone / engine.dlTotal);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(3));
            pl.setMargins(dp(24), dp(22), dp(24), 0);
            box.addView(p, pl);
        }
        if (action != null) {
            TextView b = Sheet.button(this, action, engine.state != Engine.State.DOWNLOADING);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onAction.run();
                }
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, dp(50));
            bl.topMargin = dp(26);
            b.setMinWidth(dp(200));
            box.addView(b, bl);
        }
        if (quiet != null) {
            TextView q = Ui.text(this, quiet, 14, Ui.TEXT2, Ui.MEDIUM);
            q.setGravity(Gravity.CENTER);
            q.setPadding(dp(12), dp(16), dp(12), dp(8));
            q.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onQuiet.run();
                }
            });
            box.addView(q, new LinearLayout.LayoutParams(-2, -2));
        }
        empty.addView(box, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
    }

    // ------------------------------------------------------------------ engine → UI

    @Override
    public void onEngineChanged() {
        Engine.State st = engine.state;
        boolean dl = st == Engine.State.DOWNLOADING, loading = st == Engine.State.LOADING;
        // status line: only while something is running
        String line = null;
        if (engine.indexing) {
            line = engine.idxTotal > 0 ? String.format(Locale.ROOT, "Индексирую · %d из %d", engine.idxDone, engine.idxTotal)
                    : "Ищу новые фото…";
            progress.setIndeterminate(engine.idxTotal == 0);
            if (engine.idxTotal > 0) progress.setProgress((float) engine.idxDone / engine.idxTotal);
        } else if (dl && gallery.items().size() > 0) {
            line = "Скачиваю модель";
            progress.setIndeterminate(engine.dlTotal <= 0);
            if (engine.dlTotal > 0) progress.setProgress((float) engine.dlDone / engine.dlTotal);
        } else if (loading && gallery.items().size() > 0) {
            line = "Загружаю модель…";
            progress.setIndeterminate(true);
        }
        if (line != null) {
            Ui.setTextSoft(status, line);
            if (status.getVisibility() != View.VISIBLE) Ui.fadeIn(status, 200);
            if (progress.getVisibility() != View.VISIBLE) Ui.fadeIn(progress, 200);
        } else {
            Ui.fadeOut(status, 250);
            Ui.fadeOut(progress, 250);
        }
        // the gallery grows while indexing; refresh it now and then without jumping around
        IndexStore s = engine.store();
        int indexed = s == null ? 0 : s.count(IndexStore.KIND_PHOTO) + s.count(IndexStore.KIND_VIDEO) + s.count(IndexStore.KIND_NOTE);
        long now = System.currentTimeMillis();
        if (resultsLabel == null && indexed != lastIndexed && (lastIndexed < 0 || !engine.indexing || now - lastRefreshMs > 1500)) {
            boolean first = lastIndexed <= 0;
            lastIndexed = indexed;
            lastRefreshMs = now;
            showRecent(first);
        } else if (st != lastState || dl) {
            updateEmpty();
        }
        lastState = st;
        if (engine.indexing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (pendingShare != null && engine.ready()) {
            Intent i = pendingShare;
            pendingShare = null;
            handleIntent(i);
        }
    }

    // ------------------------------------------------------------------ actions used by panels

    void downloadModel() {
        engine.download(engine.repo(), engine.prefs().getString("token", ""), engine.prefs().getBoolean("vision", true));
    }

    void requestMediaAndIndex() {
        if (!engine.ready()) {
            toast(engine.hasModelFiles() ? "Модель ещё загружается" : "Сначала скачайте модель");
            return;
        }
        if (!engine.supportsImages()) {
            toast("Модель без визуального энкодера — включите «Фото и видео» в источнике и скачайте его");
            return;
        }
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33) {
            need.add("android.permission.READ_MEDIA_IMAGES");
            need.add("android.permission.READ_MEDIA_VIDEO");
            if (Build.VERSION.SDK_INT >= 34) need.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
        } else {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        List<String> missing = new ArrayList<String>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty() || AutoIndex.hasMediaAccess(this)) {
            if (!missing.isEmpty() && Build.VERSION.SDK_INT >= 34) {
                requestPermissions(missing.toArray(new String[0]), REQ_MEDIA); // offer to widen partial access
                return;
            }
            startIndexing();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQ_MEDIA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_MEDIA) return;
        if (AutoIndex.hasMediaAccess(this)) startIndexing();
        else toast("Без доступа к галерее индексировать нечего");
    }

    private void startIndexing() {
        AutoIndex.schedule(this);
        engine.startIndexFromPrefs(false);
    }

    void runBenchmark() {
        if (!engine.ready()) {
            toast("Сначала скачайте модель");
            return;
        }
        if (engine.indexing) {
            toast("Остановите индексацию — замер идёт на том же процессоре");
            return;
        }
        toast("Замеряю варианты… телефон может нагреться");
        engine.benchmark(new Engine.Callback<String>() {
            @Override
            public void done(final String report, Exception e) {
                showReport("Скорость индексации", report != null ? report : String.valueOf(e));
            }
        });
    }

    void runDiagnostics() {
        if (!engine.ready()) {
            toast("Сначала скачайте модель");
            return;
        }
        toast("Считаю… несколько секунд");
        engine.diagnose(new Engine.Callback<String>() {
            @Override
            public void done(String report, Exception e) {
                showReport("Качество поиска", report != null ? report : String.valueOf(e));
            }
        });
    }

    private void showReport(String title, final String report) {
        sheet = Sheet.message(root, title, report, "Скопировать", new Runnable() {
            @Override
            public void run() {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("SemSearch", report));
                toast("Скопировано — вставьте в чат");
            }
        });
    }

    private void openSettings() {
        hideKeyboard();
        if (settings != null) return;
        settings = new SettingsPanel(this);
        settings.open(root);
    }

    void settingsClosed() {
        settings = null;
    }

    private void noteEditor() {
        if (!engine.ready()) {
            toast("Сначала скачайте модель");
            return;
        }
        final Sheet s = new Sheet(this, "Новая заметка");
        final EditText t = new EditText(this);
        t.setHint("Например: пароль от Wi-Fi на даче — на холодильнике");
        t.setTextColor(Ui.TEXT);
        t.setHintTextColor(Ui.TEXT3);
        t.setTextSize(16);
        t.setTypeface(Ui.font(this, Ui.REGULAR));
        t.setMinLines(4);
        t.setGravity(Gravity.TOP | Gravity.START);
        t.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        t.setBackground(Ui.round(this, Ui.SURFACE2, 18));
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        s.body().addView(t, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(14), 0, 0);
        TextView samples = Sheet.button(this, "Примеры", false);
        samples.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addSamples();
                s.dismiss();
            }
        });
        TextView save = Sheet.button(this, "Сохранить", true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = t.getText().toString().trim();
                if (text.isEmpty()) return;
                engine.addNote(text, new Engine.Callback<IndexStore.Item>() {
                    @Override
                    public void done(IndexStore.Item it, Exception e) {
                        if (e != null) toast("Ошибка: " + e.getMessage());
                        else if (resultsLabel == null) showRecent(true);
                    }
                });
                hideKeyboard();
                s.dismiss();
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(50), 1);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(50), 2);
        l2.leftMargin = dp(10);
        row.addView(samples, l1);
        row.addView(save, l2);
        s.body().addView(row);
        sheet = s.show(root);
        t.requestFocus();
    }

    private void addSamples() {
        String[] notes = {
                "Пароль от домашнего Wi-Fi: Lisa2024! — сеть Keenetic-5G",
                "Записаться к стоматологу на пятницу, 10:30, клиника на Ленина 12",
                "Купить: молоко, хлеб, яйца, сыр, кофе в зёрнах",
                "Идея подарка маме: кашемировый шарф или сертификат в спа",
                "Рецепт сырников: 500 г творога, 2 яйца, 3 ложки муки, ваниль",
                "Код домофона у Саши: 47К1290",
                "Созвон с командой в четверг в 15:00 — обсудить бюджет проекта",
                "Книги на лето: «Мастер и Маргарита», «Дюна», «Сто лет одиночества»",
                "Машина: заменить масло через 2000 км, зимние шины — в ноябре",
                "Rome trip: hotel near the Trevi fountain, check-in May 3",
        };
        for (String n : notes) engine.addNote(n, new Engine.Callback<IndexStore.Item>() {
            @Override
            public void done(IndexStore.Item r, Exception e) {
                if (resultsLabel == null && filter == 3) showRecent(false);
            }
        });
        toast("Добавляю примеры… Попробуйте «как зайти в интернет дома»");
    }

    // ------------------------------------------------------------------ viewer

    private void openViewer(int pos) {
        if (viewer != null) return;
        hideKeyboard();
        viewer = new Viewer(this, this, new ArrayList<IndexStore.Item>(gallery.items()), pos);
        viewer.open(root);
    }

    @Override
    public Bitmap thumb(IndexStore.Item it) {
        return thumbs.get(it.id);
    }

    @Override
    public void loadFull(final IndexStore.Item it, final Engine.Callback<Bitmap> cb) {
        final int px = getResources().getDisplayMetrics().widthPixels * getResources().getDisplayMetrics().heightPixels;
        thumbPool.submit(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    BitmapLoader t = testLoader;
                    b = t != null ? t.load(it, 1600) : Media.full(getContentResolver(), it, Math.min(16_000_000L, px * 2L));
                } catch (Throwable ignored) {
                    // keep the thumbnail
                }
                final Bitmap r = b;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(r, null);
                    }
                });
            }
        });
    }

    @Override
    public Rect tileRect(IndexStore.Item it) {
        int pos = gallery.items().indexOf(it);
        // closing on another photo than the one opened: bring its tile into view (the viewer still covers the grid)
        if (viewer != null && viewer.isClosing()) gallery.reveal(pos);
        return gallery.tileRect(pos);
    }

    @Override
    public void similar(IndexStore.Item it) {
        if (viewer != null) viewer.close();
        runSimilar(it);
    }

    @Override
    public void share(IndexStore.Item it) {
        Intent i = new Intent(Intent.ACTION_SEND);
        if (it.kind == IndexStore.KIND_NOTE) {
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, it.body);
        } else {
            i.setType(it.kind == IndexStore.KIND_VIDEO ? "video/*" : "image/*");
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse(it.uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            startActivity(Intent.createChooser(i, "Поделиться"));
        } catch (Exception e) {
            toast("Некуда отправить");
        }
    }

    @Override
    public void openWith(IndexStore.Item it) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(it.uri), it.kind == IndexStore.KIND_VIDEO ? "video/*" : "image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Открыть в…"));
        } catch (Exception e) {
            toast("Нет подходящего приложения");
        }
    }

    @Override
    public void delete(final IndexStore.Item it) {
        sheet = Sheet.confirm(root, "Удалить заметку?", null, "Удалить", new Runnable() {
            @Override
            public void run() {
                engine.deleteItem(it);
                if (viewer != null) viewer.close();
                List<IndexStore.Item> left = new ArrayList<IndexStore.Item>(gallery.items());
                left.remove(it);
                gallery.setItems(left, filter == 3 ? 2 : 3, false);
                updateEmpty();
            }
        });
    }

    @Override
    public void viewerClosed() {
        viewer = null;
    }

    // ------------------------------------------------------------------ gallery

    @Override
    public float aspect(IndexStore.Item it) {
        Map<Long, Float> m = aspects;
        Float a = m == null ? null : m.get(Media.aspectKey(it.kind, it.mediaId));
        if (a == null) {
            aspectsMissing = true;
            return 0f;
        }
        return a;
    }

    @Override
    public void bindThumb(final MasonryView.Tile tile, final IndexStore.Item it, int w, int h) {
        if (tile.key == it.id && tile.img.bitmap() != null) return;
        tile.key = it.id;
        Bitmap cached = thumbs.get(it.id);
        tile.img.animate().cancel();
        tile.img.setAlpha(1f);
        tile.img.setImageBitmap(cached);
        if (cached != null) return;
        final int size = Math.max(160, Math.min(960, Math.max(w, h)));
        thumbPool.submit(new Runnable() {
            @Override
            public void run() {
                if (tile.key != it.id) return; // scrolled past before its turn came
                BitmapLoader t = testLoader;
                final Bitmap b = t != null ? t.load(it, size) : Media.thumbnail(getContentResolver(), it, size);
                if (b == null) return;
                thumbs.put(it.id, b);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (tile.key != it.id) return;
                        tile.img.setAlpha(0f);
                        tile.img.setImageBitmap(b);
                        tile.img.animate().alpha(1f).setDuration(180).start();
                    }
                });
            }
        });
    }

    @Override
    public void open(int index) {
        openViewer(index);
    }

    @Override
    public void longPress(int index) {
        gallery.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        runSimilar(gallery.items().get(index));
    }

    @Override
    public void dragStarted() {
        hideKeyboard();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_PICK_IMAGE && result == RESULT_OK && data != null && data.getData() != null) runImageSearch(data.getData());
    }
}
