using System;
using System.Drawing;
using System.Windows.Forms;
using DriverCore.Controls;
using DriverCore.Models;
using DriverCore.Theme;

namespace DriverCore.UI
{
    /// <summary>Драйверы: видеокарты с ссылками на вендоров, проблемные устройства, обновление через Windows Update.</summary>
    internal sealed class DriversPanel : Panel
    {
        public event Action<bool> UpdateRequested;   // true = установить, false = только поиск
        public event Action RescanRequested;
        public event Action<GpuInfo> OpenVendorRequested;

        private readonly VStackPanel _gpuList;
        private readonly VStackPanel _problemList;
        private readonly Card _gpuCard;
        private readonly Card _problemCard;
        private readonly CoffeeButton _btnInstall, _btnSearch, _btnRescan;
        private readonly CoffeeProgressBar _progress;
        private readonly Label _status;
        private readonly LogView _log;

        public DriversPanel()
        {
            BackColor = Palette.WindowBg;
            Padding = new Padding(28, 24, 28, 24);
            GraphicsHelper.EnableDoubleBuffer(this);

            var header = Ui.Banner("Драйверы", Fonts.Title, Palette.TextPrimary, DockStyle.Top, 56);

            // ── Низ: действия Windows Update ──
            var action = new Card { Dock = DockStyle.Bottom, Height = 250, Padding = new Padding(18) };

            _log = new LogView { Dock = DockStyle.Fill };
            _status = Ui.Banner("Драйверы ищутся в официальном каталоге Microsoft (Windows Update).",
                Fonts.Body, Palette.TextSecondary, DockStyle.Top, 24);
            _progress = new CoffeeProgressBar { Dock = DockStyle.Top, Height = 16, ShowPercent = false };
            var progressHolder = new Panel { Dock = DockStyle.Top, Height = 24, BackColor = Color.Transparent, Padding = new Padding(0, 4, 0, 4) };
            progressHolder.Controls.Add(_progress);

            var btnRow = Ui.ButtonRow(DockStyle.Top, 56);
            _btnInstall = new CoffeeButton { Text = "Найти и установить драйверы", Glyph = "", Width = 280, Height = 42 };
            _btnSearch = new CoffeeButton { Text = "Только поиск", Secondary = true, Width = 150, Height = 42, Margin = new Padding(10, 0, 0, 0) };
            _btnRescan = new CoffeeButton { Text = "Опросить оборудование", Secondary = true, Glyph = "", Width = 220, Height = 42, Margin = new Padding(10, 0, 0, 0) };
            _btnInstall.Click += (s, e) => UpdateRequested?.Invoke(true);
            _btnSearch.Click += (s, e) => UpdateRequested?.Invoke(false);
            _btnRescan.Click += (s, e) => RescanRequested?.Invoke();
            btnRow.Controls.Add(_btnInstall);
            btnRow.Controls.Add(_btnSearch);
            btnRow.Controls.Add(_btnRescan);

            var caption = Ui.Banner("Найдёт WHQL-драйверы для устройств (в т.ч. видеокарты). Требуются права администратора.",
                Fonts.Small, Palette.TextMuted, DockStyle.Top, 20);
            var heading = Ui.Banner("Обновление драйверов через Windows Update", Fonts.Subheading, Palette.TextPrimary, DockStyle.Top, 28);

            action.Controls.Add(_log);
            action.Controls.Add(_status);
            action.Controls.Add(progressHolder);
            action.Controls.Add(btnRow);
            action.Controls.Add(caption);
            action.Controls.Add(heading);

            // ── Верх (прокрутка): GPU + проблемные ──
            var top = new VStackPanel { Dock = DockStyle.Fill, Padding = new Padding(0, 4, 0, 12) };

            _gpuCard = Ui.Section("Видеокарты", 90, out var gpuBody);
            _gpuCard.Margin = new Padding(0, 0, 0, 16);
            _gpuList = new VStackPanel { Dock = DockStyle.Fill, AutoScroll = false };
            gpuBody.Controls.Add(_gpuList);

            _problemCard = Ui.Section("Устройствам нужен драйвер", 90, out var probBody);
            _problemCard.Margin = new Padding(0, 0, 0, 4);
            _problemList = new VStackPanel { Dock = DockStyle.Fill, AutoScroll = false };
            probBody.Controls.Add(_problemList);

            top.Controls.Add(_gpuCard);
            top.Controls.Add(_problemCard);

            Controls.Add(top);
            Controls.Add(action);
            Controls.Add(header);
        }

