using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;
using DriverCore.Controls;
using DriverCore.Models;
using DriverCore.Theme;

namespace DriverCore.UI
{
    /// <summary>Стартовый экран: большая кнопка проверки, прогресс, статистика, сводка, журнал.</summary>
    internal sealed class OverviewPanel : Panel
    {
        public event Action ScanRequested;

        private readonly CoffeeButton _btnScan;
        private readonly CoffeeProgressBar _progress;
        private readonly Label _status;
        private readonly LogView _log;
        private readonly StatTile _tDevices, _tProblems, _tMissing, _tComponents;
        private readonly TableLayoutPanel _sysRows;

        public OverviewPanel()
        {
            BackColor = Palette.WindowBg;
            Padding = new Padding(28, 24, 28, 24);
            GraphicsHelper.EnableDoubleBuffer(this);

            var header = new Panel { Dock = DockStyle.Top, Height = 64, BackColor = Color.Transparent };
            header.Controls.Add(Ui.Banner("Состояние компьютера", Fonts.Title, Palette.TextPrimary, DockStyle.Top, 36));
            header.Controls.Add(Ui.Banner("Проверка оборудования, драйверов и системных компонентов",
                Fonts.Body, Palette.TextMuted, DockStyle.Top, 24));

            var stack = new VStackPanel { Dock = DockStyle.Fill, Padding = new Padding(0, 8, 0, 0) };

            // — Hero —
            var hero = Ui.Section("", 168, out var heroBody);
            heroBody.Margin = new Padding(0);
            var heroTitle = Ui.Banner("Полная проверка", Fonts.Heading, Palette.TextPrimary, DockStyle.Top, 28);
            var heroSub = Ui.Banner("Проверит всё оборудование и предложит, что доустановить",
                Fonts.Body, Palette.TextMuted, DockStyle.Top, 24);

            _btnScan = new CoffeeButton { Text = "Проверить компьютер", Glyph = "", Width = 240, Height = 46 };
            _btnScan.Click += (s, e) => ScanRequested?.Invoke();
            var btnRow = Ui.ButtonRow(DockStyle.Top, 58);
            btnRow.Controls.Add(_btnScan);

            _status = Ui.Banner("Готов к проверке.", Fonts.Body, Palette.TextSecondary, DockStyle.Bottom, 24);
            _progress = new CoffeeProgressBar { Dock = DockStyle.Bottom, Height = 16, Value = 0, ShowPercent = false };

            heroBody.Controls.Add(Ui.Spacer(6));
            heroBody.Controls.Add(_progress);
            heroBody.Controls.Add(_status);
            heroBody.Controls.Add(btnRow);
            heroBody.Controls.Add(heroSub);
            heroBody.Controls.Add(heroTitle);

            // — Статистика (4 плитки) —
            var statsHost = new Panel { Height = 96, BackColor = Color.Transparent, Margin = new Padding(0, 0, 0, 16) };
            var statsGrid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 4, RowCount = 1, BackColor = Color.Transparent };
            for (int i = 0; i < 4; i++) statsGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 25));
            _tDevices = MakeTile("Устройств", "", Palette.Accent);
            _tProblems = MakeTile("Требуют внимания", "", Palette.Warning);
            _tMissing = MakeTile("Без драйвера", "", Palette.Danger);
            _tComponents = MakeTile("Компонентов нет", "", Palette.Info);
            AddTile(statsGrid, _tDevices, 0);
            AddTile(statsGrid, _tProblems, 1);
            AddTile(statsGrid, _tMissing, 2);
            AddTile(statsGrid, _tComponents, 3);
            statsHost.Controls.Add(statsGrid);

            // — Система —
            var sysCard = Ui.Section("Система", 196, out var sysBody);
            _sysRows = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, BackColor = Color.Transparent };
            _sysRows.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 130));
            _sysRows.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            sysBody.Controls.Add(_sysRows);

            // — Журнал —
            var logCard = Ui.Section("Журнал", 170, out var logBody);
            _log = new LogView { Dock = DockStyle.Fill };
            logBody.Controls.Add(_log);

            foreach (var c in new Control[] { hero, statsHost, sysCard, logCard })
            {
                c.Margin = new Padding(0, 0, 0, 16);
                stack.Controls.Add(c);
            }

            Controls.Add(stack);
            Controls.Add(header);
        }

        private static StatTile MakeTile(string caption, string glyph, Color accent)
        {
            return new StatTile { Caption = caption, Glyph = glyph, Accent = accent, Number = "—" };
        }

        private static void AddTile(TableLayoutPanel grid, StatTile tile, int col)
        {
            tile.Dock = DockStyle.Fill;
            tile.Margin = new Padding(col == 0 ? 0 : 6, 0, col == 3 ? 0 : 6, 0);
            grid.Controls.Add(tile, col, 0);
        }

        // ───────── публичные методы для координатора ─────────

        public void SetScanning(bool busy)
        {
            _btnScan.Enabled = !busy;
            _btnScan.Text = busy ? "Идёт проверка…" : "Проверить компьютер";
            _progress.Indeterminate = busy;
        }

        public void Report(string text)
        {
            _status.Text = text;
            _log.AppendLine(text);
        }

        public void Log(string text) => _log.AppendLine(text);

        public void ShowSummary(HardwareInventory inv, IReadOnlyList<InstallComponent> comps)
        {
            _progress.Indeterminate = false;
            _progress.Value = 100;
            _progress.Status = inv.ProblemCount > 0 ? ProgressStatus.Normal : ProgressStatus.Success;

            int missingDrivers = inv.MissingDriverCount;
            int missingComps = 0;
            foreach (var c in comps) if (c.State == ComponentState.Missing) missingComps++;

            _tDevices.Set(inv.TotalDevices.ToString(), Palette.Accent);
            _tProblems.Set(inv.ProblemCount.ToString(), inv.ProblemCount > 0 ? Palette.Warning : Palette.Success);
            _tMissing.Set(missingDrivers.ToString(), missingDrivers > 0 ? Palette.Danger : Palette.Success);
            _tComponents.Set(missingComps.ToString(), missingComps > 0 ? Palette.Warning : Palette.Success);

            _sysRows.Controls.Clear();
            _sysRows.RowStyles.Clear();
            int row = 0;
            foreach (var kv in inv.System.AsRows())
            {
                var k = Ui.Label(kv.Key, Fonts.Body, Palette.TextMuted);
                k.Margin = new Padding(0, 4, 0, 4);
                var v = Ui.Label(string.IsNullOrEmpty(kv.Value) ? "—" : kv.Value, Fonts.BodyBold, Palette.TextPrimary);
                v.Margin = new Padding(0, 4, 0, 4);
                _sysRows.RowStyles.Add(new RowStyle(SizeType.AutoSize));
                _sysRows.Controls.Add(k, 0, row);
                _sysRows.Controls.Add(v, 1, row);
                row++;
            }

            string verdict = inv.ProblemCount == 0 && missingComps == 0
                ? "Всё в порядке: проблемных устройств не найдено."
                : $"Найдено: проблемных устройств — {inv.ProblemCount}, не хватает компонентов — {missingComps}.";
            _status.Text = verdict;
        }
    }
}
