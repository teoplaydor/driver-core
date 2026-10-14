package io.github.teoplaydor.semsearch.core;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * Russian speech recognized on the phone with GigaAM v3 (Sber's model; its «e2e» RNN-T writes the text with punctuation
 * and capitals itself), on ONNX Runtime, the way onnx-asr runs its ONNX export (istupakov/gigaam-v3-onnx): log-mel
 * features (64 bands, 20 ms windows every 10 ms), the Conformer encoder, then greedy RNN-T decoding — the LSTM decoder
 * and the joint network a step at a time — and the SentencePiece pieces joined into words.
 *
 * <p>The features are onnx-asr's for «gigaam_v3» to the bit: the window and the mel filters are the model's own, kept in
 * bfloat16 — a periodic Hann window of 320 and HTK mel triangles over 161 bins, each rounded to bfloat16.
 */
public final class GigaAm implements Closeable {
    public static final int RATE = 16000;
    static final int N_FFT = 320, HOP = 160, MELS = 64, BINS = N_FFT / 2 + 1;
    /** The decoder's LSTM state size (GigaAM's pred_hidden). */
    static final int PRED_HIDDEN = 320;
    /** At most this much sound at once: the encoder's attention is trained on pieces up to ~25 s. */
    public static final int MAX_MS = 22000;
    /** The model's files (onnx-asr's names); «.int8» (or «_int8») ones taken when there. */
    public static final String PREFIX = "v3_e2e_rnnt";

    static final float[] WINDOW = window();
    /** [bin][mel]. */
    static final float[][] FILTERS = filters();
    private static final double[] COS = new double[N_FFT], SIN = new double[N_FFT];

    static {
        for (int i = 0; i < N_FFT; i++) {
            COS[i] = Math.cos(2 * Math.PI * i / N_FFT);
            SIN[i] = Math.sin(2 * Math.PI * i / N_FFT);
        }
    }

    /** Which files of a model folder (or of a repo's listing) to use; null fields — missing. */
    public static final class Files {
        public String encoder, decoder, joint, vocab, config;
        /** Each graph's weights kept beside it (name.onnx.data), when it has them. */
        public final List<String> extra = new ArrayList<String>();

        public boolean complete() {
            return encoder != null && decoder != null && joint != null && vocab != null;
        }

        public List<String> all() {
            List<String> out = new ArrayList<String>();
            for (String s : new String[]{config, vocab, decoder, joint, encoder}) if (s != null) out.add(s);
            out.addAll(extra);
            return out;
        }

        public boolean int8() {
            return encoder != null && encoder.contains("int8");
        }
    }

    /** The files to use among these names (a folder's, or a repo's): the int8 graphs where there are some. */
    public static Files pick(Collection<String> names) {
        Files f = new Files();
        f.encoder = graph(names, PREFIX + "_encoder");
        f.decoder = graph(names, PREFIX + "_decoder");
        f.joint = graph(names, PREFIX + "_joint");
        if (names.contains(PREFIX + "_vocab.txt")) f.vocab = PREFIX + "_vocab.txt";
        if (names.contains("config.json")) f.config = "config.json";
        for (String g : new String[]{f.encoder, f.decoder, f.joint}) {
            if (g == null) continue;
            for (String n : names) if (n.equals(g + ".data") || n.equals(g + "_data")) f.extra.add(n);
        }
        return f;
    }

    private static String graph(Collection<String> names, String base) {
        Pattern int8 = Pattern.compile(Pattern.quote(base) + "[._-]int8\\.onnx");
        for (String n : names) if (int8.matcher(n).matches()) return n;
        return names.contains(base + ".onnx") ? base + ".onnx" : null;
    }

    private final OrtEnvironment env;
    private final OrtSession encoder, decoder, joint;
    private final String[] vocab;
    private final int blank;
    private final int maxTokensPerStep;
    private final OnnxJavaType lengthType, tokenType;
    private final int hidden;

