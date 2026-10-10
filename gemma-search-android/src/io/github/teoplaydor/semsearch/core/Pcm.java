package io.github.teoplaydor.semsearch.core;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Sound as the audio encoder takes it: mono, 16 kHz, in [-1, 1]. Channels averaged; another rate converted by a
 * windowed-sinc low-pass resampler (nothing above the new Nyquist folds back); WAV files read directly (PCM 8/16/24/32
 * bits, float, the extensible header); the silence before the first sound skipped.
 */
public final class Pcm {
    public static final int RATE = 16000;

    private Pcm() {
    }

    /** Interleaved samples of {@code channels} channels averaged into one. */
    public static float[] mono(float[] interleaved, int channels) {
        if (channels <= 1) return interleaved;
        float[] out = new float[interleaved.length / channels];
        for (int i = 0; i < out.length; i++) {
            float s = 0;
            for (int c = 0; c < channels; c++) s += interleaved[i * channels + c];
            out[i] = s / channels;
        }
        return out;
    }

    /** {@code x} at {@code from} Hz, at {@code to} Hz: band-limited (Blackman-windowed sinc, 16 zero crossings). */
    public static float[] resample(float[] x, int from, int to) {
        if (from == to || x.length == 0) return x;
        double ratio = (double) from / to;
        double fc = 0.97 * Math.min(1.0, (double) to / from); // the cut-off, of the input's Nyquist
        int zeros = 16;
        double half = zeros / fc; // the filter's half-width in input samples
        int k = (int) Math.ceil(half);
        int res = 256; // table steps per input sample
        int size = k * res + 2;
        float[] table = new float[size];
        for (int i = 0; i < size; i++) {
            double d = i / (double) res;
            if (d > half) {
                table[i] = 0;
                continue;
            }
            double sinc = d == 0 ? 1 : Math.sin(Math.PI * fc * d) / (Math.PI * fc * d);
            double w = d / half; // 0 at the centre, 1 at the edge
            double blackman = 0.42 + 0.5 * Math.cos(Math.PI * w) + 0.08 * Math.cos(2 * Math.PI * w);
            table[i] = (float) (fc * sinc * blackman);
        }
        int n = (int) Math.floor((x.length - 1) / ratio) + 1;
        float[] out = new float[n];
        for (int m = 0; m < n; m++) {
            double t = m * ratio;
            int c = (int) Math.floor(t);
            int lo = Math.max(0, c - k + 1), hi = Math.min(x.length - 1, c + k);
            double s = 0;
            for (int j = lo; j <= hi; j++) {
                double pos = Math.abs(t - j) * res;
                int p = (int) pos;
                if (p + 1 >= size) continue;
                double f = pos - p;
                s += x[j] * (table[p] + f * (table[p + 1] - table[p]));
            }
            out[m] = (float) s;
        }
        return out;
    }

    /** Where the sound starts: the first 10 ms with a peak above -46 dBFS, at most {@code maxSkip} samples in. */
    public static int soundStart(float[] x, int rate, int maxSkip) {
        int win = Math.max(1, rate / 100);
        for (int i = 0; i < Math.min(x.length, maxSkip); i += win) {
            float peak = 0;
            for (int j = i; j < Math.min(x.length, i + win); j++) peak = Math.max(peak, Math.abs(x[j]));
            if (peak > 0.005f) return i;
        }
        return Math.min(x.length, maxSkip) >= x.length ? 0 : Math.min(maxSkip, x.length);
    }

    /** Samples {@code from}.. of {@code x}, at most {@code max}. */
    public static float[] slice(float[] x, int from, int max) {
        int n = Math.max(0, Math.min(max, x.length - from));
        float[] out = new float[n];
        System.arraycopy(x, from, out, 0, n);
        return out;
    }

    /** A WAV file's sound: its samples (mono) and rate. */
    public static final class Wav {
        public final float[] samples;
        public final int rate;

        Wav(float[] samples, int rate) {
            this.samples = samples;
            this.rate = rate;
        }
    }

