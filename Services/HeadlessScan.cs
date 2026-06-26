using System;
using System.IO;
using System.Linq;
using System.Text;
using DriverCore.Models;

namespace DriverCore.Services
{
    /// <summary>
    /// Консольный самотест: сканирует железо и проверяет компоненты,
    /// печатает отчёт и пишет его в %TEMP%\drivercore-scan.txt. Запуск: DriverCore.exe --scan
    /// </summary>
    internal static class HeadlessScan
    {
        public static int Run()
        {
            var sb = new StringBuilder();
            void W(string s) { Console.WriteLine(s); sb.AppendLine(s); }

            try
            {
                W("=== DriverCore — самотест сканера ===");
                W("Администратор: " + (ElevationHelper.IsAdministrator() ? "да" : "нет"));

                var scanner = new HardwareScanner();
                var inv = scanner.Scan(m => W("  ." + m));

                W("");
                W("--- Система ---");
                foreach (var row in inv.System.AsRows())
                    W($"  {row.Key,-12}: {row.Value}");

                W("");
                W($"--- Оборудование ({inv.TotalDevices} устройств) ---");
                foreach (var grp in inv.ByCategory())
                {
                    W($"[{grp.Key.Title()}]");
                    foreach (var d in grp)
                    {
                        string mark = d.Health == DeviceHealth.Ok ? "ok " :
                                      d.Health == DeviceHealth.NeedsDriver ? "DRV" :
                                      d.Health == DeviceHealth.Disabled ? "off" : "ERR";
                        W($"   [{mark}] {d.Name}  ({d.Health.Title()})" +
                          (string.IsNullOrEmpty(d.Detail) ? "" : "  — " + d.Detail));
                    }
                }

                W("");
                W($"--- Проблемные устройства: {inv.ProblemCount} ---");
                foreach (var d in inv.Problems)
                    W($"   ! {d.Name}: {d.HealthText} (код {d.ConfigManagerErrorCode})");

                W("");
                W("--- Видеокарты ---");
                foreach (var g in inv.Gpus)
                    W($"   {g.Name} — {g.VendorName}, драйвер {(string.IsNullOrEmpty(g.DriverVersion) ? "?" : g.DriverVersion)} {g.DriverDate}");

                W("");
                W("--- Компоненты ---");
                var comps = ComponentCatalog.Build();
                ComponentCatalog.Refresh(comps);
                foreach (var c in comps)
                    W($"   [{c.State,-9}] {c.DisplayName}");

                W("");
                W("=== Самотест завершён успешно ===");
            }
            catch (Exception ex)
            {
                W("ОШИБКА: " + ex);
            }

            try
            {
                string path = Path.Combine(Path.GetTempPath(), "drivercore-scan.txt");
                File.WriteAllText(path, sb.ToString(), Encoding.UTF8);
                Console.WriteLine("Отчёт сохранён: " + path);
            }
            catch { }

            return 0;
        }
    }
}
