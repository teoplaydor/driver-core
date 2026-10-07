using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.ML.OnnxRuntime;
using SemSearch.Core;

namespace SemSearch
{
    /// <summary>
    /// DirectML.dll for the GPU path is not inside the exe (it would not fit a 30 MB upload). On first GPU use it is
    /// pulled out of Microsoft's official NuGet package with range requests (~9 MB) and checked against a pinned
    /// SHA-256; a matching DirectML.dll next to the exe is used instead on offline PCs.
    /// </summary>
    internal static class DirectMl
    {
        private const string Url = "https://api.nuget.org/v3-flatcontainer/microsoft.ai.directml/1.15.4/microsoft.ai.directml.1.15.4.nupkg";
        private const string Entry = "bin/x64-win/DirectML.dll";
        private const string Sha256 = "9c9e6d822561c6c41b90e6994b3e8857cf1d66dbfb1e0c4c799c7c89b4e92da1";
        private const long Size = 18527776;

        private static readonly object Sync = new object();
        private static bool prepared;

        public static string FilePath => Path.Combine(Settings.AppDir, "directml-1.15.4", "DirectML.dll");

        public static bool Downloaded => File.Exists(FilePath) && new FileInfo(FilePath).Length == Size;

        public static async Task EnsureAsync(Action<long, long> progress, CancellationToken ct)
        {
            if (Downloaded) return;
            string local = Path.Combine(AppContext.BaseDirectory, "DirectML.dll");
            if (File.Exists(local) && Hash(local) == Sha256)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(FilePath));
                File.Copy(local, FilePath, true);
                return;
            }
            // SEMSEARCH_DIRECTML_URL: a mirror of the same package (also used by the tests).
            string url = Environment.GetEnvironmentVariable("SEMSEARCH_DIRECTML_URL") ?? Url;
            await RemoteZip.ExtractAsync(url, Entry, FilePath, Sha256, progress, ct);
        }

        /// <summary>
        /// Puts DirectML.dll next to the extracted onnxruntime.dll, where its delay-load hook looks for it, and loads
        /// it: a name-only load then also resolves to this copy and never to the older one in System32.
        /// </summary>
        public static void Prepare()
        {
            lock (Sync)
            {
                if (prepared) return;
                if (!Downloaded) throw new InvalidOperationException("компонент DirectML не скачан");
                string use = FilePath;
                try
                {
                    _ = OrtEnv.Instance(); // makes sure onnxruntime.dll is loaded
                    string ortDir = Path.GetDirectoryName(ModulePath("onnxruntime.dll"));
                    string target = Path.Combine(ortDir, "DirectML.dll");
                    if (!File.Exists(target) || new FileInfo(target).Length != Size) File.Copy(FilePath, target, true);
                    use = target;
                }
                catch (Exception)
                {
                    // read-only extraction folder: the preload below still wins over System32
                }
                NativeLibrary.Load(use);
                prepared = true;
            }
        }

        private static string Hash(string file)
        {
            using (var s = File.OpenRead(file)) return Convert.ToHexString(SHA256.HashData(s)).ToLowerInvariant();
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr GetModuleHandleW(string name);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetModuleFileNameW(IntPtr module, char[] buffer, int size);

        private static string ModulePath(string name)
        {
            IntPtr h = GetModuleHandleW(name);
            if (h == IntPtr.Zero) throw new InvalidOperationException(name + " не загружен");
            var buf = new char[32768];
            int n = GetModuleFileNameW(h, buf, buf.Length);
            if (n <= 0) throw new InvalidOperationException("путь к " + name + " не определён");
            return new string(buf, 0, n);
        }
    }
}
