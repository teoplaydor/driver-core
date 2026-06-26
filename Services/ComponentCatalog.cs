using System.Collections.Generic;
using DriverCore.Models;

namespace DriverCore.Services
{
    /// <summary>
    /// Список устанавливаемых компонентов. Только официальные ссылки Microsoft —
    /// никаких сторонних «драйвер-паков».
    /// </summary>
    internal static class ComponentCatalog
    {
        public static List<InstallComponent> Build()
        {
            return new List<InstallComponent>
            {
                new InstallComponent
                {
                    Key = "vcredist_x64",
                    DisplayName = "Visual C++ 2015–2022 Redistributable (x64)",
                    Description = "C/C++ библиотеки, нужны множеству программ и игр.",
                    Url = "https://aka.ms/vs/17/release/vc_redist.x64.exe",
                    FileName = "vc_redist.x64.exe",
                    SilentArgs = "/install /quiet /norestart",
                    SuccessExitCodes = new[] { 0, 3010, 1638, 1641 },
                    RecommendedByDefault = true,
                    Detector = () => Detect.VcRedist("x64", out _),
                },
                new InstallComponent
                {
                    Key = "vcredist_x86",
                    DisplayName = "Visual C++ 2015–2022 Redistributable (x86)",
                    Description = "32-битные C/C++ библиотеки (нужны даже на 64-бит Windows).",
                    Url = "https://aka.ms/vs/17/release/vc_redist.x86.exe",
                    FileName = "vc_redist.x86.exe",
                    SilentArgs = "/install /quiet /norestart",
                    SuccessExitCodes = new[] { 0, 3010, 1638, 1641 },
                    RecommendedByDefault = true,
                    Detector = () => Detect.VcRedist("x86", out _),
                },
                new InstallComponent
                {
                    Key = "directx",
                    DisplayName = "DirectX Runtime (June 2010 / D3DX)",
                    Description = "Легаси-компоненты DirectX (d3dx9, XInput, XAudio) для игр.",
                    Url = "https://download.microsoft.com/download/1/7/1/1718CCC4-6315-4D8E-9543-8E28A4E18C4C/dxwebsetup.exe",
                    FileName = "dxwebsetup.exe",
                    SilentArgs = "/Q",
                    SuccessExitCodes = new[] { 0 },
                    FallbackPageUrl = "https://www.microsoft.com/en-us/download/details.aspx?id=35",
                    RecommendedByDefault = true,
                    Detector = () => Detect.DirectXLegacy(),
                },
                new InstallComponent
                {
                    Key = "dotnet8_desktop",
                    DisplayName = ".NET 8 Desktop Runtime (x64)",
                    Description = "Рантайм для современных приложений WPF/WinForms.",
                    Url = "https://aka.ms/dotnet/8.0/windowsdesktop-runtime-win-x64.exe",
                    FileName = "windowsdesktop-runtime-8-x64.exe",
                    SilentArgs = "/install /quiet /norestart",
                    SuccessExitCodes = new[] { 0, 3010, 1638 },
                    RecommendedByDefault = false,
                    Detector = () => Detect.DotnetDesktop("8", out _),
                },
            };
        }

        /// <summary>Заполняет State каждого компонента актуальным статусом.</summary>
        public static void Refresh(IEnumerable<InstallComponent> components)
        {
            foreach (var c in components)
            {
                try { c.State = c.Detector != null ? c.Detector() : ComponentState.Unknown; }
                catch { c.State = ComponentState.Unknown; }
            }
        }
    }
}
