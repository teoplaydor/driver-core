import io.github.teoplaydor.semsearch.core.Pcm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Sound for the audio encoder: a tone resampled from 44.1 and 48 kHz to 16 kHz keeps its pitch and loudness, one near
 * the new Nyquist passes, one above it is gone (no aliasing); WAV files of 8/16/24/32-bit PCM, float and the extensible
 * header, stereo averaged, read back; the silence before the sound skipped.
 * usage: PcmTest
 */
public class PcmTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static float[] tone(double hz, int rate, double seconds, double amp) {
        float[] x = new float[(int) (rate * seconds)];
        for (int i = 0; i < x.length; i++) x[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / rate));
        return x;
    }

    /** The amplitude of {@code hz} in {@code x} (Goertzel over the middle, away from the edges). */
    static double amplitude(float[] x, double hz, int rate) {
        int from = x.length / 4, to = 3 * x.length / 4;
        double re = 0, im = 0;
        for (int i = from; i < to; i++) {
            re += x[i] * Math.cos(2 * Math.PI * hz * i / rate);
            im += x[i] * Math.sin(2 * Math.PI * hz * i / rate);
        }
        return 2 * Math.hypot(re, im) / (to - from);
    }

    static byte[] wav(int format, int bits, int channels, int rate, float[] x, boolean extensible) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int bytes = bits / 8, data = x.length * bytes * channels;
        int fmtSize = extensible ? 40 : 16;
        w(b, "RIFF");
        le(b, 4 + (8 + fmtSize) + 8 + 12 + 8 + data, 4);
        w(b, "WAVE");
        w(b, "LIST"); // a chunk to skip
        le(b, 4, 4);
        w(b, "INFO");
        w(b, "fmt ");
        le(b, fmtSize, 4);
        le(b, extensible ? 0xFFFE : format, 2);
        le(b, channels, 2);
        le(b, rate, 4);
        le(b, rate * bytes * channels, 4);
        le(b, bytes * channels, 2);
        le(b, bits, 2);
        if (extensible) {
            le(b, 22, 2);
            le(b, bits, 2);
            le(b, 0, 4);
            le(b, format, 2);
            for (int i = 0; i < 14; i++) b.write(0);
        }
        w(b, "data");
        le(b, data, 4);
        for (float v : x) {
            for (int c = 0; c < channels; c++) {
                float s = c == 0 ? v : -v * 0.5f; // the right channel differs: the average is v/4
                if (format == 3) le(b, Float.floatToIntBits(s), 4);
                else if (bits == 8) b.write(Math.round(s * 127) + 128);
                else le(b, Math.round(s * ((1L << (bits - 1)) - 1)), bytes);
            }
        }
        return b.toByteArray();
    }

    static void w(ByteArrayOutputStream b, String s) {
        for (char c : s.toCharArray()) b.write(c);
    }

    static void le(ByteArrayOutputStream b, long v, int n) {
        for (int i = 0; i < n; i++) b.write((int) (v >> (8 * i)) & 0xff);
    }

    public static void main(String[] args) throws Exception {
        for (int rate : new int[]{44100, 48000, 22050, 8000}) {
            float[] y = Pcm.resample(tone(1000, rate, 1.0, 0.5), rate, Pcm.RATE);
            double a = amplitude(y, 1000, Pcm.RATE);
            check(Math.abs(y.length - Pcm.RATE) <= 1 && Math.abs(a - 0.5) < 0.01,
                    String.format(Locale.ROOT, "1 kHz from %d Hz: %d samples, amplitude %.3f", rate, y.length, a));
        }
        float[] near = Pcm.resample(tone(7000, 48000, 1.0, 0.5), 48000, Pcm.RATE);
        check(Math.abs(amplitude(near, 7000, Pcm.RATE) - 0.5) < 0.05, "7 kHz (under the new Nyquist) passes");
        float[] above = Pcm.resample(tone(12000, 48000, 1.0, 0.5), 48000, Pcm.RATE);
        double alias = amplitude(above, 4000, Pcm.RATE); // 12 kHz would fold to 4 kHz
        check(alias < 0.5 * 0.01, String.format(Locale.ROOT, "12 kHz is gone, no 4 kHz alias (%.1f dB)", 20 * Math.log10(alias / 0.5 + 1e-12)));

        float[] x = tone(440, 22050, 0.5, 0.8);
        for (int[] f : new int[][]{{1, 8}, {1, 16}, {1, 24}, {1, 32}, {3, 32}}) {
            for (boolean ext : new boolean[]{false, true}) {
                Pcm.Wav back = Pcm.readWav(new ByteArrayInputStream(wav(f[0], f[1], 2, 22050, x, ext)), 60);
                double err = 0;
                for (int i = 0; i < x.length; i++) err = Math.max(err, Math.abs(back.samples[i] - x[i] / 4));
                check(back.rate == 22050 && back.samples.length == x.length && err < (f[1] == 8 ? 0.01 : 1e-3),
                        String.format(Locale.ROOT, "WAV %s %d-bit%s stereo: rate, length, samples (err %.1e)", f[0] == 3 ? "float" : "PCM",
                                f[1], ext ? " extensible" : "", err));
            }
        }
        Pcm.Wav cut = Pcm.readWav(new ByteArrayInputStream(wav(1, 16, 1, 22050, x, false)), 0.1);
        check(cut.samples.length == 2205, "at most the seconds asked");
        Pcm.Wav round = Pcm.readWav(new ByteArrayInputStream(Pcm.wav16(x, 22050)), 60);
        check(round.samples.length == x.length && Math.abs(round.samples[100] - x[100]) < 1e-3, "wav16 written and read");

        float[] lead = new float[16000 + 8000];
        System.arraycopy(tone(300, 16000, 0.5, 0.3), 0, lead, 16000, 8000);
        check(Math.abs(Pcm.soundStart(lead, 16000, 5 * 16000) - 16000) <= 160, "a second of silence before the sound skipped");
        check(Pcm.soundStart(new float[8000], 16000, 5 * 16000) == 0, "all silence: nothing skipped");
        System.out.println(bad == 0 ? "PCM OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
