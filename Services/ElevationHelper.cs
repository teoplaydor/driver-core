using System;
using System.Diagnostics;
using System.Security.Principal;
using System.Windows.Forms;

namespace DriverCore.Services
{
    /// <summary>Проверка прав администратора и перезапуск с повышением.</summary>
    internal static class ElevationHelper
    {
        public static bool IsAdministrator()
        {
            try
            {
                using (var id = WindowsIdentity.GetCurrent())
                {
                    var p = new WindowsPrincipal(id);
                    return p.IsInRole(WindowsBuiltInRole.Administrator);
                }
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// Перезапускает приложение с правами администратора. Возвращает true,
        /// если перезапуск запущен (текущий экземпляр следует закрыть).
        /// </summary>
        public static bool RelaunchAsAdmin(string args = null)
        {
            try
            {
                var exe = Process.GetCurrentProcess().MainModule.FileName;
                var psi = new ProcessStartInfo
                {
                    FileName = exe,
                    Arguments = args ?? "",
                    UseShellExecute = true,
                    Verb = "runas",
                };
                Process.Start(psi);
                return true;
            }
            catch (System.ComponentModel.Win32Exception)
            {
                // Пользователь отклонил запрос UAC.
                return false;
            }
            catch (Exception ex)
            {
                MessageBox.Show("Не удалось перезапустить с правами администратора:\n" + ex.Message,
                    "DriverCore", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return false;
            }
        }

        /// <summary>
        /// Гарантирует права администратора. Если их нет — спрашивает и перезапускается.
        /// Возвращает true, если уже админ (можно продолжать).
        /// </summary>
        public static bool EnsureAdmin(IWin32Window owner)
        {
            if (IsAdministrator()) return true;

            var r = MessageBox.Show(owner,
                "Для установки драйверов и компонентов нужны права администратора.\n" +
                "Перезапустить DriverCore с повышением прав?",
                "Нужны права администратора",
                MessageBoxButtons.YesNo, MessageBoxIcon.Question);

            if (r == DialogResult.Yes && RelaunchAsAdmin())
                Application.Exit();

            return false;
        }
    }
}
