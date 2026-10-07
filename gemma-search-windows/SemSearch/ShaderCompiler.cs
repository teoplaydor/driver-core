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
    /// DXC (dxcompiler.dll + dxil.dll) for WebGPU on D3D12. Without it Dawn compiles shaders with FXC from Windows, which
    /// works but is slower on first use; with it shaders compile fast. The pair is not in the exe (it would not fit a
    /// 30 MB upload): on first GPU use it is pulled out of the onnxruntime-webgpu 1.27.0 wheel on PyPI by range requests
    /// (~8 MB) and checked against pinned SHA-256 (Microsoft-signed builds). A matching pair next to the exe also works.
    /// </summary>
    internal static class ShaderCompiler
    {
        private static readonly (string Url, string Compiler, string Validator)[] Wheels =
        {
            ("https://files.pythonhosted.org/packages/df/28/016260c51c877ba5b3eba823b43107e894659e73e0ecf250eb07e801e3c2/"
             + "onnxruntime_webgpu-1.27.0-cp312-cp312-win_amd64.whl",
             "450adfba61a72ec3f8feaec38caa371692974b91ca20470a675214d30b7bf387",
             "51326f698ac24e45d1d2f125868c66252b0c1c47b79b1427655c9ea50cfd4869"),
            ("https://files.pythonhosted.org/packages/dd/f3/6294f9617e97035771593d604729a420a848150054cc1c422a47a4915412/"
             + "onnxruntime_webgpu-1.27.0-cp313-cp313-win_amd64.whl",
             "b353a15a8d39b4138ab9dee7c0ed633c01db95c6e230974d8f4cd48d0d4666a4",
             "a07fa01608c4a0e6c4e06a0dea9bbe770bd900608e0b37a4429f6f9833479c1a"),
        };

        private static readonly object Sync = new object();
        private static bool prepared;

        private static string Dir => Path.Combine(Settings.AppDir, "dxc-1.27.0");
        private static string CompilerPath => Path.Combine(Dir, "dxcompiler.dll");
        private static string ValidatorPath => Path.Combine(Dir, "dxil.dll");

        public static bool Ready => File.Exists(CompilerPath) && File.Exists(ValidatorPath);

        public static async Task EnsureAsync(Action<string> status, Action<string, long, long> progress, CancellationToken ct)
        {
            if (Ready) return;
            // the exe's own folder: in a single-file app AppContext.BaseDirectory is the extraction folder
            string exeDir = Path.GetDirectoryName(Environment.ProcessPath) ?? "";
            string c = Path.Combine(exeDir, "dxcompiler.dll"), v = Path.Combine(exeDir, "dxil.dll");
            if (File.Exists(c) && File.Exists(v))
            {
                string hc = Hash(c), hv = Hash(v);
                if (Array.Exists(Wheels, w => w.Compiler == hc && w.Validator == hv))
                {
                    Directory.CreateDirectory(Dir);
                    File.Copy(c, CompilerPath, true);
                    File.Copy(v, ValidatorPath, true);
                    return;
                }
            }
            var sources = Array.ConvertAll(Wheels, w => new RemoteZip.Source
            {
                Url = w.Url,
                Entries = new[]
                {
                    new RemoteZip.Entry { Name = "onnxruntime/capi/dxcompiler.dll", Sha256 = w.Compiler, Dest = CompilerPath },
                    new RemoteZip.Entry { Name = "onnxruntime/capi/dxil.dll", Sha256 = w.Validator, Dest = ValidatorPath },
                }
            });
            await RemoteZip.ExtractFirstAsync(sources, status, progress, ct);
        }

        /// <summary>Puts the pair next to the extracted onnxruntime.dll, where Dawn looks for it when the GPU starts.</summary>
        public static void Prepare()
        {
            lock (Sync)
            {
                if (prepared || !Ready) return;
                _ = OrtEnv.Instance(); // makes sure onnxruntime.dll is loaded
                string ortDir = Path.GetDirectoryName(ModulePath("onnxruntime.dll"));
                foreach (var f in new[] { CompilerPath, ValidatorPath })
                {
                    string target = Path.Combine(ortDir, Path.GetFileName(f));
                    if (!File.Exists(target) || new FileInfo(target).Length != new FileInfo(f).Length) File.Copy(f, target, true);
                }
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
