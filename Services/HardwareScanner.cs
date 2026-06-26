using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Management;
using DriverCore.Models;

namespace DriverCore.Services
{
    /// <summary>
    /// Полная инвентаризация оборудования через WMI и определение проблемных
    /// устройств (нет драйвера / отключено / ошибка). Тяжёлые классы
    /// (Win32_PnPEntity, Win32_PnPSignedDriver) запрашиваются один раз и кешируются.
    /// </summary>
    internal sealed class HardwareScanner
    {
        private sealed class PnpRecord
        {
            public int ErrorCode = -1;
            public string Name = "";
            public string PnpClass = "";
            public string Service = "";
            public string Manufacturer = "";
        }

        private sealed class DriverRecord
        {
            public string Version = "";
            public string Date = "";
            public string Provider = "";
        }

        private readonly Dictionary<string, PnpRecord> _pnp = new Dictionary<string, PnpRecord>(StringComparer.OrdinalIgnoreCase);
        private readonly List<KeyValuePair<string, PnpRecord>> _pnpList = new List<KeyValuePair<string, PnpRecord>>();
        private readonly Dictionary<string, DriverRecord> _drivers = new Dictionary<string, DriverRecord>(StringComparer.OrdinalIgnoreCase);
        private readonly HashSet<string> _added = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        public HardwareInventory Scan(Action<string> log = null)
        {
            void L(string m) => log?.Invoke(m);
            var inv = new HardwareInventory();

            L("Считываю список устройств (PnP)…");
            CachePnpEntities();
            L("Считываю сведения о драйверах…");
            CacheSignedDrivers();

            L("Сводка о системе…");
            inv.System = ReadSystemSummary();

            L("Процессор…");
            ScanProcessors(inv);
            L("Видеокарта…");
            ScanGpus(inv);
            L("Оперативная память…");
            ScanMemory(inv);
            L("Накопители…");
            ScanStorage(inv);
            L("Сеть…");
            ScanNetwork(inv);
            L("Звук…");
            ScanAudio(inv);
            L("Материнская плата и BIOS…");
            ScanBoardAndBios(inv);
            L("Мониторы…");
            ScanMonitors(inv);
            L("USB-контроллеры…");
            ScanUsb(inv);

            L("Поиск устройств без драйверов…");
            ScanProblemDevices(inv);

            if (inv.System.PrimaryGpu == "" && inv.Gpus.Count > 0)
                inv.System.PrimaryGpu = inv.Gpus[0].Name;

            L($"Готово: {inv.TotalDevices} устройств, проблемных — {inv.ProblemCount}.");
            return inv;
        }

        // ───────────────────────── Кэш ─────────────────────────

        private void CachePnpEntities()
        {
            foreach (var o in Query("SELECT Name, PNPDeviceID, PNPClass, ConfigManagerErrorCode, Service, Manufacturer FROM Win32_PnPEntity"))
            {
                string id = S(o, "PNPDeviceID");
                if (string.IsNullOrEmpty(id)) continue;
                var rec = new PnpRecord
                {
                    ErrorCode = I(o, "ConfigManagerErrorCode", 0),
                    Name = S(o, "Name"),
                    PnpClass = S(o, "PNPClass"),
                    Service = S(o, "Service"),
                    Manufacturer = S(o, "Manufacturer"),
                };
                _pnp[id] = rec;
                _pnpList.Add(new KeyValuePair<string, PnpRecord>(id, rec));
            }
        }

        private void CacheSignedDrivers()
        {
            foreach (var o in Query("SELECT DeviceID, DriverVersion, DriverDate, DriverProviderName FROM Win32_PnPSignedDriver"))
            {
                string id = S(o, "DeviceID");
                if (string.IsNullOrEmpty(id) || _drivers.ContainsKey(id)) continue;
                _drivers[id] = new DriverRecord
                {
                    Version = S(o, "DriverVersion"),
                    Date = DateStr(o, "DriverDate"),
                    Provider = S(o, "DriverProviderName"),
                };
            }
        }

        // ───────────────────────── Категории ─────────────────────────

        private SystemSummary ReadSystemSummary()
        {
            var s = new SystemSummary();
            foreach (var o in Query("SELECT Caption, Version, OSArchitecture FROM Win32_OperatingSystem"))
            {
                s.OsName = S(o, "Caption").Replace("Microsoft ", "");
                s.OsVersion = S(o, "Version");
                s.OsArchitecture = S(o, "OSArchitecture");
                break;
            }
            foreach (var o in Query("SELECT Manufacturer, Model, TotalPhysicalMemory FROM Win32_ComputerSystem"))
            {
                s.Manufacturer = S(o, "Manufacturer");
                s.Model = S(o, "Model");
                s.RamGb = Math.Round(UL(o, "TotalPhysicalMemory") / 1073741824.0, 1);
                break;
            }
            foreach (var o in Query("SELECT Name, NumberOfLogicalProcessors FROM Win32_Processor"))
            {
                s.CpuName = S(o, "Name");
                s.LogicalCores = I(o, "NumberOfLogicalProcessors", 0);
                break;
            }
            return s;
        }

