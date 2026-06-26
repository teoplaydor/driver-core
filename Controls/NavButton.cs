using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using DriverCore.Theme;

namespace DriverCore.Controls
{
    /// <summary>Пункт левого меню: глиф + подпись, подсветка выбранного слева акцентной полосой.</summary>
    internal sealed class NavButton : Control
    {
        private bool _hover;
        private bool _selected;

        public NavButton()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer, true);
            Height = 46;
            Cursor = Cursors.Hand;
            Font = Fonts.Nav;
            DoubleBuffered = true;
        }

        public string Glyph { get; set; } = "";

        public bool Selected
        {
            get => _selected;
            set { if (_selected == value) return; _selected = value; Invalidate(); }
        }

        protected override void OnMouseEnter(EventArgs e) { base.OnMouseEnter(e); _hover = true; Invalidate(); }
        protected override void OnMouseLeave(EventArgs e) { base.OnMouseLeave(e); _hover = false; Invalidate(); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;

            Color bg = _selected ? Palette.SurfaceHover : _hover ? Palette.Surface : Palette.TitleBar;
            g.Clear(bg);

            if (_selected)
            {
                using (var b = new SolidBrush(Palette.Accent))
                    g.FillRectangle(b, 0, 8, 4, Height - 16);
            }

            Color fg = _selected ? Palette.TextPrimary : _hover ? Palette.TextPrimary : Palette.TextSecondary;
            Color gly = _selected ? Palette.Accent : fg;

            int textX = 20;
            if (!string.IsNullOrEmpty(Glyph))
            {
                var glyphRect = new Rectangle(18, 0, 26, Height);
                TextRenderer.DrawText(g, Glyph, Fonts.Glyph, glyphRect, gly,
                    TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.NoPadding);
                textX = 52;
            }

            var textRect = new Rectangle(textX, 0, Width - textX - 10, Height);
            TextRenderer.DrawText(g, Text, Font, textRect, fg,
                TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.EndEllipsis);
        }
    }
}
