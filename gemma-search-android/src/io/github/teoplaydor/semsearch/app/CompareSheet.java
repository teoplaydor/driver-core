package io.github.teoplaydor.semsearch.app;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * "Compare the models on my photos": both models embed the same recent photos, then for each query the
 * two top results are shown one above the other, with the speed of each model on this phone. There is
 * no ground truth on a personal gallery, so the verdict is the person's own eye; the agreement figure only
 * says how often the two models pick the same photos.
 */
final class CompareSheet implements Engine.Listener {
    private static final int PHOTOS = 40;

    private final MainActivity a;
    private final Engine e;
    private final Sheet sheet;
    private final TextView progressText, summary;
    private final ProgressLine progress;
    private final LinearLayout results, chips;
    private Engine.Comparison comparison;
    private TextView selectedChip;

    static void open(MainActivity a) {
        Engine e = Engine.get(a);
        if (!e.canCompare()) {
            String why = !e.gemmaDownloaded() ? "Для сравнения нужна и EmbeddingGemma 2: скачайте её в настройках "
                    + "(«EmbeddingGemma 2 для заметок»)."
                    : !(e.fastDownloaded(FastModel.B16) || e.fastDownloaded(FastModel.B32))
                    ? "Для сравнения нужна и быстрая модель: выберите SigLIP 2 в «Модель для фото» и скачайте её."
                    : !e.ready() ? "Модель ещё загружается — попробуйте через несколько секунд."
                    : "Сначала проиндексируйте хотя бы " + Engine.COMPARE_TOP * 2 + " фото.";
            Sheet.message(a.rootView(), "Сравнение моделей", why, null, null);
            return;
        }
        new CompareSheet(a, e);
    }

    private CompareSheet(MainActivity activity, Engine engine) {
        a = activity;
        e = engine;
        Context c = activity;
        sheet = new Sheet(c, "Сравнение моделей");
        LinearLayout body = sheet.body();
        progressText = Ui.text(c, "Готовлю обе модели…", 14, Ui.TEXT2, Ui.REGULAR);
        body.addView(progressText);
        progress = new ProgressLine(c);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, Ui.dp(c, 3));
        pl.setMargins(0, Ui.dp(c, 12), 0, Ui.dp(c, 6));
        body.addView(progress, pl);
        summary = Ui.text(c, "", 14, Ui.TEXT, Ui.REGULAR);
        summary.setLineSpacing(0, 1.3f);
        summary.setVisibility(View.GONE);
        body.addView(summary);

