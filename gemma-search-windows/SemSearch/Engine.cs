using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using SemSearch.Core;

namespace SemSearch
{
    /// <summary>
    /// Model download/loading, folder indexing and search. All model calls go through one lock so
    /// searches interleave with indexing batches. <see cref="Changed"/> fires on background threads.
    /// </summary>
    public sealed class Engine : IDisposable
    {
        public enum State { NoModel, Downloading, Loading, Ready, Error }

        public const int AccelCpu = 0, AccelCpuInt8 = 1, AccelGpu = 2;
        public static readonly string[] AccelNames = { "Процессор", "Процессор, int8", "Видеокарта (DirectML)" };
        public static readonly int[] PhotoBudgets = { 70, 140, 280 };
        public static readonly string[] BridgeModes =
            { "Русский + английский перевод (рекомендуется)", "Только английский перевод", "Без перевода" };

        private static readonly HashSet<string> SkipDirs = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            { "node_modules", "bin", "obj", "__pycache__", "venv", ".venv", "AppData", "$RECYCLE.BIN", "System Volume Information" };

        public event Action Changed;

        public readonly Settings S = Settings.Load();
        public readonly string ModelDir = Path.Combine(Settings.AppDir, "model");
        private string ManifestPath => Path.Combine(ModelDir, "manifest.json");
        public readonly VectorIndex Index = new VectorIndex(Path.Combine(Settings.AppDir, "index.bin"));
        private readonly QueryBridge bridge = QueryBridge.LoadEmbedded();
        private readonly SemaphoreSlim modelLock = new SemaphoreSlim(1, 1);
        private EmbeddingGemma2 model;

        public volatile State Current = State.NoModel;
        public volatile string Status = "";
        public string ErrorDetails;
        public long DlDone, DlTotal;
        private CancellationTokenSource dlCts;

        public volatile bool Indexing;
        public int IdxDone, IdxTotal, IdxErrors;
        public volatile string IdxStatus = "";
        private CancellationTokenSource idxCts;
        private string idxFirstError;
        private long sumWaitMs, sumVisionMs, sumTextMs;
        private int timedPhotos;

        public int LoadedAccel, LoadedThreads;

        public Engine()
        {
            if (S.GpuProbe)
            {
                // The previous run died while creating the GPU session (driver crash): stay on the CPU.
                S.GpuBroken = true;
                S.GpuProbe = false;
                if (S.Accel == AccelGpu) S.Accel = AccelCpuInt8;
                S.Save();
            }
            if (File.Exists(ManifestPath)) _ = LoadModelAsync();
        }

        private void Notify() => Changed?.Invoke();

        public bool Ready => Current == State.Ready && model != null;
        public bool SupportsImages => Ready && model.SupportsImages;
        /// <summary>Dimensions search actually compares: the Matryoshka setting, capped by the model's output.</summary>
        public int SearchDims => model != null && model.EmbeddingDim > 0 ? Math.Min(S.Dims, model.EmbeddingDim) : S.Dims;

        // ------------------------------------------------------------------ model

        public async Task<HfRepo.Plan> CheckRepoAsync(string repo, string token, bool vision)
        {
            var r = new HfRepo(repo, token);
            return HfRepo.MakePlan(await r.ListFilesAsync(CancellationToken.None), vision);
        }

