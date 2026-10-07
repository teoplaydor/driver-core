using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Windows.Forms;
using SemSearch.Core;

namespace SemSearch
{
    internal static class Program
    {
        /// <summary>The synthetic image used for the transformers.js reference embeddings (test/parity/reference.mjs).</summary>
        private sealed class RefPattern : IImageSource
        {
            private readonly int w, h, k;
            public RefPattern(int w, int h, int k) { this.w = w; this.h = h; this.k = k; }
            public int Width => w;
            public int Height => h;
            public int[] Argb(int tw, int th)
            {
                if (tw != w || th != h) throw new InvalidOperationException("unexpected resize");
                var px = new int[w * h];
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                    {
                        int r = (x * 7 + y * 13 + k * 40) % 256, g = (x * 3 + y * 5 + 50 + k * 40) % 256, b = ((x ^ y) + k * 40) % 256;
                        px[y * w + x] = unchecked((int)0xff000000) | (r << 16) | (g << 8) | b;
                    }
                return px;
            }
        }

        private const string MutexName = "SemSearch.SingleInstance.v1";
        private const string ShowEventName = "SemSearch.Show.v1";

        [STAThread]
        private static int Main(string[] args)
        {
            // SEMSEARCH_WEBGPU_BACKEND=Vulkan|D3D12: Dawn backend override (tests under Wine use Vulkan).
            string backend = Environment.GetEnvironmentVariable("SEMSEARCH_WEBGPU_BACKEND");
            if (!string.IsNullOrEmpty(backend)) EmbeddingGemma2.WebGpuOptions["dawnBackendType"] = backend;
            if (args.Length >= 2 && args[0] == "--shot") return Shots(args[1]);
            if (args.Length >= 3 && args[0] == "--selftest") return SelfTest(args[1], args[2]);
            if (args.Length >= 7 && args[0] == "--enginetest")
                return EngineTest(args[1], args[2], args[3], int.Parse(args[4]), int.Parse(args[5]), int.Parse(args[6]));

            using var mutex = new Mutex(true, MutexName, out bool first);
            if (!first)
            {
                // Already running (usually hidden in the tray): bring that window up instead.
                try
                {
                    using var ev = EventWaitHandle.OpenExisting(ShowEventName);
                    ev.Set();
                }
                catch (Exception)
                {
                    // the other instance is still starting
                }
                return 0;
            }

            Application.ThreadException += (s, e) => Crash(e.Exception);
            AppDomain.CurrentDomain.UnhandledException += (s, e) => Crash(e.ExceptionObject as Exception);
            Init();
            using var engine = new Engine();
            using var form = new MainForm(engine);
            using var show = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
            var listener = new Thread(() =>
            {
                while (show.WaitOne())
                {
                    try { form.BeginInvoke(new Action(form.ShowSearch)); }
                    catch (Exception) { return; }
                }
            }) { IsBackground = true };
            listener.Start();
            Application.Run(form);
            return 0;
        }

        private static void Init()
        {
            Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        }

        private static int showingError;

