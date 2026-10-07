package io.github.teoplaydor.semsearch.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Bottom sheet over a dimmed screen: slides up, closes on a tap outside or Back. */
final class Sheet extends FrameLayout {
    interface Choice {
        void chosen(int index);
    }

    private final View scrim;
    private final LinearLayout panel;
    private final LinearLayout body;
    private boolean closing;
    private Runnable onClosed;

    Sheet(Context c, String title) {
        super(c);
        scrim = new View(c);
        scrim.setBackgroundColor(Ui.SCRIM);
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        addView(scrim, new LayoutParams(-1, -1));

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        float r = Ui.dp(c, 26);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Ui.SURFACE);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setPadding(Ui.dp(c, 22), Ui.dp(c, 10), Ui.dp(c, 22), Ui.dp(c, 22));

        View handle = new View(c);
        handle.setBackground(Ui.round(c, Ui.SURFACE3, 3));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = Ui.dp(c, 14);
        panel.addView(handle, hl);
        if (title != null) {
            TextView t = Ui.text(c, title, 18, Ui.TEXT, Ui.SEMIBOLD);
            t.setPadding(0, 0, 0, Ui.dp(c, 12));
            panel.addView(t);
        }
        ScrollView sv = new ScrollView(c);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        LayoutParams pl = new LayoutParams(-1, -2, Gravity.BOTTOM);
        addView(panel, pl);
    }

    LinearLayout body() {
        return body;
    }

    void setOnClosed(Runnable r) {
        onClosed = r;
    }

    /** Adds the sheet to the screen and slides it in. */
    Sheet show(ViewGroup root) {
        root.addView(this, new ViewGroup.LayoutParams(-1, -1));
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(220).start();
        panel.setTranslationY(Ui.dp(getContext(), 600));
        panel.post(new Runnable() {
            @Override
            public void run() {
                panel.setTranslationY(panel.getHeight());
                panel.animate().translationY(0).setDuration(320).setInterpolator(Ui.EASE).start();
            }
        });
        return this;
    }

    boolean isClosing() {
        return closing;
    }

    void dismiss() {
        if (closing) return;
        closing = true;
        scrim.animate().alpha(0f).setDuration(200).start();
        panel.animate().translationY(panel.getHeight()).setDuration(220).setInterpolator(Ui.EASE_OUT)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        ViewGroup p = (ViewGroup) getParent();
                        if (p != null) p.removeView(Sheet.this);
                        if (onClosed != null) onClosed.run();
                    }
                }).start();
    }

    /** One row per option, a check on the current one, an optional quiet hint under each. */
    static Sheet choose(ViewGroup root, String title, String[] options, String[] hints, int selected, final Choice choice) {
        final Context c = root.getContext();
        final Sheet s = new Sheet(c, title);
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12));
            row.setBackground(Ui.round(c, i == selected ? Ui.ACCENT_SOFT : 0x00000000, 14));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(Ui.text(c, options[i], 15, i == selected ? Ui.TEXT : Ui.TEXT, Ui.MEDIUM));
            if (hints != null && hints[i] != null) {
                TextView h = Ui.text(c, hints[i], 12.5f, Ui.TEXT2, Ui.REGULAR);
                h.setPadding(0, Ui.dp(c, 4), 0, 0);
                texts.addView(h);
            }
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            if (i == selected) row.addView(Ui.icon(c, Icon.CHECK, Ui.ACCENT, 24));
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    choice.chosen(idx);
                    s.dismiss();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(c, 4);
            s.body.addView(row, lp);
        }
        return s.show(root);
    }

    /** A message (selectable, e.g. a report) with an optional action button. */
    static Sheet message(ViewGroup root, String title, CharSequence text, String action, final Runnable onAction) {
        Context c = root.getContext();
        final Sheet s = new Sheet(c, title);
        TextView t = Ui.text(c, text, 14, Ui.TEXT, Ui.REGULAR);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, 1.3f);
        s.body.addView(t);
        if (action != null) {
            TextView b = button(c, action, true);
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    onAction.run();
                    s.dismiss();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 50));
            lp.topMargin = Ui.dp(c, 18);
            s.body.addView(b, lp);
        }
        return s.show(root);
    }

    /** Asks before something irreversible. */
    static Sheet confirm(ViewGroup root, String title, String text, String action, final Runnable onAction) {
        Context c = root.getContext();
        final Sheet s = new Sheet(c, title);
        if (text != null) {
            TextView t = Ui.text(c, text, 14, Ui.TEXT2, Ui.REGULAR);
            t.setLineSpacing(0, 1.3f);
            s.body.addView(t);
        }
        LinearLayout row = new LinearLayout(c);
        row.setPadding(0, Ui.dp(c, 18), 0, 0);
        TextView cancel = button(c, "Отмена", false);
        cancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                s.dismiss();
            }
        });
        TextView ok = button(c, action, true);
        ok.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onAction.run();
                s.dismiss();
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1);
        l2.leftMargin = Ui.dp(c, 10);
        row.addView(cancel, l1);
        row.addView(ok, l2);
        s.body.addView(row);
        return s.show(root);
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView b = Ui.text(c, label, 15, primary ? Ui.ON_ACCENT : Ui.TEXT, Ui.SEMIBOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.round(c, primary ? Ui.ACCENT : Ui.SURFACE3, 16));
        b.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
        Ui.pressable(b);
        return b;
    }
}
