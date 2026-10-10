package io.github.teoplaydor.semsearch.core;

import java.util.List;
import java.util.Map;

/**
 * The Gemma 4 audio features (transformers {@code Gemma4AudioFeatureExtractor}, as transformers.js computes them): mono
 * 16 kHz audio, cut at 30 s and padded to a multiple of 128 samples, half a frame of zeros in front ("semicausal"),
 * 20 ms frames every 10 ms through a periodic Hann window, a 512-point FFT's magnitudes, 128 mel bands (HTK scale,
 * 0–8 kHz, no norm), {@code log(mel + 0.001)}. A frame is valid only when all its samples are real audio; the rest are
 * zeros and masked. The audio encoder keeps every 4th frame, so a valid 4th frame is one soft token (40 ms of audio).
 */
public final class AudioFeatures {
    /** The extractor's settings (processor_config.json "feature_extractor"; the defaults are Gemma 4's). */
    public static final class Params {
        public int featureSize = 128, samplingRate = 16000, frameLength = 320, hopLength = 160, fftLength = 512;
        public double minFrequency = 0, maxFrequency = 8000, melFloor = 1e-3, preemphasis = 0, inputScale = 1,
                paddingValue = 0;
        public boolean preemphasisHtk = true;
        public double[] perBinMean, perBinStd;
        /** The longest clip (samples, 30 s) and the multiple the clip is padded to. */
        public int maxSamples = 480000, padMultiple = 128;
        private float[][] mel; // [band][bin]
        private int[] melFrom, melTo; // each band's non-zero bins

        public static Params read(Map<String, Object> m) {
            Params p = new Params();
            if (m == null) return p;
            p.featureSize = (int) MiniJson.num(m, "feature_size", p.featureSize);
            p.samplingRate = (int) MiniJson.num(m, "sampling_rate", p.samplingRate);
            Object fl = m.get("frame_length"), hl = m.get("hop_length");
            if (fl instanceof Number) p.frameLength = ((Number) fl).intValue();
            else if (m.get("frame_length_ms") instanceof Number) {
                p.frameLength = (int) Math.round(p.samplingRate * ((Number) m.get("frame_length_ms")).doubleValue() / 1000.0);
            }
            if (hl instanceof Number) p.hopLength = ((Number) hl).intValue();
            else if (m.get("hop_length_ms") instanceof Number) {
                p.hopLength = (int) Math.round(p.samplingRate * ((Number) m.get("hop_length_ms")).doubleValue() / 1000.0);
            }
            int fft = 1;
            while (fft < p.frameLength) fft <<= 1;
            if (MiniJson.bool(m, "fft_overdrive", false)) fft <<= 1;
            p.fftLength = (int) MiniJson.num(m, "fft_length", fft);
            p.minFrequency = dbl(m, "min_frequency", p.minFrequency);
            p.maxFrequency = dbl(m, "max_frequency", p.maxFrequency);
            p.melFloor = dbl(m, "mel_floor", p.melFloor);
            p.preemphasis = dbl(m, "preemphasis", p.preemphasis);
            p.preemphasisHtk = MiniJson.bool(m, "preemphasis_htk_flavor", p.preemphasisHtk);
            p.inputScale = dbl(m, "input_scale_factor", p.inputScale);
            p.paddingValue = dbl(m, "padding_value", p.paddingValue);
            p.perBinMean = doubles(m.get("per_bin_mean"));
            p.perBinStd = doubles(m.get("per_bin_stddev"));
            return p;
        }

        private static double dbl(Map<String, Object> m, String key, double def) {
            Object v = m.get(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            List<Object> a = MiniJson.arr(v); // a 0-d numpy array saved as a list of one
            return a != null && a.size() == 1 && a.get(0) instanceof Number ? ((Number) a.get(0)).doubleValue() : def;
        }

        private static double[] doubles(Object v) {
            List<Object> a = MiniJson.arr(v);
            if (a == null) return null;
            double[] out = new double[a.size()];
            for (int i = 0; i < out.length; i++) out[i] = ((Number) a.get(i)).doubleValue();
            return out;
        }

        /** The mel filter bank (transformers' {@code mel_filter_bank}, HTK scale, no norm), made once. */
        synchronized float[][] mel() {
            if (mel != null) return mel;
            int bins = fftLength / 2 + 1;
            double melMin = hzToMel(minFrequency), melMax = hzToMel(maxFrequency);
            double[] filterFreqs = new double[featureSize + 2];
            for (int i = 0; i < filterFreqs.length; i++) {
                filterFreqs[i] = melToHz(melMin + (melMax - melMin) * i / (featureSize + 1));
            }
            double[] fftFreqs = new double[bins];
            double top = Math.floor(samplingRate / 2.0);
            for (int j = 0; j < bins; j++) fftFreqs[j] = top * j / (bins - 1);
            mel = new float[featureSize][bins];
            melFrom = new int[featureSize];
            melTo = new int[featureSize];
            for (int i = 0; i < featureSize; i++) {
                double d0 = filterFreqs[i + 1] - filterFreqs[i], d1 = filterFreqs[i + 2] - filterFreqs[i + 1];
                int from = bins, to = 0;
                for (int j = 0; j < bins; j++) {
                    double down = -(filterFreqs[i] - fftFreqs[j]) / d0, up = (filterFreqs[i + 2] - fftFreqs[j]) / d1;
                    float w = (float) Math.max(0, Math.min(down, up));
                    mel[i][j] = w;
                    if (w != 0) {
                        from = Math.min(from, j);
                        to = j + 1;
                    }
                }
                melFrom[i] = Math.min(from, to);
                melTo[i] = to;
            }
            return mel;
        }

        static double hzToMel(double f) {
            return 2595.0 * Math.log10(1.0 + f / 700.0);
        }

        static double melToHz(double m) {
            return 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0);
        }
    }