        public async Task DownloadAsync(string repo, string token, bool vision)
        {
            if (Current == State.Downloading || Current == State.Loading) return;
            S.Repo = repo;
            S.Token = token;
            S.Vision = vision;
            S.Save();
            dlCts = new CancellationTokenSource();
            Current = State.Downloading;
            Status = "Получаю список файлов…";
            DlDone = DlTotal = 0;
            Notify();
            try
            {
                var r = new HfRepo(repo, token);
                var plan = HfRepo.MakePlan(await r.ListFilesAsync(dlCts.Token), vision);
                DlTotal = plan.TotalBytes;
                Directory.CreateDirectory(ModelDir);
                await r.DownloadAsync(plan, ModelDir, (file, fd, ft, all, allTotal) =>
                {
                    DlDone = all;
                    DlTotal = allTotal;
                    Status = "Скачиваю " + file;
                    Notify();
                }, dlCts.Token);
                HfRepo.SaveManifest(plan, repo, ManifestPath);
                DlDone = DlTotal;
                await LoadModelAsync();
            }
            catch (Exception e)
            {
                Current = File.Exists(ManifestPath) ? State.Error : State.NoModel;
                Status = dlCts.IsCancellationRequested ? "Загрузка остановлена — её можно продолжить" : "Ошибка: " + e.Message;
                Notify();
            }
        }

        public void CancelDownload() => dlCts?.Cancel();

        public bool HasModelFiles => File.Exists(ManifestPath);

        /// <summary>Graph with int8 compute for 4-bit MatMuls (see OnnxPatcher); weights are shared.</summary>
        private string GraphFile(string rel, bool int8)
        {
            string orig = Path.Combine(ModelDir, rel.Replace('/', Path.DirectorySeparatorChar));
            if (!int8) return orig;
            string patched = orig.Substring(0, orig.Length - ".onnx".Length) + ".int8.onnx";
            if (!File.Exists(patched) || File.GetLastWriteTimeUtc(patched) < File.GetLastWriteTimeUtc(orig))
            {
                if (OnnxPatcher.SetMatMulNBitsAccuracy(orig, patched, 4) == 0)
                {
                    File.Delete(patched);
                    return orig;
                }
            }
            return patched;
        }

        private EmbeddingGemma2 CreateModel(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int accel, int threads)
        {
            bool gpu = accel == AccelGpu && !S.GpuBroken;
            string text = GraphFile(plan.TextModel, accel != AccelCpu);
            string vision = plan.VisionModel == null ? null : GraphFile(plan.VisionModel, accel == AccelCpuInt8);
            if (gpu)
            {
                S.GpuProbe = true;
                S.Save();
            }
            try
            {
                return new EmbeddingGemma2(cfg, tok, text, vision, threads,
                    gpu ? EmbeddingGemma2.Device.DirectML : EmbeddingGemma2.Device.Cpu);
            }
            finally
            {
                if (gpu)
                {
                    S.GpuProbe = false;
                    S.Save();
                }
            }
        }

        public Task LoadModelAsync()
        {
            Current = State.Loading;
            Status = "Загружаю модель в память…";
            Notify();
            return Task.Run(async () =>
            {
                string step = "чтение списка файлов";
                ErrorDetails = null;
                await modelLock.WaitAsync();
                try
                {
                    model?.Dispose();
                    model = null;
                    var plan = HfRepo.LoadManifest(ManifestPath, out string repo);
                    if (plan == null || !HfRepo.IsComplete(plan, ModelDir))
                    {
                        Current = State.NoModel;
                        Status = "Модель не скачана полностью";
                        return;
                    }
                    var sw = Stopwatch.StartNew();
                    step = "инициализация";
                    var cfg = EmbeddingGemma2.LoadConfig(ModelDir);
                    var tok = EmbeddingGemma2.LoadTokenizer(ModelDir);
                    int accel = S.Accel;
                    try
                    {
                        model = CreateModel(cfg, tok, plan, accel, S.Threads);
                    }
                    catch (Exception) when (accel != AccelCpu)
                    {
                        accel = AccelCpu;
                        S.Accel = AccelCpu;
                        S.Save();
                        model = CreateModel(cfg, tok, plan, AccelCpu, S.Threads);
                    }
                    LoadedAccel = accel;
                    LoadedThreads = S.Threads;
                    step = "пробный запуск";
                    model.EmbedQuery("привет");
                    string sig = repo + "|" + plan.TextModel + "|" + plan.VisionModel;
                    if (sig != S.ModelSignature)
                    {
                        Index.Clear(); // other weights: old vectors are not comparable
                        S.ModelSignature = sig;
                        S.Save();
                    }
                    Status = $"Модель готова ({sw.Elapsed.TotalSeconds:F1} с), {model.EmbeddingDim} изм., {AccelNames[accel]}"
                             + (S.Threads > 0 ? ", потоков " + S.Threads : "") + (model.SupportsImages ? "" : " · только текст");
                    Current = State.Ready;
                }
                catch (Exception e)
                {
                    model?.Dispose();
                    model = null;
                    Current = State.Error;
                    Status = "Не удалось загрузить модель (" + step + "): " + e.Message;
                    ErrorDetails = "SemSearch " + typeof(Engine).Assembly.GetName().Version + ", " + Environment.OSVersion + "\n"
                                   + Status + "\n\n" + e;
                }
                finally
                {
                    modelLock.Release();
                    Notify();
                }
            });
        }

