using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using DriverCore.Theme;

namespace DriverCore.Controls
{
    /// <summary>Плитка со статистикой: крупное число + подпись + глиф.</summary>
    internal sealed class StatTile : Control
    {
        public StatTile()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            DoubleBuffered = true;
        }

        public string Number { get; set; } = "—";
        public string Caption { get; set; } = "";
        public string Glyph { get; set; } = "";
        public Color Accent { get; set; } = Palette.Accent;

        public void Set(string number, Color accent)
        {
            Number = number;
            Accent = accent;
            Invalidate();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Parent?.BackColor ?? Palette.WindowBg);

            var rect = new Rectangle(0, 0, Width - 1, Height - 1);
            using (var path = GraphicsHelper.RoundedRect(rect, 12))
            {
                using (var b = new SolidBrush(Palette.Surface)) g.FillPath(b, path);
                using (var p = new Pen(Palette.Border)) g.DrawPath(p, path);
            }

            // акцентная полоска слева
            using (var b = new SolidBrush(Accent))
            using (var bar = GraphicsHelper.RoundedRect(new Rectangle(0, 14, 4, Height - 28), 2))
                g.FillPath(b, bar);

            var numRect = new Rectangle(20, 14, Width - 30, 38);
            TextRenderer.DrawText(g, Number, new Font("Segoe UI", 22f, FontStyle.Bold), numRect, Accent,
                TextFormatFlags.Left | TextFormatFlags.NoPadding);

            var capRect = new Rectangle(22, 54, Width - 30, 22);
            TextRenderer.DrawText(g, Caption, Fonts.Small, capRect, Palette.TextMuted,
                TextFormatFlags.Left | TextFormatFlags.NoPadding);

            if (!string.IsNullOrEmpty(Glyph))
            {
                var gr = new Rectangle(Width - 44, 12, 32, 32);
                TextRenderer.DrawText(g, Glyph, new Font("Segoe MDL2 Assets", 14f), gr,
                    Color.FromArgb(120, Accent), TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            }
        }
    }
}
