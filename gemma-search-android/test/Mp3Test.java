import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;

import io.github.teoplaydor.semsearch.core.Mp3;

/**
 * The phone's MP3 encoder (core/Mp3) against FFmpeg's decoder: the files it makes read without a single error as MPEG-2
 * Layer III, 16 kHz, mono, at the bitrate asked; at a high bitrate the filterbank and transform give the sound back
 * almost exactly (the standard's tables right); at the watch's 32 kbit/s a spoken-like sound comes back at the same
 * level and well enough; silence stays silent; the length is right.
 *
 * usage: Mp3Test OUT_DIR
 */
public class Mp3Test {
    static int bad = 0;

    static void ok(boolean c, String what) {
        System.out.println((c ? "ok   " : "FAIL ") + what);
        if (!c) bad++;
    }

    static final int R = Mp3.RATE;

    /** Something like speech: a voice at 110–180 Hz with its harmonics through two formants, syllables, small pauses. */
    static float[] speech(double seconds, long seed) {
        Random rnd = new Random(seed);
        float[] x = new float[(int) (seconds * R)];
        double phase = 0;
        int i = 0;
        while (i < x.length) {
            int syl = (int) (R * (0.12 + 0.1 * rnd.nextDouble()));
            double f0 = 110 + 70 * rnd.nextDouble(), f1 = 300 + 500 * rnd.nextDouble(), f2 = 900 + 1600 * rnd.nextDouble();
            for (int k = 0; k < syl && i < x.length; k++, i++) {
                double env = Math.pow(Math.sin(Math.PI * k / syl), 0.8);
                phase += 2 * Math.PI * f0 / R;
                double s = 0;
                for (int h = 1; h * f0 < 7000; h++) {
                    double f = h * f0;
                    double a = 1 / (1 + Math.pow((f - f1) / 120, 2)) + 0.6 / (1 + Math.pow((f - f2) / 200, 2)) + 0.02;
                    s += a * Math.sin(h * phase);
                }
                x[i] = (float) (0.12 * env * s + 0.002 * rnd.nextGaussian());
            }
            int gap = (int) (R * 0.05 * rnd.nextDouble());
            for (int k = 0; k < gap && i < x.length; k++, i++) x[i] = (float) (0.002 * rnd.nextGaussian());
        }
        return x;
    }

    /**
     * 20 ms in and out: the encoder leaves out what is above 7 kHz, and a sound cut off sharply has some there (the
     * answer's pieces are cut where it is quiet).
     */
    static void fade(float[] x) {
        int n = R / 50;
        for (int i = 0; i < n; i++) {
            float g = (float) (0.5 - 0.5 * Math.cos(Math.PI * i / n));
            x[i] *= g;
            x[x.length - 1 - i] *= g;
        }
    }

