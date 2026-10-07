package io.github.teoplaydor.semsearch.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Full-screen photo with pinch-zoom, double-tap zoom and panning. At 1x it leaves single-finger
 * drags to its parent (the viewer pages left/right and closes on a downward swipe).
 */
final class PhotoView extends View {
    interface TapListener {
        void tap();
    }

    private static final float MAX = 5f, DOUBLE_TAP = 2.5f;

    private Bitmap bmp;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final RectF fit = new RectF();
    private float scale = 1f, tx, ty; // zoom around the fitted image: screen = fit * scale + (tx, ty)
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private ValueAnimator anim;
    private TapListener tapListener;

    PhotoView(Context c) {
        super(c);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAt(scale * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                if (scale < 1f) animateTo(1f, getWidth() / 2f, getHeight() / 2f);
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapListener != null) tapListener.tap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                animateTo(scale > 1.05f ? 1f : DOUBLE_TAP, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (scale <= 1.01f) return false;
                tx -= dx;
                ty -= dy;
                clamp();
                invalidate();
                return true;
            }
        });
    }

    void setTapListener(TapListener l) {
        tapListener = l;
    }

    /** Shows a bitmap; the higher-resolution one that replaces a thumbnail keeps the current zoom. */
    void setBitmap(Bitmap b) {
        bmp = b;
        computeFit();
        clamp();
        invalidate();
    }

    Bitmap bitmap() {
        return bmp;
    }

    boolean zoomed() {
        return scale > 1.01f;
    }

    void resetZoom() {
        if (anim != null) anim.cancel();
        scale = 1f;
        tx = ty = 0;
        invalidate();
    }

    /** Where the image sits at 1x, in view coordinates (for the open/close animation). */
    RectF fitRect() {
        computeFit();
        return new RectF(fit);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        computeFit();
        clamp();
    }

    private void computeFit() {
        float w = getWidth(), h = getHeight();
        if (bmp == null || w == 0 || h == 0) {
            fit.set(0, 0, w, h);
            return;
        }
        float s = Math.min(w / bmp.getWidth(), h / bmp.getHeight());
        float bw = bmp.getWidth() * s, bh = bmp.getHeight() * s;
        fit.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
    }

    private void zoomAt(float target, float fx, float fy) {
        float s = Math.max(0.85f, Math.min(MAX, target));
        // keep the point under the fingers in place
        tx = fx - (fx - tx) * s / scale;
        ty = fy - (fy - ty) * s / scale;
        // the transform scales around the view origin; express it relative to the view centre
        scale = s;
        clamp();
        invalidate();
    }

    private void animateTo(final float target, final float fx, final float fy) {
        if (anim != null) anim.cancel();
        final float from = scale;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(260);
        anim.setInterpolator(Ui.EASE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                zoomAt(from + (target - from) * t, fx, fy);
            }
        });
        anim.start();
    }

    private void clamp() {
        if (scale <= 1f) {
            // at or below 1x the picture stays centred
            float w = getWidth(), h = getHeight();
            tx = w / 2f * (1 - scale);
            ty = h / 2f * (1 - scale);
            return;
        }
        float w = getWidth(), h = getHeight();
        float left = fit.left * scale + tx, right = fit.right * scale + tx;
        float top = fit.top * scale + ty, bottom = fit.bottom * scale + ty;
        float iw = right - left, ih = bottom - top;
        if (iw <= w) tx += (w - iw) / 2 - left;
        else if (left > 0) tx -= left;
        else if (right < w) tx += w - right;
        if (ih <= h) ty += (h - ih) / 2 - top;
        else if (top > 0) ty -= top;
        else if (bottom < h) ty += h - bottom;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if (zoomed() || e.getPointerCount() > 1) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp == null || bmp.isRecycled()) return;
        m.reset();
        m.setRectToRect(new RectF(0, 0, bmp.getWidth(), bmp.getHeight()), fit, Matrix.ScaleToFit.FILL);
        m.postScale(scale, scale);
        m.postTranslate(tx, ty);
        c.drawBitmap(bmp, m, paint);
    }
}
