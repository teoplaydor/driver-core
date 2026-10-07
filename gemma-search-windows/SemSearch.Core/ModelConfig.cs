using System.IO;
using System.Text.Json;

namespace SemSearch.Core
{
    /// <summary>The parts of config.json / tokenizer_config.json / processor_config.json the pipeline needs.</summary>
    public sealed class ModelConfig
    {
        public int HiddenSize;
        public int ImageTokenId = -1, VideoTokenId = -1, AudioTokenId = -1, BoiTokenId = -1, EoiTokenId = -1;
        public string ImageToken, BoiToken, EoiToken, VideoToken;
        public ImageParams Image = new ImageParams(280);
        public ImageParams Video = new ImageParams(280);
        public int MaxFrames = 32;
        public bool HasVision, HasVideo, HasVideoProcessor;

        /// <summary>Gemma 4 image processor settings (defaults match transformers.js).</summary>
        public sealed class ImageParams
        {
            public int PatchSize = 16, MaxSoftTokens, PoolingKernelSize = 3;
            public double RescaleFactor = 1.0 / 255.0;
            public bool DoRescale = true, DoResize = true;

            public ImageParams(int maxSoftTokens) { MaxSoftTokens = maxSoftTokens; }

            public int MaxPatches => MaxSoftTokens * PoolingKernelSize * PoolingKernelSize;

            public ImageParams WithBudget(int maxSoftTokens)
            {
                if (maxSoftTokens <= 0 || maxSoftTokens == MaxSoftTokens) return this;
                return new ImageParams(maxSoftTokens)
                {
                    PatchSize = PatchSize, PoolingKernelSize = PoolingKernelSize, RescaleFactor = RescaleFactor,
                    DoRescale = DoRescale, DoResize = DoResize
                };
            }

            internal void Read(JsonElement? m)
            {
                if (m == null || m.Value.ValueKind != JsonValueKind.Object) return;
                var e = m.Value;
                PatchSize = (int)HfTokenizer.Num(e, "patch_size", PatchSize);
                MaxSoftTokens = (int)HfTokenizer.Num(e, "max_soft_tokens", MaxSoftTokens);
                PoolingKernelSize = (int)HfTokenizer.Num(e, "pooling_kernel_size", PoolingKernelSize);
                if (e.TryGetProperty("rescale_factor", out var rf) && rf.ValueKind == JsonValueKind.Number) RescaleFactor = rf.GetDouble();
                DoRescale = HfTokenizer.Bool(e, "do_rescale", DoRescale);
                DoResize = HfTokenizer.Bool(e, "do_resize", DoResize);
            }
        }

        public static ModelConfig Load(string dir)
        {
            var c = new ModelConfig();
            var cfg = Read(Path.Combine(dir, "config.json")) ?? throw new FileNotFoundException("config.json not found");
            JsonElement text = cfg.TryGetProperty("text_config", out var t) ? t : default;
            c.HiddenSize = (int)HfTokenizer.Num(text, "hidden_size", HfTokenizer.Num(cfg, "hidden_size", -1));
            c.ImageTokenId = (int)HfTokenizer.Num(cfg, "image_token_id", HfTokenizer.Num(cfg, "image_token_index", -1));
            c.VideoTokenId = (int)HfTokenizer.Num(cfg, "video_token_id", HfTokenizer.Num(cfg, "video_token_index", -1));
            c.AudioTokenId = (int)HfTokenizer.Num(cfg, "audio_token_id", HfTokenizer.Num(cfg, "audio_token_index", -1));
            c.BoiTokenId = (int)HfTokenizer.Num(cfg, "boi_token_id", HfTokenizer.Num(cfg, "boi_token_index", -1));
            c.EoiTokenId = (int)HfTokenizer.Num(cfg, "eoi_token_id", HfTokenizer.Num(cfg, "eoi_token_index", -1));
            c.HasVision = cfg.TryGetProperty("vision_config", out var vc) && vc.ValueKind != JsonValueKind.Null;

            var tc = Read(Path.Combine(dir, "tokenizer_config.json"));
            c.ImageToken = TokenString(tc, "image_token");
            c.BoiToken = TokenString(tc, "boi_token");
            c.EoiToken = TokenString(tc, "eoi_token");
            c.VideoToken = TokenString(tc, "video_token");

            var p = Read(Path.Combine(dir, "processor_config.json"));
            JsonElement? ip = p != null && p.Value.TryGetProperty("image_processor", out var ipe) && ipe.ValueKind == JsonValueKind.Object ? ipe : (JsonElement?)null;
            JsonElement? vp = p != null && p.Value.TryGetProperty("video_processor", out var vpe) && vpe.ValueKind == JsonValueKind.Object ? vpe : (JsonElement?)null;
            if (ip == null) ip = Read(Path.Combine(dir, "preprocessor_config.json"));
            if (vp == null) vp = Read(Path.Combine(dir, "video_preprocessor_config.json"));
            c.Image.Read(ip);
            c.Video.Read(vp);
            c.HasVideoProcessor = vp != null;
            c.HasVideo = vp != null && c.VideoToken != null && c.VideoTokenId >= 0;
            if (vp != null) c.MaxFrames = (int)HfTokenizer.Num(vp.Value, "max_frames", c.MaxFrames);
            return c;
        }

        private static string TokenString(JsonElement? tc, string key)
        {
            if (tc == null) return null;
            var e = tc.Value;
            if (!e.TryGetProperty(key, out var v) || v.ValueKind == JsonValueKind.Null)
            {
                // Newer tokenizer configs keep multimodal tokens under "extra_special_tokens".
                if (!(e.TryGetProperty("extra_special_tokens", out var extra) && extra.ValueKind == JsonValueKind.Object
                      && extra.TryGetProperty(key, out v))) return null;
            }
            if (v.ValueKind == JsonValueKind.String) return v.GetString();
            return v.ValueKind == JsonValueKind.Object ? HfTokenizer.Str(v, "content", null) : null;
        }

        /// <summary>Parses a JSON file; numbers outside the long range (e.g. model_max_length 1e30) are fine here.</summary>
        public static JsonElement? Read(string path)
        {
            if (!File.Exists(path)) return null;
            using (var d = JsonDocument.Parse(File.ReadAllBytes(path), new JsonDocumentOptions { CommentHandling = JsonCommentHandling.Skip }))
                return d.RootElement.Clone();
        }
    }
}
