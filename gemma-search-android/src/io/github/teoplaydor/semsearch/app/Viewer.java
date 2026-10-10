package io.github.teoplaydor.semsearch.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import io.github.teoplaydor.semsearch.core.NoteText;

/**
 * Full-screen viewer over the gallery: grows out of the tapped tile, swipes left/right through the
 * current results, closes with a swipe up or down — the photo goes back into its tile; photos zoom, videos play
 * in place, notes read as cards. "Что на фото" lists the words of a vocabulary the picture matches best.
 */
final class Viewer extends FrameLayout {
    interface Host {
        Bitmap thumb(IndexStore.Item it);

        void loadFull(IndexStore.Item it, Engine.Callback<Bitmap> cb);

        /** On-screen rectangle of the item's tile in the grid, or null when it is not visible. */
        Rect tileRect(IndexStore.Item it);

        void similar(IndexStore.Item it);

        void share(IndexStore.Item it);

        void openWith(IndexStore.Item it);

        void delete(IndexStore.Item it);

        void viewerClosed();

        /** Words for what the picture shows (Engine.describe), best first. */
        void describe(IndexStore.Item it, Engine.Callback<List<String>> cb);

        /** Closes the viewer and searches for this text. */
        void searchFor(String query);

        /** Hiding of 18+ is on: a photo can be hidden by hand, a hidden one shown. */
        boolean hidingOn();

        boolean isHidden(IndexStore.Item it);

        void setHidden(IndexStore.Item it, boolean hide);

        /** Marking the photo: a pet or anything else (and the face models, when not there yet). */
        void whoIsThis(IndexStore.Item it);

        /** The face models are there: the photo's faces are shown on it. */
        boolean facesReady();

        void facesOf(IndexStore.Item it, Engine.Callback<List<Engine.FaceTag>> cb);

        /** The face (a box in fractions of the photo) cut out, round, into the view. */
        void faceInto(ImageView v, IndexStore.Item it, float[] box);

        /** A face tapped: who it is. */
        void faceTapped(IndexStore.Item it, Engine.FaceTag f);

        /** A face nobody needs to name, hidden. */
        void hideFace(IndexStore.Item it, Engine.FaceTag f);

        /** Whether the photo shows a document (Engine.isDocument). */
        void isDocument(IndexStore.Item it, Engine.Callback<Boolean> cb);

        /** The document made ready to print (ScanPanel). */
        void openScan(IndexStore.Item it);

        /** Every album the picture is in (Engine.albumsOf). */
        void albumsOf(IndexStore.Item it, Engine.Callback<List<Engine.Album>> cb);

        /** Closes the viewer and shows the album. */
        void openAlbum(Engine.Album a);

        /** The note changed in its editor. */
        void editNote(IndexStore.Item note);

        /** A note to this photo (a new one, or one of those it has). */
        void noteToPhoto(IndexStore.Item photo);

        /** How many notes the photo has. */
        int notesOf(IndexStore.Item photo);

        /** The photo the note was made to, if it is in the gallery. */
        IndexStore.Item linkedPhoto(IndexStore.Item note);

        /** That photo shown (the note's viewer gives way to it). */
        void openPhoto(IndexStore.Item photo);

        /** A box of the note ticked or unticked. */
        void tick(IndexStore.Item note, int line);

        void setPinned(IndexStore.Item note, boolean pinned);

        /** A reminder of the note chosen (or taken away). */
        void remind(IndexStore.Item note);

        /** A PDF's page as a picture (null when it cannot be drawn), off the main thread. */
        void pdfPage(IndexStore.Item file, int page, Engine.Callback<Bitmap> cb);
    }

    private final Host host;
    private final List<IndexStore.Item> items;
    private int index;
    private final View scrim;
    private final Pager pager;
    private final LinearLayout top, bottom, actions, tagsBox, tagsRow, albumsRow, facesRow;
    private final View facesScroll;
    /** The photo the scan offer is shown for. */
    private IndexStore.Item docShown;
    private final TextView tagsNote, albumsTitle;
    private final View albumsScroll;
    /** "Что на фото" is open: it follows the photo when swiping to the next one. */
    private boolean tagsShown;
    private final TextView title, subtitle;
    private final Page[] pages = new Page[3];
    private boolean chrome = true, closing;

