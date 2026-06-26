using System.Drawing;

namespace DriverCore.Theme
{
    /// <summary>
    /// Кофейная тёмная палитра. Эспрессо-фон, мокко-поверхности,
    /// карамельный акцент, кремовый текст. Никакого серого/чёрного.
    /// Значения выверены по контрасту (textPrimary/windowBg ≈ 13:1 AAA,
    /// тёмный текст на акценте ≈ 7:1).
    /// </summary>
    internal static class Palette
    {
        private static Color Hex(string h)
        {
            return ColorTranslator.FromHtml(h);
        }

        // Фон окна и заголовок
        public static readonly Color WindowBg = Hex("#1E1712"); // эспрессо
        public static readonly Color TitleBar = Hex("#160F0A"); // тёмный кофе

        // Поверхности (панели, карточки, строки)
        public static readonly Color Surface = Hex("#2A211A"); // мокко
        public static readonly Color SurfaceAlt = Hex("#2F261E"); // чётные строки
        public static readonly Color SurfaceHover = Hex("#362A20"); // hover / latte-foam
        public static readonly Color SurfaceActive = Hex("#43352A"); // pressed / выбрано

        // Акцент — тёплая карамель/латте
        public static readonly Color Accent = Hex("#C8A06A");
        public static readonly Color AccentHover = Hex("#DBB67E");
        public static readonly Color AccentPressed = Hex("#B58E58");
        public static readonly Color AccentText = Hex("#241B12"); // тёмный текст на акценте

        // Текст
        public static readonly Color TextPrimary = Hex("#F2E6D4"); // крем
        public static readonly Color TextSecondary = Hex("#CDBCA4");
        public static readonly Color TextMuted = Hex("#A8957E"); // кофе с молоком
        public static readonly Color TextOnAccent = Hex("#241B12");

        // Границы / разделители
        public static readonly Color Border = Hex("#43352A"); // roast
        public static readonly Color BorderStrong = Hex("#5A4838");

        // Статусы (в тёплых тонах кофейной гаммы)
        public static readonly Color Success = Hex("#8FAE6B"); // тёплая зелень
        public static readonly Color Warning = Hex("#E0A458"); // янтарь
        public static readonly Color Danger = Hex("#C56A4E"); // терракота
        public static readonly Color Info = Hex("#7FA8B0"); // приглушённый сине-зелёный

        // Кнопка закрытия окна
        public static readonly Color CloseHover = Hex("#C0392B");
    }
}
