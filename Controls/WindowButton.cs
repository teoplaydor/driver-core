using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using DriverCore.Theme;

namespace DriverCore.Controls
{
    /// <summary>
    /// Кнопка управления окном (свернуть/развернуть/закрыть) — рисуется вручную,
    /// глиф из Segoe MDL2 Assets. Без рамок, тёплый hover.
    /// </summary>
    internal sealed class WindowButton : Control
    {
        private bool _hover;
        private bool _isClose;

        public WindowButton()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            Size = new Size(46, 32);
            Font = Fonts.Glyph;
            ForeColor = Palette.TextSecondary;
            TabStop = false;
        }

        /// <summary>Символ-глиф (Segoe MDL2 Assets).</summary>
        public string Glyph { get; set; } = "";

        public bool IsCloseButton
        {
            get { return _isClose; }
            set { _isClose = value; }
        }

        protected override void OnMouseEnter(EventArgs e)
        {
            base.OnMouseEnter(e);
            _hover = true;
            Invalidate();
        }

        protected override void OnMouseLeave(EventArgs e)
        {
            base.OnMouseLeave(e);
            _hover = false;
            Invalidate();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Color bg = Palette.TitleBar;
            if (_hover) bg = _isClose ? Palette.CloseHover : Palette.SurfaceHover;
            e.Graphics.Clear(bg);

            Color fg = _hover && _isClose ? Color.White : ForeColor;
            e.Graphics.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;
            TextRenderer.DrawText(e.Graphics, Glyph, Fonts.Glyph, ClientRectangle, fg,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        }
    }
}
