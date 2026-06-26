using System.Drawing;
using System.Windows.Forms;
using DriverCore.Controls;
using DriverCore.Models;
using DriverCore.Theme;

namespace DriverCore.UI
{
    /// <summary>Мелкие фабрики и цветовые сопоставления для интерфейса.</summary>
    internal static class Ui
    {
        public static Label Label(string text, Font font, Color color)
        {
            return new Label
            {
                Text = text,
                Font = font,
                ForeColor = color,
                BackColor = Color.Transparent,
                AutoSize = true,
            };
        }

        /// <summary>Метка во всю ширину (для шапок/строк).</summary>
        public static Label Banner(string text, Font font, Color color, DockStyle dock, int height)
        {
            return new Label
            {
                Text = text,
                Font = font,
                ForeColor = color,
                BackColor = Color.Transparent,
                AutoSize = false,
                Dock = dock,
                Height = height,
                TextAlign = ContentAlignment.MiddleLeft,
            };
        }

        public static Panel Spacer(int height, DockStyle dock = DockStyle.Top)
        {
            return new Panel { Height = height, Dock = dock, BackColor = Color.Transparent };
        }

        /// <summary>Карточка-секция с заголовком сверху и контейнером body (Dock=Fill).</summary>
        public static Card Section(string title, int height, out Panel body)
        {
            var card = new Card { Height = height, Padding = new Padding(18) };
            body = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent };
            card.Controls.Add(body);
            if (!string.IsNullOrEmpty(title))
            {
                var head = Banner(title, Fonts.Subheading, Palette.TextPrimary, DockStyle.Top, 30);
                card.Controls.Add(head);
            }
            return card;
        }

        public static FlowLayoutPanel ButtonRow(DockStyle dock = DockStyle.Bottom, int height = 56)
        {
            return new FlowLayoutPanel
            {
                Dock = dock,
                Height = height,
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = false,
                BackColor = Color.Transparent,
                Padding = new Padding(0, 8, 0, 0),
            };
        }

        public static Color HealthColor(DeviceHealth h)
        {
            switch (h)
            {
                case DeviceHealth.Ok: return Palette.Success;
                case DeviceHealth.NeedsDriver: return Palette.Warning;
                case DeviceHealth.Error: return Palette.Danger;
                case DeviceHealth.Disabled: return Palette.Info;
                default: return Palette.TextMuted;
            }
        }

        public static Color StateColor(ComponentState s)
        {
            switch (s)
            {
                case ComponentState.Installed: return Palette.Success;
                case ComponentState.Missing: return Palette.Warning;
                default: return Palette.TextMuted;
            }
        }

        public static string StateText(ComponentState s)
        {
            switch (s)
            {
                case ComponentState.Installed: return "Установлено";
                case ComponentState.Missing: return "Не установлено";
                default: return "Неизвестно";
            }
        }

        /// <summary>Рисует «пилюлю»-бейдж со статусом.</summary>
        public static void DrawBadge(Graphics g, Rectangle r, string text, Color color, Font font)
        {
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using (var path = Controls.GraphicsHelper.RoundedRect(r, r.Height / 2))
            {
                using (var b = new SolidBrush(Color.FromArgb(40, color)))
                    g.FillPath(b, path);
                using (var p = new Pen(Color.FromArgb(150, color)))
                    g.DrawPath(p, path);
            }
            TextRenderer.DrawText(g, text, font, r, color,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
        }
    }
}