        public async Task DeleteModelAsync()
        {
            StopIndex();
            CancelDownload();
            await modelLock.WaitAsync();
            try
            {
                model?.Dispose();
                model = null;
                if (Directory.Exists(ModelDir)) Directory.Delete(ModelDir, true);
                Current = State.NoModel;
                Status = "Модель удалена";
            }
            finally
            {
                modelLock.Release();
                Notify();
            }
        }

        // ------------------------------------------------------------------ search

        public sealed class SearchResult
        {
            public List<VectorIndex.Hit> Hits;
            public long Millis;
            public string Label;
        }

        private async Task<T> WithModel<T>(Func<EmbeddingGemma2, T> f)
        {
            await modelLock.WaitAsync();
            try
            {
                if (model == null || Current != State.Ready) throw new InvalidOperationException("модель ещё не загружена");
                return f(model);
            }
            finally
            {
                modelLock.Release();
            }
        }

        public Task<SearchResult> SearchAsync(string query, bool images, bool docs) => Task.Run(async () =>
        {
            var sw = Stopwatch.StartNew();
            string english = null;
            var (qDocs, qImages) = await WithModel(m =>
            {
                float[] q = m.EmbedQuery(query);
                float[] qi = q;
                if (images && S.BridgeMode != 2 && QueryBridge.HasCyrillic(query))
                {
                    var br = bridge.Translate(query);
                    if (br != null)
                    {
                        english = br.English;
                        float[] en = m.EmbedQuery(br.English);
                        if (S.BridgeMode == 1)
                        {
                            qi = en;
                        }
                        else
                        {
                            qi = new float[q.Length];
                            for (int i = 0; i < q.Length; i++) qi[i] = q[i] + en[i];
                            VectorMath.Normalize(qi);
                        }
                    }
                }
                return (q, qi);
            });
            return new SearchResult
            {
                Hits = Index.Search(qImages, qDocs, S.Dims, images, docs, 150),
                Millis = sw.ElapsedMilliseconds,
                Label = "«" + query + "»" + (english != null ? " → для фото «" + english + "»" : "")
            };
        });

        public Task<SearchResult> SimilarAsync(string path, bool images, bool docs) => Task.Run(() =>
        {
            var it = Index.Get(path) ?? throw new InvalidOperationException("файла нет в индексе");
            var sw = Stopwatch.StartNew();
            return new SearchResult
            {
                Hits = Index.Search(it.Emb, it.Emb, S.Dims, images, docs, 150, path),
                Millis = sw.ElapsedMilliseconds,
                Label = "похожие на " + Path.GetFileName(path)
            };
        });

        public Task<SearchResult> SearchByImageAsync(string file, bool images, bool docs) => Task.Run(async () =>
        {
            var sw = Stopwatch.StartNew();
            float[] q;
            using (var img = ImageFile.Open(file))
                q = await WithModel(m =>
                {
                    if (!m.SupportsImages) throw new InvalidOperationException("визуальный энкодер не загружен");
                    return m.EmbedImage(img, S.PhotoBudget);
                });
            return new SearchResult
            {
                Hits = Index.Search(q, q, S.Dims, images, docs, 150, file),
                Millis = sw.ElapsedMilliseconds,
                Label = "похожие на " + Path.GetFileName(file)
            };
        });

