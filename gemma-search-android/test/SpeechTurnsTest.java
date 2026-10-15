import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.teoplaydor.semsearch.core.SpeechTurns;

/**
 * Where speech starts and ends, found on the phone (core/SpeechTurns), on made-up voices: syllables (a voiced tone with
 * a syllable's rise and fall, 4–5 a second), gaps between words, a pause that ends the phrase, a quiet room, a street's
 * steady noise, the answer heard back in the microphone, the hand lowered mid-phrase.
 */
public class SpeechTurnsTest {
    static int bad = 0;

    static void ok(boolean c, String what) {
        System.out.println((c ? "ok   " : "FAIL ") + what);
        if (!c) bad++;
    }

    static final int R = SpeechTurns.RATE;
    static final Random rnd = new Random(7);

    /** Noise of this level (RMS). */
    static void noise(List<Short> out, double ms, double level) {
        for (int i = 0; i < ms * R / 1000; i++) out.add((short) Math.round(rnd.nextGaussian() * level));
    }

    /** A word: syllables of ~200 ms (a 140 Hz voice and its harmonics, rising and falling) over the noise. */
    static void word(List<Short> out, int syllables, double peak, double noise) {
        for (int s = 0; s < syllables; s++) {
            int n = R / 5;
            for (int i = 0; i < n; i++) {
                double t = (double) i / R, env = Math.pow(Math.sin(Math.PI * i / n), 1.5);
                double v = peak * env * (0.7 * Math.sin(2 * Math.PI * 140 * t) + 0.3 * Math.sin(2 * Math.PI * 420 * t));
                out.add((short) Math.max(-32768, Math.min(32767, Math.round(v + rnd.nextGaussian() * noise))));
            }
        }
    }

