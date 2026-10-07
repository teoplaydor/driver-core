using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace SemSearch.Core
{
    /// <summary>Lists and downloads (resumably) the files of the EmbeddingGemma 2 ONNX repo on Hugging Face.</summary>
    public sealed class HfRepo
    {
        public const string DefaultRepo = "onnx-community/embeddinggemma-2-ONNX";
        public static readonly string[] DtypeSuffixes = { "_q4", "_quantized", "_int8", "_uint8", "", "_q4f16", "_fp16" };

        public sealed class RemoteFile
        {
            public string Path;
            public long Size;
        }

        public sealed class Plan
        {
            public List<RemoteFile> Files = new List<RemoteFile>();
            public string TextModel, VisionModel;
            public long TotalBytes;
        }

        /// <summary>(file, fileDone, fileTotal, allDone, allTotal)</summary>
        public delegate void Progress(string file, long fileDone, long fileTotal, long allDone, long allTotal);

        private static readonly HttpClient Http = CreateClient();
        private readonly string host, repo, token;

        public HfRepo(string repo, string token, string host = "https://huggingface.co")
        {
            this.host = host.TrimEnd('/');
            this.repo = repo.Trim();
            this.token = string.IsNullOrWhiteSpace(token) ? null : token.Trim();
        }

        private static HttpClient CreateClient()
        {
            var c = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true }) { Timeout = Timeout.InfiniteTimeSpan };
            c.DefaultRequestHeaders.UserAgent.ParseAdd("SemSearch-Windows/0.1");
            return c;
        }

        private HttpRequestMessage Request(string url, long rangeFrom)
        {
            var m = new HttpRequestMessage(HttpMethod.Get, url);
            if (token != null) m.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            if (rangeFrom > 0) m.Headers.Range = new RangeHeaderValue(rangeFrom, null);
            return m;
        }

        public async Task<List<RemoteFile>> ListFilesAsync(CancellationToken ct)
        {
            using (var resp = await Http.SendAsync(Request(host + "/api/models/" + repo + "?blobs=true", 0), ct))
            {
                if (resp.StatusCode == HttpStatusCode.Unauthorized || resp.StatusCode == HttpStatusCode.Forbidden)
                    throw new IOException("Доступ к " + repo + " закрыт (HTTP " + (int)resp.StatusCode + "). Укажите токен Hugging Face.");
                if (resp.StatusCode == HttpStatusCode.NotFound) throw new IOException("Репозиторий " + repo + " не найден (HTTP 404)");
                if (!resp.IsSuccessStatusCode) throw new IOException("Hugging Face API: HTTP " + (int)resp.StatusCode);
                using (var doc = JsonDocument.Parse(await resp.Content.ReadAsStreamAsync(ct)))
                {
                    var list = new List<RemoteFile>();
                    if (!doc.RootElement.TryGetProperty("siblings", out var sib)) throw new IOException("Hugging Face API: нет списка файлов");
                    foreach (var s in sib.EnumerateArray())
                    {
                        string name = HfTokenizer.Str(s, "rfilename", null);
                        if (name == null) continue;
                        long size = HfTokenizer.Num(s, "size", -1);
                        if (s.TryGetProperty("lfs", out var lfs) && HfTokenizer.Num(lfs, "size", -1) > 0) size = HfTokenizer.Num(lfs, "size", -1);
                        list.Add(new RemoteFile { Path = name, Size = size });
                    }
                    return list;
                }
            }
        }

        public static Plan MakePlan(List<RemoteFile> files, bool withVision)
        {
            var p = new Plan();
            foreach (var f in files) if (f.Path.IndexOf('/') < 0 && f.Path.EndsWith(".json")) p.Files.Add(f);
            p.TextModel = Pick(files, "model", p) ?? throw new IOException("В репозитории нет onnx/model*.onnx");
            if (withVision) p.VisionModel = Pick(files, "vision_encoder", p);
            if (!p.Files.Exists(f => f.Path == "tokenizer.json")) throw new IOException("В репозитории нет tokenizer.json");
            foreach (var f in p.Files) p.TotalBytes += Math.Max(0, f.Size);
            return p;
        }

        private static string Pick(List<RemoteFile> files, string component, Plan p)
        {
            foreach (var suffix in DtypeSuffixes)
            {
                string main = "onnx/" + component + suffix + ".onnx";
                var mf = files.Find(f => f.Path == main);
                if (mf == null) continue;
                p.Files.Add(mf);
                foreach (var f in files) if (f.Path.StartsWith(main + "_data", StringComparison.Ordinal)) p.Files.Add(f);
                return main;
            }
            return null;
        }

        public async Task DownloadAsync(Plan plan, string dir, Progress progress, CancellationToken ct)
        {
            long allDone = 0;
            foreach (var f in plan.Files)
            {
                string dst = Path.Combine(dir, f.Path.Replace('/', Path.DirectorySeparatorChar));
                if (File.Exists(dst) && (f.Size < 0 || new FileInfo(dst).Length == f.Size))
                {
                    allDone += new FileInfo(dst).Length;
                    continue;
                }
                for (int attempt = 1; ; attempt++)
                {
                    try
                    {
                        allDone = await DownloadOneAsync(f, dst, allDone, plan.TotalBytes, progress, ct);
                        break;
                    }
                    catch (Exception e) when (attempt < MaxAttempts && !ct.IsCancellationRequested
                                              && (e is HttpRequestException || (e is IOException && !(e is AccessError))))
                    {
                        // Dropped connection or a server hiccup: the .part file keeps what arrived, resume from there.
                        await Task.Delay(TimeSpan.FromSeconds(1 << (attempt - 1)), ct);
                    }
                }
            }
        }

        private const int MaxAttempts = 5;

        /// <summary>An answer that retrying cannot change (no access, no such file).</summary>
        private sealed class AccessError : IOException
        {
            public AccessError(string message) : base(message) { }
        }

        private async Task<long> DownloadOneAsync(RemoteFile f, string dst, long allDone, long allTotal, Progress progress,
                                                  CancellationToken ct)
        {
            string part = dst + ".part";
            Directory.CreateDirectory(Path.GetDirectoryName(dst));
            long have = File.Exists(part) ? new FileInfo(part).Length : 0;
            if (f.Size >= 0 && have > f.Size)
            {
                File.Delete(part);
                have = 0;
            }
            using (var resp = await Http.SendAsync(Request(host + "/" + repo + "/resolve/main/" + f.Path, have),
                       HttpCompletionOption.ResponseHeadersRead, ct))
            {
                if (resp.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable && f.Size >= 0 && have == f.Size)
                {
                    // already complete
                }
                else
                {
                    if (resp.StatusCode == HttpStatusCode.Unauthorized || resp.StatusCode == HttpStatusCode.Forbidden)
                        throw new AccessError("Нет доступа к " + f.Path + " (HTTP " + (int)resp.StatusCode
                                              + "): примите условия на huggingface.co и укажите токен");
                    if (resp.StatusCode == HttpStatusCode.NotFound) throw new AccessError("Файла " + f.Path + " нет в репозитории (HTTP 404)");
                    if (!resp.IsSuccessStatusCode) throw new IOException("HTTP " + (int)resp.StatusCode + " для " + f.Path);
                    bool append = resp.StatusCode == HttpStatusCode.PartialContent;
                    if (!append) have = 0;
                    long total = f.Size >= 0 ? f.Size : have + (resp.Content.Headers.ContentLength ?? -1);
                    using (var input = await resp.Content.ReadAsStreamAsync(ct))
                    using (var output = new FileStream(part, append ? FileMode.Append : FileMode.Create, FileAccess.Write))
                    {
                        var buf = new byte[1 << 16];
                        long last = 0;
                        int n;
                        while ((n = await input.ReadAsync(buf, 0, buf.Length, ct)) > 0)
                        {
                            await output.WriteAsync(buf, 0, n, ct);
                            have += n;
                            long now = Environment.TickCount64;
                            if (now - last > 250)
                            {
                                last = now;
                                progress?.Invoke(f.Path, have, total, allDone + have, allTotal);
                            }
                        }
                    }
                }
            }
            if (f.Size >= 0 && new FileInfo(part).Length != f.Size)
                throw new IOException("Файл " + f.Path + " скачан не полностью: " + new FileInfo(part).Length + " из " + f.Size);
            if (File.Exists(dst)) File.Delete(dst);
            File.Move(part, dst);
            return allDone + new FileInfo(dst).Length;
        }

        public static bool IsComplete(Plan plan, string dir)
        {
            foreach (var f in plan.Files)
            {
                string d = Path.Combine(dir, f.Path.Replace('/', Path.DirectorySeparatorChar));
                if (!File.Exists(d) || (f.Size >= 0 && new FileInfo(d).Length != f.Size)) return false;
            }
            return true;
        }

        public static void SaveManifest(Plan plan, string repo, string file)
        {
            var o = new Dictionary<string, object>
            {
                ["repo"] = repo, ["text"] = plan.TextModel, ["vision"] = plan.VisionModel,
                ["files"] = plan.Files.ConvertAll(f => new Dictionary<string, object> { ["path"] = f.Path, ["size"] = f.Size })
            };
            File.WriteAllText(file, JsonSerializer.Serialize(o));
        }

        public static Plan LoadManifest(string file, out string repo)
        {
            repo = null;
            var m = ModelConfig.Read(file);
            if (m == null) return null;
            var e = m.Value;
            repo = HfTokenizer.Str(e, "repo", null);
            var p = new Plan { TextModel = HfTokenizer.Str(e, "text", null), VisionModel = HfTokenizer.Str(e, "vision", null) };
            foreach (var f in e.GetProperty("files").EnumerateArray())
            {
                var rf = new RemoteFile { Path = HfTokenizer.Str(f, "path", ""), Size = HfTokenizer.Num(f, "size", -1) };
                p.Files.Add(rf);
                p.TotalBytes += Math.Max(0, rf.Size);
            }
            return p;
        }
    }
}
