using System.Collections.Generic;
using System.Linq;

namespace DriverCore.Models
{
    /// <summary>Результат сканирования: список устройств, проблемные узлы, GPU и сводка.</summary>
    internal sealed class HardwareInventory
    {
        public SystemSummary System { get; set; } = new SystemSummary();
        public List<HardwareDevice> Devices { get; } = new List<HardwareDevice>();
        public List<GpuInfo> Gpus { get; } = new List<GpuInfo>();

        public int TotalDevices => Devices.Count;

        public List<HardwareDevice> Problems =>
            Devices.Where(d => d.NeedsAttention).ToList();

        public int ProblemCount => Devices.Count(d => d.NeedsAttention);

        public int MissingDriverCount =>
            Devices.Count(d => d.Health == DeviceHealth.NeedsDriver);

        public IEnumerable<IGrouping<DeviceCategory, HardwareDevice>> ByCategory()
        {
            return Devices
                .OrderBy(d => (int)d.Category)
                .GroupBy(d => d.Category);
        }
    }
}