    static short[] arr(List<Short> l) {
        short[] a = new short[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** What the finder told, with the time (ms into the voice) of each start and end. */
    static final class Log implements SpeechTurns.Sink {
        final List<String> events = new ArrayList<String>();
        long fed;
        int frames, pause;

        public void start() {
            events.add("start@" + fed);
        }

        public void audio(short[] f) {
            frames++;
        }

        public void end(int silentMs) {
            pause = silentMs;
            events.add("end@" + fed);
        }
    }

    /** Fed in pieces as a headset gives them (20 ms) or as the watch's Bluetooth does (odd sizes). */
    static Log run(SpeechTurns t, short[] x, int piece, boolean strict) {
        Log l = new Log();
        for (int o = 0; o < x.length; o += piece) {
            int n = Math.min(piece, x.length - o);
            t.feed(x, o, n, strict, l);
            l.fed = (o + n) * 1000L / R;
        }
        return l;
    }

    static long at(String e) {
        return Long.parseLong(e.substring(e.indexOf('@') + 1));
    }

    public static void main(String[] a) {
        // a phrase in a quiet room: 0.5 s quiet, three words with 120 ms gaps, then quiet
        List<Short> v = new ArrayList<Short>();
        noise(v, 500, 40);
        word(v, 2, 9000, 40);
        noise(v, 120, 40);
        word(v, 3, 7000, 40);
        noise(v, 150, 40);
        word(v, 2, 8000, 40);
        long speechEnd = v.size() * 1000L / R;
        noise(v, 800, 40);
        SpeechTurns t = new SpeechTurns();
        Log l = run(t, arr(v), 320, false);
        ok(l.events.size() == 2 && l.events.get(0).startsWith("start") && l.events.get(1).startsWith("end"),
                "a phrase with gaps between its words: one turn — " + l.events);
        long end = l.events.size() == 2 ? at(l.events.get(1)) : -1;
        ok(end - speechEnd >= 230 && end - speechEnd <= 300, "its end told " + (end - speechEnd) + " ms after the last sound (the pause: "
                + t.endSilenceMs + " ms)");
        ok(l.events.size() == 2 && at(l.events.get(0)) >= 500 && at(l.events.get(0)) <= 620, "its start found at " + at(l.events.get(0))
                + " ms (the speech began at 500)");
        ok(l.frames * 20 >= end - 500, "the moment before the start sent with it: " + l.frames * 20 + " ms of voice in the turn (from "
                + (end - l.frames * 20) + " ms; the speech began at 500)");

        // fed as the watch's Bluetooth gives it (476 samples at a time): the same
        SpeechTurns t2 = new SpeechTurns();
        Log l2 = run(t2, arr(v), 476, false);
        ok(l2.events.size() == 2 && Math.abs(at(l2.events.get(1)) - end) <= 30, "in the watch's pieces: the same — " + l2.events);

        // a longer pause ends it sooner or later as set
        SpeechTurns t3 = new SpeechTurns();
        t3.endSilenceMs = 400;
        Log l3 = run(t3, arr(v), 320, false);
        ok(l3.events.size() == 2 && at(l3.events.get(1)) - speechEnd >= 350 && at(l3.events.get(1)) - speechEnd <= 450,
                "the pause set to 0.4 s: told " + (at(l3.events.get(1)) - speechEnd) + " ms after");

        // a street: steady noise at 900 RMS (louder than the floor at first), then the phrase over it
        List<Short> s = new ArrayList<Short>();
        noise(s, 1500, 900);
        long sStart = s.size() * 1000L / R;
        word(s, 3, 12000, 900);
        noise(s, 100, 900);
        word(s, 2, 11000, 900);
        long sEnd = s.size() * 1000L / R;
        noise(s, 1000, 900);
        SpeechTurns ts = new SpeechTurns();
        Log ls = run(ts, arr(s), 320, false);
        boolean one = ls.events.size() == 2;
        ok(one && at(ls.events.get(0)) >= sStart && at(ls.events.get(1)) - sEnd <= 320,
                "a street's steady noise: not speech; the phrase over it found and ended — " + ls.events + String.format(" (background %.0f)", ts.floor()));

        // a long vowel (1.3 s with no gap) is not taken for the background
        List<Short> o = new ArrayList<Short>();
        noise(o, 300, 40);
        for (int i = 0; i < R * 13 / 10; i++) o.add((short) (7000 * Math.sin(2 * Math.PI * 160 * i / R)));
        noise(o, 600, 40);
        SpeechTurns to = new SpeechTurns();
        Log lo = run(to, arr(o), 320, false);
        ok(lo.events.size() == 2 && at(lo.events.get(1)) >= 1600 + 230, "a long «о-о-о» held 1.3 s: one turn, not cut — " + lo.events);

        // the answer heard back in the microphone while it plays (quieter than the voice): not a start
        List<Short> e = new ArrayList<Short>();
        noise(e, 300, 40);
        word(e, 6, 1500, 40);
        noise(e, 300, 40);
        SpeechTurns te = new SpeechTurns();
        Log le = run(te, arr(e), 320, true);
        ok(le.events.isEmpty(), "the answer heard back (1500 peak) while it plays: not taken for speech — " + le.events);
        SpeechTurns te2 = new SpeechTurns();
        Log le2 = run(te2, arr(e), 320, false);
        ok(le2.events.size() == 2, "…the same voice when nothing plays: speech — " + le2.events);
        // spoken over it, near the mouth: found
        List<Short> b = new ArrayList<Short>();
        noise(b, 300, 40);
        word(b, 3, 9000, 300);
        SpeechTurns tb = new SpeechTurns();
        Log lb = run(tb, arr(b), 320, true);
        ok(lb.events.size() == 1 && at(lb.events.get(0)) <= 300 + 220, "spoken over the answer: a start at " + (lb.events.isEmpty() ? -1 : at(lb.events.get(0))) + " ms");

        // the hand lowered mid-phrase: the turn ends at once
        List<Short> h = new ArrayList<Short>();
        noise(h, 200, 40);
        word(h, 3, 9000, 40);
        SpeechTurns th = new SpeechTurns();
        Log lh = run(th, arr(h), 320, false);
        th.finish(lh);
        ok(lh.events.size() == 2 && lh.pause < 60, "the hand down mid-phrase: ended at once — " + lh.events);
        SpeechTurns tn = new SpeechTurns();
        Log ln = new Log();
        tn.finish(ln);
        ok(ln.events.isEmpty(), "the hand down with nothing said: nothing");

        System.out.println(bad == 0 ? "SPEECH TURNS OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