    static byte[] all(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String errs;

    /** FFmpeg's decoding of the file (16-bit at 16 kHz as floats); its complaints in errs. */
    static float[] decode(File f) throws Exception {
        Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-i", f.getPath(), "-f", "s16le", "-acodec", "pcm_s16le", "-ar", String.valueOf(R), "-ac", "1", "-")
                .start();
        byte[] raw = all(p.getInputStream());
        errs = new String(all(p.getErrorStream()), StandardCharsets.UTF_8).trim();
        p.waitFor();
        short[] s = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
        float[] y = new float[s.length];
        for (int i = 0; i < s.length; i++) y[i] = s[i] / 32768f;
        return y;
    }

    static String probe(File f) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,sample_rate,channels,bit_rate", "-of", "csv=p=0", f.getPath()).start();
        String out = new String(all(p.getInputStream()), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }

    /** The delay (samples) at which y matches x best. */
    static int delay(float[] x, float[] y) {
        int best = 0;
        double bestC = -1e30;
        for (int d = 0; d < 2000 && d < y.length; d++) {
            double c = 0;
            for (int i = 0; i + d < y.length && i < x.length; i += 3) c += x[i] * y[i + d];
            if (c > bestC) {
                bestC = c;
                best = d;
            }
        }
        return best;
    }

    /** SNR (dB) of y against x at that delay, and y's level over x's. */
    static double[] snr(float[] x, float[] y, int d) {
        double sig = 0, err = 0, py = 0;
        for (int i = 0; i < x.length && i + d < y.length; i++) {
            sig += (double) x[i] * x[i];
            double e = y[i + d] - x[i];
            err += e * e;
            py += (double) y[i + d] * y[i + d];
        }
        return new double[]{10 * Math.log10(sig / Math.max(1e-20, err)), Math.sqrt(py / Math.max(1e-20, sig))};
    }

    public static void main(String[] a) throws Exception {
        File out = new File(a[0]);
        out.mkdirs();

        // a high bitrate: the filterbank, the transform and the tables give the sound back almost exactly
        float[] tones = new float[R * 2];
        for (int i = 0; i < tones.length; i++) {
            double t = (double) i / R;
            tones[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * t) + 0.2 * Math.sin(2 * Math.PI * 1234.5 * t) + 0.1 * Math.sin(2 * Math.PI * 3100 * t));
        }
        fade(tones);
        byte[] hi = Mp3.encode(tones, 160);
        File fh = new File(out, "tones_160k.mp3");
        Files.write(fh.toPath(), hi);
        float[] yh = decode(fh);
        String ph = probe(fh);
        ok(errs.isEmpty() && ph.startsWith("mp3,16000,1"), "160 kbit/s: FFmpeg reads it — " + ph + (errs.isEmpty() ? "" : " — " + errs));
        int dh = delay(tones, yh);
        double[] sh = snr(tones, yh, dh);
        ok(sh[0] > 60 && Math.abs(sh[1] - 1) < 0.01, String.format("…and gives the sound back: SNR %.1f dB, level %.3f, delay %d samples", sh[0], sh[1], dh));

        // the watch's: 32 kbit/s, something like speech
        float[] sp = speech(3, 5);
        fade(sp);
        byte[] lo = Mp3.encode(sp, 32);
        File fl = new File(out, "speech_32k.mp3");
        Files.write(fl.toPath(), lo);
        float[] yl = decode(fl);
        String pl = probe(fl);
        ok(errs.isEmpty() && pl.startsWith("mp3,16000,1,32000"), "32 kbit/s: FFmpeg reads it without an error — " + pl + (errs.isEmpty() ? "" : " — " + errs));
        int dl = delay(sp, yl);
        double[] sl = snr(sp, yl, dl);
        ok(sl[0] > 20 && Math.abs(sl[1] - 1) < 0.05, String.format("…a spoken-like sound back: SNR %.1f dB, level %.2f", sl[0], sl[1]));
        ok(lo.length == (sp.length + 2 * Mp3.FRAME + Mp3.FRAME - 1) / Mp3.FRAME * 144 && Math.abs(lo.length / 3.0 - 4000) < 400,
                String.format("%.1f KB for 3 s (%.0f bytes a second); plays %d ms", lo.length / 1024.0, lo.length / 3.0, Mp3.durationMs(lo, 32)));
        ok(Math.abs(yl.length - Mp3.durationMs(lo, 32) * R / 1000) <= Mp3.FRAME, "FFmpeg's length the same: " + yl.length + " samples");

        // 24 kbit/s too
        byte[] l24 = Mp3.encode(sp, 24);
        File f24 = new File(out, "speech_24k.mp3");
        Files.write(f24.toPath(), l24);
        float[] y24 = decode(f24);
        double[] s24 = snr(sp, y24, delay(sp, y24));
        ok(errs.isEmpty() && s24[0] > 16, String.format("24 kbit/s: read without an error, SNR %.1f dB", s24[0]));

        // silence, and a loud sound near full scale
        byte[] q = Mp3.encode(new float[R], 32);
        File fq = new File(out, "silence.mp3");
        Files.write(fq.toPath(), q);
        float[] yq = decode(fq);
        double peak = 0;
        for (float v : yq) peak = Math.max(peak, Math.abs(v));
        ok(errs.isEmpty() && peak < 1e-3, "silence stays silent (peak " + peak + ")");
        float[] loud = new float[R];
        for (int i = 0; i < loud.length; i++) loud[i] = (float) (0.95 * Math.sin(2 * Math.PI * 200 * i / R));
        fade(loud);
        File fd = new File(out, "loud.mp3");
        Files.write(fd.toPath(), Mp3.encode(loud, 32));
        float[] yd = decode(fd);
        double[] sd = snr(loud, yd, delay(loud, yd));
        ok(errs.isEmpty() && sd[0] > 40, String.format("a loud sound (0.95): read without an error, SNR %.1f dB", sd[0]));

        // a short piece as the answer's first (0.7 s): a file of its own, plays
        float[] piece = java.util.Arrays.copyOf(sp, R * 7 / 10);
        byte[] pb = Mp3.encode(piece, 32);
        File fp = new File(out, "piece.mp3");
        Files.write(fp.toPath(), pb);
        float[] yp = decode(fp);
        ok(errs.isEmpty() && yp.length >= piece.length, "a 0.7 s piece: " + pb.length + " bytes, " + yp.length + " samples back");

        System.out.println(bad == 0 ? "MP3 OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
