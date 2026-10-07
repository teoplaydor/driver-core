using System;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace SemSearch.Core
{
    /// <summary>
    /// Pulls one file out of a remote .zip/.nupkg with HTTP range requests: the end of central directory, the central
    /// directory, the local header and the compressed bytes — not the whole archive (the DirectML package is 190 MB,
    /// its x64 DLL 9 MB compressed). The result must match a pinned SHA-256.
    /// </summary>
    public static class RemoteZip
    {
        /// <summary>One place to get the file from; any host is fine since the result must match <see cref="Sha256"/>.</summary>
        public sealed class Source
        {
            public string Url, Entry, Sha256;
            public string Host => new Uri(Url).Host;
        }

        /// <summary>How long a request may go without any bytes (headers or body) before it counts as failed.</summary>
        public static TimeSpan StallTimeout = TimeSpan.FromSeconds(15);

        private const int Attempts = 2;

        private static readonly HttpClient Http = CreateClient();

        private static HttpClient CreateClient()
        {
            var c = new HttpClient(new SocketsHttpHandler { ConnectTimeout = TimeSpan.FromSeconds(15) })
                { Timeout = Timeout.InfiniteTimeSpan };
            c.DefaultRequestHeaders.UserAgent.ParseAdd("SemSearch-Windows/0.1");
            return c;
        }

        /// <summary>
        /// Tries the sources in order until one delivers the verified file. <paramref name="status"/> gets the host being
        /// tried; the exception of the last failure lists why every source failed.
        /// </summary>
        public static async Task ExtractFirstAsync(System.Collections.Generic.IList<Source> sources, string dest, Action<string> status,
                                                   Action<string, long, long> progress, CancellationToken ct)
        {
            var why = new StringBuilder();
            foreach (var src in sources)
            {
                status?.Invoke(src.Host);
                try
                {
                    await ExtractAsync(src.Url, src.Entry, dest, src.Sha256, (d, t) => progress?.Invoke(src.Host, d, t), ct);
                    return;
                }
                catch (Exception e) when (!ct.IsCancellationRequested && (e is IOException || e is HttpRequestException))
                {
                    if (why.Length > 0) why.Append("; ");
                    why.Append(src.Host).Append(" — ").Append(e.Message);
                }
            }
            throw new IOException(why.ToString());
        }

        public static async Task ExtractAsync(string url, string entry, string dest, string sha256, Action<long, long> progress,
                                              CancellationToken ct)
        {
            // A host that does not answer the first request is filtered or down: no retry, the next source is tried.
            byte[] tail = await RangeAsync(url, null, 65536 + 22, ct, attempts: 1);
            int eocd = -1;
            for (int i = tail.Length - 22; i >= 0; i--)
                if (U32(tail, i) == 0x06054b50) { eocd = i; break; }
            if (eocd < 0) throw new IOException("архив повреждён: нет оглавления");
            long cdSize = U32(tail, eocd + 12), cdOffset = U32(tail, eocd + 16);
            if (cdOffset == 0xFFFFFFFF || cdSize == 0xFFFFFFFF) throw new IOException("архивы ZIP64 не поддерживаются");

            byte[] cd = await RangeAsync(url, cdOffset, cdSize, ct);
            long lho = -1, comp = 0, size = 0;
            int method = -1;
            for (int p = 0; p + 46 <= cd.Length && U32(cd, p) == 0x02014b50;)
            {
                int n = U16(cd, p + 28), m = U16(cd, p + 30), k = U16(cd, p + 32);
                if (p + 46 + n > cd.Length) break;
                if (Encoding.UTF8.GetString(cd, p + 46, n) == entry)
                {
                    method = U16(cd, p + 10);
                    comp = U32(cd, p + 20);
                    size = U32(cd, p + 24);
                    lho = U32(cd, p + 42);
                    break;
                }
                p += 46 + n + m + k;
            }
            if (lho < 0) throw new IOException("в архиве нет " + entry);
            if (method != 0 && method != 8) throw new IOException("неизвестное сжатие " + method);

            byte[] lh = await RangeAsync(url, lho, 30, ct);
            if (U32(lh, 0) != 0x04034b50) throw new IOException("архив повреждён: локальный заголовок");
            long dataStart = lho + 30 + U16(lh, 26) + U16(lh, 28);
            byte[] data = await RangeAsync(url, dataStart, comp, ct, progress);

            byte[] file;
            if (method == 0)
            {
                file = data;
            }
            else
            {
                using (var inflate = new DeflateStream(new MemoryStream(data), CompressionMode.Decompress))
                using (var ms = new MemoryStream((int)size))
                {
                    await inflate.CopyToAsync(ms, 81920, ct);
                    file = ms.ToArray();
                }
            }
            string got = Hex(SHA256.HashData(file));
            if (file.Length != size || !string.Equals(got, sha256, StringComparison.OrdinalIgnoreCase))
                throw new IOException("контрольная сумма не совпала (" + got.Substring(0, 12) + "…)");
            Directory.CreateDirectory(Path.GetDirectoryName(dest));
            string tmp = dest + ".part";
            await File.WriteAllBytesAsync(tmp, file, ct);
            File.Move(tmp, dest, true);
        }

        /// <summary>Bytes [from, from + count), or the last <paramref name="count"/> bytes when from is null; retried once on network errors.</summary>
        private static async Task<byte[]> RangeAsync(string url, long? from, long count, CancellationToken ct, Action<long, long> progress = null,
                                                    int attempts = Attempts)
        {
            for (int attempt = 1; ; attempt++)
            {
                try
                {
                    return await RangeOnceAsync(url, from, count, ct, progress);
                }
                catch (Exception e) when (attempt < attempts && !ct.IsCancellationRequested && (e is HttpRequestException || e is IOException))
                {
                    await Task.Delay(TimeSpan.FromSeconds(1), ct);
                }
            }
        }

        private static async Task<byte[]> RangeOnceAsync(string url, long? from, long count, CancellationToken ct, Action<long, long> progress)
        {
            // A stalled connection (filtered host, broken proxy) must fail in seconds, not hang the caller.
            using (var stall = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                stall.CancelAfter(StallTimeout);
                try
                {
                    var req = new HttpRequestMessage(HttpMethod.Get, url);
                    req.Headers.Range = from.HasValue ? new RangeHeaderValue(from, from + count - 1) : new RangeHeaderValue(null, count);
                    using (var resp = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, stall.Token))
                    {
                        if (resp.StatusCode != HttpStatusCode.PartialContent)
                            throw new IOException("сервер не отдал часть файла (HTTP " + (int)resp.StatusCode + ")");
                        using (var s = await resp.Content.ReadAsStreamAsync(stall.Token))
                        using (var ms = new MemoryStream())
                        {
                            var buf = new byte[1 << 16];
                            int n;
                            while ((n = await s.ReadAsync(buf, 0, buf.Length, stall.Token)) > 0)
                            {
                                stall.CancelAfter(StallTimeout);
                                ms.Write(buf, 0, n);
                                progress?.Invoke(ms.Length, count);
                            }
                            if (from.HasValue && ms.Length != count) throw new IOException("ответ оборвался: " + ms.Length + " из " + count);
                            return ms.ToArray();
                        }
                    }
                }
                catch (OperationCanceledException) when (!ct.IsCancellationRequested)
                {
                    throw new IOException("нет ответа " + (int)StallTimeout.TotalSeconds + " с");
                }
            }
        }

        private static long U32(byte[] b, int o) => (uint)(b[o] | b[o + 1] << 8 | b[o + 2] << 16 | b[o + 3] << 24);
        private static int U16(byte[] b, int o) => b[o] | b[o + 1] << 8;

        private static string Hex(byte[] h)
        {
            var sb = new StringBuilder(h.Length * 2);
            foreach (byte x in h) sb.Append(x.ToString("x2"));
            return sb.ToString();
        }
    }
}