    Viewer(Context c, Host host, List<IndexStore.Item> items, int index) {
        super(c);
        this.host = host;
        this.items = items;
        this.index = index;
        setClickable(true);
        // above the raised search bar, results chip and status (elevation 3–4 dp draws over later siblings without it)
        setTranslationZ(Ui.dp(c, 16));
        scrim = new View(c);
        scrim.setBackgroundColor(0xFF07090C);
        addView(scrim, new LayoutParams(-1, -1));
        pager = new Pager(c);
        addView(pager, new LayoutParams(-1, -1));
        for (int i = 0; i < 3; i++) {
            pages[i] = new Page(c);
            pager.addView(pages[i], new LayoutParams(-1, -1));
        }

        top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 26));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC07090C, 0x0007090C}));
        ImageView back = Ui.icon(c, Icon.BACK, Ui.TEXT, 44);
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(c, 6), 0, 0, 0);
        title = Ui.text(c, "", 15, Ui.TEXT, Ui.MEDIUM);
        subtitle = Ui.text(c, "", 12, Ui.TEXT2, Ui.REGULAR);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        subtitle.setPadding(0, Ui.dp(c, 3), 0, 0);
        titles.addView(title);
        titles.addView(subtitle);
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        addView(top, new LayoutParams(-1, -2, Gravity.TOP));

        bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(Ui.dp(c, 12), Ui.dp(c, 30), Ui.dp(c, 12), Ui.dp(c, 18));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE607090C, 0x0007090C}));
        // what the picture shows: a line of words, each one a search
        tagsBox = new LinearLayout(c);
        tagsBox.setOrientation(LinearLayout.VERTICAL);
        tagsBox.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), Ui.dp(c, 14));
        tagsBox.setVisibility(GONE);
        TextView tagsTitle = Ui.text(c, "Что на фото", 12, Ui.TEXT2, Ui.MEDIUM);
        tagsBox.addView(tagsTitle);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(0, Ui.dp(c, 8), 0, 0);
        scroll.setClipToPadding(false);
        tagsRow = new LinearLayout(c);
        tagsRow.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(tagsRow);
        tagsBox.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        tagsNote = Ui.text(c, "", 12, Ui.TEXT3, Ui.REGULAR);
        tagsNote.setPadding(0, Ui.dp(c, 8), 0, 0);
        tagsNote.setVisibility(GONE);
        tagsBox.addView(tagsNote);
        // the albums it is in (a photo may be in several: by meaning, people, pets), each one opens
        albumsTitle = Ui.text(c, "В альбомах", 12, Ui.TEXT2, Ui.MEDIUM);
        albumsTitle.setPadding(0, Ui.dp(c, 14), 0, 0);
        albumsTitle.setVisibility(GONE);
        tagsBox.addView(albumsTitle);
        android.widget.HorizontalScrollView ascroll = new android.widget.HorizontalScrollView(c);
        ascroll.setHorizontalScrollBarEnabled(false);
        ascroll.setPadding(0, Ui.dp(c, 8), 0, 0);
        ascroll.setClipToPadding(false);
        ascroll.setVisibility(GONE);
        albumsRow = new LinearLayout(c);
        albumsRow.setOrientation(LinearLayout.HORIZONTAL);
        ascroll.addView(albumsRow);
        albumsScroll = ascroll;
        tagsBox.addView(ascroll, new LinearLayout.LayoutParams(-1, -2));
        bottom.addView(tagsBox, new LinearLayout.LayoutParams(-1, -2));
        // who is on the photo: their faces, named or to name, right on it
        android.widget.HorizontalScrollView fscroll = new android.widget.HorizontalScrollView(c);
        fscroll.setHorizontalScrollBarEnabled(false);
        fscroll.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), Ui.dp(c, 12));
        fscroll.setClipToPadding(false);
        fscroll.setVisibility(GONE);
        facesRow = new LinearLayout(c);
        facesRow.setOrientation(LinearLayout.HORIZONTAL);
        facesRow.setGravity(Gravity.CENTER_VERTICAL);
        fscroll.addView(facesRow);
        facesScroll = fscroll;
        bottom.addView(fscroll, new LinearLayout.LayoutParams(-1, -2));
        actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        bottom.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        addView(bottom, new LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    // ------------------------------------------------------------------ open / close

    void open(final ViewGroup root) {
        root.addView(this, new ViewGroup.LayoutParams(-1, -1));
        bindAll();
        scrim.setAlpha(0f);
        top.setAlpha(0f);
        bottom.setAlpha(0f);
        post(new Runnable() {
            @Override
            public void run() {
                final Page p = pages[1];
                Rect tile = host.tileRect(items.get(index));
                RectF fit = p.photo.bitmap() != null ? p.photo.fitRect() : null;
                top.animate().alpha(1f).setDuration(260).setStartDelay(120).start();
                bottom.animate().alpha(1f).setDuration(260).setStartDelay(120).start();
                if (tile == null || fit == null || fit.width() <= 0 || MasonryView.card(p.item)) {
                    scrim.animate().alpha(1f).setDuration(220).start();
                    p.setAlpha(0f);
                    p.setScaleX(0.94f);
                    p.setScaleY(0.94f);
                    p.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(Ui.EASE).start();
                    return;
                }
                morph(p.photo, tile, fit, true, null);
            }
        });
    }

    /** Animates the photo between its grid tile (cropped square) and its full-screen fit. */
    private void morph(final PhotoView v, Rect tileOnScreen, final RectF fit, final boolean opening, final Runnable done) {
        morph(v, tileOnScreen, fit, opening, 1f, 0f, 0f, 1f, done);
    }

    /**
     * The same from (or to) another state of the photo than its fit: {@code scale}, {@code tx}, {@code ty} (pivot at its
     * top left) and the scrim's alpha — where a swipe up or down left it.
     */
    private void morph(final PhotoView v, Rect tileOnScreen, final RectF fit, final boolean opening, final float fromScale,
                       final float fromTx, final float fromTy, final float fromScrim, final Runnable done) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        final RectF tile = new RectF(tileOnScreen.left - loc[0], tileOnScreen.top - loc[1],
                tileOnScreen.right - loc[0], tileOnScreen.bottom - loc[1]);
        final float s0 = Math.max(tile.width() / fit.width(), tile.height() / fit.height());
        final float tx0 = tile.centerX() - fit.centerX() * s0, ty0 = tile.centerY() - fit.centerY() * s0;
        // what of the view, in its own coordinates, is inside the tile at the start
        final RectF clip0 = new RectF((tile.left - tx0) / s0, (tile.top - ty0) / s0, (tile.right - tx0) / s0, (tile.bottom - ty0) / s0);
        final RectF clip1 = new RectF(0, 0, v.getWidth(), v.getHeight());
        v.setPivotX(0);
        v.setPivotY(0);
        ValueAnimator a = ValueAnimator.ofFloat(opening ? 0f : 1f, opening ? 1f : 0f);
        a.setDuration(opening ? 300 : 260);
        a.setInterpolator(Ui.EASE);
        final Rect clip = new Rect();
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator an) {
                float t = (Float) an.getAnimatedValue();
                float s = s0 + (fromScale - s0) * t;
                v.setScaleX(s);
                v.setScaleY(s);
                v.setTranslationX(tx0 + (fromTx - tx0) * t);
                v.setTranslationY(ty0 + (fromTy - ty0) * t);
                clip.set(Math.round(clip0.left + (clip1.left - clip0.left) * t), Math.round(clip0.top + (clip1.top - clip0.top) * t),
                        Math.round(clip0.right + (clip1.right - clip0.right) * t), Math.round(clip0.bottom + (clip1.bottom - clip0.bottom) * t));
                v.setClipBounds(clip);
                scrim.setAlpha(fromScrim * t);
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                v.setClipBounds(null);
                if (done != null) done.run();
            }
        });
        a.start();
    }

    boolean isClosing() {
        return closing;
    }

    void close() {
        if (closing) return;
        closing = true;
        final Page p = pages[1];
        p.stopVideo();
        Runnable remove = new Runnable() {
            @Override
            public void run() {
                ViewGroup parent = (ViewGroup) getParent();
                if (parent != null) parent.removeView(Viewer.this);
                host.viewerClosed();
            }
        };
        top.animate().alpha(0f).setDuration(150).start();
        bottom.animate().alpha(0f).setDuration(150).start();
        Rect tile = MasonryView.card(p.item) ? null : host.tileRect(p.item);
        if (tile != null && p.photo.bitmap() != null && !p.photo.zoomed()) {
            // swiped up or down: from where the swipe left it (the page's offset and scale, moved onto the photo)
            float ps = p.getScaleX();
            float fx = p.getPivotX() * (1 - ps) + p.getTranslationX(), fy = p.getPivotY() * (1 - ps) + p.getTranslationY();
            p.animate().cancel();
            p.setScaleX(1f);
            p.setScaleY(1f);
            p.setTranslationY(0);
            morph(p.photo, tile, p.photo.fitRect(), false, ps, fx, fy, scrim.getAlpha(), remove);
            return;
        }
        scrim.animate().alpha(0f).setDuration(220).start();
        float away = (p.getTranslationY() < 0 ? -1 : 1) * Ui.dp(getContext(), 40);
        p.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).translationY(p.getTranslationY() + away)
                .setDuration(220).setInterpolator(Ui.EASE).withEndAction(remove).start();
    }

    private void toggleChrome() {
        chrome = !chrome;
        top.animate().alpha(chrome ? 1f : 0f).setDuration(200).start();
        bottom.animate().alpha(chrome ? 1f : 0f).setDuration(200).start();
        top.setVisibility(VISIBLE);
        bottom.setVisibility(VISIBLE);
    }

    // ------------------------------------------------------------------ pages

    private void bindAll() {
        for (int i = 0; i < 3; i++) {
            int j = index - 1 + i;
            pages[i].bind(j >= 0 && j < items.size() ? items.get(j) : null);
        }
        layoutPages(0);
        updateChrome();
    }

    private void layoutPages(float dx) {
        float w = pager.getWidth() + Ui.dp(getContext(), 16);
        for (int i = 0; i < 3; i++) pages[i].setTranslationX((i - 1) * w + dx);
    }

    private void updateChrome() {
        final IndexStore.Item it = items.get(index);
        Context c = getContext();
        title.setText(DateFormat.getDateInstance(DateFormat.LONG).format(new Date(it.date)));
        subtitle.setText(it.kind == IndexStore.KIND_NOTE ? noteState(it) : it.title != null ? it.title : "");
        actions.removeAllViews();
        loadFaces(it);
        if (MasonryView.card(it)) {
            tagsBox.setVisibility(GONE);
        } else if (tagsShown) {
            loadTags(it);
        }
        action(Icon.SIMILAR, "Похожие", new Runnable() {
            @Override
            public void run() {
                host.similar(it);
            }
        });
        action(Icon.SHARE, "Поделиться", new Runnable() {
            @Override
            public void run() {
                host.share(it);
            }
        });
        if (it.kind == IndexStore.KIND_NOTE) {
            action(Icon.EDIT, "Изменить", new Runnable() {
                @Override
                public void run() {
                    host.editNote(it);
                }
            });
            action(Icon.PIN, it.pinned ? "Открепить" : "Закрепить", new Runnable() {
                @Override
                public void run() {
                    host.setPinned(it, !it.pinned);
                    updateChrome();
                }
            });
            action(Icon.BELL, it.remind > 0 ? "Напоминание" : "Напомнить", new Runnable() {
                @Override
                public void run() {
                    host.remind(it);
                }
            });
            action(Icon.TRASH, "Удалить", new Runnable() {
                @Override
                public void run() {
                    host.delete(it);
                }
            });
        } else if (MasonryView.card(it)) {
            // a document or a sound: in another app
            action(Icon.OPEN, "Открыть", new Runnable() {
                @Override
                public void run() {
                    host.openWith(it);
                }
            });
        } else {
            action(Icon.TAG, it.kind == IndexStore.KIND_VIDEO ? "Что на видео" : "Что на фото", new Runnable() {
                @Override
                public void run() {
                    tagsShown = !tagsShown;
                    if (tagsShown) loadTags(it);
                    else tagsBox.setVisibility(GONE);
                }
            });
            if (host.hidingOn()) {
                final boolean hidden = host.isHidden(it);
                action(hidden ? Icon.SHOW : Icon.HIDE, hidden ? "Вернуть" : "Скрыть", new Runnable() {
                    @Override
                    public void run() {
                        host.setHidden(it, !hidden);
                    }
                });
            }
            int notes = host.notesOf(it);
            action(Icon.NOTE, notes == 0 ? "Заметка" : "Заметки · " + notes, new Runnable() {
                @Override
                public void run() {
                    host.noteToPhoto(it);
                }
            });
            action(Icon.OPEN, "Открыть", new Runnable() {
                @Override
                public void run() {
                    host.openWith(it);
                }
            });
        }
    }

    /** «Заметка», and whether it is pinned, when it reminds, when it was changed. */
    private static String noteState(IndexStore.Item it) {
        StringBuilder b = new StringBuilder("Заметка");
        if (it.pinned) b.append(" · закреплена");
        if (it.remind > 0) b.append(" · напомнит ").append(NoteEditor.when(it.remind));
        if (it.edited > 0) b.append(" · изменена ").append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(it.edited)));
        return b.toString();
    }

    /** The note shown again (changed in its editor, pinned, its reminder set). */
    void refreshNote() {
        if (items.isEmpty()) return;
        pages[1].bind(items.get(index));
        updateChrome();
    }

    /** The photo shown. */
    IndexStore.Item current() {
        return items.get(index);
    }

    /** The faces of the photo shown, again (after one was named or hidden). */
    void refreshFaces() {
        loadFaces(items.get(index));
    }

    /** The photo's faces as small round pictures with names; «+» marks a pet or anything else. */
    private void loadFaces(final IndexStore.Item it) {
        facesRow.removeAllViews();
        if (it.kind != IndexStore.KIND_PHOTO) {
            facesScroll.setVisibility(GONE);
            return;
        }
        facesScroll.setVisibility(VISIBLE);
        // a document: offered as a scan for printing, first in the row
        host.isDocument(it, new Engine.Callback<Boolean>() {
            @Override
            public void done(Boolean doc, Exception e) {
                if (items.get(index) != it || doc == null || !doc) return;
                View scan = pill(Icon.SCAN, "Скан для печати", it);
                scan.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.openScan(it);
                    }
                });
                ((LinearLayout.LayoutParams) scan.getLayoutParams()).rightMargin = Ui.dp(getContext(), 8);
                facesRow.addView(scan, 0);
                docShown = it;
            }
        });
        docShown = null;
        if (!host.facesReady()) {
            facesRow.addView(pill(Icon.PERSON, "Кто это", it));
            return;
        }
        host.facesOf(it, new Engine.Callback<List<Engine.FaceTag>>() {
            @Override
            public void done(List<Engine.FaceTag> faces, Exception e) {
                if (items.get(index) != it) return;
                // the scan offer, when it came first, stays
                View scan = docShown == it && facesRow.getChildCount() > 0 ? facesRow.getChildAt(0) : null;
                facesRow.removeAllViews();
                if (scan != null) facesRow.addView(scan);
                if (faces != null) for (Engine.FaceTag f : faces) facesRow.addView(facePill(it, f));
                facesRow.addView(pill(Icon.PLUS, "Отметить", it));
            }
        });
    }

    private View facePill(final IndexStore.Item it, final Engine.FaceTag f) {
        Context c = getContext();
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(Ui.round(c, 0xCC222932, 20));
        p.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, f.name == null ? 6 : 14), Ui.dp(c, 4));
        ImageView face = new ImageView(c);
        face.setBackground(Ui.round(c, 0xFF2C3440, 16));
        host.faceInto(face, it, new float[]{f.x, f.y, f.w, f.h});
        p.addView(face, new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32)));
        TextView name = Ui.text(c, f.name != null ? f.name : "Кто это?", 13.5f, f.name != null ? Ui.TEXT : Ui.TEXT2, Ui.MEDIUM);
        name.setPadding(Ui.dp(c, 8), 0, 0, 0);
        p.addView(name);
        p.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.faceTapped(it, f);
            }
        });
        if (f.name == null) {
            // a face nobody needs to name (a passer-by): hidden with one tap, right beside «Кто это?»
            ImageView hide = Ui.icon(c, Icon.HIDE, Ui.TEXT3, 30);
            hide.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
            hide.setContentDescription("Скрыть лицо");
            hide.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.hideFace(it, f);
                }
            });
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30));
            hl.leftMargin = Ui.dp(c, 4);
            p.addView(hide, hl);
        }
        Ui.pressable(p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(c, 40));
        lp.rightMargin = Ui.dp(c, 8);
        p.setLayoutParams(lp);
        return p;
    }

    private View pill(int icon, String label, final IndexStore.Item it) {
        Context c = getContext();
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(Ui.round(c, 0xCC222932, 20));
        p.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 14), 0);
        p.addView(Ui.icon(c, icon, Ui.TEXT2, 22), new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
        TextView t = Ui.text(c, label, 13.5f, Ui.TEXT2, Ui.MEDIUM);
        t.setPadding(Ui.dp(c, 6), 0, 0, 0);
        p.addView(t);
        p.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.whoIsThis(it);
            }
        });
        Ui.pressable(p);
        p.setLayoutParams(new LinearLayout.LayoutParams(-2, Ui.dp(c, 40)));
        return p;
    }

    /** The words for this item into the panel (a later swipe's item wins over an earlier one's answer). */
    private void loadTags(final IndexStore.Item it) {
        Context c = getContext();
        ((TextView) tagsBox.getChildAt(0)).setText(it.kind == IndexStore.KIND_VIDEO ? "Что на видео" : "Что на фото");
        tagsBox.setVisibility(VISIBLE);
        tagsRow.removeAllViews();
        tagsNote.setText("Подбираю слова… (в первый раз — до полуминуты: словарь переводится в векторы)");
        tagsNote.setVisibility(VISIBLE);
        host.describe(it, new Engine.Callback<List<String>>() {
            @Override
            public void done(List<String> words, Exception e) {
                if (items.get(index) != it || !tagsShown) return;
                tagsRow.removeAllViews();
                if (e != null) {
                    tagsNote.setText("Не получилось: " + e.getMessage());
                    return;
                }
                if (words.isEmpty()) {
                    tagsNote.setText("Ничего определённого — модель не выделяет здесь ни одного слова из словаря");
                    return;
                }
                tagsNote.setText("Слова, которые модель видит в этом кадре сильнее, чем в остальной галерее; нажмите — найдутся похожие");
                for (final String w : words) tagsRow.addView(chip(w));
            }
        });
        albumsRow.removeAllViews();
        albumsTitle.setVisibility(GONE);
        albumsScroll.setVisibility(GONE);
        host.albumsOf(it, new Engine.Callback<List<Engine.Album>>() {
            @Override
            public void done(List<Engine.Album> albums, Exception e) {
                if (items.get(index) != it || !tagsShown || albums == null || albums.isEmpty()) return;
                albumsRow.removeAllViews();
                for (final Engine.Album a : albums) {
                    View chip = chip(a.name);
                    chip.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            host.openAlbum(a);
                        }
                    });
                    albumsRow.addView(chip);
                }
                albumsTitle.setVisibility(VISIBLE);
                albumsScroll.setVisibility(VISIBLE);
            }
        });
    }

    private View chip(final String word) {
        Context c = getContext();
        TextView t = Ui.text(c, word, 13.5f, Ui.TEXT, Ui.MEDIUM);
        t.setBackground(Ui.round(c, 0xCC222932, 16));
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        t.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.searchFor(word);
            }
        });
        Ui.pressable(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(c, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private void action(int icon, String label, final Runnable r) {
        Context c = getContext();
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
        b.addView(Ui.icon(c, icon, Ui.TEXT, 26), new LinearLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26)));
        TextView t = Ui.text(c, label, 11.5f, Ui.TEXT2, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2), 0);
        // one line: with six actions in the row a long word gets smaller rather than broken in two
        t.setMaxLines(1);
        float sp = c.getResources().getDisplayMetrics().scaledDensity;
        try {
            // TextView.setAutoSizeTextTypeUniformWithConfiguration (API 26, newer than the platform built against)
            TextView.class.getMethod("setAutoSizeTextTypeUniformWithConfiguration", int.class, int.class, int.class, int.class)
                    .invoke(t, Math.round(9 * sp), Math.round(11.5f * sp), 1, TypedValue.COMPLEX_UNIT_PX);
        } catch (Exception e) {
            // the label as it is
        }
        b.addView(t, new LinearLayout.LayoutParams(-1, -2));
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        Ui.pressable(b);
        actions.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    }

    /** Moves one page left (+1) or right (-1) after the swipe animation. */
    private void shift(int dir) {
        pages[1].stopVideo();
        pages[1].photo.resetZoom();
        if (dir > 0) {
            Page first = pages[0];
            pages[0] = pages[1];
            pages[1] = pages[2];
            pages[2] = first;
            index++;
            int j = index + 1;
            pages[2].bind(j < items.size() ? items.get(j) : null);
        } else {
            Page last = pages[2];
            pages[2] = pages[1];
            pages[1] = pages[0];
            pages[0] = last;
            index--;
            int j = index - 1;
            pages[0].bind(j >= 0 ? items.get(j) : null);
        }
        layoutPages(0);
        updateChrome();
    }

    final class Page extends FrameLayout {
        IndexStore.Item item;
        final PhotoView photo;
        final ImageView play;
        VideoView video;
        /** A sound playing on this page. */
        MediaPlayer player;
        ScrollView noteView;

        Page(Context c) {
            super(c);
            photo = new PhotoView(c);
            photo.setTapListener(new PhotoView.TapListener() {
                @Override
                public void tap() {
                    toggleChrome();
                }
            });
            addView(photo, new LayoutParams(-1, -1));
            play = Ui.icon(c, Icon.PLAY, Ui.TEXT, 64);
            play.setBackground(Ui.round(c, 0x99141920, 32));
            play.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    startVideo();
                }
            });
            Ui.pressable(play);
            addView(play, new LayoutParams(Ui.dp(c, 64), Ui.dp(c, 64), Gravity.CENTER));
        }

        void bind(final IndexStore.Item it) {
            stopVideo();
            item = it;
            setAlpha(1f);
            setScaleX(1f);
            setScaleY(1f);
            setTranslationY(0);
            photo.resetZoom();
            photo.setBitmap(null);
            if (noteView != null) {
                removeView(noteView);
                noteView = null;
            }
            play.setVisibility(it != null && it.kind == IndexStore.KIND_VIDEO ? VISIBLE : GONE);
            setVisibility(it == null ? INVISIBLE : VISIBLE);
            if (it == null) return;
            if (it.kind == IndexStore.KIND_NOTE) {
                photo.setVisibility(GONE);
                showNote(it);
                return;
            }
            if (it.kind == IndexStore.KIND_AUDIO) {
                photo.setVisibility(GONE);
                showSound(it);
                return;
            }
            if (it.kind == IndexStore.KIND_FILE) {
                photo.setVisibility(GONE);
                showDocument(it);
                return;
            }
            photo.setVisibility(VISIBLE);
            photo.setBitmap(host.thumb(it));
            if (it.kind == IndexStore.KIND_PHOTO) {
                host.loadFull(it, new Engine.Callback<Bitmap>() {
                    @Override
                    public void done(Bitmap b, Exception e) {
                        if (b != null && item == it) photo.setBitmap(b);
                    }
                });
            }
        }

        private void showNote(final IndexStore.Item it) {
            final Context c = getContext();
            noteView = new ScrollView(c);
            noteView.setFillViewport(true);
            noteView.setVerticalScrollBarEnabled(false);
            FrameLayout holder = new FrameLayout(c);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(c, Ui.NOTE, 24));
            card.setPadding(Ui.dp(c, 24), Ui.dp(c, 20), Ui.dp(c, 24), Ui.dp(c, 22));
            // the photo it was made to: tap — the photo
            final IndexStore.Item photo = host.linkedPhoto(it);
            if (photo != null) {
                LinearLayout to = new LinearLayout(c);
                to.setGravity(Gravity.CENTER_VERTICAL);
                to.setPadding(0, 0, 0, Ui.dp(c, 14));
                MasonryView.Thumb thumb = new MasonryView.Thumb(c);
                Bitmap b = host.thumb(photo);
                if (b != null) thumb.setImageBitmap(b);
                to.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 64)));
                TextView tl = Ui.text(c, "К фото ›", 13.5f, Ui.TEXT2, Ui.MEDIUM);
                tl.setPadding(Ui.dp(c, 12), 0, 0, 0);
                to.addView(tl);
                to.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.openPhoto(photo);
                    }
                });
                card.addView(to);
            }
            // the text: a list's items with boxes to tick, the rest as text to select and copy
            String[] lines = NoteText.lines(it.body);
            StringBuilder run = new StringBuilder();
            for (int i = 0; i <= lines.length; i++) {
                int box = i < lines.length ? NoteText.box(lines[i]) : 0;
                if (i == lines.length || box != 0) {
                    String t = run.toString().replaceAll("^\\n+|\\n+$", "");
                    if (!t.isEmpty()) card.addView(noteText(c, t));
                    run.setLength(0);
                    if (i < lines.length) card.addView(item(c, it, i, NoteText.rest(lines[i]), box == 2));
                    continue;
                }
                if (run.length() > 0) run.append('\n');
                run.append(lines[i]);
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
            lp.setMargins(Ui.dp(c, 20), Ui.dp(c, 90), Ui.dp(c, 20), Ui.dp(c, 110));
            holder.addView(card, lp);
            holder.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleChrome();
                }
            });
            noteView.addView(holder, new ScrollView.LayoutParams(-1, -1));
            addView(noteView, new LayoutParams(-1, -1));
        }

        /** A card over the page, scrolling when it is long; a tap beside it shows or hides the panels. */
        private LinearLayout cardPage(Context c, int color) {
            noteView = new ScrollView(c);
            noteView.setFillViewport(true);
            noteView.setVerticalScrollBarEnabled(false);
            FrameLayout holder = new FrameLayout(c);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(c, color, 24));
            card.setPadding(Ui.dp(c, 24), Ui.dp(c, 20), Ui.dp(c, 24), Ui.dp(c, 22));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
            lp.setMargins(Ui.dp(c, 20), Ui.dp(c, 90), Ui.dp(c, 20), Ui.dp(c, 110));
            holder.addView(card, lp);
            holder.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleChrome();
                }
            });
            noteView.addView(holder, new ScrollView.LayoutParams(-1, -1));
            addView(noteView, new LayoutParams(-1, -1));
            return card;
        }

        /** A sound: its name, length and source, a play button with its progress (the phone's player). */
        private void showSound(final IndexStore.Item it) {
            final Context c = getContext();
            LinearLayout card = cardPage(c, MasonryView.cardColor(it));
            ImageView wave = Ui.icon(c, Icon.AUDIO, Ui.ACCENT, 56, 2f);
            card.addView(wave, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));
            TextView name = Ui.text(c, it.title == null ? "" : it.title, 21, Ui.TEXT, Ui.SEMIBOLD);
            name.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 4));
            name.setTextIsSelectable(true);
            card.addView(name);
            if (it.body != null && !it.body.isEmpty()) card.addView(Ui.text(c, it.body, 15, Ui.TEXT2, Ui.REGULAR));
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(c, 20), 0, 0);
            final ImageView toggle = Ui.icon(c, Icon.PLAY, Ui.ON_ACCENT, 52);
            toggle.setBackground(Ui.round(c, Ui.ACCENT, 26));
            toggle.setContentDescription("Слушать");
            Ui.pressable(toggle);
            row.addView(toggle, new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)));
            final android.widget.SeekBar bar = new android.widget.SeekBar(c);
            bar.setMax(1000);
            row.addView(bar, new LinearLayout.LayoutParams(0, -2, 1));
            final TextView time = Ui.text(c, "", 13, Ui.TEXT2, Ui.MEDIUM);
            row.addView(time);
            card.addView(row);
            final Runnable tick = new Runnable() {
                @Override
                public void run() {
                    MediaPlayer mp = player;
                    if (mp == null || item != it) return;
                    try {
                        int d = Math.max(1, mp.getDuration());
                        bar.setProgress((int) (1000L * mp.getCurrentPosition() / d));
                        time.setText(Sound.duration(mp.getCurrentPosition()) + " / " + Sound.duration(d));
                    } catch (IllegalStateException ignored) {
                        // released meanwhile
                    }
                    if (mp.isPlaying()) postDelayed(this, 250);
                }
            };
            bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar b, int v, boolean user) {
                    MediaPlayer mp = player;
                    if (user && mp != null) {
                        try {
                            mp.seekTo((int) ((long) v * mp.getDuration() / 1000));
                        } catch (IllegalStateException ignored) {
                            // not prepared yet
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(android.widget.SeekBar b) {
                }

                @Override
                public void onStopTrackingTouch(android.widget.SeekBar b) {
                }
            });
            toggle.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (player == null) {
                        try {
                            final MediaPlayer mp = new MediaPlayer();
                            mp.setDataSource(c, Uri.parse(it.uri));
                            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                                @Override
                                public void onPrepared(MediaPlayer m) {
                                    if (player != m) return;
                                    m.start();
                                    post(tick);
                                }
                            });
                            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                                @Override
                                public void onCompletion(MediaPlayer m) {
                                    toggle.setImageDrawable(new Icon(Icon.PLAY, Ui.ON_ACCENT, Ui.dp(c, 1.8f)));
                                    bar.setProgress(1000);
                                }
                            });
                            player = mp;
                            mp.prepareAsync();
                        } catch (Exception e) {
                            time.setText("Не играет: " + e.getMessage());
                            return;
                        }
                        toggle.setImageDrawable(new Icon(Icon.PAUSE, Ui.ON_ACCENT, Ui.dp(c, 1.8f)));
                        return;
                    }
                    try {
                        if (player.isPlaying()) {
                            player.pause();
                            toggle.setImageDrawable(new Icon(Icon.PLAY, Ui.ON_ACCENT, Ui.dp(c, 1.8f)));
                        } else {
                            player.start();
                            toggle.setImageDrawable(new Icon(Icon.PAUSE, Ui.ON_ACCENT, Ui.dp(c, 1.8f)));
                            post(tick);
                        }
                    } catch (IllegalStateException ignored) {
                        // still preparing
                    }
                }
            });
        }

        /** A document: its type and name; a PDF's first pages as pictures; its text, to read and copy. */
        private void showDocument(final IndexStore.Item it) {
            final Context c = getContext();
            final LinearLayout card = cardPage(c, MasonryView.cardColor(it));
            String n = it.title == null ? "" : it.title;
            int dot = n.lastIndexOf('.');
            TextView tag = Ui.text(c, dot > 0 ? n.substring(dot + 1).toUpperCase(java.util.Locale.ROOT) : "ДОКУМЕНТ", 12.5f, Ui.ACCENT, Ui.SEMIBOLD);
            card.addView(tag);
            TextView name = Ui.text(c, Engine.baseName(n), 21, Ui.TEXT, Ui.SEMIBOLD);
            name.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 12));
            name.setTextIsSelectable(true);
            card.addView(name);
            final LinearLayout pagesBox = new LinearLayout(c);
            pagesBox.setOrientation(LinearLayout.VERTICAL);
            card.addView(pagesBox);
            if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) loadPdfPage(it, pagesBox, 0);
            if (it.body != null && !it.body.isEmpty()) {
                card.addView(noteText(c, it.body));
            } else {
                card.addView(Ui.text(c, "Текста в файле нет (скан или картинки) — «Открыть», чтобы посмотреть его целиком", 15, Ui.TEXT2,
                        Ui.REGULAR));
            }
        }

        /** A PDF's pages one after another (the first three), each once the one before is drawn. */
        private void loadPdfPage(final IndexStore.Item it, final LinearLayout box, final int page) {
            if (page >= 3) return;
            host.pdfPage(it, page, new Engine.Callback<Bitmap>() {
                @Override
                public void done(Bitmap b, Exception e) {
                    if (b == null || item != it) return;
                    Context c = getContext();
                    ImageView v = new ImageView(c);
                    v.setImageBitmap(b);
                    v.setAdjustViewBounds(true);
                    v.setBackground(Ui.round(c, 0xFFFFFFFF, 6));
                    v.setClipToOutline(true);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.bottomMargin = Ui.dp(c, 10);
                    box.addView(v, lp);
                    loadPdfPage(it, box, page + 1);
                }
            });
        }

        private TextView noteText(Context c, String t) {
            TextView v = Ui.text(c, t, 19, Ui.TEXT, Ui.REGULAR);
            v.setLineSpacing(0, 1.35f);
            v.setTextIsSelectable(true);
            v.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
            return v;
        }

        /** A list's item: its box (ticked: dimmed and struck through); a tap ticks or unticks it. */
        private View item(Context c, final IndexStore.Item it, final int line, String text, boolean done) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
            TextView box = new TextView(c);
            box.setGravity(Gravity.CENTER);
            box.setBackground(done ? Ui.round(c, Ui.ACCENT, 7) : Ui.outline(c, 0x00000000, Ui.TEXT2, 7));
            if (done) {
                box.setCompoundDrawablesWithIntrinsicBounds(new Icon(Icon.CHECK, Ui.ON_ACCENT, Ui.dp(c, 2.2f)), null, null, null);
                box.setPadding(Ui.dp(c, 1), 0, 0, 0);
            }
            row.addView(box, new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
            TextView t = Ui.text(c, text, 19, done ? Ui.TEXT3 : Ui.TEXT, Ui.REGULAR);
            if (done) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            t.setPadding(Ui.dp(c, 14), 0, 0, 0);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            row.setContentDescription((done ? "Сделано: " : "Сделать: ") + text);
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.tick(it, line);
                    bind(it);
                }
            });
            Ui.pressable(row);
            return row;
        }

        void startVideo() {
            if (item == null || video != null) return;
            Context c = getContext();
            video = new VideoView(c);
            video.setVideoURI(Uri.parse(item.uri));
            video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    photo.animate().alpha(0f).setDuration(200).start();
                }
            });
            video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopVideo();
                }
            });
            video.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (video.isPlaying()) {
                        video.pause();
                        Ui.fadeIn(play, 150);
                    } else {
                        video.start();
                        Ui.fadeOut(play, 150);
                    }
                }
            });
            addView(video, 0, new LayoutParams(-1, -1, Gravity.CENTER));
            Ui.fadeOut(play, 150);
            video.start();
        }

        void stopVideo() {
            if (player != null) {
                try {
                    player.release();
                } catch (Exception ignored) {
                    // released already
                }
                player = null;
            }
            if (video == null) return;
            video.stopPlayback();
            removeView(video);
            video = null;
            photo.setAlpha(1f);
            if (item != null && item.kind == IndexStore.KIND_VIDEO) {
                play.setAlpha(1f);
                play.setVisibility(VISIBLE);
            }
        }
    }

    // ------------------------------------------------------------------ gestures

    final class Pager extends FrameLayout {
        private final int slop;
        private float downX, downY, dx, dy;
        private int mode; // 0 undecided, 1 horizontal, 2 dismiss (up or down)
        private VelocityTracker vt;

        Pager(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            layoutPages(0);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (closing) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    mode = 0;
                    if (vt != null) vt.recycle();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (vt != null) vt.addMovement(e);
                    if (e.getPointerCount() > 1 || pages[1].photo.zoomed()) return false;
                    float mx = e.getX() - downX, my = e.getY() - downY;
                    if (Math.abs(mx) > slop && Math.abs(mx) > Math.abs(my)) {
                        mode = 1;
                        downX = e.getX();
                        return true;
                    }
                    if (Math.abs(my) > slop && Math.abs(my) > Math.abs(mx) && pages[1].video == null
                            && (pages[1].noteView == null || !pages[1].noteView.canScrollVertically(my < 0 ? 1 : -1))) {
                        // a note scrolls first; at its end the swipe closes as for a photo
                        mode = 2;
                        downY = e.getY();
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (vt != null) vt.addMovement(e);
            float w = getWidth(), h = getHeight();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (mode == 1) {
                        dx = e.getX() - downX;
                        boolean edge = (dx > 0 && index == 0) || (dx < 0 && index == items.size() - 1);
                        layoutPages(edge ? dx * 0.3f : dx);
                    } else if (mode == 2) {
                        dy = e.getY() - downY;
                        float f = Math.min(1f, Math.abs(dy) / h);
                        Page p = pages[1];
                        p.setTranslationY(dy);
                        p.setScaleX(1 - 0.18f * f);
                        p.setScaleY(1 - 0.18f * f);
                        scrim.setAlpha(1 - Math.min(1f, f * 1.6f));
                        top.setAlpha(Math.max(0, 1 - f * 4));
                        bottom.setAlpha(Math.max(0, 1 - f * 4));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float vx = 0, vy = 0;
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        vx = vt.getXVelocity();
                        vy = vt.getYVelocity();
                    }
                    if (mode == 1) settle(dx, vx, w);
                    else if (mode == 2) {
                        // either way: up or down, far enough or fast enough, the photo goes back into its tile
                        if (Math.abs(dy) > h / 6 || Math.abs(vy) > 1500) {
                            close();
                        } else {
                            Page p = pages[1];
                            p.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Ui.EASE).start();
                            scrim.animate().alpha(1f).setDuration(220).start();
                            top.animate().alpha(chrome ? 1f : 0f).setDuration(220).start();
                            bottom.animate().alpha(chrome ? 1f : 0f).setDuration(220).start();
                        }
                    }
                    mode = 0;
                    dx = dy = 0;
                    return true;
                default:
                    return true;
            }
        }

        private void settle(final float from, float vx, float w) {
            final float gap = Ui.dp(getContext(), 16);
            int dir = 0;
            if ((from < -w / 4 || vx < -900) && index < items.size() - 1) dir = 1;
            else if ((from > w / 4 || vx > 900) && index > 0) dir = -1;
            final int d = dir;
            final float to = -d * (w + gap);
            ValueAnimator a = ValueAnimator.ofFloat(from, to);
            a.setDuration(d == 0 ? 200 : 240);
            a.setInterpolator(Ui.EASE);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator an) {
                    layoutPages((Float) an.getAnimatedValue());
                }
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator an) {
                    if (d != 0) shift(d);
                    else layoutPages(0);
                }
            });
            a.start();
        }
    }
}