        // ------------------------------------------------------------------ indexing

        private sealed class Job
        {
            public string Path;
            public byte Kind;
            public long Size, Mtime;
        }

        public void StartIndex()
        {
            if (Indexing || !Ready) return;
            Indexing = true;
            idxCts = new CancellationTokenSource();
            IdxDone = IdxTotal = IdxErrors = 0;
            idxFirstError = null;
            sumWaitMs = sumVisionMs = sumTextMs = 0;
            timedPhotos = 0;
            IdxStatus = "Ищу файлы…";
            Notify();
            var ct = idxCts.Token;
            Task.Run(async () =>
            {
                try
                {
                    await IndexLoop(ct);
                }
                catch (OperationCanceledException)
                {
                }
                catch (Exception e)
                {
                    idxFirstError ??= e.Message;
                }
                finally
                {
                    Indexing = false;
                    string err = TimingSplit() + (idxFirstError != null ? "\nПервая ошибка: " + idxFirstError : "");
                    IdxStatus = (ct.IsCancellationRequested ? $"Остановлено: {IdxDone} из {IdxTotal}"
                                    : IdxTotal == 0 ? "Новых файлов нет — всё уже в индексе"
                                    : $"Готово: {IdxDone - IdxErrors} файлов" + (IdxErrors > 0 ? $", пропущено {IdxErrors}" : "")) + err;
                    Notify();
                }
            });
        }

        public void StopIndex() => idxCts?.Cancel();

        private List<Job> Scan(CancellationToken ct, out HashSet<string> seen)
        {
            seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var jobs = new List<Job>();
            var stack = new Stack<string>();
            foreach (var root in S.Folders) if (Directory.Exists(root)) stack.Push(root);
            int scanned = 0;
            while (stack.Count > 0)
            {
                ct.ThrowIfCancellationRequested();
                string dir = stack.Pop();
                DirectoryInfo di;
                try { di = new DirectoryInfo(dir); }
                catch (Exception) { continue; }
                IEnumerable<FileSystemInfo> entries;
                try { entries = di.EnumerateFileSystemInfos().ToList(); }
                catch (Exception) { continue; }
                foreach (var e in entries)
                {
                    var a = e.Attributes;
                    // Hidden/system files, junctions, and OneDrive placeholders (would trigger a download).
                    if ((a & (FileAttributes.Hidden | FileAttributes.System | FileAttributes.ReparsePoint | FileAttributes.Offline)) != 0
                        || ((int)a & 0x400000) != 0) continue;
                    if (e is DirectoryInfo)
                    {
                        if (!SkipDirs.Contains(e.Name) && !e.Name.StartsWith(".")) stack.Push(e.FullName);
                        continue;
                    }
                    var f = (FileInfo)e;
                    string ext = f.Extension;
                    byte kind;
                    if (ImageFile.Extensions.Contains(ext) && f.Length >= 10 * 1024 && f.Length < 200L << 20) kind = VectorIndex.KindImage;
                    else if (S.IndexDocuments && TextExtract.IsSupported(f.FullName) && f.Length > 0 && f.Length < 20L << 20) kind = VectorIndex.KindDocument;
                    else continue;
                    if (kind == VectorIndex.KindImage && !SupportsImages) continue;
                    seen.Add(f.FullName);
                    long mtime = f.LastWriteTimeUtc.Ticks;
                    if (!Index.IsCurrent(f.FullName, f.Length, mtime))
                        jobs.Add(new Job { Path = f.FullName, Kind = kind, Size = f.Length, Mtime = mtime });
                    if (++scanned % 500 == 0)
                    {
                        IdxStatus = $"Ищу файлы… {scanned}";
                        Notify();
                    }
                }
            }
            // Newest first: recent photos become searchable right away.
            jobs.Sort((x, y) => y.Mtime.CompareTo(x.Mtime));
            return jobs;
        }