    /** The model in {@code dir} (its files as {@link #pick} finds them). */
    public GigaAm(File dir, int threads) throws IOException, OrtException {
        String[] list = dir.list();
        Files f = pick(list == null ? new ArrayList<String>() : Arrays.asList(list));
        if (!f.complete()) throw new IOException("в " + dir + " нет файлов модели GigaAM v3 e2e (" + PREFIX + "_encoder/decoder/joint, vocab)");
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(Math.max(1, threads));
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        // the small graphs run thousands of times a note: one thread each, no waking of a pool
        OrtSession.SessionOptions small = new OrtSession.SessionOptions();
        small.setIntraOpNumThreads(1);
        small.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        encoder = env.createSession(new File(dir, f.encoder).getPath(), o);
        decoder = env.createSession(new File(dir, f.decoder).getPath(), small);
        joint = env.createSession(new File(dir, f.joint).getPath(), small);
        Map<Integer, String> v = readVocab(new File(dir, f.vocab));
        int n = 0;
        for (int id : v.keySet()) n = Math.max(n, id + 1);
        vocab = new String[n];
        int b = -1;
        for (Map.Entry<Integer, String> e : v.entrySet()) {
            vocab[e.getKey()] = e.getValue();
            if ("<blk>".equals(e.getValue())) b = e.getKey();
        }
        blank = b >= 0 ? b : n - 1;
        int steps = 3;
        if (f.config != null) {
            Map<String, Object> c = MiniJson.obj(new MiniJson(reader(new File(dir, f.config))).readValue());
            if (c != null) {
                steps = (int) MiniJson.num(c, "max_tokens_per_step", steps);
                long size = MiniJson.num(c, "features_size", MELS);
                if (size != MELS) throw new IOException("GigaAM: признаков " + size + ", а не " + MELS);
            }
        }
        maxTokensPerStep = Math.max(1, steps);
        lengthType = type(encoder, "length");
        tokenType = type(decoder, "x");
        long[] hs = ((TensorInfo) decoder.getInputInfo().get("h.1").getInfo()).getShape();
        hidden = hs.length == 3 && hs[2] > 0 ? (int) hs[2] : PRED_HIDDEN;
    }

    private static OnnxJavaType type(OrtSession s, String input) throws OrtException {
        NodeInfo ni = s.getInputInfo().get(input);
        if (ni == null) throw new IllegalStateException("у графа нет входа «" + input + "» (не та выгрузка GigaAM)");
        return ((TensorInfo) ni.getInfo()).type;
    }

