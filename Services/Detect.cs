using System;
using System.IO;
using System.Linq;
using Microsoft.Win32;
using DriverCore.Models;

namespace DriverCore.Services
{
    /// <summary>Определение наличия рантаймов и компонентов (до установки).</summary>
    internal static class Detect
    {
        /// <param name="arch">"x64" или "x86".</param>
        public static ComponentState VcRedist(string arch, out string version)
        {
            version = "";
            foreach (var view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
            {
                try
                {
                    using (var baseKey = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view))
                    using (var k = baseKey.OpenSubKey($@"SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\{arch}"))
                    {
                        if (k == null) continue;
                        var installed = k.GetValue("Installed");
                        if (installed != null && Convert.ToInt32(installed) == 1)
                        {
                            version = (k.GetValue("Version") as string) ?? "";
                            return ComponentState.Installed;
                        }
                    }
                }
                catch { /* нет доступа к ветке — пробуем следующую */ }
            }
            return ComponentState.Missing;
        }

        /// <summary>Легаси-рантайм DirectX (D3DX9) — по наличию d3dx9_43.dll.</summary>
        public static ComponentState DirectXLegacy()
        {
            try
            {
                string sys = Environment.GetFolderPath(Environment.SpecialFolder.System);
                if (File.Exists(Path.Combine(sys, "d3dx9_43.dll")))
                    return ComponentState.Installed;
                string sysWow = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "SysWOW64");
                if (File.Exists(Path.Combine(sysWow, "d3dx9_43.dll")))
                    return ComponentState.Installed;
            }
            catch { return ComponentState.Unknown; }
            return ComponentState.Missing;
        }

        /// <param name="major">Например "8".</param>
        public static ComponentState DotnetDesktop(string major, out string version)
        {
            version = "";
            try
            {
                string pf = Environment.GetEnvironmentVariable("ProgramFiles") ?? @"C:\Program Files";
                string dir = Path.Combine(pf, "dotnet", "shared", "Microsoft.WindowsDesktop.App");
                if (!Directory.Exists(dir)) return ComponentState.Missing;
                var found = Directory.GetDirectories(dir)
                    .Select(Path.GetFileName)
                    .Where(n => n != null && n.StartsWith(major + ".", StringComparison.Ordinal))
                    .OrderBy(n => n, StringComparer.OrdinalIgnoreCase)
                    .LastOrDefault();
                if (found != null) { version = found; return ComponentState.Installed; }
            }
            catch { return ComponentState.Unknown; }
            return ComponentState.Missing;
        }

        /// <summary>.NET Framework 4.8+ (Release ≥ 528040).</summary>
        public static ComponentState NetFx48(out string version)
        {
            version = "";
            try
            {
                using (var k = Registry.LocalMachine.OpenSubKey(
                    @"SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full"))
                {
                    if (k == null) return ComponentState.Missing;
                    int release = Convert.ToInt32(k.GetValue("Release", 0));
                    if (release >= 528040)
                    {
                        version = release >= 533320 ? "4.8.1" : "4.8";
                        return ComponentState.Installed;
                    }
                }
            }
            catch { return ComponentState.Unknown; }
            return ComponentState.Missing;
        }
    }
}
