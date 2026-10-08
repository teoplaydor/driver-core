package io.github.teoplaydor.semsearch.app;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import io.github.teoplaydor.semsearch.core.StageProgress;

/**
 * The accelerator check as it goes: the whole in percent with its time, the stage running now with what it does
 * (on the NPU, the NPU process's own step and memory), and every stage — done with its result, running with its
 * percent and time, waiting, skipped or failed.
 */
final class BenchView extends LinearLayout {
    private final TextView percent, position, current, detail;
    private final ProgressLine line;
    private final LinearLayout rows;
    private final List<TextView[]> rowViews = new ArrayList<TextView[]>();

    BenchView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.BOTTOM);
        percent = Ui.text(c, "", 30, Ui.ACCENT, Ui.SEMIBOLD);
        head.addView(percent, new LayoutParams(-2, -2));
        position = Ui.text(c, "", 13, Ui.TEXT2, Ui.REGULAR);
        position.setGravity(Gravity.END);
        position.setPadding(Ui.dp(c, 12), 0, 0, Ui.dp(c, 6));
        head.addView(position, new LayoutParams(0, -2, 1));
        addView(head);
        line = new ProgressLine(c);
        LayoutParams ll = new LayoutParams(-1, Ui.dp(c, 4));
        ll.topMargin = Ui.dp(c, 8);
        addView(line, ll);
        current = Ui.text(c, "", 15, Ui.TEXT, Ui.SEMIBOLD);
        current.setPadding(0, Ui.dp(c, 16), 0, 0);
        addView(current);
        detail = Ui.text(c, "", 13, Ui.TEXT2, Ui.REGULAR);
        detail.setLineSpacing(0, 1.25f);
        detail.setPadding(0, Ui.dp(c, 4), 0, 0);
        addView(detail);
        rows = new LinearLayout(c);
        rows.setOrientation(VERTICAL);
        rows.setPadding(0, Ui.dp(c, 16), 0, 0);
        addView(rows);
        TextView note = Ui.text(c, "Подбор идёт и с закрытым листом. Проценты — по времени этапов в прошлый раз на этом "
                + "телефоне, а в первый раз — по прикидке; первая сборка модели под NPU занимает минуты.", 12, Ui.TEXT3, Ui.REGULAR);
        note.setLineSpacing(0, 1.25f);
        note.setPadding(0, Ui.dp(c, 16), 0, 0);
        addView(note);
    }

    /** @param p the check to show; null (or one from before) while it is being prepared */
    void update(StageProgress p) {
        long now = System.currentTimeMillis();
        if (p == null) {
            percent.setText("0%");
            position.setText("");
            line.setIndeterminate(true);
            current.setText("Готовлю список вариантов…");
            detail.setText("");
            rows.removeAllViews();
            rowViews.clear();
            return;
        }
        line.setIndeterminate(false);
        line.setProgress((float) p.fraction(now));
        percent.setText(p.percent(now) + "%");
        int[] pos = p.position();
        position.setText((p.finished() ? "готово" : "этап " + pos[0] + " из " + pos[1]) + " · " + StageProgress.clock(p.elapsedMs(now)));
        StageProgress.Stage cur = p.current();
        current.setText(cur != null ? cur.name : p.finished() ? "Готово — открываю отчёт" : "");
        detail.setText(cur != null ? cur.detail : "");
        detail.setVisibility(cur != null && !cur.detail.isEmpty() ? VISIBLE : GONE);
        List<StageProgress.Stage> stages = p.stages();
        if (stages.size() != rowViews.size()) build(stages.size());
        for (int i = 0; i < stages.size(); i++) {
            StageProgress.Stage s = stages.get(i);
            TextView[] v = rowViews.get(i);
            String mark, right;
            int markColor, nameColor = Ui.TEXT, rightColor = Ui.TEXT2;
            switch (s.state) {
                case StageProgress.DONE:
                    mark = "✓";
                    markColor = Ui.ACCENT;
                    right = s.result;
                    break;
                case StageProgress.RUNNING:
                    mark = "▸";
                    markColor = Ui.ACCENT;
                    rightColor = Ui.ACCENT;
                    right = Math.round(100 * p.stageFraction(s, now)) + "% · " + StageProgress.clock(p.stageMs(s, now));
                    break;
                case StageProgress.FAILED:
                    mark = "✕";
                    markColor = Ui.DANGER;
                    rightColor = Ui.DANGER;
                    right = s.result;
                    break;
                case StageProgress.SKIPPED:
                    mark = "–";
                    markColor = Ui.TEXT3;
                    nameColor = Ui.TEXT3;
                    right = s.result.isEmpty() ? "пропущено" : s.result;
                    break;
                default:
                    mark = "○";
                    markColor = Ui.TEXT3;
                    nameColor = Ui.TEXT2;
                    right = "";
            }
            v[0].setText(mark);
            v[0].setTextColor(markColor);
            v[1].setText(s.name);
            v[1].setTextColor(nameColor);
            v[2].setText(right);
            v[2].setTextColor(rightColor);
        }
    }

    private void build(int n) {
        Context c = getContext();
        rows.removeAllViews();
        rowViews.clear();
        for (int i = 0; i < n; i++) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
            TextView mark = Ui.text(c, "", 14, Ui.TEXT3, Ui.SEMIBOLD);
            row.addView(mark, new LayoutParams(Ui.dp(c, 22), -2));
            TextView name = Ui.text(c, "", 14, Ui.TEXT, Ui.REGULAR);
            name.setSingleLine(false);
            row.addView(name, new LayoutParams(0, -2, 1));
            TextView right = Ui.text(c, "", 13, Ui.TEXT2, Ui.MEDIUM);
            right.setGravity(Gravity.END);
            right.setMaxLines(2);
            right.setMaxWidth(Ui.dp(c, 180));
            right.setEllipsize(TextUtils.TruncateAt.END);
            right.setPadding(Ui.dp(c, 10), 0, 0, 0);
            row.addView(right, new LayoutParams(-2, -2));
            rows.addView(row);
            rowViews.add(new TextView[]{mark, name, right});
        }
    }
}
