using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;

namespace SemSearch
{
    /// <summary>User settings in %LOCALAPPDATA%\SemSearch\settings.json.</summary>
    public sealed class Settings
    {
        public static string AppDir { get; private set; } = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SemSearch");

        /// <summary>Verification modes run on a throwaway profile, never on the user's model and index.</summary>
        internal static void UseScratchProfile(string name) => AppDir = Path.Combine(Path.GetTempPath(), name);

        public List<string> Folders { get; set; } = DefaultFolders();
        public bool IndexDocuments { get; set; } = true;
        public int PhotoBudget { get; set; } = 140;
        public int Accel { get; set; } = 0;
        public int Threads { get; set; } = 0;
        public int Batch { get; set; } = 1;
        public bool AccelChosen { get; set; }
        public bool GpuBroken { get; set; }
        public bool GpuProbe { get; set; }
        public int BridgeMode { get; set; } = 0;
        public int Dims { get; set; } = 768;
        public string Repo { get; set; } = SemSearch.Core.HfRepo.DefaultRepo;
        public string Token { get; set; } = "";
        public bool Vision { get; set; } = true;
        public string ModelSignature { get; set; } = "";
        public bool TrayHintShown { get; set; }

        private static List<string> DefaultFolders()
        {
            var list = new List<string>();
            foreach (var f in new[] { Environment.SpecialFolder.MyPictures, Environment.SpecialFolder.Desktop,
                         Environment.SpecialFolder.MyDocuments })
            {
                string p = Environment.GetFolderPath(f);
                if (!string.IsNullOrEmpty(p) && !list.Contains(p)) list.Add(p);
            }
            string downloads = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
            if (Directory.Exists(downloads) && !list.Contains(downloads)) list.Add(downloads);
            return list;
        }

        private static string FilePath => Path.Combine(AppDir, "settings.json");

        public static Settings Load()
        {
            try
            {
                if (File.Exists(FilePath)) return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
            }
            catch (Exception)
            {
                // corrupted settings: start fresh
            }
            return new Settings();
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(AppDir);
                string tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
                File.Copy(tmp, FilePath, true);
                File.Delete(tmp);
            }
            catch (Exception)
            {
                // read-only profile etc.: settings simply won't persist
            }
        }
    }
}
