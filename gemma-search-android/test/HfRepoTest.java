import io.github.teoplaydor.semsearch.core.HfRepo;

import java.io.File;
import java.util.*;

/**
 * Checks file selection and resumable download against tools/mock_hub.py.
 * usage: HfRepoTest <mock host url> <download dir>
 */
public class HfRepoTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        System.out.println("ok: " + msg);
    }

    public static void main(String[] a) throws Exception {
        String host = a[0];
        File dir = new File(a[1]);
        HfRepo r = new HfRepo(host, "onnx-community/embeddinggemma-2-ONNX", null);
        List<HfRepo.RemoteFile> files = r.listFiles();
        HfRepo.Plan p = HfRepo.plan(files, true);
        List<String> names = new ArrayList<String>();
        for (HfRepo.RemoteFile f : p.files) names.add(f.path);
        System.out.println(names);
        check("onnx/model_q4.onnx".equals(p.textModel), "q4 text model chosen");
        check("onnx/vision_encoder_q4.onnx".equals(p.visionModel), "q4 vision encoder chosen");
        check(names.contains("onnx/model_q4.onnx_data") && names.contains("onnx/model_q4.onnx_data_1"), "external data chunks included");
        check(!names.contains("onnx/model_fp16.onnx") && !names.contains("onnx/audio_encoder_q4.onnx"), "other dtypes / audio skipped");
        check(names.contains("tokenizer.json") && names.contains("processor_config.json"), "configs included");
        HfRepo.Plan withAudio = HfRepo.withAudio(HfRepo.plan(files, true), files);
        check("onnx/audio_encoder_q4.onnx".equals(withAudio.audioModel) && withAudio.totalBytes == p.totalBytes + 70_000
                && HfRepo.audioBytes(files) == 70_000, "audio encoder added on request (q4), its size");
        File am = new File(dir.getPath() + "-audio-manifest.json");
        HfRepo.saveManifest(withAudio, "onnx-community/embeddinggemma-2-ONNX", am);
        check("onnx/audio_encoder_q4.onnx".equals(HfRepo.loadManifest(am).audioModel), "the manifest keeps the audio encoder");
        HfRepo.Plan textOnly = HfRepo.plan(files, false);
        check(textOnly.visionModel == null && textOnly.totalBytes < p.totalBytes, "text-only plan is smaller");

        // First attempt is cut off by the server mid-file, the second resumes with Range.
        try {
            r.download(p, dir, null);
            check(false, "expected the first download to fail");
        } catch (Exception e) {
            System.out.println("first attempt failed as expected: " + e.getMessage());
        }
        final long[] calls = {0};
        r.download(p, dir, new HfRepo.Progress() {
            public boolean onProgress(String f, long fd, long ft, long all, long allT) { calls[0]++; return true; }
        });
        check(HfRepo.isComplete(p, dir), "all files complete after resume");

        File m = new File(dir, "manifest.json");
        HfRepo.saveManifest(p, "onnx-community/embeddinggemma-2-ONNX", m);
        HfRepo.Plan back = HfRepo.loadManifest(m);
        check(back.files.size() == p.files.size() && back.textModel.equals(p.textModel), "manifest round-trip");

        try {
            new HfRepo(host, "gated/repo", null).listFiles();
            check(false, "gated repo should fail");
        } catch (Exception e) {
            check(e.getMessage().contains("токен"), "gated repo asks for a token: " + e.getMessage());
        }
        boolean cancelled = false;
        try {
            File d2 = new File(dir.getPath() + "-cancel");
            r.download(p, d2, new HfRepo.Progress() {
                public boolean onProgress(String f, long fd, long ft, long all, long allT) { return false; }
            });
        } catch (HfRepo.InterruptedIOException e) {
            cancelled = true;
        }
        check(cancelled, "download can be cancelled");
        System.out.println("ALL OK");
    }
}
