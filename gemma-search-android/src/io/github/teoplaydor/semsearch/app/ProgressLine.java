package io.github.teoplaydor.semsearch.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** A thin rounded progress line: eases to new values, or a soft segment drifts across when the total is unknown. */
final class ProgressLine extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float shown, target;
    private boolean indeterminate;
    private float phase;
    private ValueAnimator ease, spin;

    ProgressLine(Context c) {
        super(c);
    }

    void setIndeterminate(boolean on) {
        if (indeterminate == on) return;
        indeterminate = on;
        if (on) startSpin();
        else if (spin != null) spin.cancel();
        invalidate();
    }

    void setProgress(float value) {
        float v = Math.max(0f, Math.min(1f, value));
        if (Math.abs(v - target) < 0.0005f) return;
        target = v;
        if (ease != null) ease.cancel();
        if (!isAttachedToWindow()) {
            shown = v;
            invalidate();
            return;
        }
        ease = ValueAnimator.ofFloat(shown, v);
        ease.setDuration(350);
        ease.setInterpolator(Ui.EASE);
        ease.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        ease.start();
    }

    private void startSpin() {
        if (spin != null) spin.cancel();
        spin = ValueAnimator.ofFloat(0f, 1f);
        spin.setDuration(1400);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                phase = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        if (isAttachedToWindow() && getVisibility() == VISIBLE) spin.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (indeterminate) startSpin();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (spin != null) spin.cancel();
        if (ease != null) ease.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (spin == null) return;
        if (visibility == VISIBLE && indeterminate && isAttachedToWindow()) {
            if (!spin.isStarted()) spin.start();
        } else {
            spin.cancel();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), rad = h / 2;
        r.set(0, 0, w, h);
        p.setColor(Ui.SURFACE3);
        c.drawRoundRect(r, rad, rad, p);
        p.setColor(Ui.ACCENT);
        if (indeterminate) {
            float seg = w * 0.32f, x = -seg + (w + seg) * phase;
            r.set(Math.max(0, x), 0, Math.min(w, x + seg), h);
        } else {
            r.set(0, 0, w * shown, h);
        }
        if (r.width() > 0) c.drawRoundRect(r, rad, rad, p);
    }
}
