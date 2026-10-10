package io.github.teoplaydor.semsearch.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * The translucent vertical panel under the thumb: settings on top, the six filters (a highlight
 * glides between them), albums by meaning, "new note" while notes are shown, and search at the bottom where the
 * thumb rests. Icons only; a filter's name flashes in a small chip beside the panel when it changes.
 */
final class Rail extends FrameLayout {
    interface Listener {
        void filterChosen(int index);

        void searchTapped();

        void settingsTapped();

        void newNoteTapped();

        void albumsTapped();
    }

    static final int[] FILTER_ICONS = {Icon.GRID, Icon.IMAGE, Icon.VIDEO, Icon.NOTE, Icon.FILE, Icon.AUDIO};
    static final String[] FILTER_NAMES = {"Все", "Фото", "Видео", "Заметки", "Файлы", "Аудио"};
    static final int BACKGROUND = 0xD9141920;

    private final LinearLayout column;
    private final ImageView[] filters = new ImageView[FILTER_ICONS.length];
    private final ImageView search, plus;
    private final Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float pos;
    private int selected;
    private ValueAnimator glide;

    Rail(Context c, final Listener l) {
        super(c);
        setClipChildren(false);
        column = new LinearLayout(c) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                drawHighlight(canvas);
                super.dispatchDraw(canvas);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setWillNotDraw(false);
        column.setBackground(Ui.round(c, BACKGROUND, 28));
        int pad = Ui.dp(c, 5);
        column.setPadding(pad, pad, pad, pad);
        column.setElevation(Ui.dp(c, 4));
        addView(column, new LayoutParams(-2, -2));
        hl.setColor(Ui.ACCENT_SOFT);

        ImageView settings = button(c, Icon.TUNE, Ui.TEXT2, "Настройки");
        settings.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                l.settingsTapped();
            }
        });
        column.addView(settings, size(c, 46));
        column.addView(divider(c), dividerParams(c));
        for (int i = 0; i < filters.length; i++) {
            final int idx = i;
            ImageView b = button(c, FILTER_ICONS[i], i == 0 ? Ui.TEXT : Ui.TEXT3, FILTER_NAMES[i]);
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    l.filterChosen(idx);
                }
            });
            filters[i] = b;
            column.addView(b, size(c, 46));
        }
        ImageView albums = button(c, Icon.ALBUMS, Ui.TEXT2, "Альбомы");
        albums.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                l.albumsTapped();
            }
        });
        column.addView(divider(c), dividerParams(c));
        column.addView(albums, size(c, 46));
        plus = button(c, Icon.PLUS, Ui.TEXT, "Новая заметка");
        plus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                l.newNoteTapped();
            }
        });
        plus.setVisibility(GONE);
        LinearLayout.LayoutParams pl = size(c, 46);
        pl.topMargin = Ui.dp(c, 4);
        column.addView(plus, pl);
        search = Ui.icon(c, Icon.SEARCH, Ui.ON_ACCENT, 50, 1.9f);
        search.setContentDescription("Поиск");
        search.setBackground(Ui.round(c, Ui.ACCENT, 25));
        search.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                l.searchTapped();
            }
        });
        Ui.pressable(search);
        LinearLayout.LayoutParams sl = size(c, 50);
        sl.topMargin = Ui.dp(c, 8);
        column.addView(search, sl);
    }

    private static ImageView button(Context c, int icon, int color, String label) {
        ImageView b = Ui.icon(c, icon, color, 46);
        b.setContentDescription(label);
        Ui.pressable(b);
        return b;
    }

    private static LinearLayout.LayoutParams size(Context c, int dp) {
        return new LinearLayout.LayoutParams(Ui.dp(c, dp), Ui.dp(c, dp));
    }

    private static View divider(Context c) {
        View d = new View(c);
        d.setBackgroundColor(Ui.STROKE);
        return d;
    }

    private static LinearLayout.LayoutParams dividerParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 24), Math.max(1, Ui.dp(c, 1)));
        lp.setMargins(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        return lp;
    }

    private void drawHighlight(Canvas canvas) {
        ImageView a = filters[0], b = filters[filters.length - 1];
        if (a.getHeight() == 0) return;
        float step = (b.getTop() - a.getTop()) / (float) (filters.length - 1);
        float top = a.getTop() + step * pos;
        r.set(a.getLeft(), top, a.getRight(), top + a.getHeight());
        float rad = r.width() / 2;
        canvas.drawRoundRect(r, rad, rad, hl);
    }

    void select(int idx, boolean animate) {
        selected = idx;
        if (glide != null) glide.cancel();
        if (animate && isAttachedToWindow()) {
            glide = ValueAnimator.ofFloat(pos, idx);
            glide.setDuration(280);
            glide.setInterpolator(Ui.EASE);
            glide.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator va) {
                    pos = (Float) va.getAnimatedValue();
                    column.invalidate();
                }
            });
            glide.start();
        } else {
            pos = idx;
            column.invalidate();
        }
        for (int i = 0; i < filters.length; i++) {
            ((Icon) filters[i].getDrawable()).setColor(i == idx ? Ui.TEXT : Ui.TEXT3);
        }
    }

    int selected() {
        return selected;
    }

    /** Vertical centre of a filter button, in the rail's coordinates. */
    float filterCenterY(int i) {
        ImageView b = filters[i];
        return column.getTop() + b.getTop() + b.getHeight() / 2f;
    }

    void showNewNote(boolean show) {
        if (show == (plus.getVisibility() == VISIBLE)) return;
        if (show) Ui.fadeIn(plus, 200);
        else plus.setVisibility(GONE);
    }

    /** The search button while a query is shown: outlined instead of filled. */
    void setSearchActive(boolean active) {
        search.setBackground(Ui.round(getContext(), active ? Ui.ACCENT_SOFT : Ui.ACCENT, 25));
        ((Icon) search.getDrawable()).setColor(active ? Ui.ACCENT : Ui.ON_ACCENT);
    }

    View searchButton() {
        return search;
    }
}
