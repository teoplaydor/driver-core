package io.github.teoplaydor.semsearch.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Progress of a long job made of stages (the accelerator check): each stage has an expected duration, which is its
 * weight in the whole. A running stage counts by its parts done, and within a part by the time spent against the
 * expected time — up to 90% of it on time, then slowly towards 99%, so a stage never looks finished before it is.
 * Skipped stages count as done. The whole percentage never goes back, also when a stage's expectation changes.
 */
public final class StageProgress {
    public static final int WAITING = 0, RUNNING = 1, DONE = 2, SKIPPED = 3, FAILED = 4;

    public static final class Stage {
        public final String name;
        double expectMs;
        public int state;
        public String result = "", detail = "";
        long startMs, partStartMs, endMs;
        int parts, partsDone;

        Stage(String name, double expectMs) {
            this.name = name;
            this.expectMs = Math.max(1, expectMs);
        }
    }

    public final String title;
    private final List<Stage> stages = new ArrayList<Stage>();
    private long startedMs, endedMs;
    private double shown;
    private boolean finished;

    public StageProgress(String title, long now) {
        this.title = title;
        startedMs = now;
    }

    public synchronized Stage add(String name, double expectMs) {
        Stage s = new Stage(name, expectMs);
        stages.add(s);
        return s;
    }

    /** A new expectation for a stage (when it is known better: the variant measured there, its history). */
    public synchronized void expect(Stage s, double expectMs) {
        s.expectMs = Math.max(1, expectMs);
    }

    /** Hears stages start, move to a next part and end (for a journal). */
    public interface Listener {
        void event(Stage s, String what);
    }

    private Listener listener;

    public synchronized void setListener(Listener l) {
        listener = l;
    }

    private void tell(Stage s, String what) {
        if (listener != null) listener.event(s, what);
    }

    public synchronized void start(Stage s, long now) {
        s.state = RUNNING;
        s.startMs = s.partStartMs = now;
        s.detail = "";
        tell(s, "▸ " + s.name);
    }

    /** The stage is made of {@code total} parts (thread counts, batch sizes, rounds). */
    public synchronized void parts(Stage s, int total) {
        s.parts = Math.max(0, total);
        s.partsDone = 0;
    }

    /** {@code done} parts are done; the next one starts now. */
    public synchronized void part(Stage s, int done, String detail, long now) {
        s.partsDone = Math.max(0, done);
        s.partStartMs = now;
        if (detail != null) s.detail = detail;
        tell(s, "  " + s.name + ", часть " + (done + 1) + (s.parts > 0 ? " из " + s.parts : "") + (detail != null ? ": " + detail : ""));
    }

    public synchronized void detail(Stage s, String detail) {
        s.detail = detail == null ? "" : detail;
    }

    public synchronized void done(Stage s, String result, long now) {
        end(s, DONE, result, now);
    }

    public synchronized void failed(Stage s, String result, long now) {
        end(s, FAILED, result, now);
    }

    public synchronized void skip(Stage s, String why) {
        if (s.state == DONE || s.state == FAILED) return;
        s.state = SKIPPED;
        s.result = why == null ? "" : why;
        s.detail = "";
        tell(s, "– " + s.name + ": пропущено" + (s.result.isEmpty() ? "" : " — " + s.result));
    }

    private void end(Stage s, int state, String result, long now) {
        s.state = state;
        s.result = result == null ? "" : result;
        s.detail = "";
        s.endMs = now;
        tell(s, (state == DONE ? "✓ " : "✕ ") + s.name + (s.result.isEmpty() ? "" : ": " + s.result) + " — за " + clock(now - s.startMs));
    }

    /** The job is over: stages that did not run are skipped. */
    public synchronized void finish(long now) {
        for (Stage s : stages) if (s.state == WAITING || s.state == RUNNING) skip(s, "");
        finished = true;
        endedMs = now;
    }

    public synchronized long startedMs() {
        return startedMs;
    }

    public synchronized boolean finished() {
        return finished;
    }

    /** Within a part, by time: 90% at the expected time, then slowly towards 99%. */
    static double byTime(long elapsedMs, double expectMs) {
        double x = Math.max(0, elapsedMs) / Math.max(1, expectMs);
        return x < 1 ? 0.9 * x : 0.9 + 0.09 * (1 - Math.exp(-(x - 1)));
    }

    /** How far a stage is, 0..1. */
    public synchronized double stageFraction(Stage s, long now) {
        if (s.state == DONE || s.state == SKIPPED || s.state == FAILED) return 1;
        if (s.state == WAITING) return 0;
        if (s.parts > 0) {
            double part = byTime(now - s.partStartMs, s.expectMs / s.parts);
            return Math.min(0.99, (Math.min(s.partsDone, s.parts) + (s.partsDone < s.parts ? part : 0)) / s.parts);
        }
        return byTime(now - s.startMs, s.expectMs);
    }

    /** The whole, 0..1: never less than shown before; 1 only when finished. */
    public synchronized double fraction(long now) {
        if (finished) return shown = 1;
        double all = 0, got = 0;
        for (Stage s : stages) {
            all += s.expectMs;
            got += s.expectMs * stageFraction(s, now);
        }
        double f = all > 0 ? Math.min(0.99, got / all) : 0;
        if (f > shown) shown = f;
        return shown;
    }

    public synchronized int percent(long now) {
        return (int) Math.floor(100 * fraction(now));
    }

    public synchronized Stage current() {
        for (Stage s : stages) if (s.state == RUNNING) return s;
        return null;
    }

    /** {number of the current stage (1-based, or of the last one done), number of stages}. */
    public synchronized int[] position() {
        int at = 0;
        for (int i = 0; i < stages.size(); i++) {
            int st = stages.get(i).state;
            if (st == RUNNING) return new int[]{i + 1, stages.size()};
            if (st != WAITING) at = i + 1;
        }
        return new int[]{at, stages.size()};
    }

    public synchronized List<Stage> stages() {
        return new ArrayList<Stage>(stages);
    }

    public synchronized long elapsedMs(long now) {
        return (finished ? endedMs : now) - startedMs;
    }

    /** Time a stage has run (or ran). */
    public synchronized long stageMs(Stage s, long now) {
        if (s.state == WAITING || s.state == SKIPPED) return 0;
        return (s.state == RUNNING ? now : s.endMs) - s.startMs;
    }

    public static String clock(long ms) {
        long sec = Math.max(0, ms) / 1000;
        return sec >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
                : String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }

    /** One line: "42% · этап 5 из 17: name — detail". */
    public synchronized String line(long now) {
        int[] pos = position();
        Stage c = current();
        StringBuilder sb = new StringBuilder();
        sb.append(percent(now)).append('%');
        if (finished) return sb.append(" · готово за ").append(clock(elapsedMs(now))).toString();
        if (c != null) {
            sb.append(" · этап ").append(pos[0]).append(" из ").append(pos[1]).append(": ").append(c.name);
            if (!c.detail.isEmpty()) sb.append(" — ").append(c.detail);
        }
        return sb.toString();
    }
}