        private async Task IndexLoop(CancellationToken ct)
        {
            var jobs = Scan(ct, out var seen);
            // Forget files that were deleted or moved out of the indexed folders.
            foreach (var p in Index.Paths())
            {
                bool underRoot = S.Folders.Any(r => p.StartsWith(r.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase));
                if (!underRoot || !seen.Contains(p)) Index.Remove(p);
            }
            IdxTotal = jobs.Count;
            if (jobs.Count == 0) return;
            var started = Stopwatch.StartNew();
            int batch = Math.Max(1, Math.Min(8, S.Batch));
            var decodes = new Dictionary<string, Task<ImageFile>>(StringComparer.OrdinalIgnoreCase);
            int i = 0;
            while (i < jobs.Count)
            {
                ct.ThrowIfCancellationRequested();
                var job = jobs[i];
                if (job.Kind == VectorIndex.KindDocument)
                {
                    i++;
                    await IndexDocument(job);
                }
                else
                {
                    var group = new List<Job>();
                    while (i < jobs.Count && group.Count < batch && jobs[i].Kind == VectorIndex.KindImage) group.Add(jobs[i++]);
                    // Decode this group (if not prefetched) and start the next one in the background.
                    foreach (var g in group) if (!decodes.ContainsKey(g.Path)) decodes[g.Path] = Task.Run(() => ImageFile.Open(g.Path));
                    for (int k = i, n = 0; k < jobs.Count && n < batch && jobs[k].Kind == VectorIndex.KindImage; k++, n++)
                    {
                        var nx = jobs[k];
                        if (!decodes.ContainsKey(nx.Path)) decodes[nx.Path] = Task.Run(() => ImageFile.Open(nx.Path));
                    }
                    await IndexPhotos(group, decodes);
                }
                double per = started.Elapsed.TotalSeconds / Math.Max(1, IdxDone);
                IdxStatus = $"{IdxDone} из {IdxTotal} · {per:F2} с на файл · осталось ~{Eta(per * (IdxTotal - IdxDone))}" + TimingSplit();
                Notify();
            }
        }

        private async Task IndexPhotos(List<Job> group, Dictionary<string, Task<ImageFile>> decodes)
        {
            var wait = Stopwatch.StartNew();
            var ok = new List<Job>();
            var imgs = new List<ImageFile>();
            foreach (var j in group)
            {
                var t = decodes[j.Path];
                decodes.Remove(j.Path);
                try
                {
                    imgs.Add(await t);
                    ok.Add(j);
                }
                catch (Exception e)
                {
                    Fail(j, e);
                }
            }
            long waited = wait.ElapsedMilliseconds;
            if (ok.Count == 0) return;
            try
            {
                float[][] embs;
                long vis = 0, txt = 0;
                try
                {
                    (embs, vis, txt) = await WithModel(m => (m.EmbedImages(imgs.Cast<IImageSource>().ToList(), S.PhotoBudget), m.LastVisionMs, m.LastTextMs));
                }
                catch (Exception) when (imgs.Count > 1)
                {
                    // One bad photo (or a batch the encoder rejects) must not sink the others.
                    embs = new float[imgs.Count][];
                    for (int k = 0; k < imgs.Count; k++)
                    {
                        try
                        {
                            embs[k] = await WithModel(m => m.EmbedImage(imgs[k], S.PhotoBudget));
                        }
                        catch (Exception e)
                        {
                            Fail(ok[k], e);
                        }
                    }
                }
                sumWaitMs += waited;
                sumVisionMs += vis;
                sumTextMs += txt;
                timedPhotos += ok.Count;
                for (int k = 0; k < ok.Count; k++)
                {
                    if (embs[k] == null) continue;
                    Index.Put(ok[k].Path, VectorIndex.KindImage, ok[k].Size, ok[k].Mtime, embs[k]);
                    Interlocked.Increment(ref IdxDone);
                }
            }
            catch (Exception e)
            {
                foreach (var j in ok) Fail(j, e);
            }
            finally
            {
                foreach (var im in imgs) im.Dispose();
            }
        }

