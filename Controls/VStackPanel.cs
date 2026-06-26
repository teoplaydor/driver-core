using System.Drawing;
using System.Windows.Forms;

namespace DriverCore.Controls
{
    /// <summary>Вертикальный стек на всю ширину с прокруткой (карточки одна под другой).</summary>
    internal sealed class VStackPanel : FlowLayoutPanel
    {
        public VStackPanel()
        {
            FlowDirection = FlowDirection.TopDown;
            WrapContents = false;
            AutoScroll = true;
            BackColor = Color.Transparent;
        }

        protected override void OnLayout(LayoutEventArgs e)
        {
            int w = ClientSize.Width;
            foreach (Control c in Controls)
            {
                int cw = w - c.Margin.Left - c.Margin.Right;
                if (cw > 0 && c.Width != cw) c.Width = cw;
            }
            base.OnLayout(e);
        }
    }
}
