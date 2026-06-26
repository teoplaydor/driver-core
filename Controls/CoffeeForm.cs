using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using DriverCore.Theme;

namespace DriverCore.Controls
{
    /// <summary>
    /// Базовое безрамочное окно с кофейным заголовком: перетаскивание,
    /// ресайз по краям, корректный maximize в пределах рабочей области,
    /// тень, скруглённые углы на Win11. Наследники кладут UI в <see cref="ContentHost"/>.
    /// </summary>
    internal class CoffeeForm : Form
    {
        private const int WM_NCHITTEST = 0x0084;
        private const int WM_GETMINMAXINFO = 0x0024;

        private const int HTCLIENT = 1, HTCAPTION = 2;
        private const int HTLEFT = 10, HTRIGHT = 11, HTTOP = 12, HTTOPLEFT = 13,
                          HTTOPRIGHT = 14, HTBOTTOM = 15, HTBOTTOMLEFT = 16, HTBOTTOMRIGHT = 17;

        private const int CS_DROPSHADOW = 0x00020000;
        private const int WS_MINIMIZEBOX = 0x00020000;
        private const int WS_MAXIMIZEBOX = 0x00010000;
        private const int WS_SYSMENU = 0x00080000;

        private const int ResizeBorder = 7;

        protected int TitleBarHeight = 42;

        private readonly WindowButton _btnMin;
        private readonly WindowButton _btnMax;
        private readonly WindowButton _btnClose;

        /// <summary>Контейнер для содержимого окна (под заголовком).</summary>
        protected Panel ContentHost { get; private set; }

        /// <summary>Текст в заголовке.</summary>
        protected string TitleText { get; set; } = "DriverCore";

        protected string TitleGlyph { get; set; } = ""; // «значок» по умолчанию

        public CoffeeForm()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);

            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Palette.WindowBg;
            ForeColor = Palette.TextPrimary;
            Font = Fonts.Body;
            MinimumSize = new Size(720, 480);
            DoubleBuffered = true;
            Padding = new Padding(0);

            _btnMin = new WindowButton { Glyph = "" };
            _btnMax = new WindowButton { Glyph = "" };
            _btnClose = new WindowButton { Glyph = "", IsCloseButton = true };

            _btnMin.Click += (s, e) => WindowState = FormWindowState.Minimized;
            _btnMax.Click += (s, e) => ToggleMaximize();
            _btnClose.Click += (s, e) => Close();

            ContentHost = new Panel
            {
                BackColor = Palette.WindowBg,
                Location = new Point(1, TitleBarHeight),
            };

            Controls.Add(_btnMin);
            Controls.Add(_btnMax);
            Controls.Add(_btnClose);
            Controls.Add(ContentHost);

