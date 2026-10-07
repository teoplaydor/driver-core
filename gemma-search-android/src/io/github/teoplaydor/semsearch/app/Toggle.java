package io.github.teoplaydor.semsearch.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A quiet on/off switch whose thumb glides between the ends. */
final class Toggle extends View {
    interface Listener {
        void changed(boolean on);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean on;
    private float pos; // 0 = off, 1 = on
    private ValueAnimator anim;
    private Listener listener;

    Toggle(Context c, boolean on) {
        super(c);
        this.on = on;
        this.pos = on ? 1 : 0;
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setOn(!Toggle.this.on, true);
                if (listener != null) listener.changed(Toggle.this.on);
            }
        });
        setContentDescription("переключатель");
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isOn() {
        return on;
    }

    void setOn(boolean value, boolean animate) {
        on = value;
        if (anim != null) anim.cancel();
        if (!animate) {
            pos = on ? 1 : 0;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(pos, on ? 1 : 0);
        anim.setDuration(220);
        anim.setInterpolator(Ui.EASE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Ui.dp(getContext(), 46), Ui.dp(getContext(), 28));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), pad = Ui.dp(getContext(), 3);
        r.set(0, 0, w, h);
        p.setColor(blend(Ui.SURFACE3, Ui.ACCENT_SOFT, pos));
        c.drawRoundRect(r, h / 2, h / 2, p);
        float radius = h / 2 - pad;
        float cx = pad + radius + (w - 2 * pad - 2 * radius) * pos;
        p.setColor(blend(Ui.TEXT3, Ui.ACCENT, pos));
        c.drawCircle(cx, h / 2, radius, p);
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }
}