        private void ScanProcessors(HardwareInventory inv)
        {
            foreach (var o in Query("SELECT Name, Manufacturer, NumberOfCores, NumberOfLogicalProcessors, MaxClockSpeed FROM Win32_Processor"))
            {
                int cores = I(o, "NumberOfCores", 0);
                int threads = I(o, "NumberOfLogicalProcessors", 0);
                double ghz = I(o, "MaxClockSpeed", 0) / 1000.0;
                Add(inv, new HardwareDevice
                {
                    Category = DeviceCategory.Processor,
                    Name = S(o, "Name"),
                    Manufacturer = S(o, "Manufacturer"),
                    Detail = $"{cores} ядер / {threads} потоков · {ghz:0.0} ГГц",
                    Health = DeviceHealth.Ok,
                    HealthText = "Работает",
                });
            }
        }

        private void ScanGpus(HardwareInventory inv)
        {
            foreach (var o in Query("SELECT Name, AdapterCompatibility, AdapterRAM, DriverVersion, DriverDate, PNPDeviceID, CurrentHorizontalResolution, CurrentVerticalResolution, ConfigManagerErrorCode FROM Win32_VideoController"))
            {
                string name = S(o, "Name");
                string pnp = S(o, "PNPDeviceID");
                string ver = S(o, "DriverVersion");
                string date = DateStr(o, "DriverDate");
                int hres = I(o, "CurrentHorizontalResolution", 0);
                int vres = I(o, "CurrentVerticalResolution", 0);

                var vendor = DetectVendor(pnp, S(o, "AdapterCompatibility"), name);
                var gpu = new GpuInfo
                {
                    Name = name,
                    PnpDeviceId = pnp,
                    DriverVersion = ver,
                    DriverDate = date,
                    Vendor = vendor,
                };
                inv.Gpus.Add(gpu);

                int code = ResolveCode(pnp);
                var health = code > 0 ? CmErrors.Classify(code) : DeviceHealth.Ok;
                string healthText = code > 0 ? CmErrors.Describe(code) : "Работает";

                // Базовый видеодрайвер Windows = дискретная карта без родного драйвера.
                bool basic = name.IndexOf("Basic Display", StringComparison.OrdinalIgnoreCase) >= 0
                          || name.IndexOf("Standard VGA", StringComparison.OrdinalIgnoreCase) >= 0
                          || name.IndexOf("базов", StringComparison.OrdinalIgnoreCase) >= 0;
                if (basic)
                {
                    health = DeviceHealth.NeedsDriver;
                    healthText = "Базовый видеодрайвер Windows — нужен драйвер видеокарты";
                }

                string res = (hres > 0 && vres > 0) ? $"{hres}×{vres}" : "";
                Add(inv, new HardwareDevice
                {
                    Category = DeviceCategory.Graphics,
                    Name = name,
                    Manufacturer = gpu.VendorName,
                    Detail = string.Join("  ·  ", new[] { res }.Where(x => x != "")),
                    PnpDeviceId = pnp,
                    ConfigManagerErrorCode = code,
                    Health = health,
                    HealthText = healthText,
                    DriverVersion = ver,
                    DriverDate = date,
                });
            }
        }

        private void ScanMemory(HardwareInventory inv)
        {
            foreach (var o in Query("SELECT BankLabel, DeviceLocator, Capacity, Speed, ConfiguredClockSpeed, Manufacturer, PartNumber, SMBIOSMemoryType FROM Win32_PhysicalMemory"))
            {
                double gb = Math.Round(UL(o, "Capacity") / 1073741824.0, 1);
                int speed = I(o, "ConfiguredClockSpeed", 0);
                if (speed <= 0) speed = I(o, "Speed", 0);
                string type = MemoryType(I(o, "SMBIOSMemoryType", 0));
                string slot = S(o, "DeviceLocator");
                Add(inv, new HardwareDevice
                {
                    Category = DeviceCategory.Memory,
                    Name = $"{gb:0.#} ГБ {type}".Trim() + (speed > 0 ? $" · {speed} МГц" : ""),
                    Manufacturer = S(o, "Manufacturer"),
                    Detail = string.Join("  ·  ", new[] { slot, S(o, "PartNumber") }.Where(x => x != "")),
                    Health = DeviceHealth.Ok,
                    HealthText = "Работает",
                });
            }
        }

