using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using DriverCore.Theme;

namespace DriverCore.Controls
{
    /// <summary>Скруглённая панель-карточка цвета поверхности с тонкой рамкой.</summary>
    internal sealed class Card : Panel
    {
        public Card()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            DoubleBuffered = true;
            BackColor = Palette.Surface;
            Padding = new Padding(18);
        }

        public int CornerRadius { get; set; } = 12;
        public Color Fill { get; set; } = Palette.Surface;
        public bool DrawBorder { get; set; } = true;

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Parent?.BackColor ?? Palette.WindowBg);

            var rect = new Rectangle(0, 0, Width - 1, Height - 1);
            using (var path = GraphicsHelper.RoundedRect(rect, CornerRadius))
            {
                using (var b = new SolidBrush(Fill))
                    g.FillPath(b, path);
                if (DrawBorder)
                    using (var p = new Pen(Palette.Border))
                        g.DrawPath(p, path);
            }
            base.OnPaint(e);
        }
    }
}
