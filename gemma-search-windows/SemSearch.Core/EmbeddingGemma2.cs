using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;

namespace SemSearch.Core
{
    /// <summary>
    /// EmbeddingGemma 2 (onnx-community/embeddinggemma-2-ONNX) on ONNX Runtime. Mirrors transformers.js
    /// EmbeddingGemma2Model: the vision encoder turns photos into soft tokens, the text model consumes
    /// input_ids, attention_mask and per-modality features and returns the L2-normalised sentence_embedding.
    /// </summary>
    public sealed class EmbeddingGemma2 : IDisposable
    {
        public const string QueryPrefix = "task: search result | query: ";

        public enum Device { Cpu, DirectML }

        private readonly InferenceSession textSession, visionSession;
        private readonly HfTokenizer tokenizer;
        private readonly ModelConfig cfg;

        public long LastVisionMs { get; private set; }
        public long LastTextMs { get; private set; }
        public int EmbeddingDim { get; private set; } = -1;

        public EmbeddingGemma2(ModelConfig config, HfTokenizer tok, string textModel, string visionModel, int threads,
                               Device visionDevice)
        {
            cfg = config;
            tokenizer = tok;
            ResolveSpecialTokens();
            try
            {
                textSession = new InferenceSession(textModel, Options(threads, Device.Cpu));
            }
            catch (Exception e)
            {
                throw new IOException("текстовая модель " + Path.GetFileName(textModel) + ": " + e.Message, e);
            }
            try
            {
                visionSession = visionModel != null && File.Exists(visionModel)
                    ? new InferenceSession(visionModel, Options(threads, visionDevice)) : null;
            }
            catch (Exception e)
            {
                textSession.Dispose();
                throw new IOException("визуальный энкодер " + Path.GetFileName(visionModel)
                                      + (visionDevice == Device.DirectML ? " (видеокарта)" : "") + ": " + e.Message, e);
            }
        }

        public static ModelConfig LoadConfig(string dir)
        {
            try { return ModelConfig.Load(dir); }
            catch (Exception e) { throw new IOException("конфиги модели: " + e.Message, e); }
        }

        public static HfTokenizer LoadTokenizer(string dir)
        {
            try { return HfTokenizer.Load(Path.Combine(dir, "tokenizer.json"), Path.Combine(dir, "tokenizer.bin")); }
            catch (Exception e) { throw new IOException("токенизатор: " + e.Message, e); }
        }

        private static SessionOptions Options(int threads, Device device)
        {
            var o = new SessionOptions { GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL };
            if (threads > 0) o.IntraOpNumThreads = threads;
            if (device == Device.DirectML)
            {
                // DirectML requires these two settings.
                o.EnableMemoryPattern = false;
                o.ExecutionMode = ExecutionMode.ORT_SEQUENTIAL;
                o.AppendExecutionProvider_DML(0);
            }
            return o;
        }

        private void ResolveSpecialTokens()
        {
            if (cfg.ImageToken == null && cfg.ImageTokenId >= 0) cfg.ImageToken = tokenizer.Token(cfg.ImageTokenId);
            if (cfg.VideoToken == null && cfg.VideoTokenId >= 0) cfg.VideoToken = tokenizer.Token(cfg.VideoTokenId);
            if (cfg.BoiToken == null && cfg.BoiTokenId >= 0) cfg.BoiToken = tokenizer.Token(cfg.BoiTokenId);
            if (cfg.EoiToken == null && cfg.EoiTokenId >= 0) cfg.EoiToken = tokenizer.Token(cfg.EoiTokenId);
            if (cfg.ImageTokenId < 0 && cfg.ImageToken != null && tokenizer.TokenId(cfg.ImageToken) is int iid) cfg.ImageTokenId = iid;
            if (cfg.VideoTokenId < 0 && cfg.VideoToken != null && tokenizer.TokenId(cfg.VideoToken) is int vid) cfg.VideoTokenId = vid;
            cfg.HasVideo = cfg.HasVideo || (cfg.VideoToken != null && cfg.VideoTokenId >= 0 && cfg.HasVideoProcessor);
        }

        public ModelConfig Config => cfg;
        public HfTokenizer Tokenizer => tokenizer;
        public bool SupportsImages => visionSession != null && cfg.ImageToken != null && cfg.ImageTokenId >= 0;
        public bool SupportsVideo => SupportsImages && cfg.HasVideo;
        public int DefaultImageTokens => cfg.Image.MaxSoftTokens;

