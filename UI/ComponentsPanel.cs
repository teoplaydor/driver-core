using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;
using DriverCore.Controls;
using DriverCore.Models;
using DriverCore.Theme;

namespace DriverCore.UI
{
    /// <summary>Компоненты и доп. софт: галочки, статус, установка отмеченного.</summary>
    internal sealed class ComponentsPanel : Panel
    {
        public event Action InstallRequested;
        public event Action RefreshRequested;

        private readonly VStackPanel _list;
        private readonly CoffeeButton _btnInstall, _btnRefresh;
        private readonly CoffeeProgressBar _progress;
        private readonly Label _status;
        private readonly LogView _log;
        private readonly Dictionary<string, ComponentRow> _rows = new Dictionary<string, ComponentRow>();

        public ComponentsPanel()
        {
            BackColor = Palette.WindowBg;
            Padding = new Padding(28, 24, 28, 24);
            GraphicsHelper.EnableDoubleBuffer(this);

            var header = new Panel { Dock = DockStyle.Top, Height = 64, BackColor = Color.Transparent };
            header.Controls.Add(Ui.Banner("Только официальные дистрибутивы Microsoft — без лишнего софта.",
                Fonts.Body, Palette.TextMuted, DockStyle.Top, 24));
            header.Controls.Add(Ui.Banner("Компоненты и доп. софт", Fonts.Title, Palette.TextPrimary, DockStyle.Top, 36));

            // низ — действия
            var action = new Card { Dock = DockStyle.Bottom, Height = 210, Padding = new Padding(18) };
            _log = new LogView { Dock = DockStyle.Fill };
            _status = Ui.Banner("Отметьте компоненты и нажмите «Установить отмеченное».",
                Fonts.Body, Palette.TextSecondary, DockStyle.Top, 24);
            _progress = new CoffeeProgressBar { Dock = DockStyle.Top, Height = 16, ShowPercent = true };
            var progHolder = new Panel { Dock = DockStyle.Top, Height = 24, BackColor = Color.Transparent, Padding = new Padding(0, 4, 0, 4) };
            progHolder.Controls.Add(_progress);

            var btnRow = Ui.ButtonRow(DockStyle.Top, 56);
            _btnInstall = new CoffeeButton { Text = "Установить отмеченное", Glyph = "", Width = 250, Height = 42 };
            _btnRefresh = new CoffeeButton { Text = "Обновить статус", Secondary = true, Glyph = "", Width = 180, Height = 42, Margin = new Padding(10, 0, 0, 0) };
            _btnInstall.Click += (s, e) => InstallRequested?.Invoke();
            _btnRefresh.Click += (s, e) => RefreshRequested?.Invoke();
            btnRow.Controls.Add(_btnInstall);
            btnRow.Controls.Add(_btnRefresh);

            var heading = Ui.Banner("Установка компонентов", Fonts.Subheading, Palette.TextPrimary, DockStyle.Top, 28);

            action.Controls.Add(_log);
            action.Controls.Add(_status);
            action.Controls.Add(progHolder);
            action.Controls.Add(btnRow);
            action.Controls.Add(heading);

            // список
            _list = new VStackPanel { Dock = DockStyle.Fill, Padding = new Padding(0, 4, 0, 12) };

            Controls.Add(_list);
            Controls.Add(action);
            Controls.Add(header);
        }

        public void ShowComponents(IEnumerable<InstallComponent> comps)
        {
            _list.Controls.Clear();
            _rows.Clear();
            foreach (var c in comps)
            {
                var row = new ComponentRow(c);
                _rows[c.Key] = row;
                _list.Controls.Add(row);
            }
        }

        public IReadOnlyList<InstallComponent> GetSelected()
        {
            var sel = new List<InstallComponent>();
            foreach (var r in _rows.Values)
                if (r.Checked) sel.Add(r.Component);
            return sel;
        }

        public void RefreshStates()
        {
            foreach (var r in _rows.Values) r.RefreshState();
        }

        public void RowProgress(InstallComponent c, int pct)
        {
            if (_rows.TryGetValue(c.Key, out var r)) r.SetProgress(pct);
        }

        public void RowFinish(InstallComponent c, bool ok)
        {
            if (_rows.TryGetValue(c.Key, out var r)) r.Finish(ok);
        }

        public void SetBusy(bool busy)
        {
            _btnInstall.Enabled = !busy;
            _btnRefresh.Enabled = !busy;
            foreach (var r in _rows.Values) r.SetEnabledState(!busy);
            if (!busy) { _progress.Indeterminate = false; }
        }

        public void SetOverall(int pct, ProgressStatus st = ProgressStatus.Normal)
        {
            _progress.Indeterminate = false;
            _progress.Status = st;
            _progress.Value = pct;
        }

        public void Report(string text)
        {
            _status.Text = text;
            _log.AppendLine(text);
        }

        public void Log(string text) => _log.AppendLine(text);
    }
}