    /** {@code frames × featureSize}, row by row (frames in time order). */
    public final float[] features;
    public final boolean[] mask;
    public final int frames, featureSize;

    private AudioFeatures(float[] features, boolean[] mask, int frames, int featureSize) {
        this.features = features;
        this.mask = mask;
        this.frames = frames;
        this.featureSize = featureSize;
    }

    /** The audio encoder's output rows (soft tokens): a valid frame every 4th (two stride-2 convolutions). */
    public int softTokens() {
        int n = 0;
        for (int i = 0; i < frames; i += 4) if (mask[i]) n++;
        return n;
    }

    /** @param pcm mono samples at {@code p.samplingRate}, in [-1, 1] */
    public static AudioFeatures extract(float[] pcm, Params p) {
        int length = Math.min(pcm.length, p.maxSamples);
        int padded = length % p.padMultiple == 0 ? length : length + p.padMultiple - length % p.padMultiple;
        int left = p.frameLength / 2;
        // the clip with half a frame of zeros in front and the padding behind
        double[] x = new double[left + padded];
        for (int i = 0; i < length; i++) x[left + i] = pcm[i] * p.inputScale;
        if (p.paddingValue != 0) for (int i = left + length; i < x.length; i++) x[i] = p.paddingValue;
        int unfold = p.frameLength + 1;
        int frames = Math.max(0, (x.length - unfold) / p.hopLength + 1);
        float[][] mel = p.mel();
        int bins = p.fftLength / 2 + 1;
        double[] window = new double[p.frameLength];
        for (int n = 0; n < p.frameLength; n++) window[n] = 0.5 - 0.5 * Math.cos(2 * Math.PI * n / p.frameLength);
        Fft fft = new Fft(p.fftLength);
        double[] re = new double[p.fftLength], im = new double[p.fftLength], mag = new double[bins];
        float[] out = new float[frames * p.featureSize];
        boolean[] mask = new boolean[frames];
        for (int f = 0; f < frames; f++) {
            int off = f * p.hopLength;
            // valid: the frame's last sample (of the frame_length + 1 unfolded) is real audio
            int end = off + unfold - 1;
            mask[f] = end >= left && end < left + length;
            if (!mask[f]) continue; // zeros, as the extractor leaves the invalid frames
            java.util.Arrays.fill(re, 0);
            java.util.Arrays.fill(im, 0);
            for (int j = 0; j < p.frameLength; j++) re[j] = x[off + j];
            if (p.preemphasis > 0) {
                if (p.preemphasisHtk) {
                    for (int j = p.frameLength - 1; j >= 1; j--) re[j] -= p.preemphasis * re[j - 1];
                    re[0] *= 1 - p.preemphasis;
                } else {
                    for (int j = 0; j < p.frameLength; j++) re[j] = x[off + j + 1] - p.preemphasis * x[off + j];
                }
            }
            for (int j = 0; j < p.frameLength; j++) re[j] *= window[j];
            fft.transform(re, im);
            for (int j = 0; j < bins; j++) mag[j] = Math.sqrt(re[j] * re[j] + im[j] * im[j]);
            int row = f * p.featureSize;
            for (int b = 0; b < p.featureSize; b++) {
                float[] w = mel[b];
                double s = 0;
                for (int j = p.melFrom[b]; j < p.melTo[b]; j++) s += w[j] * mag[j];
                double v = Math.log(s + p.melFloor);
                if (p.perBinMean != null) v -= p.perBinMean[b];
                if (p.perBinStd != null) v /= p.perBinStd[b];
                out[row + b] = (float) v;
            }
        }
        return new AudioFeatures(out, mask, frames, p.featureSize);
    }

    /** An in-place radix-2 complex FFT of a fixed size. */
    static final class Fft {
        final int n;
        final double[] cos, sin;
        final int[] rev;

        Fft(int n) {
            if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("FFT size " + n);
            this.n = n;
            cos = new double[n / 2];
            sin = new double[n / 2];
            for (int i = 0; i < n / 2; i++) {
                cos[i] = Math.cos(-2 * Math.PI * i / n);
                sin[i] = Math.sin(-2 * Math.PI * i / n);
            }
            rev = new int[n];
            int bits = Integer.numberOfTrailingZeros(n);
            for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        }

        void transform(double[] re, double[] im) {
            for (int i = 0; i < n; i++) {
                int j = rev[i];
                if (j > i) {
                    double t = re[i];
                    re[i] = re[j];
                    re[j] = t;
                    t = im[i];
                    im[i] = im[j];
                    im[j] = t;
                }
            }
            for (int size = 2; size <= n; size <<= 1) {
                int half = size / 2, step = n / size;
                for (int i = 0; i < n; i += size) {
                    for (int k = 0; k < half; k++) {
                        double wr = cos[k * step], wi = sin[k * step];
                        int a = i + k, b = a + half;
                        double tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - tr;
                        im[b] = im[a] - ti;
                        re[a] += tr;
                        im[a] += ti;
                    }
                }
            }
        }
    }
}
