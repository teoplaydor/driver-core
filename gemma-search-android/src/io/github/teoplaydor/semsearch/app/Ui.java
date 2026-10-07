package io.github.teoplaydor.semsearch.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Look of the app: a muted, low-contrast blue-grey palette close to black, the Manrope typeface,
 * and the few motion helpers every screen shares.
 */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0E1116;
    static final int SURFACE = 0xFF141920;
    static final int SURFACE2 = 0xFF1A2027;
    static final int SURFACE3 = 0xFF222932;
    static final int STROKE = 0xFF242B34;
    static final int TEXT = 0xFFC2CBD4;
    static final int TEXT2 = 0xFF828F9C;
    static final int TEXT3 = 0xFF5B6672;
    static final int ACCENT = 0xFF8DA6C0;
    static final int ACCENT_SOFT = 0xFF25303C;
    static final int ON_ACCENT = 0xFF0E1116;
    static final int NOTE = 0xFF19202A;
    static final int DANGER = 0xFFC39797;
    static final int SCRIM = 0xCC07090C;

    /** Material "standard" easing: quick start, long gentle settle. */
    static final Interpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final Interpolator EASE_OUT = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    static final int REGULAR = 0, MEDIUM = 1, SEMIBOLD = 2;
    private static final Typeface[] fonts = new Typeface[3];

    static synchronized Typeface font(Context c, int weight) {
        if (fonts[0] == null) {
            String[] files = {"Manrope-Regular.ttf", "Manrope-Medium.ttf", "Manrope-SemiBold.ttf"};
            for (int i = 0; i < 3; i++) {
                try {
                    fonts[i] = Typeface.createFromAsset(c.getAssets(), "fonts/" + files[i]);
                } catch (RuntimeException e) {
                    fonts[i] = i == SEMIBOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
                }
            }
        }
        return fonts[weight];
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(c, weight));
        t.setLineSpacing(0, 1.18f);
        t.setIncludeFontPadding(false);
        return t;
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable outline(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = round(c, fill, radiusDp);
        g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    static ImageView icon(Context c, int kind, int color, float sizeDp) {
        return icon(c, kind, color, sizeDp, 1.7f);
    }

    /** The glyph is 14 strokes wide: a thicker stroke draws a bigger icon. */
    static ImageView icon(Context c, int kind, int color, float sizeDp, float strokeDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(new Icon(kind, color, dp(c, strokeDp)));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setMinimumWidth(dp(c, sizeDp));
        v.setMinimumHeight(dp(c, sizeDp));
        return v;
    }

    /** Clips a view (and its children) to rounded corners. */
    static void clipRound(View v, final float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    /** Soft press feedback: the view sinks a little while touched; clicks still go to its OnClickListener. */
    static void pressable(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).setInterpolator(EASE).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(EASE).start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    static void fadeIn(View v, long ms) {
        v.animate().cancel();
        if (v.getVisibility() != View.VISIBLE) {
            v.setAlpha(0f);
            v.setVisibility(View.VISIBLE);
        }
        v.animate().alpha(1f).setDuration(ms).setInterpolator(EASE).setListener(null).start();
    }

    static void fadeOut(final View v, long ms) {
        if (v.getVisibility() != View.VISIBLE) return;
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(ms).setInterpolator(EASE).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                v.setVisibility(View.GONE);
                v.setAlpha(1f);
                v.animate().setListener(null);
            }
        }).start();
    }

    /** Swaps a text with a short cross-fade (status lines). */
    static void setTextSoft(final TextView t, final CharSequence s) {
        if (String.valueOf(t.getText()).equals(String.valueOf(s))) return;
        if (t.getVisibility() != View.VISIBLE || t.getText().length() == 0) {
            t.setText(s);
            return;
        }
        t.animate().cancel();
        t.animate().alpha(0f).setDuration(90).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                t.animate().setListener(null);
                t.setText(s);
                t.animate().alpha(1f).setDuration(160).start();
            }
        }).start();
    }
}
