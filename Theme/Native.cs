using System;
using System.Runtime.InteropServices;

namespace DriverCore.Theme
{
    /// <summary>
    /// Тонкие обёртки над WinAPI для скруглённых углов окна (Win11)
    /// и тёмного режима у системных элементов.
    /// </summary>
    internal static class Native
    {
        private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33; // Win11
        private const int DWMWCP_ROUND = 2;

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        [DllImport("uxtheme.dll", CharSet = CharSet.Unicode)]
        private static extern int SetWindowTheme(IntPtr hWnd, string pszSubAppName, string pszSubIdList);

        /// <summary>Делает нативные скроллбары/рамки контрола тёмными (Win10 1903+).</summary>
        public static void UseDarkControl(IntPtr hwnd)
        {
            try { SetWindowTheme(hwnd, "DarkMode_Explorer", null); }
            catch { /* uxtheme недоступен — не критично */ }
        }

        /// <summary>Скругляет углы окна на Windows 11. На более старых — тихо игнорируется.</summary>
        public static void EnableRoundedCorners(IntPtr hwnd)
        {
            try
            {
                int pref = DWMWCP_ROUND;
                DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref pref, sizeof(int));
            }
            catch
            {
                // dwmapi отсутствует/атрибут не поддерживается — не критично.
            }
        }
    }
}
