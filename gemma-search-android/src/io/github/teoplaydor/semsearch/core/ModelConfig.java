package io.github.teoplaydor.semsearch.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * The parts of config.json, tokenizer_config.json and processor_config.json that the
 * EmbeddingGemma 2 pipeline needs (mirrors transformers.js EmbeddingGemma2Processor / Model).
 */
public final class ModelConfig {
    public int hiddenSize;
    public int imageTokenId = -1, videoTokenId = -1, audioTokenId = -1, boiTokenId = -1, eoiTokenId = -1;
    public String imageToken, boiToken, eoiToken, videoToken;
    public ImageParams image = new ImageParams(280);
    public ImageParams video = new ImageParams(280);
    public int maxFrames = 32;
    public boolean hasVision, hasVideo, hasVideoProcessor;

    /** Gemma 4 image processor settings (defaults match transformers.js). */
    public static final class ImageParams {
        public int patchSize = 16;
        public int maxSoftTokens;
        public int poolingKernelSize = 3;
        public double rescaleFactor = 1.0 / 255.0;
        public boolean doRescale = true;
        public boolean doResize = true;

        ImageParams(int maxSoftTokens) {
            this.maxSoftTokens = maxSoftTokens;
        }

        public int maxPatches() {
            return maxSoftTokens * poolingKernelSize * poolingKernelSize;
        }

        void read(Map<String, Object> m) {
            if (m == null) return;
            patchSize = (int) MiniJson.num(m, "patch_size", patchSize);
            maxSoftTokens = (int) MiniJson.num(m, "max_soft_tokens", maxSoftTokens);
            poolingKernelSize = (int) MiniJson.num(m, "pooling_kernel_size", poolingKernelSize);
            Object rf = m.get("rescale_factor");
            if (rf instanceof Number) rescaleFactor = ((Number) rf).doubleValue();
            doRescale = MiniJson.bool(m, "do_rescale", doRescale);
            doResize = MiniJson.bool(m, "do_resize", doResize);
        }
    }

    public static ModelConfig load(File dir) throws IOException {
        ModelConfig c = new ModelConfig();
        Map<String, Object> cfg = MiniJson.obj(readJson(new File(dir, "config.json")));
        if (cfg == null) throw new IOException("config.json not found");
        Map<String, Object> text = MiniJson.obj(cfg.get("text_config"));
        c.hiddenSize = (int) MiniJson.num(text, "hidden_size", MiniJson.num(cfg, "hidden_size", -1));
        c.imageTokenId = (int) MiniJson.num(cfg, "image_token_id", MiniJson.num(cfg, "image_token_index", -1));
        c.videoTokenId = (int) MiniJson.num(cfg, "video_token_id", MiniJson.num(cfg, "video_token_index", -1));
        c.audioTokenId = (int) MiniJson.num(cfg, "audio_token_id", MiniJson.num(cfg, "audio_token_index", -1));
        c.boiTokenId = (int) MiniJson.num(cfg, "boi_token_id", MiniJson.num(cfg, "boi_token_index", -1));
        c.eoiTokenId = (int) MiniJson.num(cfg, "eoi_token_id", MiniJson.num(cfg, "eoi_token_index", -1));
        c.hasVision = cfg.get("vision_config") != null;

        Map<String, Object> tc = MiniJson.obj(readJson(new File(dir, "tokenizer_config.json")));
        c.imageToken = tokenString(tc, "image_token");
        c.boiToken = tokenString(tc, "boi_token");
        c.eoiToken = tokenString(tc, "eoi_token");
        c.videoToken = tokenString(tc, "video_token");

        Map<String, Object> p = MiniJson.obj(readJson(new File(dir, "processor_config.json")));
        Map<String, Object> ip = p == null ? null : MiniJson.obj(p.get("image_processor"));
        Map<String, Object> vp = p == null ? null : MiniJson.obj(p.get("video_processor"));
        // Fall back to standalone preprocessor files if processor_config.json lacks the sections.
        if (ip == null) ip = MiniJson.obj(readJson(new File(dir, "preprocessor_config.json")));
        if (vp == null) vp = MiniJson.obj(readJson(new File(dir, "video_preprocessor_config.json")));
        c.image.read(ip);
        c.video.read(vp);
        c.hasVideoProcessor = vp != null;
        c.hasVideo = vp != null && c.videoToken != null && c.videoTokenId >= 0;
        c.maxFrames = (int) MiniJson.num(vp, "max_frames", c.maxFrames);
        return c;
    }

    /** Token strings may be plain strings or AddedToken objects {"content": ...}. */
    private static String tokenString(Map<String, Object> tc, String key) {
        if (tc == null) return null;
        Object v = tc.get(key);
        // Newer tokenizer configs keep multimodal tokens under "extra_special_tokens".
        Map<String, Object> extra = MiniJson.obj(tc.get("extra_special_tokens"));
        if (v == null && extra != null) v = extra.get(key);
        if (v instanceof String) return (String) v;
        Map<String, Object> m = MiniJson.obj(v);
        return m == null ? null : MiniJson.str(m, "content", null);
    }

    public static Object readJson(File f) throws IOException {
        if (!f.exists()) return null;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            return new MiniJson(r).readValue();
        } finally {
            r.close();
        }
    }
}
