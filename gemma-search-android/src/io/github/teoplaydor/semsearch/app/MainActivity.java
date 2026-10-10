package io.github.teoplaydor.semsearch.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
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

import io.github.teoplaydor.semsearch.core.Spoken;

/**
 * The app is a gallery: recent photos by default, the same staggered grid for search results.
 * Settings live in a panel, the viewer opens over the grid, notes are one tab of the gallery.
 */
public final class MainActivity extends Activity implements Engine.Listener, Viewer.Host, MasonryView.Host {
    private static final int REQ_PICK_IMAGE = 7, REQ_MEDIA = 8, REQ_IDLE = 9, REQ_VOICE = 10, REQ_NOTIFY = 11, REQ_FOLDER = 12,
            REQ_AUDIO = 13;
    /** The rail's filters: everything, photos, videos, notes, documents, sound. */
    public static final int F_ALL = 0, F_PHOTOS = 1, F_VIDEOS = 2, F_NOTES = 3, F_FILES = 4, F_AUDIO = 5;
    /** A new note (the app icon's shortcut; with EXTRA_VOICE dictated at once), a note shown (its reminder tapped). */
    static final String ACTION_NEW_NOTE = "io.github.teoplaydor.semsearch.NEW_NOTE",
            ACTION_OPEN_NOTE = "io.github.teoplaydor.semsearch.OPEN_NOTE", EXTRA_VOICE = "voice";
    /** A note opened in its editor (with ACTION_OPEN_NOTE); a search asked for by voice (the quick note's «найди…»). */
    static final String EXTRA_EDIT = "edit", ACTION_SEARCH = "io.github.teoplaydor.semsearch.SEARCH", EXTRA_QUERY = "query";
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
    private ScanPanel scan;
    /** The note being written or changed. */
    NoteEditor noteEditor;
    /** Where dictated text goes once the recogniser answers. */
    private Engine.Callback<String> dictated;
    /** A note to show once the index is there (a reminder tapped while the app was starting). */
    private long noteToOpen = -1;
    private boolean noteToEdit;
    private Sheet sheet;
    /** The accelerator check's progress sheet while it is open, and since when the person waits for a check. */
    private Sheet benchSheet;
    private BenchView benchView;
    private long benchAskedMs;

    /** While pictures are chosen (a long press): how many, and what can be done with them. */
    private LinearLayout chooseBar;
    private TextView chooseCount, choosePdf;
    /** Whether a picture is a document (Engine.isDocument), as found out for those chosen; those being asked. */
    private final Map<Long, Boolean> docOf = new java.util.HashMap<Long, Boolean>();
    private final java.util.Set<Long> docAsked = new java.util.HashSet<Long>();
    /** The PDF made of the chosen documents last (tests look at it). */
    PdfJob pdfJob;

    /** What the grid shows: the recent gallery, or results of the last search (label). */
    private String resultsLabel;

    /** Where the user was — the grid and what was open over it — to come back to with «Назад», newest last. */
    private static final class Place {
        String label, section, query;
        List<IndexStore.Item> items, more;
        Engine.Album album;
        boolean search;
        /** The viewer was open on this picture. */
        IndexStore.Item viewerAt;
        int over;
    }