        final EditText q = SettingsPanel.field(c, "Свой запрос — например «чек из кафе»");
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        q.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
                String text = q.getText().toString().trim();
                if (!text.isEmpty() && comparison != null) ask(text, null);
                return true;
            }
        });
        q.setVisibility(View.GONE);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(-1, Ui.dp(c, 48));
        ql.topMargin = Ui.dp(c, 16);
        body.addView(q, ql);

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chips = new LinearLayout(c);
        hs.addView(chips);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = Ui.dp(c, 12);
        body.addView(hs, hl);
        results = new LinearLayout(c);
        results.setOrientation(LinearLayout.VERTICAL);
        body.addView(results);

        sheet.setOnClosed(new Runnable() {
            @Override
            public void run() {
                e.removeListener(CompareSheet.this);
                if (comparison != null) e.endCompare(comparison);
            }
        });
        sheet.show(a.rootView());
        e.addListener(this);
        final EditText queryField = q;
        e.compare(PHOTOS, new Engine.Callback<Engine.Comparison>() {
            @Override
            public void done(Engine.Comparison c, Exception err) {
                if (err != null) {
                    progress.setVisibility(View.GONE);
                    progressText.setText("Не получилось: " + err.getMessage());
                    return;
                }
                if (sheet.isClosing()) {
                    e.endCompare(c);
                    return;
                }
                comparison = c;
                show(queryField);
            }
        });
    }

    @Override
    public void onEngineChanged() {
        if (comparison != null || e.cmpTotal <= 0) return;
        progress.setIndeterminate(e.cmpDone == 0);
        progress.setProgress(e.cmpDone / (float) e.cmpTotal);
        progressText.setText(String.format(Locale.ROOT, "Считаю обеими моделями: %d из %d фото", e.cmpDone, e.cmpTotal));
    }

    private void show(EditText q) {
        Engine.Comparison c = comparison;
        progress.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);
        double x = c.slowMs / Math.max(0.001, c.fastMs);
        String speed = x >= 1.5 ? String.format(Locale.ROOT, " — в %.0f раз медленнее", x) : " — примерно так же";
        summary.setText(String.format(Locale.ROOT, "%s: %.2f с на фото\n%s: %.2f с на фото%s\n\n"
                        + "Совпадение выдачи: %.0f%%. Что лучше — видно по фото ниже: сверху %s, снизу %s.",
                c.fastName, c.fastMs / 1000, c.slowName, c.slowMs / 1000, speed, 100 * c.agreement, c.fastName, c.slowName));
        Ui.fadeIn(summary, 200);
        Ui.fadeIn(q, 200);
        Context ctx = a;
        for (final Engine.CompareQuery cq : c.queries) {
            final TextView chip = Ui.text(ctx, cq.text, 13.5f, Ui.TEXT2, Ui.MEDIUM);
            chip.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 8), Ui.dp(ctx, 14), Ui.dp(ctx, 8));
            chip.setBackground(Ui.round(ctx, Ui.SURFACE2, 16));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(chip, cq);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(ctx, 8);
            chips.addView(chip, lp);
        }
        if (!c.queries.isEmpty()) select((TextView) chips.getChildAt(0), c.queries.get(0));
    }

    private void ask(String text, TextView chip) {
        e.compareQuery(comparison, text, new Engine.Callback<Engine.CompareQuery>() {
            @Override
            public void done(Engine.CompareQuery r, Exception err) {
                if (r != null) select(null, r);
            }
        });
    }

    private void select(TextView chip, Engine.CompareQuery cq) {
        Context c = a;
        if (selectedChip != null) {
            selectedChip.setBackground(Ui.round(c, Ui.SURFACE2, 16));
            selectedChip.setTextColor(Ui.TEXT2);
        }
        selectedChip = chip;
        if (chip != null) {
            chip.setBackground(Ui.round(c, Ui.ACCENT_SOFT, 16));
            chip.setTextColor(Ui.TEXT);
        }
        results.removeAllViews();
        TextView title = Ui.text(c, "«" + cq.text + "» · общих " + cq.shared + " из " + Engine.COMPARE_TOP, 13, Ui.TEXT3, Ui.MEDIUM);
        title.setPadding(0, Ui.dp(c, 16), 0, 0);
        results.addView(title);
        row(comparison.fastName, cq.fast);
        row(comparison.slowName, cq.slow);
        results.setAlpha(0f);
        results.animate().alpha(1f).setDuration(180).start();
    }

    private void row(String name, int[] picks) {
        Context c = a;
        TextView label = Ui.text(c, name, 13.5f, Ui.TEXT, Ui.MEDIUM);
        label.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 8));
        results.addView(label);
        LinearLayout line = new LinearLayout(c);
        int gap = Ui.dp(c, 4);
        for (int i = 0; i < Engine.COMPARE_TOP; i++) {
            MasonryView.Thumb t = new MasonryView.Thumb(c);
            t.setBackground(Ui.round(c, Ui.SURFACE2, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 54), 1);
            if (i > 0) lp.leftMargin = gap;
            line.addView(t, lp);
            if (i < picks.length) a.thumbInto(t, comparison.photos.get(picks[i]), 256);
        }
        line.setGravity(Gravity.CENTER_VERTICAL);
        results.addView(line);
    }
}