        private async Task IndexDocument(Job j)
        {
            try
            {
                string text = await Task.Run(() => TextExtract.Extract(j.Path));
                if (text == null)
                {
                    Interlocked.Increment(ref IdxDone);
                    return; // binary or empty: nothing to index
                }
                float[] emb = await WithModel(m => m.EmbedDocument(Path.GetFileNameWithoutExtension(j.Path), text));
                Index.Put(j.Path, VectorIndex.KindDocument, j.Size, j.Mtime, emb);
                Interlocked.Increment(ref IdxDone);
            }
            catch (Exception e)
            {
                Fail(j, e);
            }
        }

        private void Fail(Job j, Exception e)
        {
            Interlocked.Increment(ref IdxErrors);
            Interlocked.Increment(ref IdxDone);
            idxFirstError ??= Path.GetFileName(j.Path) + ": " + e.Message;
        }

        private string TimingSplit()
        {
            if (timedPhotos == 0) return "";
            return string.Format(CultureInfo.InvariantCulture, "\nна фото: чтение {0:F2} с · картинка {1:F2} с · текст {2:F2} с · {3}",
                sumWaitMs / 1000.0 / timedPhotos, sumVisionMs / 1000.0 / timedPhotos, sumTextMs / 1000.0 / timedPhotos,
                AccelNames[LoadedAccel]);
        }

        private static string Eta(double sec)
        {
            if (sec < 60) return (int)sec + " с";
            if (sec < 3600) return (int)(sec / 60) + " мин";
            return (int)(sec / 3600) + " ч " + (int)(sec % 3600 / 60) + " мин";
        }

        public void ClearIndex()
        {
            StopIndex();
            Index.Clear();
            Notify();
        }

        // ------------------------------------------------------------------ benchmark

        private sealed class Measure
        {
            public double PerPhotoMs = double.MaxValue, VisionMs, TextMs;
            public float Cos = 1f;
            public float[] Emb;
            public string Error;
        }

        private Measure Run(ModelConfig cfg, HfTokenizer tok, HfRepo.Plan plan, int accel, int threads, int batch, float[] reference)
        {
            var r = new Measure();
            EmbeddingGemma2 m = null;
            try
            {
                m = CreateModel(cfg, tok, plan, accel, threads);
                var imgs = Enumerable.Range(0, batch).Select(k => (IImageSource)new PatternSource(1280, 960, 2 + k)).ToList();
                m.EmbedImages(imgs, S.PhotoBudget); // warm-up (allocations, GPU shader compilation)
                for (int run = 0; run < 2; run++)
                {
                    var sw = Stopwatch.StartNew();
                    var e = m.EmbedImages(imgs, S.PhotoBudget);
                    double per = sw.Elapsed.TotalMilliseconds / batch;
                    if (per < r.PerPhotoMs)
                    {
                        r.PerPhotoMs = per;
                        r.VisionMs = m.LastVisionMs / (double)batch;
                        r.TextMs = m.LastTextMs / (double)batch;
                        r.Emb = e[0];
                    }
                }
                if (reference != null) r.Cos = VectorMath.Dot(r.Emb, reference, reference.Length);
            }
            catch (Exception e)
            {
                r.Error = e.Message;
            }
            finally
            {
                m?.Dispose();
            }
            return r;
        }