        private static void Crash(Exception e)
        {
            if (e == null) return;
            try
            {
                Directory.CreateDirectory(Settings.AppDir);
                File.AppendAllText(Path.Combine(Settings.AppDir, "errors.log"), DateTime.Now + "\r\n" + e + "\r\n\r\n");
            }
            catch (Exception)
            {
                // nowhere to log
            }
            // One dialog at a time: an error raised by the dialog's own message loop must not stack more of them.
            if (Interlocked.Exchange(ref showingError, 1) == 1) return;
            try
            {
                MessageBox.Show(e.Message + "\n\nПодробности: " + Path.Combine(Settings.AppDir, "errors.log"),
                    "Смысловой поиск — ошибка", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            finally
            {
                Interlocked.Exchange(ref showingError, 0);
            }
        }

        // ------------------------------------------------------------------ verification modes

        /// <summary>--shot dir: renders the three pages (search with demo results, index, model) to PNGs.</summary>
        private static int Shots(string dir)
        {
            Init();
            Directory.CreateDirectory(dir);
            Settings.UseScratchProfile("SemSearch-shot");
            using var engine = new Engine();
            using var form = new MainForm(engine, demo: true);
            form.Show();
            form.ShowDemoResults();
            string[] names = { "search.png", "index.png", "model.png" };
            for (int i = 0; i < names.Length; i++) form.Shot(i, Path.Combine(dir, names[i]));
            form.Close();
            return 0;
        }

        /// <summary>
        /// --enginetest modelDir photosDir out.txt images docs broken: the app's engine end to end on a scratch profile in %TEMP% —
        /// installs a model as if downloaded, indexes a folder, searches (ru query, similar, by picture), re-indexes
        /// incrementally, switches to int8, runs the accelerator benchmark and reopens the saved index.
        /// </summary>
        private static int EngineTest(string src, string photos, string outFile, int images, int docs, int broken)
        {
            var log = new StringBuilder();
            int failures = 0;
            void Check(bool ok, string what)
            {
                log.AppendLine((ok ? "ok    " : "FAIL  ") + what);
                if (!ok) failures++;
                File.WriteAllText(outFile, log.ToString());
            }
            void Wait(Func<bool> done, int seconds)
            {
                var sw = System.Diagnostics.Stopwatch.StartNew();
                while (!done() && sw.Elapsed.TotalSeconds < seconds) Thread.Sleep(50);
            }
            try
            {
                Settings.UseScratchProfile("SemSearch-enginetest");
                if (Directory.Exists(Settings.AppDir)) Directory.Delete(Settings.AppDir, true);
                string modelDir = Path.Combine(Settings.AppDir, "model");
                var files = new List<HfRepo.RemoteFile>();
                foreach (var f in Directory.EnumerateFiles(src, "*", SearchOption.AllDirectories))
                {
                    string rel = Path.GetRelativePath(src, f).Replace('\\', '/');
                    if (rel.Contains(".int8") || rel.EndsWith("tokenizer.bin")) continue;
                    string dst = Path.Combine(modelDir, rel);
                    Directory.CreateDirectory(Path.GetDirectoryName(dst));
                    File.Copy(f, dst);
                    files.Add(new HfRepo.RemoteFile { Path = rel, Size = new FileInfo(f).Length });
                }
                var plan = HfRepo.MakePlan(files, true);
                HfRepo.SaveManifest(plan, "local/dummy", Path.Combine(modelDir, "manifest.json"));
                new Settings { Folders = new List<string> { photos } }.Save();

                var engine = new Engine();
                Wait(() => engine.Current != Engine.State.Loading, 120);
                Check(engine.Ready && engine.SupportsImages, "model loaded: " + engine.Status + " " + engine.ErrorDetails);

                engine.StartIndex();
                Wait(() => !engine.Indexing, 600);
                int ni = engine.Index.Count(VectorIndex.KindImage), nd = engine.Index.Count(VectorIndex.KindDocument);
                Check(ni == images && nd == docs && engine.IdxErrors == broken,
                    $"indexed photos {ni}/{images}, docs {nd}/{docs}, errors {engine.IdxErrors}/{broken}: {engine.IdxStatus.Replace('\n', ' ')}");
                Check(engine.IdxSkippedSmall == 3 && !engine.Index.Paths().Any(p => p.Contains("icon-") || p.Contains("logo-")),
                    $"size filter (300 px, 20 KB): {engine.IdxSkippedSmall} small pictures skipped (two icons, one tiny jpeg)");

                string rotated = Directory.EnumerateFiles(photos, "rotated.jpg", SearchOption.AllDirectories).FirstOrDefault();
                if (rotated != null)
                    using (var im = ImageFile.Open(rotated))
                        Check(im.Width == 600 && im.Height == 800, $"EXIF orientation 6: stored 800x600 → shown {im.Width}x{im.Height}");

                var r = engine.SearchAsync("кот на диване", true, true).GetAwaiter().GetResult();
                Check(r.Hits.Count == images + docs && r.Label.Contains("→"), $"search ru: {r.Hits.Count} hits, {r.Millis} ms, {r.Label}");
                var r2 = engine.SearchAsync("молоко", false, true).GetAwaiter().GetResult();
                Check(r2.Hits.Count == docs && r2.Hits.TrueForAll(h => h.Item.Kind == VectorIndex.KindDocument), $"docs only: {r2.Hits.Count} hits");

                string anyPhoto = engine.Index.Paths().First(p => ImageFile.Extensions.Contains(Path.GetExtension(p)));
                var sim = engine.SimilarAsync(anyPhoto, true, true).GetAwaiter().GetResult();
                Check(sim.Hits.Count == images + docs - 1 && sim.Hits.TrueForAll(h => h.Item.Path != anyPhoto), $"similar: {sim.Hits.Count} hits");

                // a copy outside the indexed folders must find its original first
                string copy = Path.Combine(Path.GetTempPath(), "semsearch-copy" + Path.GetExtension(anyPhoto));
                File.Copy(anyPhoto, copy, true);
                var byImg = engine.SearchByImageAsync(copy, true, false).GetAwaiter().GetResult();
                Check(byImg.Hits.Count > 0 && byImg.Hits[0].Item.Path == anyPhoto && byImg.Hits[0].Score > 0.999f,
                    $"by picture: top {Path.GetFileName(byImg.Hits[0].Item.Path)} score {byImg.Hits[0].Score:0.0000}");

                engine.StartIndex();
                Wait(() => !engine.Indexing, 600);
                Check(engine.IdxTotal == broken, $"re-index: {engine.IdxTotal} files to do (only the broken ones retried)");

                // Tighter filter: the 320x320 picture is hidden from search at once and kept through re-indexing;
                // the old threshold brings it back without computing anything.
                engine.S.MinImageSide = 400;
                int hidden = engine.CountHiddenAsync().GetAwaiter().GetResult();
                int shownTight = engine.SearchAsync("кот", true, false).GetAwaiter().GetResult().Hits.Count;
                engine.StartIndex();
                Wait(() => !engine.Indexing, 600);
                int keptTight = engine.Index.Count(VectorIndex.KindImage), todoTight = engine.IdxTotal;
                engine.S.MinImageSide = 300;
                int shownAgain = engine.SearchAsync("кот", true, false).GetAwaiter().GetResult().Hits.Count;
                Check(hidden == 1 && shownTight == images - 1 && keptTight == images && todoTight == broken && shownAgain == images,
                    $"filter change: 400 px hides {hidden} (search shows {shownTight}), re-index keeps {keptTight}, 300 px shows {shownAgain} again");

                File.Delete(anyPhoto);
                engine.StartIndex();
                Wait(() => !engine.Indexing, 600);
                Check(engine.Index.Count(VectorIndex.KindImage) == images - 1 && engine.Index.Get(anyPhoto) == null, "deleted photo dropped from index");

                engine.S.Accel = Engine.AccelCpuInt8;
                engine.LoadModelAsync().GetAwaiter().GetResult();
                var r3 = engine.Ready ? engine.SearchAsync("кот", true, true).GetAwaiter().GetResult() : null;
                Check(engine.Ready && engine.LoadedAccel == Engine.AccelCpuInt8 && r3 != null && r3.Hits.Count > 0,
                    "int8: " + engine.Status + " " + engine.ErrorDetails);

                string report = engine.BenchmarkAsync().GetAwaiter().GetResult();
                Wait(() => engine.Current != Engine.State.Loading, 120);
                Check(report.Contains("Выбрано") && engine.Ready, "benchmark:\n" + report + "\n→ " + engine.Status);

                int before = engine.Index.Count(VectorIndex.KindImage) + engine.Index.Count(VectorIndex.KindDocument);
                engine.Dispose();
                var again = new Engine();
                Wait(() => again.Current != Engine.State.Loading, 120);
                int after = again.Index.Count(VectorIndex.KindImage) + again.Index.Count(VectorIndex.KindDocument);
                Check(again.Ready && after == before && after > 0, $"restart: index {after}/{before}, {again.Status}");
                again.Dispose();
            }
            catch (Exception e)
            {
                Check(false, "exception: " + e);
            }
            log.AppendLine(failures == 0 ? "ALL PASSED" : failures + " FAILED");
            File.WriteAllText(outFile, log.ToString());
            return failures == 0 ? 0 : 1;
        }

        /// <summary>
        /// --selftest modelDir reference.json: runs the shipped pipeline (ONNX Runtime from this exe) against
        /// transformers.js reference embeddings, plus the GDI+ image decoder; writes a report next to the reference.
        /// </summary>
        private static int SelfTest(string dir, string referenceJson)
        {
            var log = new StringBuilder();
            int failures = 0;
            void Check(bool ok, string what)
            {
                log.AppendLine((ok ? "ok    " : "FAIL  ") + what);
                if (!ok) failures++;
            }
            try
            {
                var cfg = EmbeddingGemma2.LoadConfig(dir);
                var tok = EmbeddingGemma2.LoadTokenizer(dir);
                using var m = new EmbeddingGemma2(cfg, tok, Path.Combine(dir, "onnx", "model.onnx"),
                    Path.Combine(dir, "onnx", "vision_encoder.onnx"), 2, EmbeddingGemma2.Device.Cpu);
                using var refs = JsonDocument.Parse(File.ReadAllText(referenceJson));
                foreach (var c in refs.RootElement.EnumerateArray())
                {
                    string label = c.GetProperty("label").GetString();
                    float[] emb;
                    if (label.StartsWith("text:")) emb = m.EmbedText(label.Substring(5), 8192);
                    else if (label.StartsWith("image:"))
                    {
                        var wh = label.Substring(6).Split('x');
                        emb = m.EmbedImage(new RefPattern(int.Parse(wh[0]), int.Parse(wh[1]), 0), 0);
                    }
                    else
                    {
                        emb = m.EmbedVideo(new IImageSource[]
                            { new RefPattern(384, 384, 0), new RefPattern(384, 384, 1), new RefPattern(384, 384, 2) }, 0);
                    }
                    var exp = c.GetProperty("embedding").EnumerateArray().Select(x => x.GetDouble()).ToArray();
                    double maxDiff = emb.Select((v, i) => Math.Abs(v - exp[i])).Max();
                    Check(maxDiff < 1e-4 && emb.Length == exp.Length,
                        $"pipeline {label.Substring(0, Math.Min(40, label.Length))}: maxDiff {maxDiff:E1}");
                }

                // GDI+ decode path: a PNG written by us must embed like the same pixels from memory.
                string png = Path.Combine(Path.GetTempPath(), "semsearch-selftest.png");
                var src = new PatternSource(640, 480, 3);
                int[] px = src.Argb(640, 480);
                using (var bmp = new System.Drawing.Bitmap(640, 480))
                {
                    for (int y = 0; y < 480; y++)
                        for (int x = 0; x < 640; x++) bmp.SetPixel(x, y, System.Drawing.Color.FromArgb(px[y * 640 + x]));
                    bmp.Save(png, System.Drawing.Imaging.ImageFormat.Png);
                }
                float[] fromFile;
                int same = 0;
                using (var f = ImageFile.Open(png))
                {
                    int[] back = f.Argb(640, 480);
                    for (int i = 0; i < px.Length; i++)
                    {
                        int d = 0;
                        for (int sh = 0; sh < 24; sh += 8) d = Math.Max(d, Math.Abs(((back[i] >> sh) & 255) - ((px[i] >> sh) & 255)));
                        if (d <= 2) same++;
                    }
                    fromFile = m.EmbedImage(f, 70);
                }
                Check(same > px.Length * 0.98, $"GDI+ decode: {100.0 * same / px.Length:0.0}% pixels exact at 1:1");
                // the file path resizes with GDI+ bicubic, the in-memory source renders at the target size directly
                float[] fromMem = m.EmbedImage(src, 70);
                float cos = VectorMath.Dot(fromFile, fromMem, fromMem.Length);
                Check(cos > 0.99f, $"GDI+ decode vs memory: cos {cos:0.0000}");

                float[] q = m.EmbedQuery("кот на диване");
                Check(q.Length == m.EmbeddingDim && Math.Abs(VectorMath.Dot(q, q, q.Length) - 1f) < 1e-3f, "query embedding normalized");
                Check((QueryBridge.LoadEmbedded().Translate("кот на диване").English ?? "").Contains("cat"), "ru→en bridge");
            }
            catch (Exception e)
            {
                Check(false, "exception: " + e);
            }
            log.AppendLine(failures == 0 ? "ALL PASSED" : failures + " FAILED");
            File.WriteAllText(Path.Combine(Path.GetDirectoryName(Path.GetFullPath(referenceJson)), "selftest.txt"), log.ToString());
            return failures == 0 ? 0 : 1;
        }
    }
}