        private void ScanStorage(HardwareInventory inv)
        {
            var media = ReadPhysicalDiskMedia();
            foreach (var o in Query("SELECT Model, InterfaceType, Size, MediaType, PNPDeviceID FROM Win32_DiskDrive"))
            {
                double gb = Math.Round(UL(o, "Size") / 1000000000.0, 0);
                string model = S(o, "Model");
                string bus = S(o, "InterfaceType");
                string kind = media.TryGetValue(model, out var k) ? k : "";
                string pnp = S(o, "PNPDeviceID");
                var drv = LookupDriver(pnp);
                int code = ResolveCode(pnp);

                Add(inv, new HardwareDevice
                {
                    Category = DeviceCategory.Storage,
                    Name = model,
                    Manufacturer = "",
                    Detail = string.Join("  ·  ", new[] { $"{gb:0} ГБ", kind, bus }.Where(x => x != "")),
                    PnpDeviceId = pnp,
                    ConfigManagerErrorCode = code,
                    Health = code > 0 ? CmErrors.Classify(code) : DeviceHealth.Ok,
                    HealthText = code > 0 ? CmErrors.Describe(code) : "Работает",
                    DriverVersion = drv?.Version ?? "",
                    DriverDate = drv?.Date ?? "",
                    DriverProvider = drv?.Provider ?? "",
                });
            }
        }

        private void ScanNetwork(HardwareInventory inv)
        {
            foreach (var o in Query("SELECT Name, Manufacturer, MACAddress, NetConnectionStatus, Speed, PNPDeviceID FROM Win32_NetworkAdapter WHERE PhysicalAdapter=TRUE"))
            {
                string pnp = S(o, "PNPDeviceID");
                if (string.IsNullOrEmpty(pnp)) continue;
                var drv = LookupDriver(pnp);
                int code = ResolveCode(pnp);
                string mac = S(o, "MACAddress");
                string status = ConnStatus(I(o, "NetConnectionStatus", -1));

                Add(inv, new HardwareDevice
                {
                    Category = DeviceCategory.Network,
                    Name = S(o, "Name"),
                    Manufacturer = S(o, "Manufacturer"),
                    Detail = string.Join("  ·  ", new[] { status, mac }.Where(x => x != "")),
                    PnpDeviceId = pnp,
                    ConfigManagerErrorCode = code,
                    Health = code > 0 ? CmErrors.Classify(code) : DeviceHealth.Ok,
                    HealthText = code > 0 ? CmErrors.Describe(code) : "Работает",
                    DriverVersion = drv?.Version ?? "",
                    DriverDate = drv?.Date ?? "",
                    DriverProvider = drv?.Provider ?? "",
                });
            }
        }

        private void ScanAudio(HardwareInventory inv)
        {
            foreach (var o in Query("SELECT Name, Manufacturer, PNPDeviceID, ConfigManagerErrorCode FROM Win32_SoundDevice"))
            {
                string pnp = S(o, "PNPDeviceID");
                var drv = LookupDriver(pnp);
                int code = ResolveCode(pnp);
                Add(inv, new HardwareDevice
                {
                    Category = DeviceCategory.Audio,
                    Name = S(o, "Name"),
                    Manufacturer = S(o, "Manufacturer"),
                    Detail = "",
                    PnpDeviceId = pnp,
                    ConfigManagerErrorCode = code,
                    Health = code > 0 ? CmErrors.Classify(code) : DeviceHealth.Ok,
                    HealthText = code > 0 ? CmErrors.Describe(code) : "Работает",
                    DriverVersion = drv?.Version ?? "",
                    DriverDate = drv?.Date ?? "",
                    DriverProvider = drv?.Provider ?? "",
                });
            }
        }

        private void ScanBoardAndBios(HardwareInventory inv)
        {
            foreach (var o in Query("SELECT Manufacturer, Product, Version FROM Win32_BaseBoard"))
            {
                Add(inv, new HardwareDevice
                {
                    Category = DeviceCategory.Motherboard,
                    Name = string.Join(" ", new[] { S(o, "Manufacturer"), S(o, "Product") }).Trim(),
                    Manufacturer = S(o, "Manufacturer"),
                    Detail = S(o, "Version"),
                    Health = DeviceHealth.Ok,
                    HealthText = "—",
                });
            }
            foreach (var o in Query("SELECT Manufacturer, SMBIOSBIOSVersion, ReleaseDate FROM Win32_BIOS"))
            {
                Add(inv, new HardwareDevice
                {
                    Category = DeviceCategory.Firmware,
                    Name = S(o, "SMBIOSBIOSVersion"),
                    Manufacturer = S(o, "Manufacturer"),
                    Detail = DateStr(o, "ReleaseDate"),
                    Health = DeviceHealth.Ok,
                    HealthText = "—",
                });
            }
        }