            LayoutChrome();
        }

        protected override CreateParams CreateParams
        {
            get
            {
                CreateParams cp = base.CreateParams;
                cp.ClassStyle |= CS_DROPSHADOW;
                cp.Style |= WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU;
                return cp;
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            Native.EnableRoundedCorners(Handle);
        }

        private void ToggleMaximize()
        {
            WindowState = WindowState == FormWindowState.Maximized
                ? FormWindowState.Normal
                : FormWindowState.Maximized;
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            if (_btnMax == null || ContentHost == null) return; // окно ещё достраивается
            _btnMax.Glyph = WindowState == FormWindowState.Maximized ? "" : "";
            LayoutChrome();
            Invalidate();
        }

        private void LayoutChrome()
        {
            if (_btnClose == null || ContentHost == null) return;
            int edge = WindowState == FormWindowState.Maximized ? 0 : 1;
            int w = ClientSize.Width;

            int bw = _btnClose.Width;
            int bh = TitleBarHeight - edge - 2;
            int top = edge + 1;

            _btnClose.Size = new Size(bw, bh);
            _btnMax.Size = new Size(bw, bh);
            _btnMin.Size = new Size(bw, bh);

            _btnClose.Location = new Point(w - edge - bw, top);
            _btnMax.Location = new Point(_btnClose.Left - bw, top);
            _btnMin.Location = new Point(_btnMax.Left - bw, top);

            ContentHost.Location = new Point(edge, TitleBarHeight);
            ContentHost.Size = new Size(w - edge * 2, ClientSize.Height - TitleBarHeight - edge);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            int edge = WindowState == FormWindowState.Maximized ? 0 : 1;

            // Фон заголовка
            using (var b = new SolidBrush(Palette.TitleBar))
                g.FillRectangle(b, 0, 0, ClientSize.Width, TitleBarHeight);

            // Заголовок
            int titleX = string.IsNullOrEmpty(TitleGlyph) ? 16 : 42;
            if (!string.IsNullOrEmpty(TitleGlyph))
            {
                var glyphRect = new Rectangle(14, 0, 24, TitleBarHeight);
                TextRenderer.DrawText(g, TitleGlyph, Fonts.Glyph, glyphRect, Palette.Accent,
                    TextFormatFlags.VerticalCenter | TextFormatFlags.HorizontalCenter);
            }

            var titleRect = new Rectangle(titleX, 0, ClientSize.Width - titleX - 160, TitleBarHeight);
            TextRenderer.DrawText(g, TitleText, Fonts.Subheading, titleRect, Palette.TextPrimary,
                TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.EndEllipsis);

            // Линия под заголовком
            using (var p = new Pen(Palette.Border))
                g.DrawLine(p, 0, TitleBarHeight - 1, ClientSize.Width, TitleBarHeight - 1);

            // Внешняя рамка окна (не в развёрнутом виде)
            if (edge > 0)
            {
                using (var p = new Pen(Palette.BorderStrong))
                    g.DrawRectangle(p, 0, 0, ClientSize.Width - 1, ClientSize.Height - 1);
            }
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_NCHITTEST)
            {
                m.Result = (IntPtr)HitTest(m.LParam);
                return;
            }

            if (m.Msg == WM_GETMINMAXINFO)
            {
                AdjustMaximizedBounds(m.LParam);
                base.WndProc(ref m);
                return;
            }

            base.WndProc(ref m);
        }

        private int HitTest(IntPtr lParam)
        {
            int lp = unchecked((int)(long)lParam);
            int sx = (short)(lp & 0xFFFF);
            int sy = (short)((lp >> 16) & 0xFFFF);
            Point p = PointToClient(new Point(sx, sy));

            bool maximized = WindowState == FormWindowState.Maximized;
            int w = ClientSize.Width, h = ClientSize.Height;

            if (!maximized)
            {
                bool left = p.X <= ResizeBorder;
                bool right = p.X >= w - ResizeBorder;
                bool top = p.Y <= ResizeBorder;
                bool bottom = p.Y >= h - ResizeBorder;

                if (top && left) return HTTOPLEFT;
                if (top && right) return HTTOPRIGHT;
                if (bottom && left) return HTBOTTOMLEFT;
                if (bottom && right) return HTBOTTOMRIGHT;
                if (left) return HTLEFT;
                if (right) return HTRIGHT;
                if (top) return HTTOP;
                if (bottom) return HTBOTTOM;
            }

            // Полоса заголовка (кнопки — дочерние контролы, сюда не попадают) => перетаскивание
            if (p.Y < TitleBarHeight)
                return HTCAPTION;

            return HTCLIENT;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct POINT { public int X; public int Y; }

        [StructLayout(LayoutKind.Sequential)]
        private struct MINMAXINFO
        {
            public POINT ptReserved;
            public POINT ptMaxSize;
            public POINT ptMaxPosition;
            public POINT ptMinTrackSize;
            public POINT ptMaxTrackSize;
        }

        private void AdjustMaximizedBounds(IntPtr lParam)
        {
            MINMAXINFO mmi = (MINMAXINFO)Marshal.PtrToStructure(lParam, typeof(MINMAXINFO));
            Screen s = Screen.FromHandle(Handle);
            Rectangle wa = s.WorkingArea;
            Rectangle mb = s.Bounds;

            mmi.ptMaxPosition.X = wa.Left - mb.Left;
            mmi.ptMaxPosition.Y = wa.Top - mb.Top;
            mmi.ptMaxSize.X = wa.Width;
            mmi.ptMaxSize.Y = wa.Height;
            mmi.ptMinTrackSize.X = MinimumSize.Width;
            mmi.ptMinTrackSize.Y = MinimumSize.Height;

            Marshal.StructureToPtr(mmi, lParam, false);
        }
    }
}
