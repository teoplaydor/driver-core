using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using DriverCore.Theme;

namespace DriverCore.Controls
{
    internal enum ProgressStatus { Normal, Success, Error }

    /// <summary>
    /// Скруглённый прогресс-бар с градиентной карамельной заливкой.
    /// Поддерживает определённый прогресс (0..100), неопределённый (бегущий блик)
    /// и состояния успех/ошибка.
    /// </summary>
    internal sealed class CoffeeProgressBar : Control
    {
        private int _value;
        private bool _indeterminate;
        private int _marquee;
        private readonly Timer _timer;

        public CoffeeProgressBar()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer, true);
            Height = 16;
            DoubleBuffered = true;
            _timer = new Timer { Interval = 30 };
            _timer.Tick += (s, e) =>
            {
                _marquee = (_marquee + 6) % (Width + 200);
                Invalidate();
            };
        }

        public ProgressStatus Status { get; set; } = ProgressStatus.Normal;

        public int Value
        {
            get => _value;
            set { _value = Math.Max(0, Math.Min(100, value)); Invalidate(); }
        }

        public bool Indeterminate
        {
            get => _indeterminate;
            set
            {
                if (_indeterminate == value) return;
                _indeterminate = value;
                if (value) _timer.Start(); else _timer.Stop();
                Invalidate();
            }
        }

        public bool ShowPercent { get; set; } = true;

        protected override void Dispose(bool disposing)
        {
            if (disposing) _timer.Dispose();
            base.Dispose(disposing);
        }

        private Color FillColor
        {
            get
            {
                switch (Status)
                {
                    case ProgressStatus.Success: return Palette.Success;
                    case ProgressStatus.Error: return Palette.Danger;
                    default: return Palette.Accent;
                }
            }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Parent?.BackColor ?? Palette.WindowBg);

            int h = Height;
            int radius = h / 2;
            var track = new Rectangle(0, 0, Width - 1, h - 1);

            using (var path = GraphicsHelper.RoundedRect(track, radius))
            using (var b = new SolidBrush(Palette.SurfaceAlt))
                g.FillPath(b, path);

            Color c1 = FillColor;
            Color c2 = Status == ProgressStatus.Normal ? Palette.AccentHover : ControlPaint.Light(c1, 0.2f);

            if (_indeterminate)
            {
                int segW = Math.Max(80, Width / 4);
                int x = _marquee - segW;
                var seg = new Rectangle(x, 0, segW, h - 1);
                using (var clip = GraphicsHelper.RoundedRect(track, radius))
                {
                    g.SetClip(clip);
                    using (var lg = new LinearGradientBrush(
                        new Rectangle(Math.Min(seg.X, 0), 0, Math.Max(seg.Width, 1), h),
                        Color.FromArgb(0, c1), c1, LinearGradientMode.Horizontal))
                        g.FillRectangle(lg, seg);
                    g.ResetClip();
                }
                return;
            }

            if (_value > 0)
            {
                int w = (int)((Width - 2) * (_value / 100.0));
                if (w < h) w = h; // чтобы скругление было видно
                var fill = new Rectangle(0, 0, Math.Min(w, Width - 1), h - 1);
                using (var path = GraphicsHelper.RoundedRect(fill, radius))
                using (var lg = new LinearGradientBrush(
                    new Rectangle(0, 0, fill.Width + 1, h), c1, c2, LinearGradientMode.Horizontal))
                    g.FillPath(lg, path);
            }

            if (ShowPercent && h >= 14)
            {
                string txt = _value + "%";
                TextRenderer.DrawText(g, txt, Fonts.Small, track, Palette.TextPrimary,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
            }
        }
    }
}