        private void ScanMonitors(HardwareInventory inv)
        {
            foreach (var o in Query("SELECT Name, MonitorManufacturer, PNPDeviceID, ScreenWidth, ScreenHeight FROM Win32_DesktopMonitor"))
            {
                string name = S(o, "Name");
                if (string.IsNullOrEmpty(name)) continue;
                string pnp = S(o, "PNPDeviceID");
                int code = ResolveCode(pnp);
                Add(inv, new HardwareDevice
                {
                    Category = DeviceCategory.Monitor,
                    Name = name,
                    Manufacturer = S(o, "MonitorManufacturer"),
                    Detail = "",
                    PnpDeviceId = pnp,
                    ConfigManagerErrorCode = code,
                    Health = code > 0 ? CmErrors.Classify(code) : DeviceHealth.Ok,
                    HealthText = code > 0 ? CmErrors.Describe(code) : "Работает",
                });
            }
        }

        private void ScanUsb(HardwareInventory inv)
        {
            foreach (var o in Query("SELECT Name, Manufacturer, PNPDeviceID, ConfigManagerErrorCode FROM Win32_USBController"))
            {
                string pnp = S(o, "PNPDeviceID");
                var drv = LookupDriver(pnp);
                int code = ResolveCode(pnp);
                Add(inv, new HardwareDevice
                {
                    Category = DeviceCategory.UsbController,
                    Name = S(o, "Name"),
                    Manufacturer = S(o, "Manufacturer"),
                    Detail = "",
                    PnpDeviceId = pnp,
                    ConfigManagerErrorCode = code,
                    Health = code > 0 ? CmErrors.Classify(code) : DeviceHealth.Ok,
                    HealthText = code > 0 ? CmErrors.Describe(code) : "Работает",
                    DriverVersion = drv?.Version ?? "",
                    DriverDate = drv?.Date ?? "",
                    DriverProvider = drv?.Provider ?? "",
                });
            }
        }

        /// <summary>Добирает все устройства с ненулевым кодом ошибки, ещё не попавшие в список.</summary>
        private void ScanProblemDevices(HardwareInventory inv)
        {
            foreach (var kv in _pnpList)
            {
                string pnp = kv.Key;
                var rec = kv.Value;
                if (rec.ErrorCode <= 0) continue;
                if (_added.Contains(pnp)) continue;

                var drv = LookupDriver(pnp);
                Add(inv, new HardwareDevice
                {
                    Category = MapPnpClass(rec.PnpClass),
                    Name = string.IsNullOrEmpty(rec.Name) ? "Неизвестное устройство" : rec.Name,
                    Manufacturer = rec.Manufacturer,
                    Detail = "",
                    PnpDeviceId = pnp,
                    ConfigManagerErrorCode = rec.ErrorCode,
                    Health = CmErrors.Classify(rec.ErrorCode),
                    HealthText = CmErrors.Describe(rec.ErrorCode),
                    DriverVersion = drv?.Version ?? "",
                    DriverDate = drv?.Date ?? "",
                    DriverProvider = drv?.Provider ?? "",
                });
            }
        }

        // ───────────────────────── Вспомогательное ─────────────────────────

        private void Add(HardwareInventory inv, HardwareDevice d)
        {
            inv.Devices.Add(d);
            if (!string.IsNullOrEmpty(d.PnpDeviceId))
                _added.Add(d.PnpDeviceId);
        }

        private int ResolveCode(string pnp)
        {
            if (!string.IsNullOrEmpty(pnp) && _pnp.TryGetValue(pnp, out var rec))
                return rec.ErrorCode;
            return 0;
        }

        private DriverRecord LookupDriver(string pnp)
        {
            if (!string.IsNullOrEmpty(pnp) && _drivers.TryGetValue(pnp, out var rec))
                return rec;
            return null;
        }

