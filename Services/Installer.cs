using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;

namespace DriverCore.Services
{
    /// <summary>Тихий запуск установщика и ожидание кода возврата.</summary>
    internal static class Installer
    {
        public static Task<int> RunAsync(string path, string args, CancellationToken ct)
        {
            var tcs = new TaskCompletionSource<int>();
            var proc = new Process
            {
                StartInfo = new ProcessStartInfo
                {
                    FileName = path,
                    Arguments = args ?? "",
                    UseShellExecute = false,
                    CreateNoWindow = true,
                },
                EnableRaisingEvents = true,
            };

            proc.Exited += (s, e) =>
            {
                int code;
                try { code = proc.ExitCode; } catch { code = -1; }
                proc.Dispose();
                tcs.TrySetResult(code);
            };

            try
            {
                proc.Start();
            }
            catch (Exception ex)
            {
                proc.Dispose();
                tcs.TrySetException(ex);
                return tcs.Task;
            }

            ct.Register(() =>
            {
                try { if (!proc.HasExited) proc.Kill(); }
                catch { /* уже завершился */ }
            });

            return tcs.Task;
        }
    }
}