        // ------------------------------------------------------------------ text

        public float[] EmbedQuery(string query) => EmbedText(QueryPrefix + query, 512);

        /// <summary>Documents use the model card's prompt with the file name as the title.</summary>
        public float[] EmbedDocument(string title, string text) =>
            EmbedText("title: " + (string.IsNullOrWhiteSpace(title) ? "none" : title) + " | text: " + text, 2048);

        public float[] EmbedText(string text, int maxTokens)
        {
            int[] ids = tokenizer.Encode(text, true, maxTokens);
            return RunTextModel(ids, new float[0], 0, new float[0], 0);
        }

        // ------------------------------------------------------------------ images & video

        public float[] EmbedImage(IImageSource image, int maxSoftTokens) =>
            EmbedImages(new[] { image }, maxSoftTokens)[0];

        /// <summary>One vision-encoder run for several photos, then the text model per photo.</summary>
        public float[][] EmbedImages(IList<IImageSource> images, int maxSoftTokens)
        {
            if (!SupportsImages) throw new InvalidOperationException("vision encoder is not loaded");
            var p = cfg.Image.WithBudget(maxSoftTokens);
            var patches = images.Select(img => ImagePreprocessor.Process(img, p)).ToList();
            var sw = Stopwatch.StartNew();
            float[] feats = EncodeVision(patches);
            LastVisionMs = sw.ElapsedMilliseconds;
            var output = new float[images.Count][];
            long textMs = 0;
            int off = 0;
            for (int k = 0; k < patches.Count; k++)
            {
                int n = patches[k].NumSoftTokens;
                var f = new float[n * cfg.HiddenSize];
                Array.Copy(feats, off, f, 0, f.Length);
                off += f.Length;
                var sb = new StringBuilder(cfg.BoiToken ?? "");
                for (int i = 0; i < n; i++) sb.Append(cfg.ImageToken);
                if (cfg.EoiToken != null) sb.Append(cfg.EoiToken);
                int[] ids = tokenizer.Encode(sb.ToString());
                sw.Restart();
                output[k] = RunTextModel(ids, f, n, new float[0], 0);
                textMs += sw.ElapsedMilliseconds;
            }
            LastTextMs = textMs;
            return output;
        }

        /// <summary>A video is a sequence of frames, each an image-like block of video soft tokens.</summary>
        public float[] EmbedVideo(IList<IImageSource> frames, int maxSoftTokens)
        {
            if (!SupportsVideo) throw new InvalidOperationException("video is not supported by this model");
            var p = cfg.Video.WithBudget(maxSoftTokens);
            var all = new List<float[]>();
            var sb = new StringBuilder();
            int total = 0;
            foreach (var f in frames)
            {
                var patches = ImagePreprocessor.Process(f, p);
                all.Add(EncodeVision(new List<ImagePreprocessor.Patches> { patches }));
                if (cfg.BoiToken != null) sb.Append(cfg.BoiToken);
                for (int i = 0; i < patches.NumSoftTokens; i++) sb.Append(cfg.VideoToken);
                if (cfg.EoiToken != null) sb.Append(cfg.EoiToken);
                total += patches.NumSoftTokens;
            }
            var feats = new float[total * cfg.HiddenSize];
            int off = 0;
            foreach (var a in all)
            {
                Array.Copy(a, 0, feats, off, a.Length);
                off += a.Length;
            }
            return RunTextModel(tokenizer.Encode(sb.ToString()), new float[0], 0, feats, total);
        }