        private static GpuVendor DetectVendor(string pnp, string adapterCompat, string name)
        {
            string id = (pnp ?? "").ToUpperInvariant();
            int i = id.IndexOf("VEN_", StringComparison.Ordinal);
            string ven = (i >= 0 && id.Length >= i + 8) ? id.Substring(i + 4, 4) : "";
            if (ven == "10DE") return GpuVendor.Nvidia;
            if (ven == "1002") return GpuVendor.Amd;
            if (ven == "8086") return GpuVendor.Intel;

            string hay = ((adapterCompat ?? "") + " " + (name ?? "")).ToUpperInvariant();
            if (hay.Contains("NVIDIA") || hay.Contains("GEFORCE")) return GpuVendor.Nvidia;
            if (hay.Contains("AMD") || hay.Contains("RADEON") || hay.Contains("ATI")) return GpuVendor.Amd;
            if (hay.Contains("INTEL")) return GpuVendor.Intel;
            return GpuVendor.Unknown;
        }

        private static DeviceCategory MapPnpClass(string cls)
        {
            switch ((cls ?? "").ToUpperInvariant())
            {
                case "DISPLAY": return DeviceCategory.Graphics;
                case "NET": return DeviceCategory.Network;
                case "MEDIA":
                case "AUDIOENDPOINT":
                case "AUDIO": return DeviceCategory.Audio;
                case "DISKDRIVE":
                case "SCSIADAPTER":
                case "HDC": return DeviceCategory.Storage;
                case "USB":
                case "USBDEVICE": return DeviceCategory.UsbController;
                case "PROCESSOR": return DeviceCategory.Processor;
                case "MONITOR": return DeviceCategory.Monitor;
                case "HIDCLASS":
                case "KEYBOARD":
                case "MOUSE": return DeviceCategory.Input;
                case "SYSTEM": return DeviceCategory.System;
                default: return DeviceCategory.OtherDevice;
            }
        }

        private Dictionary<string, string> ReadPhysicalDiskMedia()
        {
            // MSFT_PhysicalDisk различает SSD/HDD/NVMe (Win32_DiskDrive — нет).
            var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            try
            {
                var scope = new ManagementScope(@"\\.\root\Microsoft\Windows\Storage");
                scope.Connect();
                var q = new ObjectQuery("SELECT FriendlyName, MediaType, BusType FROM MSFT_PhysicalDisk");
                using (var s = new ManagementObjectSearcher(scope, q))
                foreach (var o in s.Get())
                {
                    string fn = S(o, "FriendlyName");
                    if (string.IsNullOrEmpty(fn)) continue;
                    int mt = I(o, "MediaType", 0);
                    int bt = I(o, "BusType", 0);
                    string media = mt == 4 ? "SSD" : mt == 3 ? "HDD" : "";
                    if (bt == 17) media = string.IsNullOrEmpty(media) ? "NVMe" : media + " NVMe";
                    if (!string.IsNullOrEmpty(media)) map[fn] = media;
                }
            }
            catch { /* класс хранилища недоступен — не критично */ }
            return map;
        }

        private static string MemoryType(int smbios)
        {
            switch (smbios)
            {
                case 20: return "DDR";
                case 21: return "DDR2";
                case 24: return "DDR3";
                case 26: return "DDR4";
                case 34: return "DDR5";
                default: return "RAM";
            }
        }

        private static string ConnStatus(int code)
        {
            switch (code)
            {
                case 2: return "Подключено";
                case 0: return "Отключено";
                case 7: return "Кабель не подключён";
                default: return "";
            }
        }

        // ───────────────────────── WMI-обёртки ─────────────────────────

        private static IEnumerable<ManagementBaseObject> Query(string wql)
        {
            ManagementObjectCollection col = null;
            try
            {
                var searcher = new ManagementObjectSearcher("root\\cimv2", wql);
                col = searcher.Get();
            }
            catch
            {
                yield break; // класс недоступен — пропускаем категорию
            }
            foreach (var o in col)
                yield return o;
        }

        private static string S(ManagementBaseObject o, string prop)
        {
            try
            {
                var v = o[prop];
                if (v == null) return "";
                if (v is string[] arr) return string.Join(", ", arr);
                return v.ToString().Trim();
            }
            catch { return ""; }
        }

        private static int I(ManagementBaseObject o, string prop, int def)
        {
            try
            {
                var v = o[prop];
                return v == null ? def : Convert.ToInt32(v);
            }
            catch { return def; }
        }

        private static ulong UL(ManagementBaseObject o, string prop)
        {
            try
            {
                var v = o[prop];
                return v == null ? 0UL : Convert.ToUInt64(v);
            }
            catch { return 0UL; }
        }

        private static string DateStr(ManagementBaseObject o, string prop)
        {
            try
            {
                var v = o[prop] as string;
                if (string.IsNullOrEmpty(v)) return "";
                var dt = ManagementDateTimeConverter.ToDateTime(v);
                return dt.ToString("dd.MM.yyyy", CultureInfo.InvariantCulture);
            }
            catch { return ""; }
        }
    }
}
