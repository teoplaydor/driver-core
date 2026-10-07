package io.github.teoplaydor.semsearch.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.teoplaydor.semsearch.core.HfRepo;

/** Single-activity UI built in code: Search · Index · Notes · Model tabs. */
public final class MainActivity extends Activity implements Engine.Listener {
    private static final int REQ_PICK_IMAGE = 7, REQ_MEDIA = 8;
    private static final int[] PHOTO_LIMITS = {100, 300, 1000, 3000, Integer.MAX_VALUE};
    private static final int[] VIDEO_LIMITS = {0, 10, 30, 100};

    private Engine engine;
    private boolean dark;
    private int cBg, cSurface, cPrimary, cOnPrimary, cText, cText2, cSoft, cNote, cHeader;

    private TextView headerStatus;
    private final TextView[] tabs = new TextView[4];
    private final View[] pages = new View[4];

    // search
    private EditText query;
    private CheckBox fPhotos, fVideos, fNotes;
    private TextView searchStatus;
    private ResultsAdapter results;
    private View emptyHint;

    // index
    private TextView indexStats, indexStatus;
    private ProgressBar indexProgress;
    private Button indexButton;
    private Spinner photoLimit, videoLimit, photoDetail;

    // notes
    private EditText noteText;
    private NotesAdapter notesAdapter;

    // model
    private TextView modelStatus, planText;
    private ProgressBar dlProgress;
    private EditText repoField, tokenField;
    private CheckBox visionBox;
    private Button dlButton, cancelButton, deleteButton, copyErrorButton;
    private TextView accelInfo;
    private Spinner accelSpinner;

