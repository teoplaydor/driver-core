using System.Linq;

namespace DriverCore.Models
{
    /// <summary>Одно устройство в инвентаризации железа.</summary>
    internal sealed class HardwareDevice
    {
        public DeviceCategory Category { get; set; }
        public string Name { get; set; } = "";
        public string Manufacturer { get; set; } = "";
        public string Detail { get; set; } = "";

        public string PnpDeviceId { get; set; } = "";
        public int ConfigManagerErrorCode { get; set; } = -1; // -1 = неизвестно

        public DeviceHealth Health { get; set; } = DeviceHealth.Unknown;
        public string HealthText { get; set; } = "";

        public string DriverVersion { get; set; } = "";
        public string DriverDate { get; set; } = "";
        public string DriverProvider { get; set; } = "";

        public bool NeedsAttention =>
            Health == DeviceHealth.NeedsDriver ||
            Health == DeviceHealth.Error ||
            Health == DeviceHealth.Disabled;

        public string DriverSummary
        {
            get
            {
                if (string.IsNullOrEmpty(DriverVersion) && string.IsNullOrEmpty(DriverDate))
                    return "—";
                string v = string.IsNullOrEmpty(DriverVersion) ? "" : "v" + DriverVersion;
                string d = string.IsNullOrEmpty(DriverDate) ? "" : DriverDate;
                return string.Join("  ·  ", new[] { v, d, DriverProvider }.Where(s => !string.IsNullOrEmpty(s)));
            }
        }
    }
}
