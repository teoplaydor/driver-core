using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Text.Json;
using SemSearch.Core;

/// <summary>
/// Parity tests for the C# core against the references used by the Android app's tests.
/// usage: SemSearch.Tests <android test dir: gemma-search-android/build/test> [extra tokenizer cases.jsonl ...]
/// </summary>
internal static class Program
{
    private static int failures;

    private static void Check(bool ok, string what)
    {
        Console.WriteLine((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    private static int Main(string[] args)
    {
        string t = args[0];
        Tokenizer(Path.Combine(t, "gemma3", "tokenizer.json"), Path.Combine(t, "tok-cases.jsonl"));
        for (int i = 1; i < args.Length; i++) Tokenizer(Path.Combine(t, "gemma3", "tokenizer.json"), args[i]);
        Pipeline(Path.Combine(t, "models", "dummy"), Path.Combine(t, "reference.json"));
        Accel(Path.Combine(t, "models", "dummy"));
        Stemmer(Path.Combine(t, "stem-cases.tsv"));
        Bridge();
        Index();
        Docs();
        Exif();
        Hub(Path.Combine(t, "..", "..", "tools", "mock_hub.py"));
        Zip();
        if (Environment.GetEnvironmentVariable("SEMSEARCH_ONLINE") == "1") ZipOnline();
        Console.WriteLine(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILED");
        return failures == 0 ? 0 : 1;
    }

    private static void Tokenizer(string json, string cases)
    {
        string cache = Path.Combine(Path.GetTempPath(), "semsearch-tok-test.bin");
        File.Delete(cache);
        var tok = HfTokenizer.Load(json, cache);
        var cached = HfTokenizer.Load(json, cache);
        int total = 0, bad = 0;
        foreach (var line in File.ReadLines(cases))
        {
            using var d = JsonDocument.Parse(line);
            string text = d.RootElement.GetProperty("text").GetString();
            int[] exp = d.RootElement.GetProperty("ids").EnumerateArray().Select(x => x.GetInt32()).ToArray();
            foreach (var tk in new[] { tok, cached })
            {
                total++;
                if (!tk.Encode(text).SequenceEqual(exp))
                {
                    bad++;
                    if (bad <= 3) Console.WriteLine("  mismatch: " + JsonSerializer.Serialize(text));
                }
            }
        }
        Check(bad == 0, $"tokenizer {Path.GetFileName(cases)}: {total} encodings, {bad} mismatches");
    }

    /// <summary>The image pattern used by test/parity/reference.mjs (transformers.js side).</summary>
    private sealed class Pattern : IImageSource
    {
        private readonly int w, h, k;
        public Pattern(int w, int h, int k) { this.w = w; this.h = h; this.k = k; }
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

    private static void Pipeline(string dir, string referenceJson)
    {
        var cfg = EmbeddingGemma2.LoadConfig(dir);
        var tok = EmbeddingGemma2.LoadTokenizer(dir);
        using var m = new EmbeddingGemma2(cfg, tok, Path.Combine(dir, "onnx/model.onnx"), Path.Combine(dir, "onnx/vision_encoder.onnx"), 2,
            EmbeddingGemma2.Device.Cpu);
        using var refs = JsonDocument.Parse(File.ReadAllText(referenceJson));
        foreach (var c in refs.RootElement.EnumerateArray())
        {
            string label = c.GetProperty("label").GetString();
            float[] emb;
            bool idsOk = true;
            if (label.StartsWith("text:"))
            {
                string text = label.Substring(5);
                idsOk = tok.Encode(text).SequenceEqual(c.GetProperty("input_ids").EnumerateArray().Select(x => x.GetInt32()));
                emb = m.EmbedText(text, 8192);
            }
            else if (label.StartsWith("image:"))
            {
                var wh = label.Substring(6).Split('x');
                emb = m.EmbedImage(new Pattern(int.Parse(wh[0]), int.Parse(wh[1]), 0), 0);
            }
            else
            {
                emb = m.EmbedVideo(new IImageSource[] { new Pattern(384, 384, 0), new Pattern(384, 384, 1), new Pattern(384, 384, 2) }, 0);
            }
            var exp = c.GetProperty("embedding").EnumerateArray().Select(x => x.GetDouble()).ToArray();
            double maxDiff = emb.Select((v, i) => Math.Abs(v - exp[i])).Max();
            Check(idsOk && maxDiff < 1e-4 && emb.Length == exp.Length, $"pipeline vs transformers.js {label.Substring(0, Math.Min(40, label.Length))}: maxDiff {maxDiff:E1}");
        }
    }

    private static void Accel(string dir)
    {
        var cfg = EmbeddingGemma2.LoadConfig(dir);
        var tok = EmbeddingGemma2.LoadTokenizer(dir);
        string text = Path.Combine(dir, "onnx/model_q4.onnx"), vision = Path.Combine(dir, "onnx/vision_encoder_q4.onnx");
        if (!File.Exists(vision))
        {
            Check(false, "q4 dummy missing (run test/accel/quantize_dummy.py)");
            return;
        }
        string text8 = Path.Combine(dir, "onnx/model_q4.int8.cs.onnx"), vision8 = Path.Combine(dir, "onnx/vision_encoder_q4.int8.cs.onnx");
        int nv = OnnxPatcher.SetMatMulNBitsAccuracy(vision, vision8, 4), nt = OnnxPatcher.SetMatMulNBitsAccuracy(text, text8, 4);
        Check(nv > 0 && nt > 0, $"int8 patch: vision {nv}, text {nt} MatMulNBits nodes");
        IImageSource[] imgs = { new PatternSource(640, 480, 1), new PatternSource(300, 900, 2), new PatternSource(1200, 500, 3) };
        float[][] single;
        using (var plain = new EmbeddingGemma2(cfg, tok, text, vision, 2, EmbeddingGemma2.Device.Cpu))
        {
            single = imgs.Select(i => plain.EmbedImage(i, 70)).ToArray();
            var batch = plain.EmbedImages(imgs, 70);
            double worst = Enumerable.Range(0, imgs.Length).Min(i => VectorMath.Dot(single[i], batch[i], single[i].Length));
            Check(worst > 0.9999, $"batch of {imgs.Length} == one by one (worst cos {worst:F6})");
        }
        using (var int8 = new EmbeddingGemma2(cfg, tok, text8, vision8, 2, EmbeddingGemma2.Device.Cpu))
        {
            var e = int8.EmbedImage(imgs[0], 70);
            double cos = VectorMath.Dot(single[0], e, e.Length);
            Check(cos > 0.98, $"int8 compute matches plain (cos {cos:F5})");
        }
        try
        {
            using var gpu = new EmbeddingGemma2(cfg, tok, text, vision, 2, EmbeddingGemma2.Device.DirectML);
            Check(true, "DirectML available here");
        }
        catch (IOException e)
        {
            Check(true, "DirectML unavailable here → clean error: " + e.Message.Substring(0, Math.Min(80, e.Message.Length)));
        }
    }

    private static void Stemmer(string tsv)
    {
        int n = 0, bad = 0;
        foreach (var line in File.ReadLines(tsv))
        {
            var p = line.Split('\t');
            n++;
            if (RussianStemmer.Stem(p[0]) != p[1]) bad++;
        }
        Check(bad == 0, $"stemmer vs Snowball: {n} words, {bad} mismatches");
    }

    private static void Bridge()
    {
        var b = QueryBridge.LoadEmbedded();
        var cases = new (string ru, string en)[]
        {
            ("кот на диване", "cat on sofa"), ("Кошка спит на кровати", "cat sleeping on bed"), ("закат на море", "sunset on sea"),
            ("красная машина", "red car"), ("чек из магазина", "receipt from store"), ("скриншот с текстом", "screenshot with text"),
            ("торт на день рождения", "cake on birthday"), ("ёлка и подарки", "christmas tree and gift"), ("еда в ресторане", "food in restaurant"),
            ("голубое небо", "light blue sky"), ("голуби в парке", "pigeon in park"), ("iPhone на столе", "iphone on table"),
        };
        int bad = 0;
        foreach (var (ru, en) in cases)
        {
            string got = b.Translate(ru)?.English;
            if (got != en)
            {
                bad++;
                Console.WriteLine($"  {ru} -> {got} (expected {en})");
            }
        }
        Check(bad == 0 && b.Translate("Маша и Петя") == null, $"query bridge: {cases.Length} cases, {bad} wrong");
    }

    private static void Index()
    {
        string f = Path.Combine(Path.GetTempPath(), "semsearch-index-test.bin");
        File.Delete(f);
        var rnd = new Random(1);
        float[] V()
        {
            var v = new float[768];
            for (int i = 0; i < v.Length; i++) v[i] = (float)(rnd.NextDouble() - 0.5);
            VectorMath.Normalize(v);
            return v;
        }
        var ix = new VectorIndex(f);
        var a = V();
        ix.Put(@"C:\photos\cat.jpg", VectorIndex.KindImage, 100, 1, a);
        ix.Put(@"C:\photos\dog.jpg", VectorIndex.KindImage, 200, 2, V());
        ix.Put(@"C:\docs\note.txt", VectorIndex.KindDocument, 10, 3, V());
        ix.Put(@"C:\photos\dog.jpg", VectorIndex.KindImage, 201, 4, V()); // re-indexed after a change
        ix.Remove(@"C:\docs\note.txt");
        var re = new VectorIndex(f);
        var hits = re.Search(a, a, 768, true, true, 10);
        Check(re.Count(VectorIndex.KindImage) == 2 && re.Count(VectorIndex.KindDocument) == 0
              && re.IsCurrent(@"C:\PHOTOS\dog.jpg", 201, 4) && !re.IsCurrent(@"C:\photos\dog.jpg", 200, 2)
              && hits[0].Item.Path.EndsWith("cat.jpg") && hits[0].Score > 0.999, "index: upsert, delete, reload, search");
        re.Compact();
        var re2 = new VectorIndex(f);
        Check(re2.Count(VectorIndex.KindImage) == 2 && re2.Search(a, a, 256, true, false, 1)[0].Item.Path.EndsWith("cat.jpg"),
            "index: compaction keeps live items; Matryoshka 256 search");
    }

    private static void Docs()
    {
        string dir = Path.Combine(Path.GetTempPath(), "semsearch-docs-test");
        Directory.CreateDirectory(dir);
        string docx = Path.Combine(dir, "a.docx");
        File.Delete(docx);
        using (var z = ZipFile.Open(docx, ZipArchiveMode.Create))
        using (var w = new StreamWriter(z.CreateEntry("word/document.xml").Open()))
            w.Write("<w:document><w:body><w:p><w:r><w:t>Договор аренды</w:t></w:r></w:p><w:p><w:r><w:t>Цена &amp; сроки</w:t></w:r></w:p></w:body></w:document>");
        string txt1251 = Path.Combine(dir, "b.txt");
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        File.WriteAllBytes(txt1251, Encoding.GetEncoding(1251).GetBytes("Пароль от Wi-Fi на даче"));
        string bin = Path.Combine(dir, "c.txt");
        File.WriteAllBytes(bin, new byte[] { 1, 0, 2, 0, 3 });
        Check(TextExtract.Extract(docx) == "Договор аренды\nЦена & сроки", "docx text");
        Check(TextExtract.Extract(txt1251) == "Пароль от Wi-Fi на даче", "cp1251 text file");
        Check(TextExtract.Extract(bin) == null, "binary file skipped");
    }

    /// <summary>SOI, JFIF APP0, APP1 "Exif" with IFD0 {Make, Orientation}, SOS — in either byte order.</summary>
    private static byte[] ExifJpeg(int orientation, bool bigEndian, bool fill = false)
    {
        var tiff = new List<byte>();
        void U16(int v) { if (bigEndian) { tiff.Add((byte)(v >> 8)); tiff.Add((byte)v); } else { tiff.Add((byte)v); tiff.Add((byte)(v >> 8)); } }
        void U32(int v) { if (bigEndian) { U16(v >> 16); U16(v & 0xffff); } else { U16(v & 0xffff); U16(v >> 16); } }
        tiff.AddRange(bigEndian ? new byte[] { (byte)'M', (byte)'M' } : new byte[] { (byte)'I', (byte)'I' });
        U16(42); U32(8);
        U16(2);
        U16(0x010F); U16(2); U32(4); tiff.AddRange(new byte[] { (byte)'A', (byte)'B', (byte)'C', 0 }); // Make "ABC"
        U16(0x0112); U16(3); U32(1); U16(orientation); U16(0);
        U32(0);
        var d = new List<byte> { 0xFF, 0xD8 };
        if (fill) d.Add(0xFF);
        d.AddRange(new byte[] { 0xFF, 0xE0, 0, 16, (byte)'J', (byte)'F', (byte)'I', (byte)'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0 });
        int len = 2 + 6 + tiff.Count;
        d.AddRange(new byte[] { 0xFF, 0xE1, (byte)(len >> 8), (byte)len, (byte)'E', (byte)'x', (byte)'i', (byte)'f', 0, 0 });
        d.AddRange(tiff);
        d.AddRange(new byte[] { 0xFF, 0xDA, 0, 8, 1, 1, 0, 0, 63, 0, 0x12, 0x34, 0xFF, 0xD9 });
        return d.ToArray();
    }

    private static void Exif()
    {
        bool ok = true;
        for (int o = 1; o <= 8; o++)
            foreach (bool be in new[] { false, true })
                ok &= ExifOrientation.FromJpeg(ExifJpeg(o, be, o == 3)) == o;
        Check(ok, "EXIF orientation 1..8, II and MM byte order");
        byte[] good = ExifJpeg(6, false);
        bool robust = ExifOrientation.FromJpeg(new byte[0]) == 0 && ExifOrientation.FromJpeg(new byte[] { 0x89, (byte)'P', (byte)'N', (byte)'G' }) == 0
                      && ExifOrientation.FromJpeg(ExifJpeg(9, false)) == 0;
        for (int cut = 0; cut < good.Length; cut++) robust &= ExifOrientation.FromJpeg(good.Take(cut).ToArray()) is int v && (v == 0 || v == 6);
        var rnd = new Random(5);
        for (int k = 0; k < 2000; k++)
        {
            byte[] b = (byte[])good.Clone();
            b[2 + rnd.Next(b.Length - 2)] = (byte)rnd.Next(256);
            ExifOrientation.FromJpeg(b); // must not throw
        }
        Check(robust, "EXIF parser: no EXIF / truncated / corrupted input");
    }

    /// <summary>Model download against tools/mock_hub.py: file choice, resume after a dropped connection, content.</summary>
    private static void Hub(string mockHub)
    {
        int port;
        using (var l = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0))
        {
            l.Start();
            port = ((System.Net.IPEndPoint)l.LocalEndpoint).Port;
        }
        using var server = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("python3", $"\"{mockHub}\" {port}")
            { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true });
        try
        {
            string host = "http://127.0.0.1:" + port;
            for (int i = 0; i < 100; i++)
            {
                try { using var c = new System.Net.Sockets.TcpClient("127.0.0.1", port); break; }
                catch (Exception) { System.Threading.Thread.Sleep(100); }
            }
            var hub = new HfRepo(HfRepo.DefaultRepo, null, host);
            var files = hub.ListFilesAsync(default).GetAwaiter().GetResult();
            var plan = HfRepo.MakePlan(files, true);
            var names = plan.Files.Select(f => f.Path).OrderBy(x => x, StringComparer.Ordinal).ToList();
            var expect = new[] { "config.json", "onnx/model_q4.onnx", "onnx/model_q4.onnx_data", "onnx/model_q4.onnx_data_1",
                "onnx/vision_encoder_q4.onnx", "preprocessor_config.json", "processor_config.json", "tokenizer.json", "tokenizer_config.json" };
            Check(names.SequenceEqual(expect.OrderBy(x => x, StringComparer.Ordinal)) && plan.TextModel == "onnx/model_q4.onnx"
                  && plan.VisionModel == "onnx/vision_encoder_q4.onnx", "hub plan: q4 text + vision, external data, configs only");

            string dir = Path.Combine(Path.GetTempPath(), "semsearch-hub-test");
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
            hub.DownloadAsync(plan, dir, null, default).GetAwaiter().GetResult(); // the mock drops model_q4.onnx_data once
            bool same = plan.Files.All(f =>
            {
                byte[] got = File.ReadAllBytes(Path.Combine(dir, f.Path));
                int seed = Encoding.UTF8.GetBytes(f.Path).Sum(b => b);
                return got.Length == f.Size && got.Select((b, i) => b == (seed + i * 31L) % 251).All(x => x);
            });
            Check(same && HfRepo.IsComplete(plan, dir) && !Directory.EnumerateFiles(dir, "*.part", SearchOption.AllDirectories).Any(),
                "hub download: resumed after a dropped connection, every byte matches");
            string err = null;
            try { new HfRepo("someone/private", null, host).ListFilesAsync(default).GetAwaiter().GetResult(); }
            catch (IOException e) { err = e.Message; }
            Check(err != null && err.Contains("токен"), "hub: closed repo asks for a token");
            Directory.Delete(dir, true);
        }
        catch (Exception e)
        {
            Check(false, "hub: " + e.Message);
        }
        finally
        {
            server.Kill();
        }
    }

    private static string Sha(byte[] b) => Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(b)).ToLowerInvariant();

    /// <summary>One entry out of a remote zip by range requests (local server drops the big range once).</summary>
    private static void Zip()
    {
        string dir = Path.Combine(Path.GetTempPath(), "semsearch-zip-test");
        if (Directory.Exists(dir)) Directory.Delete(dir, true);
        Directory.CreateDirectory(dir);
        var rnd = new Random(3);
        byte[] stored = new byte[300_000], text = Encoding.UTF8.GetBytes(string.Concat(Enumerable.Repeat("DirectML ", 200_000)));
        rnd.NextBytes(stored);
        using (var z = new System.IO.Compression.ZipArchive(File.Create(Path.Combine(dir, "pkg.zip")), System.IO.Compression.ZipArchiveMode.Create))
        {
            foreach (var (name, data, level) in new[] { ("lib/readme.txt", Encoding.UTF8.GetBytes("hi"), System.IO.Compression.CompressionLevel.Optimal),
                         ("bin/x64-win/stored.bin", stored, System.IO.Compression.CompressionLevel.NoCompression),
                         ("bin/x64-win/DirectML.dll", text, System.IO.Compression.CompressionLevel.Optimal) })
            {
                using var e = z.CreateEntry(name, level).Open();
                e.Write(data, 0, data.Length);
            }
        }
        string nupkg = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            ".nuget/packages/microsoft.ai.directml/1.15.4/microsoft.ai.directml.1.15.4.nupkg");
        if (File.Exists(nupkg)) File.Copy(nupkg, Path.Combine(dir, "real.nupkg"));
        int port;
        using (var l = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0))
        {
            l.Start();
            port = ((System.Net.IPEndPoint)l.LocalEndpoint).Port;
        }
        string script = Path.Combine(AppContext.BaseDirectory, "range_server.py");
        using var server = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("python3", $"\"{script}\" {port} \"{dir}\"")
            { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true });
        try
        {
            for (int i = 0; i < 100; i++)
            {
                try { using var c = new System.Net.Sockets.TcpClient("127.0.0.1", port); break; }
                catch (Exception) { System.Threading.Thread.Sleep(100); }
            }
            string url = $"http://127.0.0.1:{port}/pkg.zip", outFile = Path.Combine(dir, "out", "x.bin");
            RemoteZip.ExtractAsync(url, "bin/x64-win/DirectML.dll", outFile, Sha(text), null, default).GetAwaiter().GetResult();
            bool deflated = File.ReadAllBytes(outFile).SequenceEqual(text);
            RemoteZip.ExtractAsync(url, "bin/x64-win/stored.bin", outFile, Sha(stored), null, default).GetAwaiter().GetResult();
            Check(deflated && File.ReadAllBytes(outFile).SequenceEqual(stored), "remote zip: deflated and stored entries by range, after a dropped connection");
            string bad = null, missing = null;
            try { RemoteZip.ExtractAsync(url, "bin/x64-win/stored.bin", outFile + "2", new string('0', 64), null, default).GetAwaiter().GetResult(); }
            catch (IOException e) { bad = e.Message; }
            try { RemoteZip.ExtractAsync(url, "nope.dll", outFile + "3", Sha(stored), null, default).GetAwaiter().GetResult(); }
            catch (IOException e) { missing = e.Message; }
            Check(bad != null && !File.Exists(outFile + "2") && missing != null, "remote zip: wrong checksum and missing entry are refused");
            if (File.Exists(nupkg))
            {
                long last = 0;
                var sw = System.Diagnostics.Stopwatch.StartNew();
                RemoteZip.ExtractAsync($"http://127.0.0.1:{port}/real.nupkg", "bin/x64-win/DirectML.dll", Path.Combine(dir, "DirectML.dll"),
                    "9c9e6d822561c6c41b90e6994b3e8857cf1d66dbfb1e0c4c799c7c89b4e92da1", (d, tot) => last = d, default).GetAwaiter().GetResult();
                Check(new FileInfo(Path.Combine(dir, "DirectML.dll")).Length == 18527776 && last == 9332741,
                    $"remote zip: DirectML.dll from the real 193 MB nupkg, {last / 1048576.0:F1} MB transferred, {sw.ElapsedMilliseconds} ms");
            }
        }
        catch (Exception e)
        {
            Check(false, "remote zip: " + e.Message);
        }
        finally
        {
            server.Kill();
            Directory.Delete(dir, true);
        }
    }

    /// <summary>Opt-in (SEMSEARCH_ONLINE=1): the same fetch the app does on first GPU use, from nuget.org.</summary>
    private static void ZipOnline()
    {
        string dest = Path.Combine(Path.GetTempPath(), "semsearch-online", "DirectML.dll");
        var sw = System.Diagnostics.Stopwatch.StartNew();
        try
        {
            RemoteZip.ExtractAsync("https://api.nuget.org/v3-flatcontainer/microsoft.ai.directml/1.15.4/microsoft.ai.directml.1.15.4.nupkg",
                "bin/x64-win/DirectML.dll", dest, "9c9e6d822561c6c41b90e6994b3e8857cf1d66dbfb1e0c4c799c7c89b4e92da1", null, default).GetAwaiter().GetResult();
            Check(new FileInfo(dest).Length == 18527776, $"nuget.org: DirectML.dll fetched and verified in {sw.ElapsedMilliseconds} ms");
        }
        catch (Exception e)
        {
            Check(false, "nuget.org: " + e.Message);
        }
    }
}
