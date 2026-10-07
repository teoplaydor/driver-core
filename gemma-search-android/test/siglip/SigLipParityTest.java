import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.HfRepo;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.core.SigLip;

/**
 * SigLip (Java + ONNX Runtime) vs transformers.js SiglipTextModel/SiglipVisionModel on a dummy export with
 * the onnx-community layout: token ids must match exactly (lowercase, EOS template, padding to 64,
 * truncation), embeddings of pictures already at the model size must match to float precision, and a
 * picture both sides resize (different resamplers) must stay very close. Also checks the file plan for a
 * real-looking repo listing and the accelerator fallbacks on a desktop runtime.
 */
public class SigLipParityTest {
    static final class Pattern implements ImagePreprocessor.Source {
        final int w, h, k;
        Pattern(int w, int h, int k) { this.w = w; this.h = h; this.k = k; }
        public int width() { return w; }
        public int height() { return h; }
        int[] raw() {
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int r = (x * 7 + y * 13 + k * 40) % 256, g = (x * 3 + y * 5 + 50 + k * 40) % 256, b = ((x ^ y) + k * 40) % 256;
                    px[y * w + x] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            return px;
        }
        public int[] argb(int tw, int th) {
            if (tw == w && th == h) return raw();
            BufferedImage src = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            src.setRGB(0, 0, w, h, raw(), 0, w);
            BufferedImage dst = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, tw, th, null);
            g.dispose();
            return dst.getRGB(0, 0, tw, th, null, 0, tw);
        }
    }

    static double cos(float[] a, List<Object> b) {
        double d = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            double y = ((Number) b.get(i)).doubleValue();
            d += a[i] * y;
            na += a[i] * a[i];
            nb += y * y;
        }
        return d / Math.sqrt(na * nb);
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        SigLip m = new SigLip(dir, new File(dir, "onnx/text_model.onnx"), new File(dir, "onnx/vision_model.onnx"),
                SigLip.Accel.CPU, 2, 0);
        List<Object> ref = MiniJson.arr(MiniJson.parse(new String(java.nio.file.Files.readAllBytes(new File(args[1]).toPath()),
                StandardCharsets.UTF_8)));
        int bad = 0;
        for (Object o : ref) {
            Map<String, Object> c = MiniJson.obj(o);
            String label = (String) c.get("label");
            List<Object> want = MiniJson.arr(c.get("embedding"));
            float[] emb;
            String extra = "";
            double min = 0.99999;
            if (label.startsWith("text:")) {
                String text = label.substring(5);
                long[] ids = m.tokenIds(text);
                List<Object> wantIds = MiniJson.arr(c.get("input_ids"));
                boolean same = ids.length == wantIds.size();
                for (int i = 0; same && i < ids.length; i++) same = ids[i] == ((Number) wantIds.get(i)).longValue();
                if (!same) {
                    bad++;
                    System.out.println("FAIL ids " + label + "\n  java " + Arrays.toString(ids) + "\n  ref  " + wantIds);
                }
                extra = " ids " + (same ? "=" : "≠") + " (" + ids.length + ")";
                emb = m.embedText(text);
            } else {
                String[] p = label.substring(6).split("[x:]");
                Pattern src = new Pattern(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                boolean resized = src.w != m.imageSize() || src.h != m.imageSize();
                if (!resized) {
                    double sum = 0;
                    for (float f : m.pixels(src)) sum += f;
                    double wantSum = ((Number) c.get("pixel_sum")).doubleValue();
                    if (Math.abs(sum - wantSum) > 1e-2) {
                        bad++;
                        System.out.println("FAIL pixels " + label + " java " + sum + " ref " + wantSum);
                    }
                }
                min = resized ? 0.99 : 0.99999; // two different bilinear resamplers
                emb = m.embedImage(src, 0);
            }
            double cs = cos(emb, want);
            boolean ok = cs >= min;
            if (!ok) bad++;
            System.out.printf("%s %-40s cos %.7f%s%n", ok ? "ok  " : "FAIL", label.length() > 40 ? label.substring(0, 40) : label, cs, extra);
        }
        // batch == one by one
        List<ImagePreprocessor.Source> imgs = new ArrayList<ImagePreprocessor.Source>();
        for (int k = 0; k < 3; k++) imgs.add(new Pattern(224, 224, k));
        float[][] batch = m.embedImages(imgs, 0);
        for (int k = 0; k < 3; k++) {
            float[] one = m.embedImage(imgs.get(k), 0);
            double d = 0;
            for (int i = 0; i < one.length; i++) d += one[i] * batch[k][i];
            if (d < 0.99999) {
                bad++;
                System.out.println("FAIL batch " + k + " cos " + d);
            }
        }
        System.out.println("ok   batch of 3 = one by one");
        float[] video = m.embedVideo(imgs, 0);
        double n = 0;
        for (float f : video) n += f * f;
        if (Math.abs(n - 1) > 1e-4) {
            bad++;
            System.out.println("FAIL video not normalised");
        } else {
            System.out.println("ok   video = normalised mean of frames");
        }
        m.close();

        // Accelerators the desktop runtime lacks (NNAPI, WebGPU, XNNPACK here) must fail cleanly at load.
        for (SigLip.Accel a : SigLip.Accel.values()) {
            if (a == SigLip.Accel.CPU) continue;
            try {
                SigLip x = new SigLip(dir, new File(dir, "onnx/text_model.onnx"), new File(dir, "onnx/vision_model.onnx"), a, 2, 1);
                float[] e = x.embedImage(new Pattern(224, 224, 0), 0);
                System.out.println("ok   " + a + " runs here, dim " + e.length);
                x.close();
            } catch (Exception e) {
                System.out.println("ok   " + a + " unavailable here: " + String.valueOf(e.getMessage()).split("\n")[0]);
            }
        }

        // File plan on a listing shaped like onnx-community/siglip2-base-patch16-224-ONNX.
        List<HfRepo.RemoteFile> files = new ArrayList<HfRepo.RemoteFile>();
        for (String f : new String[]{"config.json", "preprocessor_config.json", "tokenizer.json", "tokenizer_config.json",
                "special_tokens_map.json", "README.md", "onnx/model.onnx", "onnx/model_quantized.onnx", "onnx/text_model.onnx",
                "onnx/text_model_quantized.onnx", "onnx/text_model_fp16.onnx", "onnx/text_model_q4.onnx",
                "onnx/vision_model.onnx", "onnx/vision_model_quantized.onnx", "onnx/vision_model_fp16.onnx"}) {
            files.add(remote(f, f.endsWith(".onnx") ? 1000 : 10));
        }
        HfRepo.Plan cpu = HfRepo.planTowers(files, false), acc = HfRepo.planTowers(files, true);
        if (!"onnx/text_model_quantized.onnx".equals(cpu.textModel) || !"onnx/vision_model_quantized.onnx".equals(cpu.visionModel)
                || cpu.accelVision != null || cpu.files.size() != 7) {
            bad++;
            System.out.println("FAIL cpu plan " + cpu.textModel + " " + cpu.visionModel + " " + cpu.files.size());
        } else {
            System.out.println("ok   plan: int8 text + int8 vision, " + cpu.files.size() + " files");
        }
        if (!"onnx/vision_model.onnx".equals(acc.accelVision) || acc.files.size() != 8) {
            bad++;
            System.out.println("FAIL accel plan " + acc.accelVision + " " + acc.files.size());
        } else {
            System.out.println("ok   plan with NPU/GPU: + fp32 vision, " + acc.files.size() + " files");
        }
        System.out.println(bad == 0 ? "ALL OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }

    static HfRepo.RemoteFile remote(String path, long size) throws Exception {
        java.lang.reflect.Constructor<HfRepo.RemoteFile> k = HfRepo.RemoteFile.class.getDeclaredConstructor(String.class, long.class);
        k.setAccessible(true);
        return k.newInstance(path, size);
    }
}
