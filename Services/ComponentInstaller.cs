using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using DriverCore.Models;

namespace DriverCore.Services
{
    /// <summary>Скачивает и тихо устанавливает компонент, отчитываясь о прогрессе.</summary>
    internal sealed class ComponentInstaller
    {
        public static string TempDir => Path.Combine(Path.GetTempPath(), "DriverCore");

        /// <param name="percent">0..100 при скачивании; -1 — идёт установка/неизвестно.</param>
        public async Task<bool> InstallAsync(InstallComponent c,
            Action<int> percent, Action<string> log, CancellationToken ct)
        {
            Directory.CreateDirectory(TempDir);
            string dest = Path.Combine(TempDir, c.FileName);

            log($"Скачиваю: {c.DisplayName}…");
            try
            {
                await Downloader.DownloadAsync(c.Url, dest, (recv, total) =>
                {
                    int pct = total > 0 ? (int)(recv * 100 / total) : -1;
                    percent?.Invoke(pct);
                }, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                log("Отменено.");
                return false;
            }
            catch (Exception ex)
            {
                log($"Не удалось скачать: {ex.Message}");
                if (!string.IsNullOrEmpty(c.FallbackPageUrl))
                {
                    log("Открываю официальную страницу загрузки в браузере…");
                    Sys.OpenUrl(c.FallbackPageUrl);
                }
                return false;
            }

            log("Устанавливаю (тихий режим)…");
            percent?.Invoke(-1);

            int code;
            try
            {
                code = await Installer.RunAsync(dest, c.SilentArgs, ct).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                log($"Не удалось запустить установщик: {ex.Message}");
                return false;
            }

            bool ok = c.IsSuccess(code);
            log(ok
                ? $"✓ Установлено (код возврата {code})."
                : $"✗ Ошибка установки (код возврата {code}).");

            try { if (c.Detector != null) c.State = c.Detector(); } catch { }
            try { File.Delete(dest); } catch { }
            return ok;
        }
    }
}
