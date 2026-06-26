using System.Collections.Generic;

namespace DriverCore.Models
{
    /// <summary>Краткая сводка о ПК для шапки отчёта.</summary>
    internal sealed class SystemSummary
    {
        public string OsName { get; set; } = "";
        public string OsVersion { get; set; } = "";
        public string OsArchitecture { get; set; } = "";
        public string Manufacturer { get; set; } = "";
        public string Model { get; set; } = "";
        public string CpuName { get; set; } = "";
        public int LogicalCores { get; set; }
        public double RamGb { get; set; }
        public string PrimaryGpu { get; set; } = "";

        public IEnumerable<KeyValuePair<string, string>> AsRows()
        {
            yield return new KeyValuePair<string, string>("Система", $"{OsName} ({OsArchitecture})");
            yield return new KeyValuePair<string, string>("Версия", OsVersion);
            yield return new KeyValuePair<string, string>("Компьютер", string.Join(" ", new[] { Manufacturer, Model }).Trim());
            yield return new KeyValuePair<string, string>("Процессор", CpuName);
            yield return new KeyValuePair<string, string>("Память", $"{RamGb:0.#} ГБ");
            yield return new KeyValuePair<string, string>("Видеокарта", PrimaryGpu);
        }
    }
}
