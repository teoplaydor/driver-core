import io.github.teoplaydor.semsearch.core.AudioFeatures;
import io.github.teoplaydor.semsearch.core.EmbeddingGemma2;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.MiniJson;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Audio in the Java EmbeddingGemma 2 pipeline against transformers.js (test/parity/reference_audio.mjs): the Gemma 4
 * log-mel features and their mask for clips of several lengths (a multiple of 128 samples and not, a fifth of a
 * second, one second over the 30 s cut), and the vectors of audio alone and of a video with its sound (token ids too).
 * usage: AudioParityTest <model_dir> <reference_audio.json>
 */
public class AudioParityTest {
    /** The clip reference_audio.mjs makes (the same generator, exact in both). */
    static float[] clip(double seconds, int k) {
        int n = (int) Math.round(seconds * 16000);
        float[] out = new float[n];
        long seed = 12345 + k;
        for (int i = 0; i < n; i++) {
            seed = (seed * 16807) % 2147483647L;
            double t = i / 16000.0;
            double noise = (seed / 2147483647.0 - 0.5) * 0.02;
            double on = i < 800 ? 0 : 1;
            out[i] = (float) (on * (0.3 * Math.sin(2 * Math.PI * (220 + 40 * k) * t) + 0.2 * Math.sin(2 * Math.PI * (300 + 900 * t) * t)
                    + 0.1 * Math.sin(2 * Math.PI * 3150 * t)) + noise);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        EmbeddingGemma2 m = new EmbeddingGemma2(dir, new File(dir, "onnx/model.onnx"), new File(dir, "onnx/vision_encoder.onnx"), 2);
        if (m.supportsAudio()) throw new AssertionError("audio before its encoder was loaded");
        m.loadAudio(new File(dir, "onnx/audio_encoder.onnx"), 2);
        List<Object> ref = MiniJson.arr(MiniJson.parse(new String(java.nio.file.Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.UTF_8)));
        int bad = 0;
        for (Object o : ref) {
            Map<String, Object> c = MiniJson.obj(o);
            String label = (String) c.get("label");
            String[] parts = label.split(":");
            boolean ok;
            if (parts[0].equals("features")) {
                AudioFeatures f = AudioFeatures.extract(clip(Double.parseDouble(parts[1]), Integer.parseInt(parts[2])), m.config().audio);
                List<Object> dims = MiniJson.arr(c.get("dims")), mask = MiniJson.arr(c.get("mask"));
                boolean shape = ((Number) dims.get(1)).intValue() == f.frames && ((Number) dims.get(2)).intValue() == f.featureSize;
                boolean maskOk = mask.size() == f.frames;
                for (int i = 0; maskOk && i < f.frames; i++) maskOk = (((Number) mask.get(i)).intValue() != 0) == f.mask[i];
                double maxDiff = 0, sum = 0, sumSq = 0;
                for (float v : f.features) {
                    sum += v;
                    sumSq += (double) v * v;
                }
                List<Object> feats = MiniJson.arr(c.get("features"));
                if (feats != null) {
                    for (int i = 0; i < f.features.length && i < feats.size(); i++) {
                        maxDiff = Math.max(maxDiff, Math.abs(f.features[i] - ((Number) feats.get(i)).doubleValue()));
                    }
                }
                double expSum = ((Number) c.get("sum")).doubleValue(), expSq = ((Number) c.get("sum_sq")).doubleValue();
                double sumRel = Math.abs(sum - expSum) / Math.max(1, Math.abs(expSum)), sqRel = Math.abs(sumSq - expSq) / Math.max(1, expSq);
                ok = shape && maskOk && maxDiff < 2e-3 && sumRel < 1e-5 && sqRel < 1e-5;
                System.out.printf("%-24s frames=%d tokens=%d mask=%s maxDiff=%.2e sum %.6e/%.6e %s%n", label, f.frames, f.softTokens(),
                        maskOk ? "ok" : "MISMATCH", maxDiff, sumRel, sqRel, ok ? "OK" : "FAIL");
            } else {
                float[] pcm = clip(Double.parseDouble(parts[1]), Integer.parseInt(parts[2]));
                float[] emb;
                if (parts[0].equals("audio")) {
                    emb = m.embedAudio(pcm);
                } else {
                    List<ImagePreprocessor.Source> frames = new ArrayList<ImagePreprocessor.Source>();
                    for (int k = 0; k < 3; k++) frames.add(new PipelineParityTest.Pattern(384, 384, k));
                    emb = m.embedVideo(frames, 0, pcm);
                }
                List<Object> exp = MiniJson.arr(c.get("embedding"));
                double maxDiff = 0;
                for (int i = 0; i < emb.length; i++) maxDiff = Math.max(maxDiff, Math.abs(emb[i] - ((Number) exp.get(i)).doubleValue()));
                ok = maxDiff < 1e-4 && emb.length == exp.size();
                System.out.printf("%-24s dim=%d maxDiff=%.2e %s%n", label, emb.length, maxDiff, ok ? "OK" : "FAIL");
            }
            if (!ok) bad++;
        }
        m.close();
        System.out.println(bad == 0 ? "AUDIO MATCH" : bad + " FAILED");
        if (bad > 0) System.exit(1);
    }
}