        public void ShowInventory(HardwareInventory inv)
        {
            _gpuList.Controls.Clear();
            foreach (var g in inv.Gpus)
                _gpuList.Controls.Add(BuildGpuRow(g));
            _gpuCard.Height = 64 + Math.Max(1, inv.Gpus.Count) * 50;

            _problemList.Controls.Clear();
            int n = 0;
            foreach (var d in inv.Devices)
            {
                if (d.Health != DeviceHealth.NeedsDriver && d.Health != DeviceHealth.Error) continue;
                _problemList.Controls.Add(BuildProblemRow(d));
                n++;
            }
            if (n == 0)
                _problemList.Controls.Add(Ui.Banner("Устройств без драйвера не найдено — всё в порядке.",
                    Fonts.Body, Palette.Success, DockStyle.Top, 32));
            _problemCard.Height = 64 + Math.Max(1, n) * 44;
        }

        private Control BuildGpuRow(GpuInfo g)
        {
            var row = new Panel { Height = 48, BackColor = Color.Transparent, Margin = new Padding(0, 0, 0, 2) };

            var rightHolder = new Panel { Dock = DockStyle.Right, Width = 180, BackColor = Color.Transparent, Padding = new Padding(12, 8, 0, 8) };
            var btn = new CoffeeButton { Dock = DockStyle.Fill, Secondary = true, Text = "Сайт драйверов", Glyph = "" };
            btn.Enabled = g.VendorDriverUrl != null;
            btn.Click += (s, e) => OpenVendorRequested?.Invoke(g);
            rightHolder.Controls.Add(btn);

            var left = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent };
            var name = Ui.Banner(g.Name, Fonts.BodyBold, Palette.TextPrimary, DockStyle.Top, 24);
            string drv = string.IsNullOrEmpty(g.DriverVersion) ? "драйвер не определён" : $"драйвер {g.DriverVersion}  ·  {g.DriverDate}";
            var sub = Ui.Banner($"{g.VendorName}  ·  {drv}", Fonts.Small, Palette.TextMuted, DockStyle.Top, 20);
            left.Controls.Add(sub);
            left.Controls.Add(name);

            row.Controls.Add(left);
            row.Controls.Add(rightHolder);
            return row;
        }

        private Control BuildProblemRow(HardwareDevice d)
        {
            var row = new Panel { Height = 42, BackColor = Color.Transparent };
            var status = Ui.Banner(d.HealthText, Fonts.Body, Ui.HealthColor(d.Health), DockStyle.Right, 42);
            status.TextAlign = ContentAlignment.MiddleRight;
            status.Width = 260;
            var left = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent };
            left.Controls.Add(Ui.Banner(d.Name, Fonts.BodyBold, Palette.TextPrimary, DockStyle.Top, 22));
            left.Controls.Add(Ui.Banner(d.Category.Title(), Fonts.Small, Palette.TextMuted, DockStyle.Top, 18));
            row.Controls.Add(left);
            row.Controls.Add(status);
            return row;
        }

        public void SetBusy(bool busy)
        {
            _btnInstall.Enabled = !busy;
            _btnSearch.Enabled = !busy;
            _btnRescan.Enabled = !busy;
            _progress.Indeterminate = busy;
            if (!busy) _progress.Value = 0;
        }

        public void Report(string text)
        {
            _status.Text = text;
            _log.AppendLine(text);
        }

        public void Log(string text) => _log.AppendLine(text);

        public void SetProgressStatus(ProgressStatus st) => _progress.Status = st;
    }
}
