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

/**
 * Full-screen viewer over the gallery: grows out of the tapped tile, swipes left/right through the
 * current results, closes with a swipe down; photos zoom, videos play in place, notes read as cards.
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
    }

    private final Host host;
    private final List<IndexStore.Item> items;
    private int index;
    private final View scrim;
    private final Pager pager;
    private final LinearLayout top, bottom;
    private final TextView title, subtitle;
    private final Page[] pages = new Page[3];
    private boolean chrome = true, closing;

    Viewer(Context c, Host host, List<IndexStore.Item> items, int index) {
        super(c);
        this.host = host;
        this.items = items;
        this.index = index;
        setClickable(true);
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
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(Ui.dp(c, 12), Ui.dp(c, 30), Ui.dp(c, 12), Ui.dp(c, 18));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC07090C, 0x0007090C}));
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
                if (tile == null || fit == null || fit.width() <= 0 || p.item.kind == IndexStore.KIND_NOTE) {
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
                float s = s0 + (1 - s0) * t;
                v.setScaleX(s);
                v.setScaleY(s);
                v.setTranslationX(tx0 * (1 - t));
                v.setTranslationY(ty0 * (1 - t));
                clip.set(Math.round(clip0.left + (clip1.left - clip0.left) * t), Math.round(clip0.top + (clip1.top - clip0.top) * t),
                        Math.round(clip0.right + (clip1.right - clip0.right) * t), Math.round(clip0.bottom + (clip1.bottom - clip0.bottom) * t));
                v.setClipBounds(clip);
                scrim.setAlpha(t);
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
        Rect tile = p.item.kind == IndexStore.KIND_NOTE || p.getTranslationY() != 0 ? null : host.tileRect(p.item);
        if (tile != null && p.photo.bitmap() != null && !p.photo.zoomed()) {
            morph(p.photo, tile, p.photo.fitRect(), false, remove);
            return;
        }
        scrim.animate().alpha(0f).setDuration(220).start();
        p.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).translationY(p.getTranslationY() + Ui.dp(getContext(), 40))
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
        subtitle.setText(it.kind == IndexStore.KIND_NOTE ? "Заметка" : it.title != null ? it.title : "");
        bottom.removeAllViews();
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
            action(Icon.TRASH, "Удалить", new Runnable() {
                @Override
                public void run() {
                    host.delete(it);
                }
            });
        } else {
            action(Icon.OPEN, "Открыть в…", new Runnable() {
                @Override
                public void run() {
                    host.openWith(it);
                }
            });
        }
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
        t.setPadding(0, Ui.dp(c, 6), 0, 0);
        b.addView(t);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        Ui.pressable(b);
        bottom.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
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

        private void showNote(IndexStore.Item it) {
            Context c = getContext();
            noteView = new ScrollView(c);
            noteView.setFillViewport(true);
            noteView.setVerticalScrollBarEnabled(false);
            FrameLayout holder = new FrameLayout(c);
            TextView t = Ui.text(c, it.body, 19, Ui.TEXT, Ui.REGULAR);
            t.setLineSpacing(0, 1.35f);
            t.setTextIsSelectable(true);
            t.setBackground(Ui.round(c, Ui.NOTE, 24));
            t.setPadding(Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 24));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
            lp.setMargins(Ui.dp(c, 20), Ui.dp(c, 90), Ui.dp(c, 20), Ui.dp(c, 110));
            holder.addView(t, lp);
            holder.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleChrome();
                }
            });
            noteView.addView(holder, new ScrollView.LayoutParams(-1, -1));
            addView(noteView, new LayoutParams(-1, -1));
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
        private int mode; // 0 undecided, 1 horizontal, 2 dismiss
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
                    if (my > slop && my > Math.abs(mx) && pages[1].video == null) {
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
                        dy = Math.max(0, e.getY() - downY);
                        float f = Math.min(1f, dy / h);
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
                        if (dy > h / 6 || vy > 1500) {
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
