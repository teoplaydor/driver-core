using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using DriverCore.Theme;

namespace DriverCore.Controls
{
    /// <summary>
    /// Плоская кнопка в кофейном стиле со скруглением. Primary — карамель с тёмным текстом,
    /// Secondary («призрак») — поверхность с рамкой. Поддерживает глиф Segoe MDL2.
    /// </summary>
    internal sealed class CoffeeButton : Control
    {
        private bool _hover;
        private bool _pressed;

        public CoffeeButton()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.SupportsTransparentBackColor, true);
            Size = new Size(180, 40);
            Font = new Font("Segoe UI Semibold", 9.75f, FontStyle.Bold);
            Cursor = Cursors.Hand;
            DoubleBuffered = true;
        }

        public bool Secondary { get; set; }
        public bool Danger { get; set; }
        public string Glyph { get; set; } = "";
        public int CornerRadius { get; set; } = 8;

        protected override void OnEnabledChanged(EventArgs e)
        {
            base.OnEnabledChanged(e);
            Cursor = Enabled ? Cursors.Hand : Cursors.Default;
            Invalidate();
        }

        protected override void OnMouseEnter(EventArgs e) { base.OnMouseEnter(e); _hover = true; Invalidate(); }
        protected override void OnMouseLeave(EventArgs e) { base.OnMouseLeave(e); _hover = false; _pressed = false; Invalidate(); }
        protected override void OnMouseDown(MouseEventArgs e) { base.OnMouseDown(e); if (e.Button == MouseButtons.Left) { _pressed = true; Invalidate(); } }
        protected override void OnMouseUp(MouseEventArgs e) { base.OnMouseUp(e); _pressed = false; Invalidate(); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Parent?.BackColor ?? Palette.WindowBg);

            var rect = new Rectangle(0, 0, Width - 1, Height - 1);

            Color fill, text;
            if (!Enabled)
            {
                fill = Secondary ? Palette.Surface : Palette.SurfaceHover;
                text = Palette.TextMuted;
            }
            else if (Secondary)
            {
                fill = _pressed ? Palette.SurfaceActive : _hover ? Palette.SurfaceHover : Palette.Surface;
                text = Danger ? Palette.Danger : Palette.TextPrimary;
            }
            else
            {
                fill = _pressed ? Palette.AccentPressed : _hover ? Palette.AccentHover : Palette.Accent;
                text = Palette.AccentText;
            }

            using (var path = GraphicsHelper.RoundedRect(rect, CornerRadius))
            using (var b = new SolidBrush(fill))
            {
                g.FillPath(b, path);
                if (Secondary)
                {
                    using (var p = new Pen(Enabled ? Palette.BorderStrong : Palette.Border))
                        g.DrawPath(p, path);
                }
            }

            DrawContent(g, text);
        }

        private void DrawContent(Graphics g, Color text)
        {
            const int gap = 8;
            Size textSize = TextRenderer.MeasureText(g, Text, Font, Size.Empty, TextFormatFlags.NoPadding);
            Size glyphSize = string.IsNullOrEmpty(Glyph)
                ? Size.Empty
                : TextRenderer.MeasureText(g, Glyph, Fonts.Glyph, Size.Empty, TextFormatFlags.NoPadding);

            int total = textSize.Width + (glyphSize.Width > 0 ? glyphSize.Width + gap : 0);
            int x = (Width - total) / 2;
            int cy = Height / 2;

            if (glyphSize.Width > 0)
            {
                var gr = new Rectangle(x, cy - glyphSize.Height / 2, glyphSize.Width, glyphSize.Height);
                TextRenderer.DrawText(g, Glyph, Fonts.Glyph, gr, text, TextFormatFlags.NoPadding);
                x += glyphSize.Width + gap;
            }
            var tr = new Rectangle(x, cy - textSize.Height / 2, textSize.Width, textSize.Height);
            TextRenderer.DrawText(g, Text, Font, tr, text, TextFormatFlags.NoPadding);
        }
    }
}
