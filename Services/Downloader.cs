using System;
using System.IO;
using System.Net;
using System.Threading;
using System.Threading.Tasks;

namespace DriverCore.Services
{
    /// <summary>Загрузка файла по HTTPS с отчётом о прогрессе. TLS 1.2/1.3.</summary>
    internal static class Downloader
    {
        static Downloader()
        {
            try
            {
                // TLS 1.3 (12288) + TLS 1.2; если 1.3 не поддерживается — откат на 1.2.
                ServicePointManager.SecurityProtocol =
                    (SecurityProtocolType)12288 | SecurityProtocolType.Tls12;
            }
            catch
            {
                ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            }
            ServicePointManager.DefaultConnectionLimit = 8;
        }

        /// <param name="progress">received, total (total = -1, если размер неизвестен).</param>
        public static async Task DownloadAsync(string url, string destPath,
            Action<long, long> progress, CancellationToken ct)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.AllowAutoRedirect = true;
            req.UserAgent = "DriverCore/1.0";
            req.Timeout = 30000;
            req.ReadWriteTimeout = 60000;

            using (var resp = (HttpWebResponse)await req.GetResponseAsync().ConfigureAwait(false))
            using (var src = resp.GetResponseStream())
            using (var dst = new FileStream(destPath, FileMode.Create, FileAccess.Write,
                       FileShare.None, 81920, true))
            {
                long total = resp.ContentLength;
                var buffer = new byte[81920];
                long done = 0;
                int read;
                int lastReported = -1;
                while ((read = await src.ReadAsync(buffer, 0, buffer.Length, ct).ConfigureAwait(false)) > 0)
                {
                    await dst.WriteAsync(buffer, 0, read, ct).ConfigureAwait(false);
                    done += read;
                    int pct = total > 0 ? (int)(done * 100 / total) : -1;
                    if (pct != lastReported)
                    {
                        lastReported = pct;
                        progress?.Invoke(done, total);
                    }
                }
                progress?.Invoke(done, total);
            }
        }
    }
}
