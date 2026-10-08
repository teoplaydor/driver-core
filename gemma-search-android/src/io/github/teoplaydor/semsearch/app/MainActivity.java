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
    private static final int REQ_PICK_IMAGE = 7, REQ_MEDIA = 8, REQ_IDLE = 9;
    private static final int RECENT_LIMIT = 3000;

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
    private Rail rail;
    private LinearLayout searchBar, info;
    private TextView chip;
    private int filter;
    private ProgressLine progress;
    private TextView status, section;
    private MasonryView gallery;
    private FrameLayout empty;
    private Viewer viewer;
    private SettingsPanel settings;
    private Sheet sheet;
    /** The accelerator check's progress sheet while it is open, and since when the person waits for a check. */
    private Sheet benchSheet;
    private BenchView benchView;
    private long benchAskedMs;

    /** What the grid shows: the recent gallery, or results of the last search (label). */
    private String resultsLabel;
    private Intent pendingShare;
    private int lastIndexed = -1;
    /** IndexStore.hiddenVersion the grid shows. */
    private int lastHidden;
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
        resumeIdleIndex();
        ui.post(new Runnable() {
            @Override
            public void run() {
                reportPreviousCrash();
            }
        });
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
            hideSearch();
            return;
        }
        if (searchBar.getVisibility() == View.VISIBLE) {
            hideSearch();
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

        // the gallery takes the whole screen; everything else floats over it, translucent
        FrameLayout area = new FrameLayout(this);
        gallery = new MasonryView(this, this);
        area.addView(gallery, new FrameLayout.LayoutParams(-1, -1));
        empty = new FrameLayout(this);
        area.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(area, new FrameLayout.LayoutParams(-1, -1));

        // top: what is running (indexing, download) and what the grid shows (search results)
        info = new LinearLayout(this) {
            @Override
            protected void onMeasure(int w, int h) {
                for (int i = 0; i < getChildCount(); i++) {
                    if (getChildAt(i).getVisibility() != GONE) {
                        super.onMeasure(w, h);
                        return;
                    }
                }
                setMeasuredDimension(0, 0); // nothing to say: no empty bubble
            }
        };
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackground(Ui.round(this, Rail.BACKGROUND, 18));
        info.setPadding(dp(14), dp(9), dp(14), dp(10));
        info.setElevation(dp(3));
        section = Ui.text(this, "", 13.5f, Ui.TEXT, Ui.MEDIUM);
        section.setSingleLine(true);
        section.setEllipsize(TextUtils.TruncateAt.END);
        section.setVisibility(View.GONE);
        section.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMoreResults();
            }
        });
        info.addView(section, new LinearLayout.LayoutParams(-2, -2)); // wrap: the bubble follows the text
        status = Ui.text(this, "", 12.5f, Ui.TEXT2, Ui.REGULAR);
        status.setVisibility(View.GONE);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // a running accelerator check: its stages
                if (benchRunning()) showBenchProgress(engine.bench.startedMs());
            }
        });
        info.addView(status, new LinearLayout.LayoutParams(-2, -2));
        progress = new ProgressLine(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(180), dp(2));
        pl.topMargin = dp(7);
        info.addView(progress, pl);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        il.setMargins(dp(16), dp(10), dp(16), 0);
        root.addView(info, il);

        // bottom: the search field, opened from the rail's search button
        searchBar = new LinearLayout(this);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(Ui.round(this, Rail.BACKGROUND, 26));
        searchBar.setElevation(dp(4));
        searchBar.setPadding(dp(14), 0, dp(4), 0);
        searchBar.setVisibility(View.GONE);
        searchBar.addView(Ui.icon(this, Icon.SEARCH, Ui.TEXT3, 20), new LinearLayout.LayoutParams(dp(20), dp(20)));
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
                rail.setSearchActive(has);
                ui.removeCallbacks(debounced);
                if (has) ui.postDelayed(debounced, 550);
                else if (resultsLabel != null) showRecent(true);
            }
        });
        searchBar.addView(query, new LinearLayout.LayoutParams(0, dp(52), 1));
        clear = Ui.icon(this, Icon.CLOSE, Ui.TEXT2, 40);
        clear.setVisibility(View.GONE);
        clear.setContentDescription("Очистить");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                query.setText("");
                hideKeyboard();
                showRecent(true);
                hideSearch();
            }
        });
        searchBar.addView(clear, new LinearLayout.LayoutParams(dp(40), dp(40)));
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
        searchBar.addView(byPhoto, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(searchBar, new FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM));

        // the rail under the thumb
        rail = new Rail(this, new Rail.Listener() {
            @Override
            public void filterChosen(int index) {
                selectFilter(index);
            }

            @Override
            public void searchTapped() {
                if (searchBar.getVisibility() == View.VISIBLE && query.getText().length() == 0) hideSearch();
                else openSearch();
            }

            @Override
            public void settingsTapped() {
                openSettings();
            }

            @Override
            public void newNoteTapped() {
                noteEditor();
            }

            @Override
            public void albumsTapped() {
                showAlbums();
            }
        });
        root.addView(rail, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));

        chip = Ui.text(this, "", 13, Ui.TEXT, Ui.MEDIUM);
        chip.setBackground(Ui.round(this, Rail.BACKGROUND, 14));
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        chip.setElevation(dp(4));
        chip.setVisibility(View.GONE);
        root.addView(chip, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        placeRail(false);
        return root;
    }

    // ------------------------------------------------------------------ rail and search field

    /** 0: the rail sits on the right (right thumb), 1: on the left. */
    int railSide() {
        return engine.prefs().getInt("rail_side", 0) == 1 ? 1 : 0;
    }

    void setRailSide(int side) {
        if (side == railSide()) return;
        engine.prefs().edit().putInt("rail_side", side).apply();
        rail.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
            @Override
            public void run() {
                placeRail(true);
                rail.animate().alpha(1f).setDuration(200).start();
            }
        }).start();
    }

    private void placeRail(boolean animate) {
        boolean right = railSide() == 0;
        FrameLayout.LayoutParams rl = (FrameLayout.LayoutParams) rail.getLayoutParams();
        rl.gravity = Gravity.BOTTOM | (right ? Gravity.END : Gravity.START);
        rl.setMargins(dp(12), 0, dp(12), dp(20));
        rail.setLayoutParams(rl);
        // the field runs along the bottom up to the rail, centred on its search button
        FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) searchBar.getLayoutParams();
        int side = dp(12 + 60 + 8);
        bl.setMargins(right ? dp(12) : side, 0, right ? side : dp(12), dp(24));
        searchBar.setLayoutParams(bl);
        searchBar.setPivotX(right ? 99999 : 0);
    }

    private void openSearch() {
        if (searchBar.getVisibility() != View.VISIBLE) {
            boolean right = railSide() == 0;
            searchBar.setVisibility(View.VISIBLE);
            searchBar.setAlpha(0f);
            searchBar.setTranslationX(dp(right ? 40 : -40));
            searchBar.animate().alpha(1f).translationX(0).setDuration(240).setInterpolator(Ui.EASE).start();
        }
        query.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideSearch() {
        if (searchBar.getVisibility() != View.VISIBLE) return;
        hideKeyboard();
        boolean right = railSide() == 0;
        searchBar.animate().alpha(0f).translationX(dp(right ? 40 : -40)).setDuration(180).setInterpolator(Ui.EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        searchBar.setVisibility(View.GONE);
                    }
                }).start();
    }

    /** The filter's name, briefly, beside the rail. */
    private void flashChip(String text, int filterIndex) {
        chip.setText(text);
        chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        boolean right = railSide() == 0;
        float x = right ? rail.getLeft() - chip.getMeasuredWidth() - dp(8) : rail.getRight() + dp(8);
        float y = rail.getTop() + rail.filterCenterY(filterIndex) - chip.getMeasuredHeight() / 2f;
        chip.setTranslationX(x);
        chip.setTranslationY(y);
        chip.animate().cancel();
        chip.setVisibility(View.VISIBLE);
        chip.setAlpha(0f);
        chip.animate().alpha(1f).setDuration(140).withEndAction(new Runnable() {
            @Override
            public void run() {
                chip.animate().alpha(0f).setStartDelay(650).setDuration(260).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        chip.setVisibility(View.GONE);
                        chip.animate().setStartDelay(0);
                    }
                }).start();
            }
        }).start();
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        if (f != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
            f.clearFocus();
        }
    }

    private void selectFilter(int idx) {
        if (idx == filter) return;
        filter = idx;
        rail.select(idx, true);
        rail.showNewNote(idx == 3);
        flashChip(Rail.FILTER_NAMES[idx], idx);
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

    /** The less sure results of the last search, added on a tap on the line above the grid. */
    private List<IndexStore.Item> moreResults;
    private List<IndexStore.Item> shownResults;

    private void showResults(Engine.SearchResult r, Exception e, String label) {
        if (e != null) {
            sectionText("Не получилось: " + e.getMessage());
            return;
        }
        List<IndexStore.Item> list = new ArrayList<IndexStore.Item>();
        for (IndexStore.Hit h : r.hits) list.add(h.item);
        List<IndexStore.Item> more = new ArrayList<IndexStore.Item>();
        for (IndexStore.Hit h : r.more) more.add(h.item);
        resultsLabel = label;
        shownResults = list;
        moreResults = more.isEmpty() ? null : more;
        setItems(list, true);
        String text;
        if (list.isEmpty()) text = label;
        else if (r.nearestOnly) text = label + " · точных совпадений нет, ближайшие " + list.size();
        else text = label + " · " + list.size();
        if (moreResults != null) text += " · ещё " + more.size() + " менее похожих ›";
        sectionText(text);
        updateEmpty();
    }

    /** Albums by meaning (Engine.albums) in a sheet; one opens as results. */
    void showAlbums() {
        if (!engine.ready()) {
            toast(engine.hasModelFiles() ? "Модель ещё загружается" : "Сначала скачайте модель");
            return;
        }
        final Sheet s = new Sheet(this, "Альбомы по смыслу");
        final TextView note = Ui.text(this, "Собираю альбомы… (в первый раз — до полуминуты: словарь переводится в векторы)", 13,
                Ui.TEXT2, Ui.REGULAR);
        note.setLineSpacing(0, 1.25f);
        note.setPadding(0, 0, 0, dp(12));
        s.body().addView(note);
        s.show(root);
        engine.albums(new Engine.Callback<List<Engine.Album>>() {
            @Override
            public void done(List<Engine.Album> albums, Exception e) {
                if (s.isClosing()) return;
                if (e != null) {
                    note.setText("Не получилось: " + e.getMessage());
                    return;
                }
                if (engine.hideAdult() && !engine.hiddenItems().isEmpty()) s.body().addView(hiddenRow(s));
                if (albums.isEmpty()) {
                    note.setText("Пока не из чего собрать альбомы — проиндексируйте больше фото");
                    return;
                }
                note.setText("Фото, которые модель явно относит к теме; одно фото может быть в нескольких альбомах");
                for (final Engine.Album a : albums) s.body().addView(albumRow(s, a));
            }
        });
    }

    private View albumRow(final Sheet s, final Engine.Album a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        for (int i = 0; i < 3; i++) {
            MasonryView.Thumb t = new MasonryView.Thumb(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
            lp.rightMargin = dp(4);
            row.addView(t, lp);
            if (i < a.items.size()) thumbInto(t, a.items.get(i), 160);
        }
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(10), 0, 0, 0);
        text.addView(Ui.text(this, a.name, 15, Ui.TEXT, Ui.MEDIUM));
        text.addView(Ui.text(this, a.items.size() + " " + plural(a.items.size(), "файл", "файла", "файлов"), 12.5f, Ui.TEXT2, Ui.REGULAR));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                s.dismiss();
                showAlbum(a);
            }
        });
        Ui.pressable(row);
        return row;
    }

    /** The hidden folder (18+) among the albums: a padlock, no pictures. */
    private View hiddenRow(final Sheet s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        FrameLayout lock = new FrameLayout(this);
        lock.setBackground(Ui.round(this, Ui.SURFACE3, 10));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(26), dp(26));
        ip.gravity = Gravity.CENTER;
        lock.addView(Ui.icon(this, Icon.LOCK, Ui.TEXT2, 26), ip);
        row.addView(lock, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, 0, 0);
        text.addView(Ui.text(this, HIDDEN, 15, Ui.TEXT, Ui.MEDIUM));
        int n = engine.hiddenItems().size();
        text.addView(Ui.text(this, "18+ · " + n + " " + plural(n, "файл", "файла", "файлов"), 12.5f, Ui.TEXT2, Ui.REGULAR));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                s.dismiss();
                showHidden();
            }
        });
        Ui.pressable(row);
        return row;
    }

    private static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }

    /** An album's pictures in the grid, as results. */
    private void showAlbum(Engine.Album a) {
        hideKeyboard();
        resultsLabel = "Альбом «" + a.name + "»";
        shownResults = a.items;
        moreResults = null;
        setItems(a.items, true);
        sectionText(resultsLabel + " · " + a.items.size());
        updateEmpty();
    }

    static final String HIDDEN = "Скрытое";

    /** The hidden photos and videos (18+) in the grid, as results. */
    void showHidden() {
        hideKeyboard();
        List<IndexStore.Item> items = engine.hiddenItems();
        resultsLabel = HIDDEN;
        shownResults = items;
        moreResults = null;
        setItems(items, true);
        sectionText(HIDDEN + " · " + items.size());
        updateEmpty();
    }

    /** What is hidden changed: the grid drops what it no longer shows (the hidden folder is read again). */
    private void hiddenChanged() {
        if (resultsLabel == null) {
            showRecent(false);
        } else if (HIDDEN.equals(resultsLabel)) {
            List<IndexStore.Item> items = engine.hiddenItems();
            shownResults = items;
            setItems(items, false);
            sectionText(HIDDEN + " · " + items.size());
            updateEmpty();
        } else {
            List<IndexStore.Item> left = new ArrayList<IndexStore.Item>();
            for (IndexStore.Item it : gallery.items()) if (!engine.isHidden(it)) left.add(it);
            if (left.size() == gallery.items().size()) return;
            if (shownResults != null) {
                List<IndexStore.Item> sr = new ArrayList<IndexStore.Item>();
                for (IndexStore.Item it : shownResults) if (!engine.isHidden(it)) sr.add(it);
                shownResults = sr;
            }
            if (moreResults != null) {
                List<IndexStore.Item> mr = new ArrayList<IndexStore.Item>();
                for (IndexStore.Item it : moreResults) if (!engine.isHidden(it)) mr.add(it);
                moreResults = mr.isEmpty() ? null : mr;
            }
            setItems(left, false);
            updateEmpty();
        }
    }

    /** The less sure results after the sure ones. */
    private void showMoreResults() {
        if (moreResults == null || shownResults == null || resultsLabel == null) return;
        List<IndexStore.Item> all = new ArrayList<IndexStore.Item>(shownResults);
        all.addAll(moreResults);
        int sure = shownResults.size(), more = moreResults.size();
        moreResults = null;
        shownResults = all;
        setItems(all, true);
        sectionText(resultsLabel + " · " + sure + " + " + more + " менее похожих");
    }

    // ------------------------------------------------------------------ empty states

    private void updateEmpty() {
        empty.removeAllViews();
        Engine.State st = engine.state;
        boolean noModel = !engine.hasModelFiles() || (st == Engine.State.NO_MODEL && !engine.hasModelFiles());
        boolean welcome = st == Engine.State.DOWNLOADING || (noModel && st != Engine.State.LOADING);
        if (welcome) {
            if (rail.getVisibility() == View.VISIBLE) {
                rail.setVisibility(View.GONE);
                hideSearch();
            }
        } else if (rail.getVisibility() != View.VISIBLE) {
            Ui.fadeIn(rail, 220);
        }
        if (welcome) {
            welcome();
        } else if (st == Engine.State.ERROR) {
            card(Icon.INFO, "Модель не загрузилась", engine.status, "Повторить", new Runnable() {
                @Override
                public void run() {
                    engine.retryLoad();
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
                    "Начать сейчас", new Runnable() {
                        @Override
                        public void run() {
                            requestMediaAndIndex();
                        }
                    }, IdleIndex.enabled(this) ? null : "Пока телефон не используется", new Runnable() {
                        @Override
                        public void run() {
                            enableIdleIndex();
                        }
                    });
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
        if (engine.prefs().getBoolean("g_report_unseen", false) && !isFinishing()) {
            // the automatic check after "Check the NPU" finished: show what it found, once
            engine.prefs().edit().putBoolean("g_report_unseen", false).apply();
            String r = engine.gemmaReport();
            if (r != null) showReport("Скорость на этом телефоне", r);
        }
        Engine.State st = engine.state;
        boolean dl = st == Engine.State.DOWNLOADING, loading = st == Engine.State.LOADING;
        // status line: only while something is running
        String line = null;
        updateBench();
        if (benchRunning()) {
            long now = System.currentTimeMillis();
            line = "Подбор ускорения · " + engine.bench.line(now);
            progress.setIndeterminate(false);
            progress.setProgress((float) engine.bench.fraction(now));
        } else if (engine.indexing) {
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
            // the check's line changes every second: no cross-fade
            if (benchRunning() && status.getVisibility() == View.VISIBLE) status.setText(line);
            else Ui.setTextSoft(status, line);
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
        int hidden = s == null ? lastHidden : s.hiddenVersion();
        if (hidden != lastHidden) {
            // 18+ hidden or shown: out of the gallery and the results, into the hidden folder (or back)
            lastHidden = hidden;
            hiddenChanged();
        }
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
        if (engine.photoModel() == FastModel.GEMMA) {
            engine.download(engine.repo(), engine.prefs().getString("token", ""), engine.prefs().getBoolean("vision", true));
        } else {
            engine.downloadFast(false);
        }
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
        if (code == REQ_IDLE) {
            if (!AutoIndex.hasMediaAccess(this)) {
                toast("Без доступа к галерее индексировать нечего");
                return;
            }
            AutoIndex.schedule(this);
            finishIdleSetup();
            return;
        }
        if (code != REQ_MEDIA) return;
        if (AutoIndex.hasMediaAccess(this)) startIndexing();
        else toast("Без доступа к галерее индексировать нечего");
    }

    /** If the app closed unexpectedly last time, says so once and offers the details for the chat. */
    private void reportPreviousCrash() {
        final String report = CrashLog.takeUnseen(this);
        if (report == null || isFinishing()) return;
        String shown = report.length() > 1400 ? report.substring(0, 1400) + "…" : report;
        sheet = Sheet.message(root, "Приложение закрылось в прошлый раз", shown
                + "\n\nЕсли повторится — скопируйте отчёт и пришлите его в чат.", "Скопировать отчёт", new Runnable() {
            @Override
            public void run() {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("SemSearch crash", report));
                toast("Скопировано — вставьте в чат");
            }
        });
    }

    // ------------------------------------------------------------------ indexing while the phone rests

    /**
     * Turns on indexing-while-idle: gallery access, the (silent) notification Android requires for
     * foreground work, and the exemption from battery limits so the work survives the night.
     */
    void enableIdleIndex() {
        List<String> need = new ArrayList<String>();
        if (!AutoIndex.hasMediaAccess(this)) {
            if (Build.VERSION.SDK_INT >= 33) {
                need.add("android.permission.READ_MEDIA_IMAGES");
                need.add("android.permission.READ_MEDIA_VIDEO");
            } else {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_IDLE);
            return;
        }
        finishIdleSetup();
    }

    private void finishIdleSetup() {
        IdleIndex.setEnabled(this, true);
        if (!IdleIndex.unrestricted(this)) {
            try {
                startActivity(IdleIndex.exemptionRequest(this));
            } catch (Exception e) {
                // no such screen on this phone: the service still runs, maybe with pauses at night
            }
        }
        toast("Начну, когда экран погаснет, и встану на паузу, как только возьмёте телефон");
        updateEmpty();
    }

    /** With the mode on, (re)starts the service when there is a big batch to index. */
    private void resumeIdleIndex() {
        if (!IdleIndex.enabled(this) || !AutoIndex.hasMediaAccess(this)) return;
        engine.countPending(new Engine.Callback<Integer>() {
            @Override
            public void done(Integer n, Exception e) {
                if (n != null && n >= IdleIndex.BIG_BATCH) IdleIndex.start(MainActivity.this);
            }
        });
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
        String slow = Engine.slowdown(this);
        if (slow != null) {
            // a throttled phone gives numbers several times worse than usual (seen at 9% battery while charging)
            sheet = Sheet.confirm(root, "Замер сейчас будет неточным", "Сейчас " + slow + ": телефон работает медленнее обычного, "
                    + "и все варианты покажутся в разы хуже. Лучше подобрать на зарядке с запасом заряда, когда телефон не "
                    + "горячий.", "Всё равно замерить", new Runnable() {
                        @Override
                        public void run() {
                            startBenchmark();
                        }
                    });
            return;
        }
        startBenchmark();
    }

    private void startBenchmark() {
        long asked = System.currentTimeMillis();
        engine.benchmark(new Engine.Callback<String>() {
            @Override
            public void done(final String report, Exception e) {
                if (benchSheet != null && !benchSheet.isClosing()) benchSheet.dismiss();
                showReport("Скорость индексации", report != null ? report : String.valueOf(e));
            }
        });
        showBenchProgress(asked);
    }

    private boolean benchRunning() {
        io.github.teoplaydor.semsearch.core.StageProgress p = engine.bench;
        return p != null && !p.finished();
    }

    /** The check's stages in a sheet, live; {@code since}: a check started before it is an earlier one. */
    void showBenchProgress(long since) {
        if (benchSheet != null && benchSheet.getParent() != null && !benchSheet.isClosing()) return;
        benchAskedMs = since;
        Sheet s = new Sheet(this, "Подбор ускорения");
        benchView = new BenchView(this);
        s.body().addView(benchView);
        s.setOnClosed(new Runnable() {
            @Override
            public void run() {
                benchSheet = null;
                benchView = null;
            }
        });
        benchSheet = s.show(root);
        updateBench();
    }

    private void updateBench() {
        if (benchView == null) return;
        io.github.teoplaydor.semsearch.core.StageProgress p = engine.bench;
        boolean current = p != null && p.startedMs() >= benchAskedMs - 1000;
        benchView.update(current ? p : null);
        // the report replaces it (a check the app started by itself has no callback here)
        if (current && p.finished() && benchSheet != null && !benchSheet.isClosing()) benchSheet.dismiss();
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

    /** A report with "Copy" (the person pastes it into the chat). */
    void showReport(String title, final String report) {
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

    @Override
    public void describe(IndexStore.Item it, Engine.Callback<List<String>> cb) {
        if (!engine.ready()) {
            cb.done(null, new IllegalStateException(engine.hasModelFiles() ? "модель ещё загружается" : "сначала скачайте модель"));
            return;
        }
        engine.describe(it, cb);
    }

    @Override
    public boolean hidingOn() {
        return engine.hideAdult();
    }

    @Override
    public boolean isHidden(IndexStore.Item it) {
        return engine.isHidden(it);
    }

    @Override
    public void setHidden(IndexStore.Item it, boolean hide) {
        if (viewer != null) viewer.close();
        engine.setHidden(it, hide);
        toast(hide ? "Скрыто — оно в папке «Скрытое» (кнопка альбомов)" : "Возвращено в галерею");
    }

    @Override
    public void searchFor(String q) {
        if (viewer != null) viewer.close();
        openSearch();
        query.setText(q);
        query.setSelection(q.length());
        runSearch(true);
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

    /** A thumbnail into any rounded picture view (the comparison sheet). */
    void thumbInto(final MasonryView.Thumb t, final IndexStore.Item it, final int size) {
        Bitmap cached = thumbs.get(it.id);
        if (cached != null) {
            t.setImageBitmap(cached);
            return;
        }
        thumbPool.submit(new Runnable() {
            @Override
            public void run() {
                BitmapLoader l = testLoader;
                final Bitmap b = l != null ? l.load(it, size) : Media.thumbnail(getContentResolver(), it, size);
                if (b == null) return;
                thumbs.put(it.id, b);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        t.setAlpha(0f);
                        t.setImageBitmap(b);
                        t.animate().alpha(1f).setDuration(160).start();
                    }
                });
            }
        });
    }

    ViewGroup rootView() {
        return root;
    }

    void openCompare() {
        hideKeyboard();
        CompareSheet.open(this);
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
        if (query.getText().length() == 0) hideSearch();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_PICK_IMAGE && result == RESULT_OK && data != null && data.getData() != null) runImageSearch(data.getData());
    }
}
