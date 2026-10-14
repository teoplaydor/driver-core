import io.github.teoplaydor.semsearch.core.OggOpus;
import io.github.teoplaydor.semsearch.core.Utterances;
import io.github.teoplaydor.semsearch.core.VoiceNote;
import io.github.teoplaydor.semsearch.core.WristGesture;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * A voice note from the watch on its way to the phone: the VOICE packets put together in order — a lost write found at
 * once and asked for again, bytes after the gap kept, a write sent twice taken once, the end before the last bytes —
 * and the watch's Ogg Opus taken apart into its packets (pages, packets across segments and pages, OpusHead, OpusTags),
 * packets each after its length, an Opus packet's length in samples. And the phone's answers to the watch (settings,
 * «again from», «done» with its line cut on a whole letter): written to events.hex for the watch's JavaScript to read
 * (proto_test.mjs). And a note made ready for the recognizer: cleaned (rumble out, loudness evened, peaks below full scale)
 * and split into phrases at its pauses (a recognizer stops at the first pause), none longer than 12 s.
 * usage: VoiceNoteTest OUT_DIR
 */
public class VoiceNoteTest {
    static int bad;

    static void ok(boolean c, String what) {
        if (!c) bad++;
        System.out.println((c ? "ok   " : "FAIL ") + what);
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    public static void main(String[] a) throws Exception {
        notes();
        ogg();
        events(a.length > 0 ? a[0] : null);
        phrases();
        System.out.println(bad == 0 ? "VOICE NOTES OK" : bad + " FAILED");
        if (bad > 0) System.exit(1);
    }

    static final int R = 16000;

    /** Something like speech: noise in syllables (4–5 a second) at this loudness, for so many seconds; or quiet. */
    static void say(List<Float> out, Random r, double secs, double amp) {
        int n = (int) (secs * R);
        for (int i = 0; i < n; i++) {
            double t = i / (double) R;
            double syll = Math.max(0, Math.sin(2 * Math.PI * 4.5 * t)); // 4.5 syllables a second, a short gap between
            double v = amp * syll * (r.nextGaussian() * 0.5 + 0.5 * Math.sin(2 * Math.PI * 220 * t));
            out.add((float) (v + 0.001 * r.nextGaussian()));
        }
    }

    static void quiet(List<Float> out, Random r, double secs) {
        say(out, r, secs, 0);
    }

    static float[] arr(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    static String spans(List<int[]> p) {
        StringBuilder b = new StringBuilder();
        for (int[] s : p) b.append(String.format(java.util.Locale.ROOT, " %.2f–%.2f", s[0] / (double) R, s[1] / (double) R));
        return b.toString().trim();
    }

    static void phrases() {
        Random r = new Random(17);
        // two phrases with a second between, short pauses inside: two phrases, where they are
        List<Float> l = new ArrayList<Float>();
        quiet(l, r, 0.6);
        say(l, r, 1.2, 0.2);
        quiet(l, r, 0.3);
        say(l, r, 0.8, 0.2);
        quiet(l, r, 1.0);
        say(l, r, 1.5, 0.2);
        quiet(l, r, 0.5);
        List<int[]> p = Utterances.phrases(arr(l), R);
        ok(p.size() == 2 && Math.abs(p.get(0)[0] - 0.6 * R) < 0.3 * R && p.get(0)[1] > 2.9 * R && p.get(0)[1] < 3.3 * R
                && Math.abs(p.get(1)[0] - 3.9 * R) < 0.3 * R && p.get(1)[1] > 5.3 * R,
                "two phrases a second apart (a short pause inside the first does not split it): " + spans(p));
        // twenty seconds without a pause: cut, none longer than 12 s
        l.clear();
        say(l, r, 20, 0.2);
        p = Utterances.phrases(arr(l), R);
        boolean shortEnough = p.size() >= 2;
        for (int[] s : p) shortEnough &= s[1] - s[0] <= (Utterances.MAX_MS + 600) * R / 1000;
        ok(shortEnough && p.get(0)[0] == 0 && p.get(p.size() - 1)[1] >= 19.9 * R, "20 s without a pause: cut in " + p.size() + " (" + spans(p) + ")");
        // a click, and silence: nothing
        l.clear();
        quiet(l, r, 1);
        say(l, r, 0.06, 0.5);
        quiet(l, r, 1);
        ok(Utterances.phrases(arr(l), R).isEmpty(), "a click alone is not a phrase");
        l.clear();
        quiet(l, r, 2);
        ok(Utterances.phrases(arr(l), R).isEmpty(), "silence: nothing");

        // cleaned: a quiet voice made louder, a loud one kept below full scale, an offset gone
        l.clear();
        say(l, r, 2, 0.02);
        float[] q = Utterances.clean(arr(l), R);
        ok(loud(q) > 0.07 && loud(q) < 0.13, String.format(java.util.Locale.ROOT, "a quiet voice evened out (%.3f → %.3f)", loud(arr(l)), loud(q)));
        l.clear();
        say(l, r, 2, 1.5);
        float[] big = arr(l);
        for (int i = 0; i < big.length; i++) big[i] += 0.2f;
        q = Utterances.clean(big, R);
        float peak = 0;
        double mean = 0;
        for (float v : q) {
            peak = Math.max(peak, Math.abs(v));
            mean += v;
        }
        mean /= q.length;
        ok(peak <= 0.951 && Math.abs(mean) < 0.01, String.format(java.util.Locale.ROOT, "a loud voice with an offset: peaks %.2f, mean %.4f", peak, mean));
    }

    /** RMS of the loudest third of 20 ms frames. */
    static double loud(float[] x) {
        int f = R / 50;
        double[] fr = new double[x.length / f];
        for (int k = 0; k < fr.length; k++) {
            double s = 0;
            for (int i = k * f; i < (k + 1) * f; i++) s += x[i] * x[i];
            fr[k] = Math.sqrt(s / f);
        }
        Arrays.sort(fr);
        double s = 0;
        int from = fr.length * 2 / 3;
        for (int i = from; i < fr.length; i++) s += fr[i] * fr[i];
        return Math.sqrt(s / (fr.length - from));
    }

    static void notes() {
        Random r = new Random(5);
        byte[] sound = new byte[5000];
        r.nextBytes(sound);

        // in order, 180 at a time
        VoiceNote n = new VoiceNote();
        ok(n.packet(WristGesture.voiceStart(7, WristGesture.CODEC_RECORDED, 16000, 0)) == VoiceNote.STARTED && n.id() == 7
                && n.rate() == 16000 && n.total() == -1, "start: id, codec, rate; the length not known yet");
        int last = -1;
        for (int o = 0; o < sound.length; o += 180) last = n.packet(WristGesture.voiceData(7, o, sound, o, Math.min(180, sound.length - o)));
        ok(last == VoiceNote.PROGRESS && n.received() == sound.length, "the bytes in order");
        ok(n.packet(WristGesture.voiceEnd(7, sound.length, 3200)) == VoiceNote.COMPLETE && Arrays.equals(n.bytes(), sound)
                && n.millis() == 3200, "the end: the whole note, its duration");
        ok(n.packet(WristGesture.voiceData(7, 0, sound, 0, 10)) == VoiceNote.NONE, "done: more of it is not taken");

        // a write lost: the gap told, the bytes after it kept; sent again from the gap — the note whole
        n = new VoiceNote();
        n.packet(WristGesture.voiceStart(8, WristGesture.CODEC_RECORDED, 16000, 0));
        n.packet(WristGesture.voiceData(8, 0, sound, 0, 1000));
        int gap = n.packet(WristGesture.voiceData(8, 2000, sound, 2000, 1000));
        ok(gap == VoiceNote.MISSING && n.missingFrom() == 1000, "a write lost: «again from 1000»");
        ok(n.packet(WristGesture.voiceData(8, 3000, sound, 3000, 1000)) == VoiceNote.MISSING, "…and the next ones still wait for it");
        ok(n.packet(WristGesture.voiceEnd(8, 5000, 3100)) == VoiceNote.MISSING && n.missingFrom() == 1000,
                "the end with bytes missing: again from 1000");
        ok(n.packet(WristGesture.voiceData(8, 1000, sound, 1000, 1000)) == VoiceNote.PROGRESS && n.received() == 4000,
                "the lost bytes again: what was kept after them follows on (4000 in order)");
        ok(n.packet(WristGesture.voiceData(8, 3500, sound, 3500, 1500)) == VoiceNote.COMPLETE && Arrays.equals(n.bytes(), sound),
                "the rest (overlapping what came): complete, the same bytes");

        // a write sent twice, another note's packets, a new start
        n = new VoiceNote();
        n.packet(WristGesture.voiceStart(9, WristGesture.CODEC_PCM16, 8000, 0));
        n.packet(WristGesture.voiceData(9, 0, sound, 0, 100));
        n.packet(WristGesture.voiceData(9, 0, sound, 0, 100));
        ok(n.received() == 100 && n.codec() == WristGesture.CODEC_PCM16, "a write sent twice is taken once");
        ok(n.packet(WristGesture.voiceData(10, 100, sound, 100, 100)) == VoiceNote.NONE && n.received() == 100,
                "another note's bytes are not mixed in");
        ok(n.packet(WristGesture.voiceStart(10, WristGesture.CODEC_RECORDED, 16000, 0)) == VoiceNote.STARTED && n.received() == 0,
                "a new start: a new note");
        ok(n.packet(new byte[]{0x51, 10, 0}) == VoiceNote.NONE && n.packet(null) == VoiceNote.NONE, "a broken packet does nothing");
    }

    /** An Ogg page (RFC 3533) with these segments; the checksum is not checked by the reader. */
    static byte[] page(int flags, long granule, int seq, byte[] lacing, byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write('O');
        o.write('g');
        o.write('g');
        o.write('S');
        o.write(0);
        o.write(flags);
        for (int i = 0; i < 8; i++) o.write((int) (granule >>> (8 * i)));
        for (int i = 0; i < 4; i++) o.write(i == 0 ? 0x2a : 0);
        for (int i = 0; i < 4; i++) o.write(seq >>> (8 * i));
        for (int i = 0; i < 4; i++) o.write(0);
        o.write(lacing.length);
        o.write(lacing, 0, lacing.length);
        o.write(data, 0, data.length);
        return o.toByteArray();
    }

    static byte[] lacing(int... lens) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int n : lens) {
            while (n >= 255) {
                o.write(255);
                n -= 255;
            }
            o.write(n);
        }
        return o.toByteArray();
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static void ogg() {
        Random r = new Random(11);
        byte[] head = OggOpus.head(1, 312, 16000);
        byte[] tags = cat("OpusTags".getBytes(StandardCharsets.US_ASCII), new byte[]{4, 0, 0, 0, 'v', 'e', 'l', 'a', 0, 0, 0, 0});
        List<byte[]> audio = new ArrayList<byte[]>();
        int[] sizes = {40, 255, 300, 61, 510, 12};
        for (int s : sizes) {
            byte[] p = new byte[s];
            r.nextBytes(p);
            p[0] = (byte) (9 << 3); // config 9: SILK 20 ms, one frame
            audio.add(p);
        }
        // pages: OpusHead; OpusTags; the first three packets; the fourth and half of the fifth (on to the next page); the rest
        byte[] fifth = audio.get(4);
        byte[] p3 = page(0, 0, 2, lacing(40, 255, 300), cat(audio.get(0), audio.get(1), audio.get(2)));
        byte[] l4 = cat(lacing(61), new byte[]{(byte) 255});
        byte[] p4 = page(0, 0, 3, l4, cat(audio.get(3), Arrays.copyOfRange(fifth, 0, 255)));
        byte[] p5 = page(1, 0, 4, lacing(510 - 255, 12), cat(Arrays.copyOfRange(fifth, 255, 510), audio.get(5)));
        byte[] file = cat(page(2, 0, 0, lacing(head.length), head), page(0, 0, 1, lacing(tags.length), tags), p3, p4, p5);
        OggOpus o = OggOpus.read(file);
        boolean same = o != null && o.packets.size() == audio.size();
        for (int i = 0; same && i < audio.size(); i++) same = Arrays.equals(o.packets.get(i), audio.get(i));
        ok(o != null && o.channels == 1 && o.preSkip == 312 && Arrays.equals(o.head, head), "Ogg Opus: OpusHead read (1 channel, pre-skip 312)");
        ok(same, "Ogg Opus: the audio packets as they were — 255 bytes exactly, across segments, across pages; OpusTags left out");
        ok(OggOpus.read(Arrays.copyOf(file, file.length - 100)) != null, "a file cut short still gives what it has");
        ok(OggOpus.read("RIFF....WAVE".getBytes(StandardCharsets.US_ASCII)) == null && !OggOpus.looksLike(new byte[]{1, 2}),
                "not Ogg: nothing");

        // packets each after its length (how the watch sends Opus with no file around it)
        ByteArrayOutputStream lp = new ByteArrayOutputStream();
        for (byte[] p : audio) {
            lp.write(p.length & 0xff);
            lp.write(p.length >>> 8);
            lp.write(p, 0, p.length);
        }
        List<byte[]> back = OggOpus.lengthPrefixed(lp.toByteArray());
        same = back.size() == audio.size();
        for (int i = 0; same && i < audio.size(); i++) same = Arrays.equals(back.get(i), audio.get(i));
        ok(same, "Opus packets each after its length: the same packets");

        // an Opus packet's length (RFC 6716): SILK 20 ms; CELT 2.5 ms × 2; CELT 20 ms × 3 (code 3)
        ok(OggOpus.samples48k(new byte[]{(byte) (9 << 3)}) == 960 && OggOpus.samples48k(new byte[]{(byte) (16 << 3 | 1)}) == 240
                && OggOpus.samples48k(new byte[]{(byte) (31 << 3 | 3), 3}) == 2880, "an Opus packet's length in samples");
    }

    static void events(String dir) throws Exception {
        byte[] cfg = WristGesture.config(WristGesture.CFG_LEFT_HAND | WristGesture.CFG_VOICE, 247);
        ok(hex(cfg).equals("0603f700"), "EV_CONFIG: left hand, voice notes, MTU 247");
        byte[] from = WristGesture.voiceFrom(7, 70000);
        ok(hex(from).equals("080770110100"), "EV_VOICE_FROM: note 7, again from 70000");
        String line = "Сохранено: купить хлеб · напомнит завтра в 9:00";
        byte[] done = WristGesture.voiceDone(7, WristGesture.VOICE_SAVED, line, 244);
        ok(new String(done, 3, done.length - 3, StandardCharsets.UTF_8).equals(line), "EV_VOICE_DONE: the whole line when it fits");
        byte[] cut = WristGesture.voiceDone(7, WristGesture.VOICE_SAVED, line, 20);
        String part = new String(cut, 3, cut.length - 3, StandardCharsets.UTF_8);
        ok(cut.length <= 20 && cut.length >= 19 && line.startsWith(part) && part.length() == 8,
                "EV_VOICE_DONE in 20 bytes: cut on a whole letter («" + part + "»)");
        if (dir != null) {
            Files.createDirectories(Paths.get(dir));
            Files.write(Paths.get(dir, "events.hex"), (hex(cfg) + "\n" + hex(WristGesture.config(0, 23)) + "\n" + hex(from) + "\n"
                    + hex(done) + "\n" + hex(cut) + "\n").getBytes(StandardCharsets.US_ASCII));
        }
    }
}
