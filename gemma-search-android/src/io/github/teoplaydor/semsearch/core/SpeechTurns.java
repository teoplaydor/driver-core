package io.github.teoplaydor.semsearch.core;

import java.util.ArrayDeque;

/**
 * Where speech starts and ends in a live voice (16 kHz), found on the phone as it comes — so the end of a phrase is told
 * to Gemini at once instead of the server waiting to hear the silence itself. Frames of 20 ms: a frame is speech when its
 * level is well over the background (the quietest frame of the last 1.5 s — speech always has gaps between its sounds,
 * so a steady noise is found even while one speaks; it rises slowly) and over a floor; speech starts after a few
 * such frames in a row (the moment before it — {@link #prerollMs} — goes with it), ends after {@link #endSilenceMs} of
 * no speech. While an answer plays (it may be heard back in the microphone) the start needs more: louder and longer.
 */
public final class SpeechTurns {
    public interface Sink {
        /** Speech begins: the frames that follow (the moment before it first) are the turn. */
        void start();

        /** A frame of the turn (20 ms). */
        void audio(short[] frame);

        /** The turn is over; {@code silentMs} of it was the pause that ended it. */
        void end(int silentMs);
    }

    public static final int RATE = 16000, FRAME = RATE / 50;

    /** The pause that ends a phrase. */
    public int endSilenceMs = 250;
    /** Speech this long in a row starts a turn (twice that while an answer plays). */
    public int onsetMs = 60;
    /** The moment before the start, sent with it (the first sound of a word is quiet). */
    public int prerollMs = 300;
    /** Over the background this many times (3 ≈ 10 dB); while speaking, a little less keeps it. */
    public double ratio = 3.0, keepRatio = 2.0;
    /** Never speech below this level (RMS of 16-bit samples). */
    public double minLevel = 500;

    /** The background before there is enough to tell (a quiet room). */
    static final double PRIOR = 300;
    private static final int WINDOW = 75;
    private final double[] levels = new double[WINDOW];
    private int seen;
    private double floor = PRIOR;
    private boolean active;
    private int run, silentMs;
    private final short[] part = new short[FRAME];
    private int partN;
    private final ArrayDeque<short[]> before = new ArrayDeque<short[]>();
    private double lastLevel;

    public boolean active() {
        return active;
    }

    /** The background's level now (RMS). */
    public double floor() {
        return floor;
    }

    public double lastLevel() {
        return lastLevel;
    }

    /**
     * The next piece of the voice.
     *
     * @param strict an answer is playing: a start needs to be louder and longer (not the answer heard back)
     */
    public void feed(short[] pcm, int off, int n, boolean strict, Sink s) {
        while (n > 0) {
            int k = Math.min(n, FRAME - partN);
            System.arraycopy(pcm, off, part, partN, k);
            partN += k;
            off += k;
            n -= k;
            if (partN == FRAME) {
                frame(part.clone(), strict, s);
                partN = 0;
            }
        }
    }

    public void feed(short[] pcm, boolean strict, Sink s) {
        feed(pcm, 0, pcm.length, strict, s);
    }

    /** The voice is over (the hand lowered): a turn going on ends now. */
    public void finish(Sink s) {
        if (!active) {
            partN = 0;
            return;
        }
        if (partN > 0) s.audio(java.util.Arrays.copyOf(part, partN));
        partN = 0;
        active = false;
        run = 0;
        s.end(silentMs);
        silentMs = 0;
    }

    /** Forget the background and any turn (a new conversation). */
    public void reset() {
        seen = 0;
        floor = PRIOR;
        active = false;
        run = 0;
        silentMs = 0;
        partN = 0;
        before.clear();
    }

    private void frame(short[] f, boolean strict, Sink s) {
        double mean = 0;
        for (short v : f) mean += v;
        mean /= f.length;
        double e = 0;
        for (short v : f) {
            double d = v - mean;
            e += d * d;
        }
        double level = Math.sqrt(e / f.length);
        lastLevel = level;
        double over = active ? keepRatio : ratio;
        double min = minLevel;
        if (strict && !active) {
            over *= 2;
            min *= 2;
        }
        levels[seen++ % WINDOW] = level;
        double low = Double.MAX_VALUE;
        for (int i = 0; i < Math.min(seen, WINDOW); i++) low = Math.min(low, levels[i]);
        // down at once; up by at most 3 % a frame (a noise that stays becomes the background in under a second, a long
        // vowel does not)
        floor = Math.max(30, Math.min(seen < 10 ? Math.min(PRIOR, low) : low, floor * 1.03));
        boolean speech = level > Math.max(min, floor * over);
        if (!active) {
            before.addLast(f);
            while (before.size() > Math.max(1, prerollMs / 20)) before.removeFirst();
            run = speech ? run + 1 : 0;
            if (run * 20 >= (strict ? 2 * onsetMs : onsetMs)) {
                active = true;
                silentMs = 0;
                run = 0;
                s.start();
                for (short[] b : before) s.audio(b);
                before.clear();
            }
            return;
        }
        s.audio(f);
        silentMs = speech ? 0 : silentMs + 20;
        if (silentMs >= endSilenceMs) {
            active = false;
            s.end(silentMs);
            silentMs = 0;
        }
    }
}
