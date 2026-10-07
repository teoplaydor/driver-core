package io.github.teoplaydor.semsearch.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.OverScroller;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A staggered gallery: equal columns, every picture scaled whole to the column width (wide and tall
 * shots alike, nothing cropped), each next one dropped into the shortest column. Notes become
 * cards as tall as their text. Only the tiles near the screen exist as views; they are recycled
 * while scrolling.
 */
final class MasonryView extends ViewGroup {
    interface Host {
        /** Width / height of the photo or video (rotation applied), or 0 when unknown. */
        float aspect(IndexStore.Item it);

        void bindThumb(Tile tile, IndexStore.Item it, int w, int h);

        void open(int index);

        void longPress(int index);

        void dragStarted();
    }

    /**
     * A picture filling its tile with soft corners: drawn through a bitmap shader, so the corners are
     * smooth on any canvas and no clipping layer is needed while scrolling.
     */
    static final class Thumb extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix m = new Matrix();
        private final RectF r = new RectF();
        private final float radius;
        private Bitmap bmp;

        Thumb(Context c) {
            super(c);
            radius = Ui.dp(c, 10);
        }

        Bitmap bitmap() {
            return bmp;
        }

        void setImageBitmap(Bitmap b) {
            bmp = b;
            paint.setShader(b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bmp == null) return;
            float w = getWidth(), h = getHeight(), bw = bmp.getWidth(), bh = bmp.getHeight();
            float s = Math.max(w / bw, h / bh); // fills the tile; the tile already has the picture's shape
            m.setScale(s, s);
            m.postTranslate((w - bw * s) / 2f, (h - bh * s) / 2f);
            paint.getShader().setLocalMatrix(m);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, radius, radius, paint);
        }
    }

    /** One picture or note card with soft corners. */
    static final class Tile extends FrameLayout {
        final Thumb img;
        final TextView note;
        final ImageView badge;
        long key = -1;
        int index = -1;

        Tile(Context c) {
            super(c);
            img = new Thumb(c);
            addView(img, new LayoutParams(-1, -1));
            note = Ui.text(c, "", NOTE_SP, Ui.TEXT, Ui.REGULAR);
            int pad = Ui.dp(c, NOTE_PAD_DP);
            note.setPadding(pad, pad, pad, pad);
            note.setEllipsize(TextUtils.TruncateAt.END);
            note.setLineSpacing(0, NOTE_SPACING);
            note.setGravity(Gravity.TOP | Gravity.START);
            addView(note, new LayoutParams(-1, -1));
            badge = Ui.icon(c, Icon.VIDEO, 0xFFE6ECF2, 26);
            badge.setBackground(Ui.round(c, 0x66000000, 13));
            LayoutParams bl = new LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26), Gravity.TOP | Gravity.END);
            bl.setMargins(0, Ui.dp(c, 6), Ui.dp(c, 6), 0);
            addView(badge, bl);
        }
    }

    static final float NOTE_SP = 13.5f, NOTE_SPACING = 1.25f;
    static final int NOTE_PAD_DP = 12;
    /** Panoramas and very tall screenshots are trimmed a little at these ratios. */
    private static final float MIN_ASPECT = 0.42f, MAX_ASPECT = 2.4f;

    private final Host host;
    private List<IndexStore.Item> items = new ArrayList<IndexStore.Item>();
    private int columns = 3;
    private final int gap, padH, padTop, padBottom;
    private int[] ix = new int[0], iy = new int[0], iw = new int[0], ih = new int[0];
    private int contentHeight, laidWidth = -1;
    private final SparseArray<Tile> shown = new SparseArray<Tile>();
    private final ArrayList<Tile> pool = new ArrayList<Tile>();
    private final TextPaint notePaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private boolean intro;

    private final OverScroller scroller;
    private VelocityTracker velocity;
    private final int touchSlop, minFling, maxFling;
    private float downY, lastY;
    private boolean dragging;
    private final Runnable flinger = new Runnable() {
        @Override
        public void run() {
            if (scroller.computeScrollOffset()) {
                scrollTo(0, scroller.getCurrY());
                postOnAnimation(this);
            }
        }
    };

    MasonryView(Context c, Host host) {
        super(c);
        this.host = host;
        gap = Ui.dp(c, 4);
        padH = Ui.dp(c, 10);
        padTop = Ui.dp(c, 2);
        padBottom = Ui.dp(c, 110);
        scroller = new OverScroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        notePaint.setTypeface(Ui.font(c, Ui.REGULAR));
        notePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, NOTE_SP, c.getResources().getDisplayMetrics()));
        setClipChildren(true);
    }

    List<IndexStore.Item> items() {
        return items;
    }

    /** Shows a new list; with animate the view jumps to the top and the first screen fades in. */
    void setItems(List<IndexStore.Item> list, int columns, boolean animate) {
        items = list;
        this.columns = columns;
        recycleAll();
        computePositions();
        if (animate) {
            scroller.forceFinished(true);
            intro = true;
            super.scrollTo(0, 0);
        } else {
            super.scrollTo(0, clamp(getScrollY()));
        }
        fill();
        intro = false;
        invalidate();
    }

    /** Recomputes the layout for the same items (e.g. once picture sizes are known), keeping the place. */
    void relayout() {
        IndexStore.Item anchor = null;
        int offset = 0;
        for (int i = 0; i < items.size() && i < iy.length; i++) {
            if (iy[i] + ih[i] > getScrollY()) {
                anchor = items.get(i);
                offset = iy[i] - getScrollY();
                break;
            }
        }
        recycleAll();
        computePositions();
        int i = anchor == null ? -1 : items.indexOf(anchor);
        super.scrollTo(0, clamp(i >= 0 ? iy[i] - offset : getScrollY()));
        fill();
        invalidate();
    }

    // ------------------------------------------------------------------ layout

    private void computePositions() {
        int width = getWidth();
        if (width <= 0) {
            laidWidth = -1;
            return;
        }
        laidWidth = width;
        int n = items.size();
        ix = new int[n];
        iy = new int[n];
        iw = new int[n];
        ih = new int[n];
        float step = (width - 2 * padH + gap) / (float) columns;
        int[] colH = new int[columns];
        for (int k = 0; k < columns; k++) colH[k] = padTop;
        int maxNoteLines = columns <= 2 ? 10 : 7;
        for (int i = 0; i < n; i++) {
            int col = 0;
            for (int k = 1; k < columns; k++) if (colH[k] < colH[col]) col = k;
            int x0 = padH + Math.round(col * step), x1 = padH + Math.round((col + 1) * step) - gap;
            int w = x1 - x0;
            int h = heightFor(items.get(i), w, maxNoteLines);
            ix[i] = x0;
            iy[i] = colH[col];
            iw[i] = w;
            ih[i] = h;
            colH[col] += h + gap;
        }
        int max = 0;
        for (int k = 0; k < columns; k++) max = Math.max(max, colH[k]);
        contentHeight = max + padBottom;
    }

    private int heightFor(IndexStore.Item it, int w, int maxLines) {
        if (it.kind == IndexStore.KIND_NOTE) {
            int pad = Ui.dp(getContext(), NOTE_PAD_DP);
            String body = it.body == null ? "" : it.body;
            StaticLayout l = new StaticLayout(body, notePaint, Math.max(1, w - 2 * pad), Layout.Alignment.ALIGN_NORMAL,
                    NOTE_SPACING, 0f, true);
            int lines = Math.max(1, Math.min(l.getLineCount(), maxLines));
            return Math.max(Math.round(w * 0.5f), l.getLineTop(lines) + 2 * pad);
        }
        float a = host.aspect(it);
        if (a <= 0) a = 1f;
        a = Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, a));
        return Math.round(w / a);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w), getDefaultSize(getSuggestedMinimumHeight(), h));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getWidth() != laidWidth) {
            recycleAll();
            computePositions();
            super.scrollTo(0, clamp(getScrollY()));
        }
        for (int k = 0; k < shown.size(); k++) place(shown.keyAt(k), shown.valueAt(k));
        fill();
    }

    private void place(int i, Tile t) {
        t.measure(MeasureSpec.makeMeasureSpec(iw[i], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ih[i], MeasureSpec.EXACTLY));
        t.layout(ix[i], iy[i], ix[i] + iw[i], iy[i] + ih[i]);
    }

    /** Attaches the tiles near the visible window and recycles the rest. */
    private void fill() {
        if (laidWidth < 0) return;
        int n = Math.min(items.size(), iy.length);
        int extra = getHeight() / 2;
        int top = getScrollY() - extra, bottom = getScrollY() + getHeight() + extra;
        for (int k = shown.size() - 1; k >= 0; k--) {
            int i = shown.keyAt(k);
            if (i >= n || iy[i] + ih[i] < top || iy[i] > bottom) {
                Tile t = shown.valueAt(k);
                shown.removeAt(k);
                detach(t);
            }
        }
        for (int i = 0; i < n; i++) {
            if (iy[i] + ih[i] < top || iy[i] > bottom || shown.get(i) != null) continue;
            attach(i);
        }
    }

    private void attach(final int i) {
        Tile t = pool.isEmpty() ? newTile() : pool.remove(pool.size() - 1);
        t.index = i;
        t.animate().cancel();
        t.setAlpha(1f);
        t.setTranslationY(0f);
        t.setScaleX(1f);
        t.setScaleY(1f);
        IndexStore.Item it = items.get(i);
        t.badge.setVisibility(it.kind == IndexStore.KIND_VIDEO ? VISIBLE : GONE);
        if (it.kind == IndexStore.KIND_NOTE) {
            t.setBackground(Ui.round(getContext(), Ui.NOTE, 10));
            t.img.setVisibility(GONE);
            t.img.setImageBitmap(null);
            t.note.setVisibility(VISIBLE);
            t.note.setMaxLines(columns <= 2 ? 10 : 7);
            t.note.setText(it.body);
            t.key = it.id;
        } else {
            t.setBackground(Ui.round(getContext(), Ui.SURFACE2, 10));
            t.note.setVisibility(GONE);
            t.img.setVisibility(VISIBLE);
        }
        addViewInLayout(t, -1, new LayoutParams(iw[i], ih[i]), true);
        place(i, t);
        if (it.kind != IndexStore.KIND_NOTE) host.bindThumb(t, it, iw[i], ih[i]);
        shown.put(i, t);
        if (intro && iy[i] < getScrollY() + getHeight()) {
            // the first screen rises in, top to bottom
            float rel = Math.max(0f, (iy[i] - getScrollY()) / (float) Math.max(1, getHeight()));
            t.setAlpha(0f);
            t.setTranslationY(Ui.dp(getContext(), 16));
            t.animate().alpha(1f).translationY(0f).setDuration(300).setStartDelay(Math.round(rel * 260))
                    .setInterpolator(Ui.EASE).start();
        }
        invalidate();
    }

    private void detach(Tile t) {
        t.animate().cancel();
        removeViewInLayout(t);
        t.index = -1;
        pool.add(t);
        invalidate();
    }

    private void recycleAll() {
        for (int k = shown.size() - 1; k >= 0; k--) detach(shown.valueAt(k));
        shown.clear();
    }

    private Tile newTile() {
        final Tile t = new Tile(getContext());
        t.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (t.index >= 0) host.open(t.index);
            }
        });
        t.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (t.index < 0) return false;
                host.longPress(t.index);
                return true;
            }
        });
        Ui.pressable(t);
        return t;
    }

    /** Where the item's tile is on screen, or null when it is scrolled away. */
    Rect tileRect(int i) {
        if (i < 0 || i >= iy.length || laidWidth < 0) return null;
        int sy = getScrollY();
        if (iy[i] + ih[i] <= sy || iy[i] >= sy + getHeight()) return null;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int l = loc[0] + ix[i], t = loc[1] + iy[i] - sy;
        return new Rect(l, t, l + iw[i], t + ih[i]);
    }

    /** Scrolls (instantly — the viewer covers the grid) so that the item's tile is fully visible. */
    void reveal(int i) {
        if (i < 0 || i >= iy.length || laidWidth < 0) return;
        int sy = getScrollY();
        if (iy[i] >= sy && iy[i] + ih[i] <= sy + getHeight()) return;
        scroller.forceFinished(true);
        scrollTo(0, iy[i] - (getHeight() - ih[i]) / 2);
    }

    // ------------------------------------------------------------------ scrolling

    private int maxScroll() {
        return Math.max(0, contentHeight - getHeight());
    }

    private int clamp(int y) {
        return Math.max(0, Math.min(maxScroll(), y));
    }

    @Override
    public void scrollTo(int x, int y) {
        super.scrollTo(0, clamp(y));
    }

    @Override
    protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        fill();
    }

    private void track(MotionEvent e) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
    }

    private void endTouch() {
        dragging = false;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private void startDrag() {
        dragging = true;
        ViewGroup p = (ViewGroup) getParent();
        if (p != null) p.requestDisallowInterceptTouchEvent(true);
        host.dragStarted();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                endTouch();
                track(e);
                downY = lastY = e.getY();
                if (!scroller.isFinished()) {
                    // a touch stops a fling and starts a drag, not a tap
                    scroller.forceFinished(true);
                    startDrag();
                }
                return dragging;
            case MotionEvent.ACTION_MOVE:
                track(e);
                if (!dragging && Math.abs(e.getY() - downY) > touchSlop) {
                    startDrag();
                    lastY = e.getY();
                }
                return dragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endTouch();
                return false;
            default:
                return dragging;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        track(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = lastY = e.getY();
                scroller.forceFinished(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float y = e.getY();
                if (!dragging && Math.abs(y - downY) > touchSlop) {
                    startDrag();
                    lastY = y;
                }
                if (dragging) {
                    scrollBy(0, Math.round(lastY - y));
                    lastY = y;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragging && velocity != null) {
                    velocity.computeCurrentVelocity(1000, maxFling);
                    float v = -velocity.getYVelocity();
                    if (Math.abs(v) > minFling) {
                        scroller.fling(0, getScrollY(), 0, Math.round(v), 0, 0, 0, maxScroll());
                        postOnAnimation(flinger);
                    }
                }
                endTouch();
                return true;
            case MotionEvent.ACTION_CANCEL:
                endTouch();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        scroller.forceFinished(true);
        removeCallbacks(flinger);
        super.onDetachedFromWindow();
    }
}
