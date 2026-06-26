namespace DriverCore.Models
{
    internal enum GpuVendor { Nvidia, Amd, Intel, Unknown }

    /// <summary>Видеоадаптер + распознанный производитель и ссылка на драйверы.</summary>
    internal sealed class GpuInfo
    {
        public string Name { get; set; } = "";
        public string PnpDeviceId { get; set; } = "";
        public string DriverVersion { get; set; } = "";
        public string DriverDate { get; set; } = "";
        public GpuVendor Vendor { get; set; } = GpuVendor.Unknown;

        public string VendorName
        {
            get
            {
                switch (Vendor)
                {
                    case GpuVendor.Nvidia: return "NVIDIA";
                    case GpuVendor.Amd: return "AMD";
                    case GpuVendor.Intel: return "Intel";
                    default: return "Неизвестно";
                }
            }
        }

        /// <summary>Официальная страница драйверов производителя (открывается в браузере).</summary>
        public string VendorDriverUrl
        {
            get
            {
                switch (Vendor)
                {
                    case GpuVendor.Nvidia: return "https://www.nvidia.com/Download/index.aspx";
                    case GpuVendor.Amd: return "https://www.amd.com/en/support";
                    case GpuVendor.Intel: return "https://www.intel.com/content/www/us/en/download-center/home.html";
                    default: return null;
                }
            }
        }
    }
}