    static final int OVER_NONE = 0, OVER_ALBUMS = 1, OVER_SETTINGS = 2;
    private final ArrayList<Place> places = new ArrayList<Place>();
    /** The grid holds the results of the typed query (typing on refines them: no new place). */
    private boolean searchShown;
    /** A place was just left for a search about to run (a word in the viewer). */
    private boolean searchLeft;
    private boolean restoring;
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
        if (noteEditor != null && !noteEditor.isClosing()) {
            noteEditor.done();
            return;
        }
        if (scan != null && !scan.isClosing()) {
            if (!scan.back()) scan.close();
            return;
        }
        if (viewer != null && !viewer.isClosing()) {
            viewer.close();
            return;
        }
        if (settings != null && !settings.isClosing()) {
            settings.close();
            return;
        }
        if (gallery.choosing()) {
            gallery.stopChoosing();
            return;
        }
        if (!places.isEmpty()) {
            back(places.remove(places.size() - 1));
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
        if (intent == null) return;
        if (ACTION_NEW_NOTE.equals(intent.getAction())) {
            setIntent(new Intent());
            if (noteEditor != null) noteEditor.done();
            openNoteEditor(null, null, null);
            Object voice = intent.getExtras() == null ? null : intent.getExtras().get(EXTRA_VOICE);
            if ((Boolean.TRUE.equals(voice) || "true".equals(voice)) && noteEditor != null) {
                final NoteEditor e = noteEditor;
                dictate(new Engine.Callback<String>() {
                    @Override
                    public void done(String said, Exception err) {
                        if (said != null && !e.isClosing()) e.insert(said.trim());
                    }
                });
            }
            return;
        }
        if (ACTION_OPEN_NOTE.equals(intent.getAction())) {
            setIntent(new Intent());
            if (noteEditor != null) noteEditor.done();
            noteToOpen = intent.getLongExtra(Reminders.EXTRA_NOTE, -1);
            noteToEdit = intent.getBooleanExtra(EXTRA_EDIT, false);
            openPendingNote();
            return;
        }
        if (ACTION_SEARCH.equals(intent.getAction())) {
            String q = intent.getStringExtra(EXTRA_QUERY);
            if (q == null || q.trim().isEmpty()) return;
            if (!engine.ready()) {
                if (pendingShare == null) toast(engine.hasModelFiles() ? "Модель ещё загружается — поиск начнётся сам" : "Сначала скачайте модель");
                pendingShare = intent;
                return;
            }
            setIntent(new Intent());
            if (noteEditor != null) noteEditor.done();
            // asked by voice: among everything, whatever the filter was
            if (filter != F_ALL) {
                filter = F_ALL;
                rail.select(F_ALL, false);
                rail.showNewNote(false);
            }
            searchFor(q.trim());
            return;
        }
        // text shared «В заметки» (the share target named so): a new note with it
        if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.getComponent() != null
                && intent.getComponent().getClassName().endsWith(".ToNotes")) {
            setIntent(new Intent());
            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT), text = intent.getStringExtra(Intent.EXTRA_TEXT);
            String body = (subject != null && !subject.trim().isEmpty() && (text == null || !text.contains(subject.trim()))
                    ? subject.trim() + "\n" : "") + (text != null ? text.trim() : "");
            if (noteEditor != null) noteEditor.done();
            openNoteEditor(null, null, body);
            return;
        }
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
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
                if (shownAlbum != null && shownAlbum.kind == Engine.Album.UNNAMED) unnamedChoice(shownAlbum);
                else showMoreResults();
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

        // top, while pictures are chosen: how many, a scan of the documents among them as one PDF, share, all, stop
        chooseBar = new LinearLayout(this);
        chooseBar.setGravity(Gravity.CENTER_VERTICAL);
        chooseBar.setBackground(Ui.round(this, Rail.BACKGROUND, 26));
        chooseBar.setElevation(dp(5));
        chooseBar.setPadding(dp(2), 0, dp(6), 0);
        chooseBar.setVisibility(View.GONE);
        chooseBar.setClickable(true);
        ImageView stopChoosing = Ui.icon(this, Icon.CLOSE, Ui.TEXT, 44);
        stopChoosing.setContentDescription("Отменить выбор");
        stopChoosing.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gallery.stopChoosing();
            }
        });
        chooseBar.addView(stopChoosing, new LinearLayout.LayoutParams(dp(44), dp(44)));
        chooseCount = Ui.text(this, "", 15, Ui.TEXT, Ui.MEDIUM);
        chooseCount.setSingleLine(true);
        chooseCount.setEllipsize(TextUtils.TruncateAt.END);
        chooseBar.addView(chooseCount, new LinearLayout.LayoutParams(0, -2, 1));
        choosePdf = Ui.text(this, "Скан в PDF", 13.5f, Ui.ON_ACCENT, Ui.SEMIBOLD);
        choosePdf.setGravity(Gravity.CENTER);
        choosePdf.setBackground(Ui.round(this, Ui.ACCENT, 16));
        choosePdf.setPadding(dp(12), 0, dp(12), 0);
        choosePdf.setVisibility(View.GONE);
        Ui.pressable(choosePdf);
        choosePdf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pdfOfChosen();
            }
        });
        LinearLayout.LayoutParams pdl = new LinearLayout.LayoutParams(-2, dp(34));
        pdl.rightMargin = dp(2);
        chooseBar.addView(choosePdf, pdl);
        ImageView shareChosen = Ui.icon(this, Icon.SHARE, Ui.TEXT, 44);
        shareChosen.setContentDescription("Поделиться выбранными");
        shareChosen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareChosen();
            }
        });
        chooseBar.addView(shareChosen, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageView all = Ui.icon(this, Icon.CHECK, Ui.TEXT, 44);
        all.setContentDescription("Выбрать все");
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gallery.chooseAll();
            }
        });
        chooseBar.addView(all, new LinearLayout.LayoutParams(dp(44), dp(44)));
        FrameLayout.LayoutParams cbl = new FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP);
        cbl.setMargins(dp(10), dp(10), dp(10), 0);
        root.addView(chooseBar, cbl);

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
                if (restoring) return; // a place come back to brings its own results
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
        return filter == F_ALL || filter == F_PHOTOS;
    }

    private boolean videos() {
        return filter == F_ALL || filter == F_VIDEOS;
    }

    private boolean notes() {
        return filter == F_ALL || filter == F_NOTES;
    }

    /** What the filter shows (IndexStore kind bits). */
    private int kinds() {
        switch (filter) {
            case F_PHOTOS:
                return IndexStore.PHOTOS;
            case F_VIDEOS:
                return IndexStore.VIDEOS;
            case F_NOTES:
                return IndexStore.NOTES;
            case F_FILES:
                return IndexStore.FILES;
            case F_AUDIO:
                return IndexStore.AUDIO;
            default:
                return IndexStore.ALL;
        }
    }

    /** Notes, documents and sound are cards of text: two columns of them. */
    private int columns() {
        return filter == F_NOTES || filter == F_FILES || filter == F_AUDIO ? 2 : 3;
    }

    // ------------------------------------------------------------------ content

    /**
     * Remembers where the user is before the grid shows something else: the grid, the query, and what is open over it —
     * the viewer, or (by the caller) the albums sheet or the settings.
     */
    void leave(int over) {
        Place p = new Place();
        p.label = resultsLabel;
        p.section = section.getVisibility() == View.VISIBLE ? section.getText().toString() : null;
        p.items = resultsLabel == null ? null : new ArrayList<IndexStore.Item>(gallery.items());
        p.more = moreResults;
        p.album = shownAlbum;
        p.search = searchShown;
        p.query = query.getText().toString();
        p.viewerAt = viewer != null && !viewer.isClosing() ? viewer.current() : null;
        p.over = over;
        places.add(p);
        if (places.size() > 30) places.remove(0);
    }

    /** «Назад»: the place before — its grid, its query, and the viewer, the albums or the settings open as they were. */
    private void back(final Place p) {
        restoring = true;
        try {
            query.setText(p.query);
            query.setSelection(p.query.length());
            if (p.query.isEmpty()) hideSearch();
            if (p.label == null) {
                showRecent(false);
            } else {
                resultsLabel = p.label;
                shownAlbum = p.album;
                shownResults = p.items;
                moreResults = p.more;
                setItems(p.items, false);
                if (p.section != null) sectionText(p.section);
                updateEmpty();
            }
            searchShown = p.search;
        } finally {
            restoring = false;
        }
        if (p.viewerAt != null) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    int pos = gallery.items().indexOf(p.viewerAt);
                    if (pos >= 0) openViewer(pos);
                }
            });
        } else if (p.over == OVER_ALBUMS) {
            showAlbums();
        } else if (p.over == OVER_SETTINGS) {
            openSettings();
        }
    }

    private void showRecent(boolean animate) {
        resultsLabel = null;
        shownAlbum = null;
        searchShown = false;
        if (aspects == null) {
            // lay the gallery out once picture proportions are known, so nothing jumps
            recentWaits = true;
            recentAnimate |= animate;
            loadAspects();
            return;
        }
        IndexStore s = engine.store();
        List<IndexStore.Item> list = s == null ? new ArrayList<IndexStore.Item>()
                : s.recent(filter == F_ALL ? IndexStore.MEDIA : kinds(), RECENT_LIMIT); // «Все» shows the gallery; search finds all
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
        gallery.setItems(list, columns(), animate);
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
                    if (IndexStore.picture(it.kind) && !m.containsKey(k)) m.put(k, 0f);
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
        // a new search leaves the place before it; typing on refines the same one
        if (!searchShown && !searchLeft) leave(OVER_NONE);
        searchLeft = false;
        searchShown = true;
        sectionText("Ищу…");
        engine.search(q, kinds(), new Engine.Callback<Engine.SearchResult>() {
            @Override
            public void done(Engine.SearchResult r, Exception e) {
                if (!q.equals(query.getText().toString().trim())) return; // typed on: a newer search follows
                showResults(r, e, "«" + q + "»");
                searchShown = true;
            }
        });
    }

    private void runImageSearch(Uri uri) {
        if (!engine.supportsImages()) {
            toast("Для поиска по фото нужен визуальный энкодер");
            return;
        }
        leave(OVER_NONE);
        searchShown = false;
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
        engine.similar(item, kinds(), new Engine.Callback<Engine.SearchResult>() {
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
        shownAlbum = null;
        searchShown = false;
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

    /**
     * The albums in a sheet: the hidden folder, people (named, then unnamed), pets and things, then by meaning; one
     * opens as results. A photo may be in several.
     */
    @SuppressWarnings("unchecked")
    void showAlbums() {
        if (!engine.ready()) {
            toast(engine.hasModelFiles() ? "Модель ещё загружается" : "Сначала скачайте модель");
            return;
        }
        final Sheet s = new Sheet(this, "Альбомы");
        final TextView note = Ui.text(this, "Собираю альбомы… (в первый раз — до полуминуты: словарь переводится в векторы)", 13,
                Ui.TEXT2, Ui.REGULAR);
        note.setLineSpacing(0, 1.25f);
        note.setPadding(0, 0, 0, dp(12));
        s.body().addView(note);
        s.show(root);
        final List<Engine.Album>[] parts = new List[2];
        final Exception[] failed = new Exception[1];
        final Runnable fill = new Runnable() {
            @Override
            public void run() {
                if (parts[0] == null || parts[1] == null || s.isClosing()) return;
                fillAlbums(s, note, parts[0], parts[1], failed[0]);
            }
        };
        engine.people(new Engine.Callback<List<Engine.Album>>() {
            @Override
            public void done(List<Engine.Album> r, Exception e) {
                parts[0] = r != null ? r : new ArrayList<Engine.Album>();
                fill.run();
            }
        });
        engine.albums(new Engine.Callback<List<Engine.Album>>() {
            @Override
            public void done(List<Engine.Album> r, Exception e) {
                parts[1] = r != null ? r : new ArrayList<Engine.Album>();
                failed[0] = e;
                fill.run();
            }
        });
    }

    private void fillAlbums(final Sheet s, TextView note, List<Engine.Album> people, List<Engine.Album> meaning, Exception failed) {
        note.setText("Одно фото может быть сразу в нескольких альбомах: в людях, в питомцах и в темах");
        if (engine.hideAdult() && !engine.hiddenItems().isEmpty()) s.body().addView(hiddenRow(s));
        List<Engine.Album> persons = new ArrayList<Engine.Album>(), things = new ArrayList<Engine.Album>();
        for (Engine.Album a : people) (a.kind == Engine.Album.THING ? things : persons).add(a);
        s.body().addView(header("Люди"));
        for (Engine.Album a : persons) s.body().addView(personRow(s, a));
        if (!engine.facesInstalled()) {
            s.body().addView(actionRow(Icon.PERSON, engine.faceDownloading ? "Модели лиц скачиваются…" : "Узнавать людей по лицам",
                    "модели лиц OpenCV, ≈40 МБ, один раз; всё считается на телефоне", new Runnable() {
                        @Override
                        public void run() {
                            s.dismiss();
                            downloadFaces();
                        }
                    }));
        } else if (persons.isEmpty()) {
            s.body().addView(hint(engine.faceScanning ? String.format(Locale.ROOT, "Ищу лица на фото · %d из %d", engine.faceDone,
                    engine.faceTotal) : "Лиц пока не нашлось — они появятся, когда лица найдутся на нескольких фото"));
        }
        s.body().addView(header("Питомцы и другое"));
        for (Engine.Album a : things) s.body().addView(albumRow(s, a));
        if (things.isEmpty()) {
            s.body().addView(hint("Откройте фото → «Отметить» → «Отметить питомца или что-то ещё»: похожие фото соберутся здесь"));
        }
        s.body().addView(header("По смыслу"));
        if (failed != null) s.body().addView(hint("Не получилось: " + failed.getMessage()));
        else if (meaning.isEmpty()) s.body().addView(hint("Пока не из чего собрать — проиндексируйте больше фото"));
        for (Engine.Album a : meaning) s.body().addView(albumRow(s, a));
    }

    private TextView header(String text) {
        TextView t = Ui.text(this, text, 13, Ui.TEXT3, Ui.MEDIUM);
        t.setPadding(0, dp(16), 0, dp(6));
        return t;
    }

    private TextView hint(String text) {
        TextView t = Ui.text(this, text, 13, Ui.TEXT2, Ui.REGULAR);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
    }

    /** A row with an icon in a round box: an action rather than an album. */
    private View actionRow(int icon, String title, String sub, final Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        FrameLayout box = new FrameLayout(this);
        box.setBackground(Ui.round(this, Ui.SURFACE3, 26));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(26), dp(26));
        ip.gravity = Gravity.CENTER;
        box.addView(Ui.icon(this, icon, Ui.TEXT2, 26), ip);
        row.addView(box, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, 0, 0);
        text.addView(Ui.text(this, title, 15, Ui.TEXT, Ui.MEDIUM));
        if (sub != null) text.addView(Ui.text(this, sub, 12.5f, Ui.TEXT2, Ui.REGULAR));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        Ui.pressable(row);
        return row;
    }

    /** A person (or someone unnamed): their face in a circle, the name, how many photos; held — rename or delete. */
    private View personRow(final Sheet s, final Engine.Album a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        ImageView face = avatar(52);
        if (a.face != null) faceInto(face, a.face, a.box);
        row.addView(face, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, 0, 0);
        boolean unnamed = a.kind == Engine.Album.UNNAMED;
        text.addView(Ui.text(this, a.name, 15, unnamed ? Ui.TEXT2 : Ui.TEXT, Ui.MEDIUM));
        text.addView(Ui.text(this, a.items.size() + " фото" + (unnamed ? " · нажмите, чтобы назвать" : ""), 12.5f, Ui.TEXT2,
                Ui.REGULAR));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (unnamed) {
            // nobody to name (strangers, a crowd): the whole group hidden, right from the list
            final LinearLayout self = row;
            ImageView hide = Ui.icon(this, Icon.HIDE, Ui.TEXT2, 44);
            hide.setPadding(dp(10), dp(10), dp(10), dp(10));
            hide.setContentDescription("Скрыть — узнавать не нужно");
            hide.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    engine.hideUnnamed(a, null);
                    ((ViewGroup) self.getParent()).removeView(self);
                    toast("Скрыто: " + a.faces.length + " лиц — вернуть можно в настройках");
                }
            });
            row.addView(hide, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                leave(OVER_ALBUMS);
                s.dismiss();
                showAlbum(a);
            }
        });
        if (!unnamed) {
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    manageGroup(s, a);
                    return true;
                }
            });
        }
        Ui.pressable(row);
        return row;
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
                leave(OVER_ALBUMS);
                s.dismiss();
                showAlbum(a);
            }
        });
        if (a.kind == Engine.Album.THING) {
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    manageGroup(s, a);
                    return true;
                }
            });
        }
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
                leave(OVER_ALBUMS);
                s.dismiss();
                showHidden();
            }
        });
        Ui.pressable(row);
        return row;
    }

    static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }

    /** The album shown in the grid (null: something else is). */
    private Engine.Album shownAlbum;

    /** An album's pictures in the grid, as results; someone unnamed — a tap on the line names them. */
    private void showAlbum(Engine.Album a) {
        showAlbum(a, true);
    }

    private void showAlbum(Engine.Album a, boolean animate) {
        hideKeyboard();
        resultsLabel = a.kind == Engine.Album.MEANING ? "Альбом «" + a.name + "»" : a.name;
        shownAlbum = a;
        searchShown = false;
        shownResults = a.items;
        moreResults = a.more.isEmpty() ? null : a.more;
        setItems(a.items, animate);
        String text = resultsLabel + " · " + a.items.size();
        if (a.kind == Engine.Album.UNNAMED) text += " · назвать или скрыть ›";
        else if (moreResults != null) text += " · ещё " + a.more.size() + " похожих ›";
        sectionText(text);
        updateEmpty();
    }

    /** People or pets changed: the person or pet in the grid is shown again as it is now. */
    private void refreshShownAlbum(final String renamed) {
        final Engine.Album was = shownAlbum;
        if (was == null || was.kind == Engine.Album.MEANING) return;
        engine.people(new Engine.Callback<List<Engine.Album>>() {
            @Override
            public void done(List<Engine.Album> all, Exception e) {
                if (all == null || shownAlbum != was) return;
                for (Engine.Album a : all) {
                    boolean same = was.kind == Engine.Album.UNNAMED ? a.kind == Engine.Album.PERSON && a.name.equals(renamed)
                            : a.kind == was.kind && a.id == was.id;
                    if (same) {
                        showAlbum(a, false);
                        return;
                    }
                }
            }
        });
    }

    static final String HIDDEN = "Скрытое";

    /** The hidden photos and videos (18+) in the grid, as results. */
    void showHidden() {
        hideKeyboard();
        List<IndexStore.Item> items = engine.hiddenItems();
        resultsLabel = HIDDEN;
        shownAlbum = null;
        searchShown = false;
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
        } else if (filter == F_NOTES) {
            card(Icon.NOTE, "Заметок пока нет", "Запишите что угодно — найдётся по смыслу, не по словам", "Новая заметка",
                    new Runnable() {
                        @Override
                        public void run() {
                            noteEditor();
                        }
                    }, null, null);
        } else if (filter == F_FILES) {
            boolean none = engine.folders().isEmpty();
            card(Icon.FILE, none ? "Документы — из папок, которые вы дадите" : engine.indexing ? "Читаю документы…" : "Документов пока нет",
                    none ? "PDF, Word, Excel, PowerPoint, OpenDocument, текст, книги: найдутся по смыслу, не по словам в названии. "
                            + "Приложение читает только выбранные папки" : "В выбранных папках нет документов, которые можно прочитать",
                    none ? "Выбрать папку" : "Ещё папку", new Runnable() {
                        @Override
                        public void run() {
                            pickFolder();
                        }
                    }, null, null);
        } else if (filter == F_AUDIO) {
            soundCard();
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

    /** The sound filter with nothing to show: what is missing (the audio encoder, the permission) or that it is coming. */
    private void soundCard() {
        if (!engine.audioDownloaded()) {
            card(Icon.AUDIO, "Поиск по звуку", "Записи диктофона, голосовые и музыка найдутся по тому, что в них звучит: "
                    + "«собака лает», «разговор о ремонте». Нужна звуковая часть модели" + (audioMb > 0 ? String.format(Locale.ROOT,
                    " (%.0f МБ)", audioMb) : "") + "; видео тогда ищутся и по звуку", "Скачать", new Runnable() {
                @Override
                public void run() {
                    if (!engine.canDownloadAudio()) {
                        toast(engine.hasModelFiles() || engine.state == Engine.State.DOWNLOADING
                                ? "Подождите — модель скачивается или загружается" : "Сначала скачайте EmbeddingGemma 2 в настройках");
                        return;
                    }
                    requestAudioAccess();
                    engine.downloadAudio();
                }
            }, null, null);
            if (audioMb == 0) {
                audioMb = -1;
                engine.audioSize(new Engine.Callback<Long>() {
                    @Override
                    public void done(Long n, Exception e) {
                        if (n != null && n > 0) {
                            audioMb = n / 1048576.0;
                            if (filter == F_AUDIO) updateEmpty();
                        }
                    }
                });
            }
        } else if (!AutoIndex.hasAudioAccess(this)) {
            card(Icon.AUDIO, "Нужен доступ к аудио", "Чтобы найти записи и голосовые, приложению нужно их читать — "
                    + "на телефоне, без интернета", "Разрешить", new Runnable() {
                @Override
                public void run() {
                    requestAudioAccess();
                }
            }, null, null);
        } else if (engine.indexing) {
            card(Icon.AUDIO, "Слушаю записи…", "Звуки появятся здесь по мере обработки", null, null, null, null);
        } else {
            card(Icon.AUDIO, "Звуков пока нет", engine.audioError() != null ? "Звуковая часть не загрузилась: " + engine.audioError()
                    : "Записей, голосовых и музыки на телефоне не нашлось", "Проиндексировать", new Runnable() {
                @Override
                public void run() {
                    engine.startIndexFromPrefs(false);
                }
            }, null, null);
        }
    }

    /** The sound part's size in MB (0: not asked yet, -1: asking). */
    private double audioMb;

    /** Android's folder picker: the folder given is read (and only it). */
    void pickFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_FOLDER);
        } catch (Exception e) {
            toast("Выбор папки на этом телефоне недоступен");
        }
    }

    /** Asks for the sound files (Android 13+: audio; before: storage). */
    void requestAudioAccess() {
        if (AutoIndex.hasAudioAccess(this)) return;
        requestPermissions(new String[]{Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO"
                : Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_AUDIO);
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
        // clear of the rail when it is shown (with its six filters it reaches the middle of the screen)
        int side = dp(32), railPad = rail != null && rail.getVisibility() == View.VISIBLE ? dp(80) : side;
        box.setPadding(railSide() == 1 ? railPad : side, 0, railSide() == 0 ? railPad : side, dp(60));
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
        openPendingNote();
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
        } else if (engine.faceDownloading) {
            line = "Скачиваю модели лиц";
            progress.setIndeterminate(engine.faceDlTotal <= 0);
            if (engine.faceDlTotal > 0) progress.setProgress((float) engine.faceDlDone / engine.faceDlTotal);
        } else if (engine.faceScanning && engine.faceTotal > 0) {
            line = String.format(Locale.ROOT, "Ищу лица · %d из %d", engine.faceDone, engine.faceTotal);
            progress.setIndeterminate(false);
            progress.setProgress((float) engine.faceDone / engine.faceTotal);
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
        int indexed = s == null ? 0 : s.count(IndexStore.KIND_PHOTO) + s.count(IndexStore.KIND_VIDEO) + s.count(IndexStore.KIND_NOTE)
                + s.count(IndexStore.KIND_AUDIO) + s.count(IndexStore.KIND_FILE);
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
        if (code == REQ_AUDIO) {
            if (AutoIndex.hasAudioAccess(this)) {
                AutoIndex.schedule(this); // new recordings wake the background run too
                if (engine.soundIndexing()) engine.startIndexFromPrefs(false);
            }
            updateEmpty();
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

    /** A new note (the rail's «+»). */
    private void noteEditor() {
        openNoteEditor(null, null, null);
    }

    /** The editor: a note changed, or a new one (to a photo; with some text to start from). */
    void openNoteEditor(IndexStore.Item note, IndexStore.Item photo, String start) {
        // one open at a time; one on its way out (kept already) does not stand in the way
        if (noteEditor != null && !noteEditor.isClosing()) return;
        noteEditor = new NoteEditor(this, note, photo, start);
        noteEditor.open(root);
    }

    void noteEditorClosed(NoteEditor e) {
        if (noteEditor == e) noteEditor = null;
        if (viewer != null && !viewer.isClosing()) viewer.refreshNote();
    }

    /**
     * What the editor holds, kept: a new note added (unless empty), a note changed — its text, pin and reminder — or,
     * emptied, deleted.
     */
    void saveNote(NoteEditor e, final String text, IndexStore.Item photo) {
        IndexStore.Item note = e.note;
        if (e.remind > 0 && (note == null || note.remind != e.remind)) askNotifications();
        if (note == null) {
            if (text.trim().isEmpty()) return;
            engine.addNote(text, photo, e.pinned, e.remind, e.repeat, new Engine.Callback<IndexStore.Item>() {
                @Override
                public void done(IndexStore.Item it, Exception err) {
                    if (err != null) toast("Не сохранилось: " + err.getMessage());
                    else if (resultsLabel == null) showRecent(false);
                }
            });
            return;
        }
        if (text.trim().isEmpty()) {
            engine.deleteItem(note);
            if (viewer != null) viewer.close();
            removeFromGrid(note);
            toast("Пустая заметка удалена");
            return;
        }
        if (!text.equals(note.body)) engine.updateNote(note, text, null);
        if (e.pinned != note.pinned) engine.setPinned(note, e.pinned);
        if (e.remind != note.remind || e.repeat != note.repeat) engine.setReminder(note, e.remind, e.remind > 0 ? e.repeat : Spoken.ONCE);
        if (resultsLabel == null) showRecent(false);
    }

    void confirmDeleteNote(final NoteEditor e) {
        sheet = Sheet.confirm(root, "Удалить заметку?", null, "Удалить", new Runnable() {
            @Override
            public void run() {
                engine.deleteItem(e.note);
                e.close();
                if (viewer != null) viewer.close();
                removeFromGrid(e.note);
            }
        });
    }

    private void removeFromGrid(IndexStore.Item it) {
        List<IndexStore.Item> left = new ArrayList<IndexStore.Item>(gallery.items());
        if (left.remove(it)) {
            gallery.setItems(left, columns(), false);
            updateEmpty();
        }
    }

    /**
     * When to remind: in an hour, this evening, tomorrow morning, or a day and time chosen; «Без напоминания» when one
     * is set. The time chosen (0: none) to {@code cb}; nothing when the sheet is closed.
     */
    void chooseReminder(long current, final Engine.Callback<Long> cb) {
        chooseReminder(current, Spoken.ONCE, cb, null);
    }

    /** As above, and «Повторять…» (how often, to {@code repeatCb}) when one is set. */
    void chooseReminder(long current, final int repeat, final Engine.Callback<Long> cb, final Engine.Callback<Integer> repeatCb) {
        final java.util.Calendar now = java.util.Calendar.getInstance();
        final java.util.List<String> labels = new java.util.ArrayList<String>();
        final java.util.List<Long> times = new java.util.ArrayList<Long>();
        labels.add("Через час");
        times.add(System.currentTimeMillis() + 3_600_000L);
        if (now.get(java.util.Calendar.HOUR_OF_DAY) < 19) {
            labels.add("Сегодня вечером, в 19:00");
            times.add(at(0, 19));
        }
        labels.add("Завтра утром, в 9:00");
        times.add(at(1, 9));
        labels.add("Выбрать день и время…");
        times.add(-1L);
        if (current > 0 && repeatCb != null) {
            labels.add(repeat == Spoken.ONCE ? "Повторять…" : "Повтор: " + Spoken.repeatLabel(repeat) + "…");
            times.add(-2L);
        }
        if (current > 0) {
            labels.add("Без напоминания");
            times.add(0L);
        }
        String title = current > 0 ? "Напомнит " + NoteEditor.when(current) : "Напомнить";
        if (current > 0 && repeat != Spoken.ONCE) title += ", " + Spoken.repeatLabel(repeat);
        sheet = Sheet.choose(root, title, labels.toArray(new String[0]),
                null, -1, new Sheet.Choice() {
                    @Override
                    public void chosen(int i) {
                        long t = times.get(i);
                        if (t >= 0) cb.done(t, null);
                        else if (t == -2) chooseRepeat(repeat, repeatCb);
                        else pickDayAndTime(cb);
                    }
                });
    }

    private static final int[] REPEATS = {Spoken.ONCE, Spoken.DAILY, Spoken.WEEKDAYS, Spoken.WEEKLY, Spoken.MONTHLY, Spoken.YEARLY};

    /** How often a reminder comes again: never, every day, on weekdays, every week, month, year. */
    void chooseRepeat(int current, final Engine.Callback<Integer> cb) {
        String[] labels = new String[REPEATS.length];
        int selected = 0;
        for (int i = 0; i < REPEATS.length; i++) {
            String l = REPEATS[i] == Spoken.ONCE ? "не повторять" : Spoken.repeatLabel(REPEATS[i]);
            labels[i] = Character.toUpperCase(l.charAt(0)) + l.substring(1);
            if (REPEATS[i] == current) selected = i;
        }
        sheet = Sheet.choose(root, "Повторять", labels, null, selected, new Sheet.Choice() {
            @Override
            public void chosen(int i) {
                cb.done(REPEATS[i], null);
            }
        });
    }

    /** Today (+{@code days}) at {@code hour}:00. */
    private static long at(int days, int hour) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_YEAR, days);
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void pickDayAndTime(final Engine.Callback<Long> cb) {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.HOUR_OF_DAY, 1);
        android.app.DatePickerDialog d = new android.app.DatePickerDialog(this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK,
                new android.app.DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(android.widget.DatePicker v, int y, int m, int day) {
                        c.set(y, m, day);
                        new android.app.TimePickerDialog(MainActivity.this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK,
                                new android.app.TimePickerDialog.OnTimeSetListener() {
                                    @Override
                                    public void onTimeSet(android.widget.TimePicker v, int h, int min) {
                                        c.set(java.util.Calendar.HOUR_OF_DAY, h);
                                        c.set(java.util.Calendar.MINUTE, min);
                                        c.set(java.util.Calendar.SECOND, 0);
                                        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
                                            toast("Это время уже прошло");
                                            return;
                                        }
                                        cb.done(c.getTimeInMillis(), null);
                                    }
                                }, c.get(java.util.Calendar.HOUR_OF_DAY), 0, true).show();
                    }
                }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH));
        d.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        d.show();
    }

    /** Android 13+: notifications are asked for (a reminder is one). */
    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
        }
    }

    /** Speech to text by the phone's recogniser (Google's or the maker's), in the phone's language. */
    void dictate(Engine.Callback<String> cb) {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Говорите — текст попадёт в заметку");
        try {
            dictated = cb;
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            dictated = null;
            toast("На телефоне нет распознавания речи — можно диктовать с клавиатуры (значок микрофона)");
        }
    }

    /** The note a reminder was tapped for, shown (as soon as the index is open). */
    private void openPendingNote() {
        if (noteToOpen < 0 || engine.store() == null) return;
        IndexStore.Item it = engine.store().find(noteToOpen);
        noteToOpen = -1;
        if (it == null) {
            toast("Этой заметки уже нет");
            return;
        }
        if (noteToEdit) {
            noteToEdit = false;
            openNoteEditor(it, null, null);
            return;
        }
        if (viewer != null) viewer.close();
        if (scan != null) scan.close();
        viewer = new Viewer(this, this, new ArrayList<IndexStore.Item>(java.util.Collections.singletonList(it)), 0);
        viewer.open(root);
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
        leave(OVER_NONE);
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
            i.setType(mimeOf(it));
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
    public void pdfPage(final IndexStore.Item file, final int page, final Engine.Callback<Bitmap> cb) {
        thumbPool.submit(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = Media.pdfPage(MainActivity.this, Uri.parse(file.uri), page, 1400);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(b, null);
                    }
                });
            }
        });
    }

    /** The type to hand a file to another app with: what its provider says, else by its kind. */
    String mimeOf(IndexStore.Item it) {
        String t = null;
        try {
            t = getContentResolver().getType(Uri.parse(it.uri));
        } catch (Exception ignored) {
            // a provider that does not say
        }
        if (t != null) return t;
        switch (it.kind) {
            case IndexStore.KIND_VIDEO:
                return "video/*";
            case IndexStore.KIND_AUDIO:
                return "audio/*";
            case IndexStore.KIND_FILE: {
                String ext = it.title == null ? "" : android.webkit.MimeTypeMap.getFileExtensionFromUrl(it.title.replace(' ', '_'));
                String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
                return m != null ? m : "*/*";
            }
            default:
                return "image/*";
        }
    }

    @Override
    public void openWith(IndexStore.Item it) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(it.uri), mimeOf(it));
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
                gallery.setItems(left, columns(), false);
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
    public void albumsOf(IndexStore.Item it, Engine.Callback<List<Engine.Album>> cb) {
        engine.albumsOf(it, cb);
    }

    @Override
    public void openAlbum(Engine.Album a) {
        leave(OVER_NONE);
        if (viewer != null) viewer.close();
        showAlbum(a);
    }

    @Override
    public void editNote(IndexStore.Item note) {
        openNoteEditor(note, null, null);
    }

    /** A note to the photo: a new one when it has none; else its notes to choose from, and a new one. */
    @Override
    public void noteToPhoto(final IndexStore.Item photo) {
        final List<IndexStore.Item> notes = engine.store() == null ? new ArrayList<IndexStore.Item>() : engine.store().notesOf(photo.mediaId);
        if (notes.isEmpty()) {
            openNoteEditor(null, photo, null);
            return;
        }
        String[] options = new String[notes.size() + 1];
        for (int i = 0; i < notes.size(); i++) options[i] = io.github.teoplaydor.semsearch.core.NoteText.title(notes.get(i).body, 60);
        options[notes.size()] = "Новая заметка к фото";
        sheet = Sheet.choose(root, "Заметки к фото", options, null, -1, new Sheet.Choice() {
            @Override
            public void chosen(int i) {
                if (i < notes.size()) openNoteEditor(notes.get(i), null, null);
                else openNoteEditor(null, photo, null);
            }
        });
    }

    @Override
    public int notesOf(IndexStore.Item photo) {
        return engine.store() == null ? 0 : engine.store().notesOf(photo.mediaId).size();
    }

    @Override
    public IndexStore.Item linkedPhoto(IndexStore.Item note) {
        if (note.mediaId < 0 || engine.store() == null) return null;
        for (IndexStore.Item m : engine.store().media()) if (m.mediaId == note.mediaId && m.kind != IndexStore.KIND_NOTE) return m;
        return null;
    }

    @Override
    public void openPhoto(IndexStore.Item photo) {
        if (viewer != null) viewer.close();
        final IndexStore.Item p = photo;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (viewer != null) return;
                int pos = gallery.items().indexOf(p);
                viewer = pos >= 0 ? new Viewer(MainActivity.this, MainActivity.this, new ArrayList<IndexStore.Item>(gallery.items()), pos)
                        : new Viewer(MainActivity.this, MainActivity.this, new ArrayList<IndexStore.Item>(java.util.Collections.singletonList(p)), 0);
                viewer.open(root);
            }
        }, 260);
    }

    @Override
    public void tick(IndexStore.Item note, int line) {
        engine.tick(note, line);
    }

    @Override
    public void setPinned(IndexStore.Item note, boolean pinned) {
        engine.setPinned(note, pinned);
        toast(pinned ? "Закреплена — первой среди заметок" : "Откреплена");
        if (resultsLabel == null) showRecent(false);
    }

    @Override
    public void remind(final IndexStore.Item note) {
        chooseReminder(note.remind, note.repeat, new Engine.Callback<Long>() {
            @Override
            public void done(Long at, Exception e) {
                if (at == null) return;
                if (at > 0) askNotifications();
                engine.setReminder(note, at);
                toast(at > 0 ? "Напомнит " + NoteEditor.when(at) : "Напоминание убрано");
                if (viewer != null) viewer.refreshNote();
            }
        }, new Engine.Callback<Integer>() {
            @Override
            public void done(Integer repeat, Exception e) {
                engine.setReminder(note, note.remind, repeat);
                toast(repeat == Spoken.ONCE ? "Напомнит один раз" : "Будет напоминать " + Spoken.repeatLabel(repeat));
                if (viewer != null) viewer.refreshNote();
            }
        });
    }

    void downloadFaces() {
        engine.downloadFaces();
        toast("Скачиваю модели лиц (≈40 МБ) — потом найду лица на всех фото");
    }

    @Override
    public void isDocument(IndexStore.Item it, Engine.Callback<Boolean> cb) {
        engine.isDocument(it, cb);
    }

    /** The document made ready to print, over the viewer (it stays under it for «Назад»). */
    @Override
    public void openScan(IndexStore.Item it) {
        if (scan != null) return;
        scan = new ScanPanel(this, it);
        scan.open(root);
    }

    void scanClosed() {
        scan = null;
    }

    /** The photo for a scan: about {@code pixels}, upright. */
    Bitmap loadForScan(IndexStore.Item it, long pixels) throws Exception {
        BitmapLoader t = testLoader;
        if (t != null) return t.load(it, 3000);
        // at least that many pixels (Media.full gives at most: a 50 MP photo came as a quarter)
        ContentResolver cr = getContentResolver();
        return Media.decode(cr, Uri.parse(it.uri), Media.orientation(cr, it.mediaId), pixels);
    }

    @Override
    public boolean facesReady() {
        return engine.facesInstalled();
    }

    @Override
    public void facesOf(IndexStore.Item it, Engine.Callback<List<Engine.FaceTag>> cb) {
        engine.facesOf(it, cb);
    }

    @Override
    public void faceTapped(IndexStore.Item it, Engine.FaceTag f) {
        personChooser(it, f);
    }

    @Override
    public void hideFace(IndexStore.Item it, Engine.FaceTag f) {
        engine.hideFace(f.faceId, facesChanged());
        toast("Лицо скрыто — вернуть можно в настройках, «Люди и питомцы»");
    }

    /** After a face was named, corrected or hidden: the viewer's faces and the person shown, as they are now. */
    private Runnable facesChanged() {
        return new Runnable() {
            @Override
            public void run() {
                if (viewer != null && !viewer.isClosing()) viewer.refreshFaces();
                refreshShownAlbum(null);
            }
        };
    }

    /**
     * Marking the photo: a person on it whose face was not found (turned away, far, in the dark), a pet or anything
     * else; taking it out of a person's or pet's album; with no face models yet, the offer to download them first. The
     * faces found are on the photo itself, in the viewer.
     */
    @Override
    public void whoIsThis(final IndexStore.Item it) {
        boolean faces = engine.facesInstalled();
        final Sheet s = new Sheet(this, "Отметить на фото");
        if (!faces) {
            TextView note = hint(engine.faceDownloading ? "Модели лиц скачиваются — когда закончат, лица появятся прямо на фото"
                    : "Чтобы узнавать людей по лицам, нужны модели лиц OpenCV (≈40 МБ, один раз; всё считается на телефоне)");
            note.setPadding(0, 0, 0, dp(8));
            s.body().addView(note);
            if (!engine.faceDownloading) {
                s.body().addView(actionRow(Icon.DOWNLOAD, "Скачать модели лиц", null, new Runnable() {
                    @Override
                    public void run() {
                        s.dismiss();
                        downloadFaces();
                    }
                }));
            }
        }
        final LinearLayout outBox = new LinearLayout(this);
        outBox.setOrientation(LinearLayout.VERTICAL);
        s.body().addView(outBox);
        s.body().addView(header("Человек"));
        s.body().addView(actionRow(Icon.PERSON, "Отметить человека", "когда лицо не нашлось: спиной, далеко, в темноте", new Runnable() {
            @Override
            public void run() {
                personPicker(s, it);
            }
        }));
        s.body().addView(header("Питомец или что-то ещё"));
        s.body().addView(actionRow(Icon.PLUS, "Отметить питомца или что-то ещё", "кошку, собаку, машину, дом — похожие фото соберутся "
                + "в альбом", new Runnable() {
            @Override
            public void run() {
                thingChooser(s, it);
            }
        }));
        s.body().addView(header("Документ"));
        s.body().addView(actionRow(Icon.SCAN, "Скан для печати", "вырезать документ — лист, чек, паспорт, карту — выровнять текст, ч/б как на сканере", new Runnable() {
            @Override
            public void run() {
                s.dismiss();
                openScan(it);
            }
        }));
        engine.albumsOf(it, new Engine.Callback<List<Engine.Album>>() {
            @Override
            public void done(List<Engine.Album> albums, Exception e) {
                if (albums == null || s.isClosing()) return;
                for (final Engine.Album a : albums) {
                    if (a.kind != Engine.Album.THING && a.kind != Engine.Album.PERSON) continue;
                    outBox.addView(actionRow(Icon.CLOSE, "Убрать из «" + a.name + "»", "это фото не про «" + a.name + "»", new Runnable() {
                        @Override
                        public void run() {
                            s.dismiss();
                            Runnable done = new Runnable() {
                                @Override
                                public void run() {
                                    toast("Убрано из «" + a.name + "»");
                                    facesChanged().run();
                                }
                            };
                            if (a.kind == Engine.Album.PERSON) engine.markPerson(it, a.name, false, done);
                            else engine.markThing(it, a.name, false, done);
                        }
                    }));
                }
            }
        });
        s.show(root);
    }

    /** Which person is on the photo, the whole photo marked: one of the people there are or someone new. */
    private void personPicker(final Sheet who, final IndexStore.Item it) {
        final Sheet s = new Sheet(this, "Кто на фото?");
        final Engine.Callback<String> mark = new Engine.Callback<String>() {
            @Override
            public void done(final String name, Exception e) {
                if (!who.isClosing()) who.dismiss();
                engine.markPerson(it, name, true, new Runnable() {
                    @Override
                    public void run() {
                        toast("Отмечено: «" + name.trim() + "»");
                        facesChanged().run();
                    }
                });
            }
        };
        for (final String name : engine.groupNames(true)) {
            s.body().addView(actionRow(Icon.PERSON, name, null, new Runnable() {
                @Override
                public void run() {
                    s.dismiss();
                    mark.done(name, null);
                }
            }));
        }
        s.body().addView(actionRow(Icon.PLUS, "Новый человек…", null, new Runnable() {
            @Override
            public void run() {
                s.dismiss();
                askName("Как зовут?", "Например: Маша", "", mark);
            }
        }));
        s.show(root);
    }

    /** Who this face is: one of the people there are, someone new, or not the one it was taken for. */
    private void personChooser(final IndexStore.Item it, final Engine.FaceTag f) {
        final Sheet s = new Sheet(this, f.name != null ? "Это " + f.name + "?" : "Кто это?");
        final Runnable after = facesChanged();
        if (f.name != null) {
            s.body().addView(actionRow(Icon.CLOSE, "Это не " + f.name, "лица, похожие на это, к «" + f.name + "» не попадут", new Runnable() {
                @Override
                public void run() {
                    s.dismiss();
                    engine.notPerson(f.faceId, f.personId, after);
                }
            }));
        }
        for (final String name : engine.groupNames(true)) {
            if (name.equals(f.name)) continue;
            s.body().addView(actionRow(Icon.PERSON, name, null, new Runnable() {
                @Override
                public void run() {
                    s.dismiss();
                    engine.nameFace(f.faceId, name, after);
                }
            }));
        }
        s.body().addView(actionRow(Icon.PLUS, "Новый человек…", null, new Runnable() {
            @Override
            public void run() {
                s.dismiss();
                askName("Как зовут?", "Например: Маша", "", new Engine.Callback<String>() {
                    @Override
                    public void done(String name, Exception e) {
                        engine.nameFace(f.faceId, name, after);
                    }
                });
            }
        }));
        s.body().addView(actionRow(Icon.HIDE, "Скрыть это лицо", "никого называть не нужно: оно больше не появится", new Runnable() {
            @Override
            public void run() {
                s.dismiss();
                hideFace(it, f);
            }
        }));
        s.show(root);
    }

    /** Which pet or thing the photo is: one there is or a new one. */
    private void thingChooser(final Sheet who, final IndexStore.Item it) {
        final Sheet s = new Sheet(this, "Что на фото?");
        final Engine.Callback<String> mark = new Engine.Callback<String>() {
            @Override
            public void done(final String name, Exception e) {
                if (!who.isClosing()) who.dismiss();
                engine.markThing(it, name, true, new Runnable() {
                    @Override
                    public void run() {
                        toast("Отмечено: «" + name.trim() + "» — похожие фото в «Альбомах»");
                        refreshShownAlbum(null);
                    }
                });
            }
        };
        for (final String name : engine.groupNames(false)) {
            s.body().addView(actionRow(Icon.TAG, name, null, new Runnable() {
                @Override
                public void run() {
                    s.dismiss();
                    mark.done(name, null);
                }
            }));
        }
        s.body().addView(actionRow(Icon.PLUS, "Новое…", "питомец, машина, дом, место", new Runnable() {
            @Override
            public void run() {
                s.dismiss();
                askName("Как назвать?", "Например: Барсик", "", mark);
            }
        }));
        s.show(root);
    }

    /** Someone unnamed, from the line above their photos: a name, or hidden — nobody to name. */
    private void unnamedChoice(final Engine.Album a) {
        final Sheet s = new Sheet(this, "Кто это?");
        s.body().addView(actionRow(Icon.PERSON, "Назвать…", "все эти фото соберутся в альбом с именем", new Runnable() {
            @Override
            public void run() {
                s.dismiss();
                nameUnnamed(a);
            }
        }));
        s.body().addView(actionRow(Icon.HIDE, "Скрыть — узнавать не нужно", "эти лица больше не появятся; вернуть — в настройках",
                new Runnable() {
                    @Override
                    public void run() {
                        s.dismiss();
                        engine.hideUnnamed(a, new Runnable() {
                            @Override
                            public void run() {
                                toast("Скрыто: " + a.faces.length + " лиц");
                                if (!places.isEmpty()) back(places.remove(places.size() - 1));
                                else showRecent(true);
                            }
                        });
                    }
                }));
        s.show(root);
    }

    /** Someone unnamed gets a name (from the line above the grid). */
    private void nameUnnamed(final Engine.Album a) {
        askName("Как зовут?", "Например: Маша", "", new Engine.Callback<String>() {
            @Override
            public void done(final String name, Exception e) {
                engine.nameUnnamed(a, name, new Runnable() {
                    @Override
                    public void run() {
                        toast("«" + name.trim() + "» — в «Альбомах», в людях");
                        refreshShownAlbum(name.trim());
                    }
                });
            }
        });
    }

    /** A person's or pet's album held in the sheet: rename or delete. */
    private void manageGroup(final Sheet albums, final Engine.Album a) {
        final Sheet s = new Sheet(this, a.name);
        s.body().addView(actionRow(Icon.NOTE, "Переименовать", null, new Runnable() {
            @Override
            public void run() {
                s.dismiss();
                askName("Новое имя", a.name, a.name, new Engine.Callback<String>() {
                    @Override
                    public void done(String name, Exception e) {
                        albums.dismiss();
                        engine.renameGroup(a.id, name, null);
                    }
                });
            }
        }));
        s.body().addView(actionRow(Icon.TRASH, "Удалить", "фото останутся на месте, пропадут только отметки", new Runnable() {
            @Override
            public void run() {
                s.dismiss();
                albums.dismiss();
                engine.deleteGroup(a.id, null);
            }
        }));
        s.show(root);
    }

    /** A line to type a name in; {@code cb} gets it (not empty). */
    private void askName(String title, String hint, String value, final Engine.Callback<String> cb) {
        final Sheet s = new Sheet(this, title);
        final EditText t = new EditText(this);
        t.setHint(hint);
        t.setText(value);
        t.setSelection(value.length());
        t.setSingleLine(true);
        t.setTextColor(Ui.TEXT);
        t.setHintTextColor(Ui.TEXT3);
        t.setTextSize(16);
        t.setTypeface(Ui.font(this, Ui.REGULAR));
        t.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        t.setImeOptions(EditorInfo.IME_ACTION_DONE);
        t.setBackground(Ui.round(this, Ui.SURFACE2, 18));
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        s.body().addView(t, new LinearLayout.LayoutParams(-1, -2));
        final Runnable done = new Runnable() {
            @Override
            public void run() {
                String name = t.getText().toString().trim();
                if (name.isEmpty()) return;
                hideKeyboard();
                s.dismiss();
                cb.done(name, null);
            }
        };
        t.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent ev) {
                done.run();
                return true;
            }
        });
        TextView ok = Sheet.button(this, "Готово", true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                done.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.topMargin = dp(14);
        s.body().addView(ok, lp);
        s.show(root);
        t.requestFocus();
    }

    /** A round place for a face (the picture comes round: faceInto). */
    private ImageView avatar(int sizeDp) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackground(Ui.round(this, Ui.SURFACE3, sizeDp / 2f));
        return v;
    }

    private final LruCache<String, Bitmap> faceCrops = new LruCache<String, Bitmap>(64);

    /** The face (a box in fractions of the photo, widened a little) cut out of the photo, into the view. */
    @Override
    public void faceInto(final ImageView v, final IndexStore.Item it, final float[] box) {
        final String key = it.id + ":" + box[0] + ":" + box[1];
        Bitmap cached = faceCrops.get(key);
        if (cached != null) {
            v.setImageBitmap(cached);
            return;
        }
        v.setTag(key);
        thumbPool.submit(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    BitmapLoader t = testLoader;
                    b = t != null ? t.load(it, 512) : Media.full(getContentResolver(), it, 1_500_000L);
                } catch (Throwable ignored) {
                    // no picture: the empty circle stays
                }
                if (b == null) return;
                float cx = (box[0] + box[2] / 2) * b.getWidth(), cy = (box[1] + box[3] / 2) * b.getHeight();
                float side = Math.max(box[2] * b.getWidth(), box[3] * b.getHeight()) * 1.4f;
                int x0 = Math.max(0, Math.round(cx - side / 2)), y0 = Math.max(0, Math.round(cy - side / 2));
                int x1 = Math.min(b.getWidth(), Math.round(cx + side / 2)), y1 = Math.min(b.getHeight(), Math.round(cy + side / 2));
                if (x1 - x0 < 2 || y1 - y0 < 2) return;
                // a square around the face, scaled, in a circle
                int side2 = Math.min(x1 - x0, y1 - y0);
                int sx = x0 + (x1 - x0 - side2) / 2, sy = y0 + (y1 - y0 - side2) / 2;
                Bitmap square = Bitmap.createScaledBitmap(Bitmap.createBitmap(b, sx, sy, side2, side2), 160, 160, true);
                final Bitmap small = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
                android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                paint.setShader(new android.graphics.BitmapShader(square, android.graphics.Shader.TileMode.CLAMP,
                        android.graphics.Shader.TileMode.CLAMP));
                new android.graphics.Canvas(small).drawCircle(80, 80, 80, paint);
                faceCrops.put(key, small);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(v.getTag())) v.setImageBitmap(small);
                    }
                });
            }
        });
    }

    @Override
    public void searchFor(String q) {
        leave(OVER_NONE);
        searchLeft = true;
        searchShown = false;
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
        // while choosing a tap chooses (or un-chooses)
        if (gallery.choosing()) gallery.toggle(index);
        else openViewer(index);
    }

    /** A long press starts choosing pictures with this one (similar ones are in the viewer: «Похожие»). */
    @Override
    public void longPress(int index) {
        gallery.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        if (gallery.choosing()) {
            gallery.toggle(index);
        } else {
            hideKeyboard();
            gallery.startChoosing(index);
        }
    }

    @Override
    public void choosingChanged() {
        boolean on = gallery.choosing();
        if (on && chooseBar.getVisibility() != View.VISIBLE) Ui.fadeIn(chooseBar, 150);
        if (!on && chooseBar.getVisibility() == View.VISIBLE) Ui.fadeOut(chooseBar, 150);
        info.animate().alpha(on ? 0f : 1f).setDuration(150).start();
        gallery.setTopInset(on ? dp(62) : 0);
        if (!on) return;
        List<IndexStore.Item> chosen = gallery.chosenItems();
        chooseCount.setText(chosen.isEmpty() ? "Выберите" : "Выбрано: " + chosen.size());
        // the documents among them (found out once per picture): a scan of them as one PDF on offer
        int photos = 0;
        boolean doc = false;
        for (final IndexStore.Item it : chosen) {
            if (it.kind != IndexStore.KIND_PHOTO) continue;
            photos++;
            Boolean d = docOf.get(it.id);
            if (d != null) {
                doc |= d;
            } else if (docAsked.add(it.id)) {
                engine.isDocument(it, new Engine.Callback<Boolean>() {
                    @Override
                    public void done(Boolean yes, Exception e) {
                        docOf.put(it.id, yes != null && yes);
                        docAsked.remove(it.id);
                        if (gallery.choosing()) choosingChanged();
                    }
                });
            }
        }
        choosePdf.setText(photos <= 1 ? "Скан в PDF" : "Скан в PDF · " + photos);
        choosePdf.setVisibility(doc ? View.VISIBLE : View.GONE);
    }

    /**
     * The chosen photos (documents among them) scanned into one PDF, a page each in the order chosen — all chosen photos
     * (a page the document finder missed still belongs), not videos or notes.
     */
    void pdfOfChosen() {
        List<IndexStore.Item> photos = new ArrayList<IndexStore.Item>();
        for (IndexStore.Item it : gallery.chosenItems()) if (it.kind == IndexStore.KIND_PHOTO) photos.add(it);
        if (photos.isEmpty()) return;
        gallery.stopChoosing();
        pdfJob = new PdfJob(this, photos);
        sheet = pdfJob.start(root);
    }

    /** The chosen pictures (and notes, as text) to another app at once. */
    void shareChosen() {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        StringBuilder text = new StringBuilder();
        boolean images = false, videos = false, other = false;
        for (IndexStore.Item it : gallery.chosenItems()) {
            if (it.kind == IndexStore.KIND_NOTE) {
                if (it.body != null) text.append(text.length() > 0 ? "\n\n" : "").append(it.body);
                continue;
            }
            uris.add(Uri.parse(it.uri));
            images |= it.kind == IndexStore.KIND_PHOTO;
            videos |= it.kind == IndexStore.KIND_VIDEO;
            other |= !IndexStore.picture(it.kind); // documents and sounds
        }
        if (uris.isEmpty() && text.length() == 0) return;
        Intent i = new Intent(uris.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
        if (uris.isEmpty()) {
            i.setType("text/plain");
        } else {
            i.setType(other || (images && videos) ? "*/*" : videos ? "video/*" : "image/*");
            if (uris.size() > 1) i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            else i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        if (text.length() > 0) i.putExtra(Intent.EXTRA_TEXT, text.toString());
        try {
            startActivity(Intent.createChooser(i, "Поделиться"));
        } catch (Exception e) {
            toast("Некуда отправить");
        }
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
        if (code == REQ_FOLDER && result == RESULT_OK && data != null && data.getData() != null) {
            engine.addFolder(data.getData());
            toast("Папка «" + Folders.label(data.getData()) + "» добавлена — читаю документы");
            if (filter != F_FILES) selectFilter(F_FILES);
            else updateEmpty();
        }
        if (code == REQ_VOICE) {
            Engine.Callback<String> cb = dictated;
            dictated = null;
            java.util.ArrayList<String> said = result == RESULT_OK && data != null
                    ? data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS) : null;
            if (cb != null && said != null && !said.isEmpty()) cb.done(said.get(0), null);
        }
    }
}
