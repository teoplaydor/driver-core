using System.Drawing;
using System.Windows.Forms;
using DriverCore.Controls;
using DriverCore.Models;
using DriverCore.Theme;

namespace DriverCore.UI
{
    /// <summary>Строка компонента: чекбокс, название, описание, статус, мини-прогресс.</summary>
    internal sealed class ComponentRow : Panel
    {
        private readonly CoffeeCheckBox _chk;
        private readonly Label _state;
        private readonly CoffeeProgressBar _mini;

        public InstallComponent Component { get; }

        public bool Checked => _chk.Checked;

        public ComponentRow(InstallComponent c)
        {
            Component = c;
            Height = 70;
            BackColor = Color.Transparent;
            Margin = new Padding(0, 0, 0, 6);
            GraphicsHelper.EnableDoubleBuffer(this);

            var bg = new Card { Dock = DockStyle.Fill, Padding = new Padding(14, 10, 14, 10) };

            _chk = new CoffeeCheckBox
            {
                Text = "",
                Dock = DockStyle.Left,
                Width = 30,
                Checked = c.RecommendedByDefault && c.State != ComponentState.Installed,
            };

            _state = new Label
            {
                Dock = DockStyle.Right,
                Width = 150,
                Font = Fonts.BodyBold,
                TextAlign = ContentAlignment.MiddleRight,
                BackColor = Color.Transparent,
            };

            var mid = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent };
            mid.Controls.Add(Ui.Banner(c.Description, Fonts.Small, Palette.TextMuted, DockStyle.Top, 20));
            mid.Controls.Add(Ui.Banner(c.DisplayName, Fonts.BodyBold, Palette.TextPrimary, DockStyle.Top, 24));

            _mini = new CoffeeProgressBar { Dock = DockStyle.Bottom, Height = 6, ShowPercent = false, Visible = false };

            bg.Controls.Add(mid);
            bg.Controls.Add(_state);
            bg.Controls.Add(_chk);
            bg.Controls.Add(_mini);

            Controls.Add(bg);
            RefreshState();
        }

        public void RefreshState()
        {
            _state.Text = Ui.StateText(Component.State);
            _state.ForeColor = Ui.StateColor(Component.State);
            _chk.Checked = Component.RecommendedByDefault && Component.State != ComponentState.Installed;
        }

        public void SetProgress(int pct)
        {
            _mini.Visible = true;
            _mini.Status = ProgressStatus.Normal;
            if (pct < 0) { _mini.Indeterminate = true; }
            else { _mini.Indeterminate = false; _mini.Value = pct; }
        }

        public void Finish(bool ok)
        {
            _mini.Indeterminate = false;
            _mini.Visible = true;
            _mini.Value = 100;
            _mini.Status = ok ? ProgressStatus.Success : ProgressStatus.Error;
            _state.Text = Ui.StateText(Component.State);
            _state.ForeColor = Ui.StateColor(Component.State);
        }

        public void SetEnabledState(bool enabled)
        {
            _chk.Enabled = enabled;
        }
    }
}
