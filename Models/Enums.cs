namespace DriverCore.Models
{
    /// <summary>Категория оборудования для группировки в интерфейсе.</summary>
    internal enum DeviceCategory
    {
        Processor,
        Graphics,
        Memory,
        Storage,
        Network,
        Audio,
        Motherboard,
        Firmware,
        Monitor,
        UsbController,
        Input,
        System,
        OtherDevice,
    }

    /// <summary>Состояние устройства по данным диспетчера устройств.</summary>
    internal enum DeviceHealth
    {
        Ok,
        NeedsDriver,
        Disabled,
        Error,
        NotConnected,
        Unknown,
    }

    /// <summary>Состояние устанавливаемого компонента/софта.</summary>
    internal enum ComponentState
    {
        Unknown,
        Installed,
        Missing,
    }

    internal static class Enums
    {
        public static string Title(this DeviceCategory c)
        {
            switch (c)
            {
                case DeviceCategory.Processor: return "Процессор";
                case DeviceCategory.Graphics: return "Видеокарта";
                case DeviceCategory.Memory: return "Оперативная память";
                case DeviceCategory.Storage: return "Накопители";
                case DeviceCategory.Network: return "Сеть";
                case DeviceCategory.Audio: return "Звук";
                case DeviceCategory.Motherboard: return "Материнская плата";
                case DeviceCategory.Firmware: return "BIOS / UEFI";
                case DeviceCategory.Monitor: return "Мониторы";
                case DeviceCategory.UsbController: return "USB-контроллеры";
                case DeviceCategory.Input: return "Устройства ввода";
                case DeviceCategory.System: return "Системные устройства";
                default: return "Прочие устройства";
            }
        }

        /// <summary>Глиф Segoe MDL2 Assets для категории.</summary>
        public static string Glyph(this DeviceCategory c)
        {
            switch (c)
            {
                case DeviceCategory.Processor: return "";
                case DeviceCategory.Graphics: return "";
                case DeviceCategory.Memory: return "";
                case DeviceCategory.Storage: return "";
                case DeviceCategory.Network: return "";
                case DeviceCategory.Audio: return "";
                case DeviceCategory.Motherboard: return "";
                case DeviceCategory.Firmware: return "";
                case DeviceCategory.Monitor: return "";
                case DeviceCategory.UsbController: return "";
                case DeviceCategory.Input: return "";
                case DeviceCategory.System: return "";
                default: return "";
            }
        }

        public static string Title(this DeviceHealth h)
        {
            switch (h)
            {
                case DeviceHealth.Ok: return "Работает";
                case DeviceHealth.NeedsDriver: return "Нет драйвера";
                case DeviceHealth.Disabled: return "Отключено";
                case DeviceHealth.Error: return "Ошибка";
                case DeviceHealth.NotConnected: return "Не подключено";
                default: return "Неизвестно";
            }
        }
    }
}