    /** Reads a WAV file, at most {@code maxSeconds} of it, channels averaged. */
    public static Wav readWav(InputStream raw, double maxSeconds) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        byte[] id = new byte[4];
        in.readFully(id);
        if (!new String(id, "ISO-8859-1").equals("RIFF")) throw new IOException("не WAV (нет RIFF)");
        le32(in);
        in.readFully(id);
        if (!new String(id, "ISO-8859-1").equals("WAVE")) throw new IOException("не WAV (нет WAVE)");
        int format = -1, channels = 1, rate = 0, bits = 16;
        while (true) {
            try {
                in.readFully(id);
            } catch (EOFException e) {
                throw new IOException("WAV без данных");
            }
            String chunk = new String(id, "ISO-8859-1");
            long size = le32(in) & 0xffffffffL;
            if (chunk.equals("fmt ")) {
                format = le16(in);
                channels = le16(in);
                rate = le32(in);
                le32(in);
                le16(in);
                bits = le16(in);
                long left = size - 16;
                if (format == 0xFFFE && left >= 10) { // WAVE_FORMAT_EXTENSIBLE: the real format in the sub-format GUID
                    le16(in);
                    le16(in);
                    le32(in);
                    format = le16(in);
                    left -= 10;
                }
                skip(in, left + (size & 1));
            } else if (chunk.equals("data")) {
                if (format != 1 && format != 3) throw new IOException("WAV в формате " + format + " (нужен PCM)");
                int bytes = bits / 8;
                long frames = size / ((long) bytes * channels);
                long want = Math.min(frames, (long) (maxSeconds * rate));
                float[] inter = new float[(int) (want * channels)];
                byte[] buf = new byte[bytes * channels * 4096];
                int done = 0;
                while (done < inter.length) {
                    int n = (int) Math.min(buf.length, (long) (inter.length - done) * bytes);
                    in.readFully(buf, 0, n);
                    for (int k = 0; k < n; k += bytes) inter[done++] = sample(buf, k, bits, format == 3);
                }
                return new Wav(mono(inter, channels), rate);
            } else {
                skip(in, size + (size & 1));
            }
        }
    }

    private static float sample(byte[] b, int k, int bits, boolean flt) {
        switch (bits) {
            case 8:
                return ((b[k] & 0xff) - 128) / 128f;
            case 16:
                return (short) ((b[k] & 0xff) | (b[k + 1] << 8)) / 32768f;
            case 24:
                return (((b[k] & 0xff) | ((b[k + 1] & 0xff) << 8) | (b[k + 2] << 16))) / 8388608f;
            case 32:
                int v = (b[k] & 0xff) | ((b[k + 1] & 0xff) << 8) | ((b[k + 2] & 0xff) << 16) | (b[k + 3] << 24);
                return flt ? Float.intBitsToFloat(v) : v / 2147483648f;
            default:
                return 0;
        }
    }

    private static int le16(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte(), b = in.readUnsignedByte();
        return a | (b << 8);
    }

    private static int le32(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte(), b = in.readUnsignedByte(), c = in.readUnsignedByte(), d = in.readUnsignedByte();
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    private static void skip(DataInputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException();
                s = 1;
            }
            n -= s;
        }
    }

    /** 16-bit mono PCM as a WAV file (tests; a clip to share). */
    public static byte[] wav16(float[] x, int rate) {
        int data = x.length * 2;
        byte[] b = new byte[44 + data];
        put(b, 0, "RIFF");
        le(b, 4, 36 + data, 4);
        put(b, 8, "WAVEfmt ");
        le(b, 16, 16, 4);
        le(b, 20, 1, 2);
        le(b, 22, 1, 2);
        le(b, 24, rate, 4);
        le(b, 28, rate * 2, 4);
        le(b, 32, 2, 2);
        le(b, 34, 16, 2);
        put(b, 36, "data");
        le(b, 40, data, 4);
        for (int i = 0; i < x.length; i++) {
            int v = Math.max(-32768, Math.min(32767, Math.round(x[i] * 32767)));
            le(b, 44 + 2 * i, v, 2);
        }
        return b;
    }

    private static void put(byte[] b, int at, String s) {
        for (int i = 0; i < s.length(); i++) b[at + i] = (byte) s.charAt(i);
    }

    private static void le(byte[] b, int at, int v, int n) {
        for (int i = 0; i < n; i++) b[at + i] = (byte) (v >> (8 * i));
    }
}
