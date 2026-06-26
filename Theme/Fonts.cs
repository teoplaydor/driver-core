using System.Drawing;

namespace DriverCore.Theme
{
    /// <summary>
    /// Единый набор шрифтов. Segoe UI есть на любой Win10/11 из коробки.
    /// </summary>
    internal static class Fonts
    {
        private const string Family = "Segoe UI";

        public static readonly Font Title = new Font(Family, 15f, FontStyle.Bold);
        public static readonly Font Heading = new Font(Family, 11.5f, FontStyle.Bold);
        public static readonly Font Subheading = new Font(Family, 10.5f, FontStyle.Bold);
        public static readonly Font Body = new Font(Family, 9.75f, FontStyle.Regular);
        public static readonly Font BodyBold = new Font(Family, 9.75f, FontStyle.Bold);
        public static readonly Font Small = new Font(Family, 8.75f, FontStyle.Regular);
        public static readonly Font Mono = new Font("Consolas", 9f, FontStyle.Regular);
        public static readonly Font Nav = new Font(Family, 10.5f, FontStyle.Regular);
        public static readonly Font Glyph = new Font("Segoe MDL2 Assets", 9f, FontStyle.Regular);
    }
}