        private float[] EncodeVision(List<ImagePreprocessor.Patches> ps)
        {
            int b = ps.Count, maxPatches = ps[0].MaxPatches, patchDim = ps[0].PatchDim, expected = 0;
            var pixels = new float[b * maxPatches * patchDim];
            var positions = new long[b * maxPatches * 2];
            for (int k = 0; k < b; k++)
            {
                if (ps[k].MaxPatches != maxPatches) throw new ArgumentException("mixed token budgets in a batch");
                Array.Copy(ps[k].PixelValues, 0, pixels, k * maxPatches * patchDim, ps[k].PixelValues.Length);
                Array.Copy(ps[k].PositionIds, 0, positions, k * maxPatches * 2, ps[k].PositionIds.Length);
                expected += ps[k].NumSoftTokens;
            }
            var inputs = new List<NamedOnnxValue>();
            foreach (var name in visionSession.InputMetadata.Keys)
            {
                if (name == "pixel_values")
                    inputs.Add(FloatInput(visionSession, name, pixels, new[] { b, maxPatches, patchDim }));
                else if (name == "pixel_position_ids" || name == "image_position_ids")
                    inputs.Add(NamedOnnxValue.CreateFromTensor(name, new DenseTensor<long>(positions, new[] { b, maxPatches, 2 })));
                else
                    throw new InvalidOperationException("unexpected vision encoder input: " + name);
            }
            using (var r = visionSession.Run(inputs))
            {
                var v = r.FirstOrDefault(x => x.Name == "image_features") ?? r.First();
                float[] data = ToFloats(v);
                if (data.Length / cfg.HiddenSize != expected)
                    throw new InvalidOperationException("vision encoder returned " + data.Length / cfg.HiddenSize
                                                        + " rows for " + expected + " soft tokens");
                return data;
            }
        }

        // ------------------------------------------------------------------ text model

        private float[] RunTextModel(int[] ids, float[] imageFeats, int nImage, float[] videoFeats, int nVideo)
        {
            int imageCount = 0, videoCount = 0;
            foreach (int id in ids)
            {
                if (id == cfg.ImageTokenId) imageCount++;
                if (id == cfg.VideoTokenId) videoCount++;
            }
            if (imageCount != nImage) throw new InvalidOperationException("image tokens " + imageCount + " != features " + nImage);
            if (videoCount != nVideo) throw new InvalidOperationException("video tokens " + videoCount + " != features " + nVideo);

            var idsL = new long[ids.Length];
            var mask = new long[ids.Length];
            for (int i = 0; i < ids.Length; i++)
            {
                idsL[i] = ids[i];
                mask[i] = 1;
            }
            int h = cfg.HiddenSize;
            var inputs = new List<NamedOnnxValue>();
            foreach (var name in textSession.InputMetadata.Keys)
            {
                switch (name)
                {
                    case "input_ids":
                        inputs.Add(NamedOnnxValue.CreateFromTensor(name, new DenseTensor<long>(idsL, new[] { 1, ids.Length })));
                        break;
                    case "attention_mask":
                        inputs.Add(NamedOnnxValue.CreateFromTensor(name, new DenseTensor<long>(mask, new[] { 1, ids.Length })));
                        break;
                    case "image_features":
                        inputs.Add(FloatInput(textSession, name, imageFeats, new[] { nImage, h }));
                        break;
                    case "video_features":
                        inputs.Add(FloatInput(textSession, name, videoFeats, new[] { nVideo, h }));
                        break;
                    case "audio_features":
                        inputs.Add(FloatInput(textSession, name, new float[0], new[] { 0, h }));
                        break;
                    default:
                        throw new InvalidOperationException("unexpected text model input: " + name);
                }
            }
            using (var r = textSession.Run(inputs))
            {
                var se = r.FirstOrDefault(x => x.Name == "sentence_embedding");
                float[] emb;
                if (se != null)
                {
                    emb = ToFloats(se);
                }
                else
                {
                    float[] hs = ToFloats(r.First());
                    int d = hs.Length / ids.Length;
                    emb = new float[d];
                    for (int t = 0; t < ids.Length; t++)
                        for (int j = 0; j < d; j++) emb[j] += hs[t * d + j];
                }
                VectorMath.Normalize(emb);
                EmbeddingDim = emb.Length;
                return emb;
            }
        }

        private static NamedOnnxValue FloatInput(InferenceSession s, string name, float[] data, int[] dims)
        {
            if (s.InputMetadata[name].ElementType == typeof(Float16))
            {
                var h = new Float16[data.Length];
                for (int i = 0; i < data.Length; i++) h[i] = (Float16)data[i];
                return NamedOnnxValue.CreateFromTensor(name, new DenseTensor<Float16>(h, dims));
            }
            return NamedOnnxValue.CreateFromTensor(name, new DenseTensor<float>(data, dims));
        }

        private static float[] ToFloats(NamedOnnxValue v)
        {
            if (v.Value is Tensor<float> tf) return tf.ToArray();
            if (v.Value is Tensor<Float16> th) return th.Select(x => (float)x).ToArray();
            throw new InvalidOperationException("unsupported output type for " + v.Name);
        }

        public void Dispose()
        {
            textSession?.Dispose();
            visionSession?.Dispose();
        }
    }
}