    private Intent pendingShare;
    private final ExecutorService thumbPool = Executors.newFixedThreadPool(2);
    private final LruCache<Long, Bitmap> thumbs = new LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            cBg = 0xFF121017; cSurface = 0xFF1E1B26; cPrimary = 0xFF9D8FFF; cOnPrimary = 0xFF120E2B;
            cText = 0xFFECE9F5; cText2 = 0xFFA8A3BA; cSoft = 0xFF2C2740; cNote = 0xFF3A3220; cHeader = 0xFF17141F;
        } else {
            cBg = 0xFFF5F3FA; cSurface = 0xFFFFFFFF; cPrimary = 0xFF5B4BDB; cOnPrimary = 0xFFFFFFFF;
            cText = 0xFF1D1A2C; cText2 = 0xFF6C6880; cSoft = 0xFFE9E5FF; cNote = 0xFFFFF1CC; cHeader = 0xFF4535B8;
        }
        engine = Engine.get(this);
        setContentView(buildRoot());
        selectTab(engine.state == Engine.State.NO_MODEL ? 3 : 0);
        engine.addListener(this);
        handleIntent(getIntent());
        refresh();
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
    public void onEngineChanged() {
        refresh();
        if (pendingShare != null && engine.ready()) {
            Intent i = pendingShare;
            pendingShare = null;
            handleIntent(i);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        if (!engine.ready()) {
            pendingShare = intent;
            toast("Модель ещё загружается — поиск начнётся автоматически");
            return;
        }
        setIntent(new Intent());
        selectTab(0);
        String type = intent.getType();
        if (type != null && type.startsWith("image/")) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) runImageSearch(uri);
        } else {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                query.setText(text);
                runSearch();
            }
        }
    }

    // ------------------------------------------------------------------ layout helpers

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(primary ? cOnPrimary : cPrimary);
        b.setBackground(round(primary ? cPrimary : cSoft, 12));
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setStateListAnimator(null);
        return b;
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(cText);
        e.setHintTextColor(cText2);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setBackground(round(cSoft, 12));
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        return e;
    }

    private CheckBox check(String s, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(cText);
        c.setChecked(on);
        return c;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(cSurface, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(12), dp(6), dp(12), dp(6));
        parent.addView(c, lp);
        if (title != null) {
            TextView t = text(title, 17, cText, true);
            t.setPadding(0, 0, 0, dp(8));
            c.addView(t);
        }
        return c;
    }

    private void gap(LinearLayout l, int h) {
        l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h)));
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, -2, w);
    }

    private LinearLayout.LayoutParams wrapWithMargin(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(leftDp);
        return lp;
    }

    private Spinner spinner(String[] items, int selected) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(cText);
                return v;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(selected);
        return s;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.0f МБ", bytes / 1048576.0);
    }

    // ------------------------------------------------------------------ root & tabs

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(cHeader);
        header.setPadding(dp(18), dp(14), dp(18), dp(12));
        header.addView(text("Смысловой поиск", 22, Color.WHITE, true));
        headerStatus = text("EmbeddingGemma 2 · на устройстве", 13, 0xCCFFFFFF, false);
        header.addView(headerStatus);
        root.addView(header);

        LinearLayout tabBar = row();
        tabBar.setPadding(dp(8), dp(8), dp(8), dp(4));
        String[] names = {"Поиск", "Индекс", "Заметки", "Модель"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = text(names[i], 14, cText2, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            LinearLayout.LayoutParams lp = weight(1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            tabBar.addView(t, lp);
            tabs[i] = t;
        }
        root.addView(tabBar);

        FrameLayout content = new FrameLayout(this);
        pages[0] = buildSearch();
        pages[1] = buildIndex();
        pages[2] = buildNotes();
        pages[3] = buildModel();
        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void selectTab(int idx) {
        for (int i = 0; i < 4; i++) {
            boolean on = i == idx;
            pages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(on ? cPrimary : cText2);
            tabs[i].setBackground(on ? round(cSoft, 20) : null);
        }
        hideKeyboard();
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        if (f != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
    }

    // ------------------------------------------------------------------ search tab

    private View buildSearch() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(6), dp(12), 0);

        LinearLayout r1 = row();
        query = edit("Что ищем? Например: кот на диване");
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
                runSearch();
                return true;
            }
        });
        r1.addView(query, weight(1));
        Button go = button("Найти", true);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSearch();
            }
        });
        r1.addView(go, wrapWithMargin(8));
        page.addView(r1);

        LinearLayout r2 = row();
        r2.setPadding(0, dp(6), 0, 0);
        Button byPhoto = button("По фото", false);
        byPhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Выберите фото"), REQ_PICK_IMAGE);
                } catch (Exception e) {
                    toast("Нет приложения для выбора фото");
                }
            }
        });
        r2.addView(byPhoto);
        fPhotos = check("Фото", true);
        fVideos = check("Видео", true);
        fNotes = check("Заметки", true);
        r2.addView(fPhotos, wrapWithMargin(6));
        r2.addView(fVideos);
        r2.addView(fNotes);
        page.addView(r2);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row();
        chips.setPadding(0, dp(8), 0, dp(4));
        String[] examples = {"кот", "закат на море", "скриншот с текстом", "еда", "документы", "как зайти в интернет дома",
                "зуб болит", "что приготовить на завтрак"};
        for (final String ex : examples) {
            TextView c = text(ex, 13, cPrimary, false);
            c.setBackground(round(cSoft, 16));
            c.setPadding(dp(12), dp(6), dp(12), dp(6));
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    query.setText(ex);
                    runSearch();
                }
            });
            chips.addView(c, wrapWithMargin(chips.getChildCount() == 0 ? 0 : 6));
        }
        hs.addView(chips);
        page.addView(hs);

        searchStatus = text("", 13, cText2, false);
        searchStatus.setPadding(dp(2), dp(4), 0, dp(6));
        page.addView(searchStatus);

        FrameLayout area = new FrameLayout(this);
        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(6));
        grid.setVerticalSpacing(dp(6));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(16));
        results = new ResultsAdapter();
        grid.setAdapter(results);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onResultClick(results.hits.get(pos).item);
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                runSimilar(results.hits.get(pos).item);
                return true;
            }
        });
        area.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        TextView hint = text("Найдите фото, видео и заметки по смыслу — на любом языке.\n\n"
                + "1. Скачайте модель на вкладке «Модель» (один раз).\n"
                + "2. Проиндексируйте галерею на вкладке «Индекс».\n"
                + "3. Пишите запрос словами или ищите по фото.\n\n"
                + "Долгое нажатие на результат — найти похожие.\n\n"
                + "Число на плитке — косинусное сходство. Для пары «текст ↔ фото» обычные значения 0,1–0,4: "
                + "важен порядок результатов, а не само число.", 15, cText2, false);
        hint.setPadding(dp(8), dp(24), dp(8), 0);
        emptyHint = hint;
        area.addView(hint);
        page.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private void runSearch() {
        final String q = query.getText().toString().trim();
        if (q.isEmpty()) return;
        if (!engine.ready()) {
            toast("Сначала скачайте модель (вкладка «Модель»)");
            return;
        }
        hideKeyboard();
        searchStatus.setText("Ищу…");
        engine.search(q, fPhotos.isChecked(), fVideos.isChecked(), fNotes.isChecked(), searchCallback());
    }

    private void runImageSearch(Uri uri) {
        if (!engine.supportsImages()) {
            toast("Для поиска по фото нужен визуальный энкодер");
            return;
        }
        searchStatus.setText("Анализирую фото…");
        engine.searchByImage(uri, fPhotos.isChecked(), fVideos.isChecked(), fNotes.isChecked(), searchCallback());
    }

    private void runSimilar(IndexStore.Item item) {
        selectTab(0);
        searchStatus.setText("Ищу похожие…");
        engine.similar(item, fPhotos.isChecked(), fVideos.isChecked(), fNotes.isChecked(), searchCallback());
    }

    private Engine.Callback<Engine.SearchResult> searchCallback() {
        return new Engine.Callback<Engine.SearchResult>() {
            @Override
            public void done(Engine.SearchResult r, Exception e) {
                if (e != null) {
                    searchStatus.setText("Ошибка: " + e.getMessage());
                    return;
                }
                results.set(r.hits);
                emptyHint.setVisibility(r.hits.isEmpty() ? View.VISIBLE : View.GONE);
                int total = engine.store() == null ? 0
                        : engine.store().count(IndexStore.KIND_PHOTO) + engine.store().count(IndexStore.KIND_VIDEO)
                        + engine.store().count(IndexStore.KIND_NOTE);
                searchStatus.setText(r.hits.isEmpty()
                        ? "Ничего не найдено — индекс пуст? Добавьте фото или заметки."
                        : String.format(Locale.ROOT, "%s · топ-%d из %d · %d мс · %d изм.",
                        r.label, r.hits.size(), total, r.millis, engine.searchDims()));
            }
        };
    }

    private void onResultClick(final IndexStore.Item it) {
        if (it.kind == IndexStore.KIND_NOTE) {
            new AlertDialog.Builder(this)
                    .setMessage(it.body)
                    .setPositiveButton("Похожие", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            runSimilar(it);
                        }
                    })
                    .setNegativeButton("Закрыть", null)
                    .show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(it.uri), it.kind == IndexStore.KIND_VIDEO ? "video/*" : "image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("Не удалось открыть файл");
        }
    }

    /** Square tile container for the result grid. */
    private static final class SquareFrame extends FrameLayout {
        SquareFrame(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    private final class ResultsAdapter extends BaseAdapter {
        final List<IndexStore.Hit> hits = new ArrayList<IndexStore.Hit>();

        void set(List<IndexStore.Hit> h) {
            hits.clear();
            hits.addAll(h);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return hits.size();
        }

        @Override
        public Object getItem(int p) {
            return hits.get(p);
        }

        @Override
        public long getItemId(int p) {
            return hits.get(p).item.id;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            SquareFrame tile;
            if (convert instanceof SquareFrame) {
                tile = (SquareFrame) convert;
            } else {
                tile = new SquareFrame(MainActivity.this);
                tile.setClipToOutline(true);
                ImageView img = new ImageView(MainActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                tile.addView(img, new FrameLayout.LayoutParams(-1, -1));
                TextView note = text("", 13, cText, false);
                note.setPadding(dp(8), dp(8), dp(8), dp(22));
                note.setEllipsize(TextUtils.TruncateAt.END);
                note.setMaxLines(6);
                tile.addView(note, new FrameLayout.LayoutParams(-1, -1));
                TextView badge = text("", 11, Color.WHITE, true);
                badge.setBackground(round(0x99000000, 8));
                badge.setPadding(dp(6), dp(1), dp(6), dp(1));
                FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
                bl.setMargins(dp(5), 0, 0, dp(5));
                tile.addView(badge, bl);
                TextView play = text("▶", 13, Color.WHITE, true);
                play.setBackground(round(0x99000000, 12));
                play.setPadding(dp(7), dp(2), dp(6), dp(2));
                FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                pl.setMargins(0, dp(5), dp(5), 0);
                tile.addView(play, pl);
            }
            final ImageView img = (ImageView) tile.getChildAt(0);
            TextView note = (TextView) tile.getChildAt(1);
            TextView badge = (TextView) tile.getChildAt(2);
            View play = tile.getChildAt(3);
            final IndexStore.Hit h = hits.get(pos);
            badge.setText(String.format(Locale.ROOT, "%.2f", h.score));
            play.setVisibility(h.item.kind == IndexStore.KIND_VIDEO ? View.VISIBLE : View.GONE);
            if (h.item.kind == IndexStore.KIND_NOTE) {
                tile.setBackground(round(cNote, 12));
                img.setVisibility(View.GONE);
                note.setVisibility(View.VISIBLE);
                note.setText(h.item.body);
            } else {
                tile.setBackground(round(cSoft, 12));
                note.setVisibility(View.GONE);
                img.setVisibility(View.VISIBLE);
                final long key = h.item.id;
                img.setTag(key);
                Bitmap cached = thumbs.get(key);
                img.setImageBitmap(cached);
                if (cached == null) {
                    thumbPool.submit(new Runnable() {
                        @Override
                        public void run() {
                            final Bitmap b = Media.thumbnail(getContentResolver(), h.item, 320);
                            if (b == null) return;
                            thumbs.put(key, b);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (Long.valueOf(key).equals(img.getTag())) img.setImageBitmap(b);
                                }
                            });
                        }
                    });
                }
            }
            return tile;
        }
    }

    // ------------------------------------------------------------------ index tab

    private View buildIndex() {
        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(4), 0, dp(24));
        sv.addView(page);

        LinearLayout stats = card(page, "Что уже в индексе");
        indexStats = text("", 15, cText, false);
        stats.addView(indexStats);

        LinearLayout set = card(page, "Настройки индексации");
        set.addView(text("Сколько последних фото обработать", 14, cText2, false));
        photoLimit = spinner(new String[]{"100", "300", "1000", "3000", "Все"}, engine.prefs.getInt("photo_limit", 1));
        set.addView(photoLimit);
        gap(set, 8);
        set.addView(text("Видео (по " + Engine.VIDEO_FRAMES + " кадра из каждого)", 14, cText2, false));
        videoLimit = spinner(new String[]{"Не индексировать", "Последние 10", "Последние 30", "Последние 100"},
                engine.prefs.getInt("video_limit", 1));
        set.addView(videoLimit);
        gap(set, 8);
        set.addView(text("Детализация фото", 14, cText2, false));
        photoDetail = spinner(new String[]{"Быстро — 70 токенов", "Средне — 140 токенов", "Максимум — 280 токенов"},
                engine.prefs.getInt("photo_detail", 0));
        photoDetail.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                engine.prefs.edit().putInt("photo_detail", pos).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        set.addView(photoDetail);
        gap(set, 6);
        set.addView(text("Больше токенов — точнее мелкие детали и текст на скриншотах, но медленнее. "
                + "Если поменять детализацию, уже проиндексированные фото останутся как есть.", 13, cText2, false));
        gap(set, 10);
        Button bench = button("Подобрать самое быстрое ускорение (1–2 мин)", false);
        bench.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runBenchmark();
            }
        });
        set.addView(bench, new LinearLayout.LayoutParams(-1, -2));
        accelInfo = text("", 13, cText2, false);
        set.addView(accelInfo);

        LinearLayout run = card(page, "Индексация");
        indexButton = button("Начать индексацию", true);
        indexButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine.indexing) {
                    engine.stopIndex();
                } else {
                    requestMediaAndIndex();
                }
            }
        });
        run.addView(indexButton, new LinearLayout.LayoutParams(-1, -2));
        gap(run, 10);
        indexProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        indexProgress.setMax(1000);
        run.addView(indexProgress, new LinearLayout.LayoutParams(-1, -2));
        indexStatus = text("", 14, cText2, false);
        run.addView(indexStatus);
        gap(run, 6);
        run.addView(text("Всё считается на телефоне, файлы никуда не отправляются. Пока идёт индексация, "
                + "экран не гаснет. Если прервать, при следующем запуске уже обработанные файлы будут пропущены.",
                13, cText2, false));

        LinearLayout danger = card(page, null);
        Button clear = button("Очистить индекс фото и видео", false);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("Удалить векторы всех фото и видео? Сами файлы не трогаются.")
                        .setPositiveButton("Очистить", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                engine.clearMediaIndex();
                            }
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
            }
        });
        danger.addView(clear, new LinearLayout.LayoutParams(-1, -2));
        return sv;
    }

    private void requestMediaAndIndex() {
        if (!engine.ready()) {
            toast("Сначала скачайте модель");
            selectTab(3);
            return;
        }
        if (!engine.supportsImages()) {
            toast("Модель загружена без визуального энкодера — включите «Фото и видео» и скачайте его");
            selectTab(3);
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
        if (missing.isEmpty() || hasAnyMediaAccess()) {
            if (!missing.isEmpty() && Build.VERSION.SDK_INT >= 34) {
                // Partial access ("selected photos") is fine, but offer to widen it.
                requestPermissions(missing.toArray(new String[0]), REQ_MEDIA);
                return;
            }
            startIndexing();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQ_MEDIA);
        }
    }

    private boolean hasAnyMediaAccess() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission("android.permission.READ_MEDIA_IMAGES") == PackageManager.PERMISSION_GRANTED
                    || (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(
                    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED);
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_MEDIA) return;
        if (hasAnyMediaAccess()) {
            startIndexing();
        } else {
            toast("Без доступа к галерее индексировать нечего");
        }
    }

    private void startIndexing() {
        int pl = photoLimit.getSelectedItemPosition(), vl = videoLimit.getSelectedItemPosition();
        engine.prefs.edit().putInt("photo_limit", pl).putInt("video_limit", vl).apply();
        engine.startIndex(PHOTO_LIMITS[pl], VIDEO_LIMITS[vl]);
    }

    // ------------------------------------------------------------------ notes tab

    private View buildNotes() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(4), 0, 0);
        LinearLayout c = card(page, "Новая заметка");
        noteText = edit("Например: пароль от Wi-Fi на даче — на холодильнике");
        noteText.setMinLines(3);
        noteText.setGravity(Gravity.TOP | Gravity.START);
        noteText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        c.addView(noteText, new LinearLayout.LayoutParams(-1, -2));
        gap(c, 8);
        LinearLayout r = row();
        Button save = button("Сохранить", true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = noteText.getText().toString().trim();
                if (t.isEmpty()) return;
                if (!engine.ready()) {
                    toast("Сначала скачайте модель");
                    return;
                }
                engine.addNote(t, new Engine.Callback<IndexStore.Item>() {
                    @Override
                    public void done(IndexStore.Item it, Exception e) {
                        if (e != null) {
                            toast("Ошибка: " + e.getMessage());
                        } else {
                            noteText.setText("");
                            hideKeyboard();
                        }
                    }
                });
            }
        });
        r.addView(save, weight(1));
        Button samples = button("Добавить примеры", false);
        samples.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addSamples();
            }
        });
        LinearLayout.LayoutParams lp = weight(1);
        lp.leftMargin = dp(8);
        r.addView(samples, lp);
        c.addView(r);

        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(8));
        list.setPadding(dp(12), 0, dp(12), dp(12));
        list.setClipToPadding(false);
        notesAdapter = new NotesAdapter();
        list.setAdapter(notesAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                final IndexStore.Item it = notesAdapter.items.get(pos);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(it.body)
                        .setPositiveButton("Похожие", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                runSimilar(it);
                            }
                        })
                        .setNeutralButton("Удалить", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                engine.deleteItem(it);
                            }
                        })
                        .setNegativeButton("Закрыть", null)
                        .show();
            }
        });
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private void addSamples() {
        if (!engine.ready()) {
            toast("Сначала скачайте модель");
            return;
        }
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
            }
        });
        toast("Добавляю " + notes.length + " заметок… Попробуйте запрос «как зайти в интернет дома»");
    }

    private final class NotesAdapter extends BaseAdapter {
        List<IndexStore.Item> items = new ArrayList<IndexStore.Item>();

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int p) {
            return items.get(p);
        }

        @Override
        public long getItemId(int p) {
            return items.get(p).id;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout v;
            if (convert instanceof LinearLayout) {
                v = (LinearLayout) convert;
            } else {
                v = new LinearLayout(MainActivity.this);
                v.setOrientation(LinearLayout.VERTICAL);
                v.setPadding(dp(14), dp(10), dp(14), dp(10));
                v.addView(text("", 15, cText, false));
                v.addView(text("", 12, cText2, false));
            }
            v.setBackground(round(cSurface, 12));
            IndexStore.Item it = items.get(pos);
            ((TextView) v.getChildAt(0)).setText(it.body);
            ((TextView) v.getChildAt(1)).setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(it.date)));
            return v;
        }
    }

    // ------------------------------------------------------------------ model tab

    private View buildModel() {
        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(4), 0, dp(24));
        sv.addView(page);

        LinearLayout about = card(page, "EmbeddingGemma 2");
        about.addView(text("Открытая модель Google DeepMind (740M, Apache 2.0). Переводит текст, фото и видео "
                + "в общее векторное пространство на 768 чисел, поэтому текстовый запрос находит картинку, "
                + "а фото — похожие фото и заметки. Работает полностью офлайн после загрузки.\n\n"
                + "Формат: ONNX (onnx-community), квантование q4, движок ONNX Runtime на CPU.", 14, cText2, false));

        LinearLayout st = card(page, "Состояние");
        modelStatus = text("", 15, cText, false);
        st.addView(modelStatus);
        gap(st, 8);
        dlProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        dlProgress.setMax(1000);
        st.addView(dlProgress, new LinearLayout.LayoutParams(-1, -2));
        copyErrorButton = button("Скопировать подробности ошибки", false);
        copyErrorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("SemSearch error", engine.errorDetails));
                toast("Скопировано — вставьте в чат");
            }
        });
        st.addView(copyErrorButton, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout src = card(page, "Загрузка");
        src.addView(text("Репозиторий Hugging Face", 13, cText2, false));
        repoField = edit(HfRepo.DEFAULT_REPO);
        repoField.setSingleLine(true);
        repoField.setText(engine.repo());
        src.addView(repoField, new LinearLayout.LayoutParams(-1, -2));
        gap(src, 8);
        tokenField = edit("Токен HF — только если доступ закрыт");
        tokenField.setSingleLine(true);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenField.setText(engine.prefs.getString("token", ""));
        src.addView(tokenField, new LinearLayout.LayoutParams(-1, -2));
        visionBox = check("Фото и видео (визуальный энкодер)", engine.prefs.getBoolean("vision", true));
        src.addView(visionBox);
        planText = text("", 13, cText2, false);
        src.addView(planText);
        gap(src, 8);
        LinearLayout r = row();
        Button check = button("Проверить", false);
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                planText.setText("Проверяю репозиторий…");
                engine.checkRepo(repoField.getText().toString(), tokenField.getText().toString(), visionBox.isChecked(),
                        new Engine.Callback<HfRepo.Plan>() {
                            @Override
                            public void done(HfRepo.Plan p, Exception e) {
                                if (e != null) {
                                    planText.setText("Ошибка: " + e.getMessage());
                                    return;
                                }
                                StringBuilder sb = new StringBuilder("Будет скачано " + mb(p.totalBytes) + ":\n");
                                for (HfRepo.RemoteFile f : p.files) {
                                    if (f.size > 1 << 20) sb.append("• ").append(f.path).append(" — ").append(mb(f.size)).append('\n');
                                }
                                sb.append("• конфиги и токенизатор");
                                planText.setText(sb.toString());
                            }
                        });
            }
        });
        r.addView(check, weight(1));
        dlButton = button("Скачать", true);
        dlButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine.state == Engine.State.ERROR && engine.hasModelFiles()) {
                    engine.loadModel(); // files are already on the phone: just retry loading
                } else {
                    engine.download(repoField.getText().toString(), tokenField.getText().toString(), visionBox.isChecked());
                }
            }
        });
        LinearLayout.LayoutParams lp = weight(1);
        lp.leftMargin = dp(8);
        r.addView(dlButton, lp);
        src.addView(r);
        gap(src, 8);
        LinearLayout r2 = row();
        cancelButton = button("Остановить загрузку", false);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.cancelDownload();
            }
        });
        r2.addView(cancelButton, weight(1));
        deleteButton = button("Удалить модель", false);
        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("Удалить файлы модели с телефона? Индекс сохранится.")
                        .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                engine.deleteModel();
                            }
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
            }
        });
        LinearLayout.LayoutParams lp2 = weight(1);
        lp2.leftMargin = dp(8);
        r2.addView(deleteButton, lp2);
        src.addView(r2);

        LinearLayout acc = card(page, "Ускорение");
        acc.addView(text("Где считается модель. «Подобрать» на вкладке «Индекс» замерит все варианты на этом телефоне "
                + "и выберет самый быстрый; здесь можно выбрать вручную.", 13, cText2, false));
        accelSpinner = spinner(Engine.ACCEL_NAMES, engine.accel());
        accelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == engine.accel()) return; // initial selection / no change
                if (pos >= Engine.ACCEL_GPU && engine.gpuBroken()) {
                    toast("Видеокарта на этом телефоне уже приводила к сбою — оставляю процессор");
                    accelSpinner.setSelection(engine.accel());
                    return;
                }
                engine.prefs.edit().putInt("accel", pos).apply();
                if (engine.ready() && !engine.indexing) engine.loadModel();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        acc.addView(accelSpinner);

        LinearLayout s = card(page, "Поиск");
        s.addView(text("Длина вектора (Matryoshka): короче — меньше памяти, чуть ниже точность", 13, cText2, false));
        final int[] dims = {768, 512, 256, 128};
        int cur = 0;
        for (int i = 0; i < dims.length; i++) if (dims[i] == engine.searchDims()) cur = i;
        final Spinner dimSpinner = spinner(new String[]{"768 (полная)", "512", "256", "128"}, cur);
        dimSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                engine.prefs.edit().putInt("dims", dims[pos]).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        s.addView(dimSpinner);
        gap(s, 10);
        s.addView(text("Русские запросы к фото и видео. Модель лучше всего связывает картинки с английским текстом, "
                + "поэтому запрос дополнительно переводится по встроенному словарю (заметки ищутся по исходному тексту).",
                13, cText2, false));
        Spinner bridgeSpinner = spinner(Engine.BRIDGE_MODES, engine.bridgeMode());
        bridgeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                engine.prefs.edit().putInt("bridge_mode", pos).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        s.addView(bridgeSpinner);
        gap(s, 10);
        s.addView(text("Потоки процессора для модели. «Авто» берёт быстрые ядра; больше потоков не всегда быстрее — "
                + "медленные ядра тормозят общий шаг.", 13, cText2, false));
        final int[] threadOpts = {0, 2, 3, 4, 6, 8};
        int curT = 0;
        for (int i = 0; i < threadOpts.length; i++) if (threadOpts[i] == engine.prefs.getInt("threads", 0)) curT = i;
        Spinner threadSpinner = spinner(new String[]{"Авто (" + Engine.autoThreads() + ")", "2", "3", "4", "6", "8"}, curT);
        threadSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (threadOpts[pos] == engine.prefs.getInt("threads", 0)) return; // initial selection
                engine.prefs.edit().putInt("threads", threadOpts[pos]).apply();
                if (engine.ready() && !engine.indexing) {
                    engine.loadModel(); // sessions are created with a fixed thread count
                    toast("Перезагружаю модель с " + engine.threadCount() + " потоками");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        s.addView(threadSpinner);
        gap(s, 10);
        Button diag = button("Проверить качество поиска", false);
        diag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiagnostics();
            }
        });
        s.addView(diag, new LinearLayout.LayoutParams(-1, -2));
        return sv;
    }

    private void runBenchmark() {
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
                accelSpinner.setSelection(engine.accel());
                TextView t = text(report != null ? report : String.valueOf(e), 13, cText, false);
                t.setTextIsSelectable(true);
                t.setPadding(dp(20), dp(12), dp(20), dp(12));
                ScrollView sv = new ScrollView(MainActivity.this);
                sv.addView(t);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Скорость индексации")
                        .setView(sv)
                        .setPositiveButton("Скопировать", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                android.content.ClipboardManager cm =
                                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("SemSearch benchmark", report));
                                toast("Скопировано — вставьте в чат");
                            }
                        })
                        .setNegativeButton("Закрыть", null)
                        .show();
            }
        });
    }

    private void runDiagnostics() {
        if (!engine.ready()) {
            toast("Сначала скачайте модель");
            return;
        }
        toast("Считаю… это займёт несколько секунд");
        engine.diagnose(new Engine.Callback<String>() {
            @Override
            public void done(final String report, Exception e) {
                if (e != null) {
                    toast("Ошибка: " + e.getMessage());
                    return;
                }
                TextView t = text(report, 13, cText, false);
                t.setTextIsSelectable(true);
                t.setPadding(dp(20), dp(12), dp(20), dp(12));
                ScrollView sv = new ScrollView(MainActivity.this);
                sv.addView(t);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Качество поиска")
                        .setView(sv)
                        .setPositiveButton("Скопировать", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                android.content.ClipboardManager cm =
                                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("SemSearch diagnostics", report));
                                toast("Скопировано — вставьте в чат");
                            }
                        })
                        .setNegativeButton("Закрыть", null)
                        .show();
            }
        });
    }

    // ------------------------------------------------------------------ state → UI

    private void refresh() {
        Engine.State st = engine.state;
        String head;
        switch (st) {
            case READY: head = "EmbeddingGemma 2 · на устройстве · готово"; break;
            case DOWNLOADING: head = "Скачиваю модель…"; break;
            case LOADING: head = "Загружаю модель…"; break;
            case ERROR: head = "Ошибка модели — см. вкладку «Модель»"; break;
            default: head = "Модель не скачана — вкладка «Модель»";
        }
        headerStatus.setText(head);

        // model tab
        StringBuilder ms = new StringBuilder(engine.status == null ? "" : engine.status);
        if (st == Engine.State.DOWNLOADING && engine.dlTotal > 0) {
            ms.append("\n").append(mb(engine.dlDone)).append(" из ").append(mb(engine.dlTotal));
        }
        if (ms.length() == 0) ms.append(st == Engine.State.NO_MODEL
                ? "Модель не скачана. Нажмите «Проверить», затем «Скачать» (нужен Wi-Fi)." : "");
        modelStatus.setText(ms.toString());
        dlProgress.setVisibility(st == Engine.State.DOWNLOADING || st == Engine.State.LOADING ? View.VISIBLE : View.GONE);
        dlProgress.setIndeterminate(st == Engine.State.LOADING || engine.dlTotal <= 0);
        if (engine.dlTotal > 0) dlProgress.setProgress((int) (1000 * engine.dlDone / engine.dlTotal));
        boolean busy = st == Engine.State.DOWNLOADING || st == Engine.State.LOADING;
        dlButton.setEnabled(!busy);
        dlButton.setText(st == Engine.State.READY ? "Скачать заново"
                : st == Engine.State.ERROR && engine.hasModelFiles() ? "Повторить загрузку" : "Скачать");
        copyErrorButton.setVisibility(st == Engine.State.ERROR && engine.errorDetails != null ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(st == Engine.State.DOWNLOADING ? View.VISIBLE : View.GONE);
        deleteButton.setEnabled(!busy);

        // index tab
        IndexStore store = engine.store();
        if (store != null) {
            indexStats.setText(String.format(Locale.ROOT, "Фото: %d   Видео: %d   Заметки: %d",
                    store.count(IndexStore.KIND_PHOTO), store.count(IndexStore.KIND_VIDEO),
                    store.count(IndexStore.KIND_NOTE)));
            notesAdapter.items = store.notes();
            notesAdapter.notifyDataSetChanged();
        }
        indexButton.setText(engine.indexing ? "Остановить" : "Начать индексацию");
        if (engine.ready()) {
            accelInfo.setText("Сейчас: " + Engine.ACCEL_NAMES[engine.loadedAccel] + ", потоков " + engine.threads
                    + (engine.prefs.getBoolean("accel_chosen", false) ? "" : " · ещё не подбиралось"));
        }
        indexStatus.setText(engine.idxStatus);
        indexProgress.setVisibility(engine.indexing ? View.VISIBLE : View.GONE);
        indexProgress.setIndeterminate(engine.idxTotal == 0);
        if (engine.idxTotal > 0) indexProgress.setProgress(1000 * engine.idxDone / engine.idxTotal);
        if (engine.indexing) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_PICK_IMAGE && result == RESULT_OK && data != null && data.getData() != null) {
            selectTab(0);
            runImageSearch(data.getData());
        }
    }
}