        /// <summary>Tries CPU / CPU int8 / GPU, then thread counts and batch sizes for the best one; keeps the fastest.</summary>
        public Task<string> BenchmarkAsync() => Task.Run(async () =>
        {
            if (Indexing) throw new InvalidOperationException("дождитесь конца индексации");
            var rep = new StringBuilder();
            await modelLock.WaitAsync();
            Current = State.Loading;
            Status = "Подбираю ускорение…";
            Notify();
            try
            {
                model?.Dispose();
                model = null;
                var plan = HfRepo.LoadManifest(ManifestPath, out _) ?? throw new InvalidOperationException("модель не скачана");
                if (plan.VisionModel == null) throw new InvalidOperationException("нет визуального энкодера");
                var cfg = EmbeddingGemma2.LoadConfig(ModelDir);
                var tok = EmbeddingGemma2.LoadTokenizer(ModelDir);
                int cores = Environment.ProcessorCount;
                rep.Append($"Детализация {S.PhotoBudget} токенов, логических ядер {cores}\n(картинка + текст на одно фото)\n\n");
                float[] reference = null;
                int[] best = null;
                double bestMs = double.MaxValue;
                for (int phase = 0; phase < 3; phase++)
                {
                    var cands = new List<int[]>(); // {accel, threads, batch}
                    if (phase == 0)
                    {
                        for (int a = 0; a < AccelNames.Length; a++) if (a != AccelGpu || !S.GpuBroken) cands.Add(new[] { a, 0, 1 });
                    }
                    else if (phase == 1 && best != null && best[0] != AccelGpu)
                    {
                        foreach (int t in new SortedSet<int> { Math.Max(2, cores / 2), cores }) cands.Add(new[] { best[0], t, 1 });
                    }
                    else if (phase == 2 && best != null)
                    {
                        foreach (int b in new[] { 2, 4 }) cands.Add(new[] { best[0], best[1], b });
                    }
                    foreach (var c in cands)
                    {
                        string name = AccelNames[c[0]] + (c[1] > 0 ? ", потоков " + c[1] : ", потоков авто") + (c[2] > 1 ? ", пачка " + c[2] : "");
                        Status = "Подбираю ускорение: " + name;
                        Notify();
                        var m = Run(cfg, tok, plan, c[0], c[1], c[2], reference);
                        if (m.Error != null)
                        {
                            rep.Append("• ").Append(name).Append(": не работает — ").Append(m.Error).Append('\n');
                            continue;
                        }
                        bool first = reference == null;
                        if (first) reference = m.Emb;
                        bool ok = m.Cos >= 0.98f;
                        rep.Append(string.Format(CultureInfo.InvariantCulture, "• {0}: {1:F3} с ({2:F3} + {3:F3}){4}{5}\n", name,
                            m.PerPhotoMs / 1000, m.VisionMs / 1000, m.TextMs / 1000,
                            first ? "" : string.Format(CultureInfo.InvariantCulture, ", совпадение {0:F3}", m.Cos),
                            ok ? "" : " — отклонено, результат расходится"));
                        if (ok && m.PerPhotoMs < bestMs)
                        {
                            bestMs = m.PerPhotoMs;
                            best = c;
                        }
                    }
                }
                if (best == null) throw new InvalidOperationException("ни один вариант не сработал");
                S.Accel = best[0];
                S.Threads = best[1];
                S.Batch = best[2];
                S.AccelChosen = true;
                S.Save();
                rep.Append(string.Format(CultureInfo.InvariantCulture, "\nВыбрано: {0}{1}{2} — {3:F3} с на фото", AccelNames[best[0]],
                    best[1] > 0 ? ", потоков " + best[1] : "", best[2] > 1 ? ", пачка " + best[2] : "", bestMs / 1000));
            }
            catch (Exception e)
            {
                rep.Append("\nОшибка: ").Append(e.Message);
            }
            finally
            {
                modelLock.Release();
            }
            await LoadModelAsync();
            return rep.ToString();
        });

        public void Dispose()
        {
            StopIndex();
            model?.Dispose();
        }
    }
}
