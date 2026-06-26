using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using DriverCore.Theme;

namespace DriverCore.Controls
{
    /// <summary>Чекбокс с кофейной отрисовкой: карамельная галочка, тёплый hover.</summary>
    internal sealed class CoffeeCheckBox : Control
    {
        private bool _checked;
        private bool _hover;
        private const int Box = 18;

        public event EventHandler CheckedChanged;

        public CoffeeCheckBox()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.SupportsTransparentBackColor, true);
            Cursor = Cursors.Hand;
            Font = Fonts.Body;
            ForeColor = Palette.TextPrimary;
            Height = 24;
            DoubleBuffered = true;
        }

        public bool Checked
        {
            get => _checked;
            set { if (_checked == value) return; _checked = value; Invalidate(); CheckedChanged?.Invoke(this, EventArgs.Empty); }
        }

        protected override void OnMouseEnter(EventArgs e) { base.OnMouseEnter(e); _hover = true; Invalidate(); }
        protected override void OnMouseLeave(EventArgs e) { base.OnMouseLeave(e); _hover = false; Invalidate(); }
        protected override void OnClick(EventArgs e) { if (Enabled) Checked = !Checked; base.OnClick(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Parent?.BackColor ?? Palette.Surface);

            int by = (Height - Box) / 2;
            var box = new Rectangle(0, by, Box, Box);

            using (var path = GraphicsHelper.RoundedRect(box, 4))
            {
                if (_checked)
                {
                    using (var b = new SolidBrush(Enabled ? Palette.Accent : Palette.SurfaceActive))
                        g.FillPath(b, path);
                    // галочка
                    using (var pen = new Pen(Palette.AccentText, 2f) { StartCap = LineCap.Round, EndCap = LineCap.Round })
                    {
                        g.DrawLines(pen, new[]
                        {
                            new PointF(box.X + 4, box.Y + 9),
                            new PointF(box.X + 7.5f, box.Y + 12.5f),
                            new PointF(box.X + 14, box.Y + 5),
                        });
                    }
                }
                else
                {
                    using (var b = new SolidBrush(_hover ? Palette.SurfaceHover : Palette.SurfaceAlt))
                        g.FillPath(b, path);
                    using (var pen = new Pen(_hover ? Palette.Accent : Palette.BorderStrong, 1.5f))
                        g.DrawPath(pen, path);
                }
            }

            var textRect = new Rectangle(Box + 10, 0, Width - Box - 10, Height);
            TextRenderer.DrawText(g, Text, Font, textRect, Enabled ? ForeColor : Palette.TextMuted,
                TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.EndEllipsis);
        }
    }
}
