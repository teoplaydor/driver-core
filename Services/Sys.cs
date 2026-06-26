using System;
using System.Diagnostics;

namespace DriverCore.Services
{
    internal static class Sys
    {
        /// <summary>Открыть URL в браузере по умолчанию.</summary>
        public static void OpenUrl(string url)
        {
            if (string.IsNullOrEmpty(url)) return;
            try
            {
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            }
            catch { /* нет браузера/ошибка — молча игнорируем */ }
        }
    }
}
