import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.GigaAm;
import io.github.teoplaydor.semsearch.core.MiniJson;

/**
 * GigaAM v3 e2e RNN-T on the phone (core/GigaAm) against onnx-asr 0.12, the library its ONNX export is made for, on a
 * stand-in model with the same files, inputs and outputs (test/gigaam/make_gigaam_dummy.py → reference_gigaam.py):
 * the model's window and mel filters, the features of each sound, the pieces greedy decoding emits — with the fp32
 * graphs and with the int8 ones — and how the pieces become text.
 *
 * usage: GigaAmTest DUMMY_DIR (with reference.json)
 */
public class GigaAmTest {
    static int bad = 0;

    static void ok(boolean c, String what) {
        System.out.println((c ? "ok   " : "FAIL ") + what);
        if (!c) bad++;
    }

    static float[] f32(File f) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[b.remaining() / 4];
        b.asFloatBuffer().get(out);
        return out;
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        Map<String, Object> ref = MiniJson.obj(new MiniJson(new InputStreamReader(new FileInputStream(new File(root, "reference.json")), "UTF-8")).readValue());
        Map<String, Object> sounds = MiniJson.obj(ref.get("sounds"));

        // the model's own window and filters, as onnx-asr keeps them
        float[] w = f32(new File(root, "window.f32"));
        float[] fb = f32(new File(root, "filters.f32"));
        float[] myW = GigaAm.window();
        float[][] myF = GigaAm.filters();
        double dw = 0, df = 0;
        for (int i = 0; i < w.length; i++) dw = Math.max(dw, Math.abs(w[i] - myW[i]));
        for (int k = 0; k < 161; k++) for (int m = 0; m < 64; m++) df = Math.max(df, Math.abs(fb[k * 64 + m] - myF[k][m]));
        ok(w.length == 320 && dw == 0, "the window: periodic Hann of 320 in bfloat16 — the same to the bit (" + dw + ")");
        ok(fb.length == 161 * 64 && df == 0, "the mel filters: HTK, 161 bins × 64 bands in bfloat16 — the same to the bit (" + df + ")");

        // features
        for (String name : new String[]{"short", "quiet", "long", "tiny"}) {
            Map<String, Object> s = MiniJson.obj(sounds.get(name));
            float[] x = f32(new File(root, "sounds/" + name + ".f32"));
            int frames = (int) MiniJson.num(s, "frames", -1);
            ok(GigaAm.frames(x.length) == frames, name + ": frames " + GigaAm.frames(x.length) + " (onnx-asr " + frames + ")");
            if (frames == 0) continue;
            float[] want = f32(new File(root, "sounds/" + name + ".feats.f32"));
            float[] got = GigaAm.features(x);
            double d = 0;
            for (int i = 0; i < want.length; i++) d = Math.max(d, Math.abs(want[i] - got[i]));
            ok(got.length == want.length && d < 2e-3, name + ": log-mel features the same as onnx-asr's (largest difference " + String.format("%.2g", d) + ")");
        }

        // decoding, fp32 and int8 graphs
        for (String q : new String[]{"fp32", "int8"}) {
            GigaAm g = new GigaAm(new File(root, q), 2);
            try {
                for (String name : new String[]{"short", "quiet", "long", "tiny"}) {
                    Map<String, Object> s = MiniJson.obj(MiniJson.obj(sounds.get(name)).get(q));
                    List<Object> ids = MiniJson.arr(s.get("ids"));
                    int[] want = new int[ids.size()];
                    for (int i = 0; i < want.length; i++) want[i] = ((Number) ids.get(i)).intValue();
                    float[] x = f32(new File(root, "sounds/" + name + ".f32"));
                    long t0 = System.nanoTime();
                    int[] got = g.tokens(x);
                    long ms = (System.nanoTime() - t0) / 1000000;
                    int same = 0;
                    while (same < Math.min(got.length, want.length) && got[same] == want[same]) same++;
                    ok(Arrays.equals(got, want), q + " " + name + ": the same " + want.length + " pieces as onnx-asr's greedy RNN-T"
                            + (Arrays.equals(got, want) ? "" : " (first " + same + " of " + got.length + " the same)") + ", " + ms + " ms");
                    if (name.equals("short")) {
                        System.out.println("     onnx-asr: «" + MiniJson.str(s, "text", "") + "»");
                        System.out.println("     phone:    «" + g.text(got) + "»");
                    }
                }
            } finally {
                g.close();
            }
        }

        // pieces to text
        String[][] joins = {
                {"Привет, мир.", " Привет", ",", " мир", "."},
                {"Купить хлеб — завтра в 10:00!", " Купить", " хлеб", " —", " завтра", " в", " 10", ":", "00", "!"},
                {"Позвонить маме (срочно)", " Позвонить", " маме", " (", "срочно", ")"},
                {"про «дом»…", " про", " «", "дом", "»", "…"},
                {"ну-ка", " ну", "-", "ка"},
                {"не забыть", "<unk>", " не", "<blk>", " забыть"},
        };
        for (String[] j : joins) {
            String got = GigaAm.join(new ArrayList<String>(Arrays.asList(j).subList(1, j.length)));
            ok(got.equals(j[0]), "pieces to text: «" + got + "»");
        }

        // which files: int8 where there are some, the weights beside a graph, nothing half
        GigaAm.Files f = GigaAm.pick(Arrays.asList("config.json", "v3_e2e_rnnt_vocab.txt", "v3_e2e_rnnt_encoder.onnx", "v3_e2e_rnnt_encoder.int8.onnx",
                "v3_e2e_rnnt_decoder.onnx", "v3_e2e_rnnt_decoder.int8.onnx", "v3_e2e_rnnt_joint.onnx", "v3_e2e_rnnt_joint.int8.onnx",
                "v3_ctc.onnx", "v3_e2e_ctc.int8.onnx", "README.md"));
        ok(f.complete() && f.int8() && "v3_e2e_rnnt_encoder.int8.onnx".equals(f.encoder) && "v3_e2e_rnnt_joint.int8.onnx".equals(f.joint)
                && f.all().size() == 5, "files: the int8 graphs taken when the repo has them — " + f.all());
        f = GigaAm.pick(Arrays.asList("config.json", "v3_e2e_rnnt_vocab.txt", "v3_e2e_rnnt_encoder.onnx", "v3_e2e_rnnt_encoder.onnx.data",
                "v3_e2e_rnnt_decoder.onnx", "v3_e2e_rnnt_joint.onnx"));
        ok(f.complete() && !f.int8() && f.extra.contains("v3_e2e_rnnt_encoder.onnx.data"), "files: fp32 when there is no int8, with its weights file — " + f.all());
        f = GigaAm.pick(Arrays.asList("v3_e2e_rnnt_encoder_int8.onnx", "v3_e2e_rnnt_decoder.onnx", "v3_e2e_rnnt_vocab.txt"));
        ok(!f.complete() && "v3_e2e_rnnt_encoder_int8.onnx".equals(f.encoder), "files: «_int8» named too; no joint — not complete");

        System.out.println(bad == 0 ? "GIGAAM OK" : bad + " FAILED");
        if (bad > 0) System.exit(1);
    }
}
