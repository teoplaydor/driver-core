using System;

namespace DriverCore.Models
{
    /// <summary>
    /// Устанавливаемый компонент: рантайм (VC++, DirectX, .NET) или доп. софт.
    /// Содержит ссылку, тихие ключи установки, коды успеха и делегат проверки наличия.
    /// </summary>
    internal sealed class InstallComponent
    {
        public string Key { get; set; } = "";
        public string DisplayName { get; set; } = "";
        public string Description { get; set; } = "";

        public string Url { get; set; } = "";
        public string FileName { get; set; } = "";
        public string SilentArgs { get; set; } = "";
        public int[] SuccessExitCodes { get; set; } = new[] { 0 };

        /// <summary>Запасная страница, открываемая в браузере, если прямая загрузка не удалась.</summary>
        public string FallbackPageUrl { get; set; }

        /// <summary>Проверка, установлен ли компонент. Может быть null (тогда состояние Unknown).</summary>
        public Func<ComponentState> Detector { get; set; }

        /// <summary>Отмечен ли по умолчанию (рекомендуется).</summary>
        public bool RecommendedByDefault { get; set; } = true;

        // Заполняется во время работы:
        public ComponentState State { get; set; } = ComponentState.Unknown;
        public string DetectedVersion { get; set; } = "";

        public bool IsSuccess(int exitCode) => Array.IndexOf(SuccessExitCodes, exitCode) >= 0;
    }
}
