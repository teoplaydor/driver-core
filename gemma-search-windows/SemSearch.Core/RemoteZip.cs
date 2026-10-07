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
        private static readonly HttpClient Http = CreateClient();

        private static HttpClient CreateClient()
        {
            var c = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
            c.DefaultRequestHeaders.UserAgent.ParseAdd("SemSearch-Windows/0.1");
            return c;
        }

        public static async Task ExtractAsync(string url, string entry, string dest, string sha256, Action<long, long> progress,
                                              CancellationToken ct)
        {
            byte[] tail = await RangeAsync(url, null, 65536 + 22, ct);
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

        /// <summary>Bytes [from, from + count), or the last <paramref name="count"/> bytes when from is null; retried on network errors.</summary>
        private static async Task<byte[]> RangeAsync(string url, long? from, long count, CancellationToken ct, Action<long, long> progress = null)
        {
            for (int attempt = 1; ; attempt++)
            {
                try
                {
                    var req = new HttpRequestMessage(HttpMethod.Get, url);
                    req.Headers.Range = from.HasValue ? new RangeHeaderValue(from, from + count - 1) : new RangeHeaderValue(null, count);
                    using (var resp = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct))
                    {
                        if (resp.StatusCode != HttpStatusCode.PartialContent)
                            throw new IOException("сервер не отдал часть файла (HTTP " + (int)resp.StatusCode + ")");
                        using (var s = await resp.Content.ReadAsStreamAsync(ct))
                        using (var ms = new MemoryStream())
                        {
                            var buf = new byte[1 << 16];
                            int n;
                            while ((n = await s.ReadAsync(buf, 0, buf.Length, ct)) > 0)
                            {
                                ms.Write(buf, 0, n);
                                progress?.Invoke(ms.Length, count);
                            }
                            if (from.HasValue && ms.Length != count) throw new IOException("ответ оборвался: " + ms.Length + " из " + count);
                            return ms.ToArray();
                        }
                    }
                }
                catch (Exception e) when (attempt < 4 && !ct.IsCancellationRequested && (e is HttpRequestException || e is IOException))
                {
                    await Task.Delay(TimeSpan.FromSeconds(1 << (attempt - 1)), ct);
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