    private static Reader reader(File f) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
    }

    /** «piece id» per line (onnx-asr's vocab), «▁» — a space. */
    static Map<Integer, String> readVocab(File f) throws IOException {
        Map<Integer, String> out = new HashMap<Integer, String>();
        BufferedReader r = (BufferedReader) reader(f);
        try {
            String l;
            while ((l = r.readLine()) != null) {
                int sp = l.lastIndexOf(' ');
                if (sp <= 0) continue;
                out.put(Integer.parseInt(l.substring(sp + 1).trim()), l.substring(0, sp).replace('▁', ' '));
            }
        } finally {
            r.close();
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ features

    /** The nearest bfloat16 (ties to even), as the model keeps its window and filters. */
    public static float bf16(float v) {
        int u = Float.floatToRawIntBits(v);
        u = (u + 0x7FFF + ((u >>> 16) & 1)) & 0xFFFF0000;
        return Float.intBitsToFloat(u);
    }

    public static float[] window() {
        float[] w = new float[N_FFT];
        for (int i = 0; i < N_FFT; i++) w[i] = bf16((float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N_FFT)));
        return w;
    }

    private static double hzToMel(double f) {
        return 2595 * Math.log10(1 + f / 700);
    }

    private static double melToHz(double m) {
        return 700 * (Math.pow(10, m / 2595) - 1);
    }

    /** torchaudio's melscale_fbanks(161, 0, 8000, 64, 16000, norm=None, «htk»), in bfloat16. */
    public static float[][] filters() {
        double[] pts = new double[MELS + 2];
        double top = hzToMel(RATE / 2.0);
        for (int i = 0; i < pts.length; i++) pts[i] = melToHz(top * i / (MELS + 1));
        float[][] f = new float[BINS][MELS];
        for (int k = 0; k < BINS; k++) {
            double hz = (float) (RATE / 2.0 * k / (BINS - 1));
            for (int m = 0; m < MELS; m++) {
                double down = (hz - pts[m]) / (pts[m + 1] - pts[m]);
                double up = (pts[m + 2] - hz) / (pts[m + 2] - pts[m + 1]);
                f[k][m] = bf16((float) Math.max(0, Math.min(down, up)));
            }
        }
        return f;
    }

    /** How many feature frames this many samples give (none for less than one window). */
    public static int frames(int samples) {
        return samples < N_FFT ? 0 : (samples - N_FFT) / HOP + 1;
    }

    /** The log-mel features, [64][frames] flattened row by row (the encoder's [1, 64, T]). */
    public static float[] features(float[] x) {
        int t = frames(x.length);
        float[] out = new float[MELS * t];
        double[] s = new double[N_FFT];
        float[] power = new float[BINS];
        for (int j = 0; j < t; j++) {
            int at = j * HOP;
            for (int i = 0; i < N_FFT; i++) s[i] = x[at + i] * WINDOW[i];
            for (int k = 0; k < BINS; k++) {
                double re = 0, im = 0;
                int idx = 0;
                for (int i = 0; i < N_FFT; i++) {
                    re += s[i] * COS[idx];
                    im -= s[i] * SIN[idx];
                    idx += k;
                    if (idx >= N_FFT) idx -= N_FFT;
                }
                float mag = (float) Math.sqrt(re * re + im * im);
                power[k] = mag * mag;
            }
            for (int m = 0; m < MELS; m++) {
                float e = 0;
                for (int k = 0; k < BINS; k++) e += power[k] * FILTERS[k][m];
                out[m * t + j] = (float) Math.log(Math.max(1e-9f, Math.min(1e9f, e)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ decoding

    /** The pieces said (vocabulary ids), greedy RNN-T as onnx-asr decodes. */
    public synchronized int[] tokens(float[] x) throws OrtException {
        int t = frames(x.length);
        if (t == 0) return new int[0];
        float[] feats = features(x);
        float[] enc;
        int dim, len, encT;
        OnnxTensor fe = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), new long[]{1, MELS, t});
        OnnxTensor le = lengthType == OnnxJavaType.INT32 ? OnnxTensor.createTensor(env, IntBuffer.wrap(new int[]{t}), new long[]{1})
                : OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{t}), new long[]{1});
        try {
            Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
            in.put("audio_signal", fe);
            in.put("length", le);
            OrtSession.Result r = encoder.run(in);
            try {
                OnnxTensor e = (OnnxTensor) r.get("encoded").get();
                long[] shape = e.getInfo().getShape(); // [1, D, T']
                dim = (int) shape[1];
                encT = (int) shape[2];
                FloatBuffer fb = e.getFloatBuffer();
                enc = new float[fb.remaining()];
                fb.get(enc);
                len = (int) Math.min(encT, first((OnnxTensor) r.get("encoded_len").get()));
            } finally {
                r.close();
            }
        } finally {
            fe.close();
            le.close();
        }
        List<Integer> out = new ArrayList<Integer>();
        float[] h = new float[hidden], c = new float[hidden];
        float[][] step = decode(blank, h, c); // {dec, h, c} for the last piece (none yet: blank)
        float[] frame = new float[dim];
        int emitted = 0;
        int i = 0;
        while (i < len) {
            for (int d = 0; d < dim; d++) frame[d] = enc[d * encT + i];
            float[] logits = joint(frame, step[0]);
            int best = 0;
            for (int k = 1; k < logits.length; k++) if (logits[k] > logits[best]) best = k;
            if (best != blank) {
                out.add(best);
                emitted++;
                step = decode(best, step[1], step[2]);
            }
            if (best == blank || emitted == maxTokensPerStep) {
                i++;
                emitted = 0;
            }
        }
        int[] ids = new int[out.size()];
        for (int k = 0; k < ids.length; k++) ids[k] = out.get(k);
        return ids;
    }

    private static long first(OnnxTensor t) throws OrtException {
        Object v = t.getValue();
        if (v instanceof int[]) return ((int[]) v)[0];
        if (v instanceof long[]) return ((long[]) v)[0];
        throw new IllegalStateException("длина кодировщика: неожиданный тип " + v.getClass());
    }

    /** The decoder after one more piece: {its output, h, c}. */
    private float[][] decode(int token, float[] h, float[] c) throws OrtException {
        OnnxTensor x = tokenType == OnnxJavaType.INT32 ? OnnxTensor.createTensor(env, IntBuffer.wrap(new int[]{token}), new long[]{1, 1})
                : OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{token}), new long[]{1, 1});
        OnnxTensor ht = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), new long[]{1, 1, hidden});
        OnnxTensor ct = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), new long[]{1, 1, hidden});
        try {
            Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
            in.put("x", x);
            in.put("h.1", ht);
            in.put("c.1", ct);
            OrtSession.Result r = decoder.run(in);
            try {
                return new float[][]{floats(r.get("dec").get()), floats(r.get("h").get()), floats(r.get("c").get())};
            } finally {
                r.close();
            }
        } finally {
            x.close();
            ht.close();
            ct.close();
        }
    }

    private float[] joint(float[] frame, float[] dec) throws OrtException {
        OnnxTensor e = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), new long[]{1, frame.length, 1});
        OnnxTensor d = OnnxTensor.createTensor(env, FloatBuffer.wrap(dec), new long[]{1, dec.length, 1});
        try {
            Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
            in.put("enc", e);
            in.put("dec", d);
            OrtSession.Result r = joint.run(in);
            try {
                return floats(r.get("joint").get());
            } finally {
                r.close();
            }
        } finally {
            e.close();
            d.close();
        }
    }

    private static float[] floats(OnnxValue v) {
        FloatBuffer fb = ((OnnxTensor) v).getFloatBuffer();
        float[] out = new float[fb.remaining()];
        fb.get(out);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ text

    public String piece(int id) {
        return id >= 0 && id < vocab.length && vocab[id] != null ? vocab[id] : "";
    }

    /** The pieces as text (SentencePiece's way: «▁» is a space), the spaces around punctuation tidied. */
    public String text(int[] ids) {
        List<String> p = new ArrayList<String>();
        for (int id : ids) p.add(piece(id));
        return join(p);
    }

    static final Pattern SPECIAL = Pattern.compile("<[^<>\\s]+>");

    /** Pieces (their «▁» already spaces) joined: no special pieces, one space between words, none before , . ! ? : ; ) ». */
    public static String join(List<String> pieces) {
        StringBuilder b = new StringBuilder();
        for (String s : pieces) if (!SPECIAL.matcher(s).matches()) b.append(s);
        return b.toString().replaceAll("\\s+", " ").trim().replaceAll(" ([,.!?:;)»…])", "$1").replaceAll("([(«]) ", "$1");
    }

    /** What was said in this sound (16 kHz mono, at most {@link #MAX_MS}; longer — split it first). */
    public String recognize(float[] x) throws OrtException {
        return text(tokens(x));
    }

    @Override
    public void close() {
        for (OrtSession s : new OrtSession[]{encoder, decoder, joint}) {
            try {
                s.close();
            } catch (OrtException e) {
                // closing anyway
            }
        }
    }
}
