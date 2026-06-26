using System;
using System.Drawing;
using System.Windows.Forms;
using DriverCore.Controls;
using DriverCore.Models;
using DriverCore.Theme;

namespace DriverCore.UI
{
    /// <summary>Полный список оборудования в тёмной таблице с фильтром «только проблемные».</summary>
    internal sealed class HardwarePanel : Panel
    {
        private readonly DataGridView _grid;
        private readonly CoffeeCheckBox _onlyProblems;
        private readonly Label _empty;
        private HardwareInventory _inv;

        public HardwarePanel()
        {
            BackColor = Palette.WindowBg;
            Padding = new Padding(28, 24, 28, 24);
            GraphicsHelper.EnableDoubleBuffer(this);

            var header = new Panel { Dock = DockStyle.Top, Height = 70, BackColor = Color.Transparent };
            header.Controls.Add(Ui.Banner("Оборудование", Fonts.Title, Palette.TextPrimary, DockStyle.Top, 36));
            _onlyProblems = new CoffeeCheckBox
            {
                Text = "Показывать только устройства, требующие внимания",
                Dock = DockStyle.Top, Height = 26, Width = 460,
            };
            _onlyProblems.CheckedChanged += (s, e) => Populate();
            header.Controls.Add(_onlyProblems);

            var card = new Card { Dock = DockStyle.Fill, Padding = new Padding(2) };

            _grid = BuildGrid();
            _empty = new Label
            {
                Text = "Нажмите «Проверить компьютер» на вкладке «Обзор».",
                Dock = DockStyle.Fill, ForeColor = Palette.TextMuted, Font = Fonts.Body,
                TextAlign = System.Drawing.ContentAlignment.MiddleCenter, BackColor = Color.Transparent,
            };
            card.Controls.Add(_grid);
            card.Controls.Add(_empty);

            Controls.Add(card);
            Controls.Add(header);
        }

        private DataGridView BuildGrid()
        {
            var g = new DataGridView
            {
                Dock = DockStyle.Fill,
                BackgroundColor = Palette.Surface,
                BorderStyle = BorderStyle.None,
                GridColor = Palette.Border,
                EnableHeadersVisualStyles = false,
                RowHeadersVisible = false,
                AllowUserToAddRows = false,
                AllowUserToDeleteRows = false,
                AllowUserToResizeRows = false,
                ReadOnly = true,
                MultiSelect = false,
                SelectionMode = DataGridViewSelectionMode.FullRowSelect,
                AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill,
                CellBorderStyle = DataGridViewCellBorderStyle.SingleHorizontal,
                ColumnHeadersBorderStyle = DataGridViewHeaderBorderStyle.None,
                ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.DisableResizing,
                ColumnHeadersHeight = 38,
                Visible = false,
            };
            g.RowTemplate.Height = 30;

            g.DefaultCellStyle.BackColor = Palette.Surface;
            g.DefaultCellStyle.ForeColor = Palette.TextPrimary;
            g.DefaultCellStyle.SelectionBackColor = Palette.SurfaceActive;
            g.DefaultCellStyle.SelectionForeColor = Palette.TextPrimary;
            g.DefaultCellStyle.Padding = new Padding(8, 0, 8, 0);
            g.DefaultCellStyle.Font = Fonts.Body;
            g.AlternatingRowsDefaultCellStyle.BackColor = Palette.SurfaceAlt;

            g.ColumnHeadersDefaultCellStyle.BackColor = Palette.TitleBar;
            g.ColumnHeadersDefaultCellStyle.ForeColor = Palette.TextMuted;
            g.ColumnHeadersDefaultCellStyle.SelectionBackColor = Palette.TitleBar;
            g.ColumnHeadersDefaultCellStyle.Font = Fonts.BodyBold;
            g.ColumnHeadersDefaultCellStyle.Padding = new Padding(8, 0, 8, 0);

            g.Columns.Add(NewCol("cat", "Категория", 16));
            g.Columns.Add(NewCol("name", "Устройство", 40));
            g.Columns.Add(NewCol("state", "Состояние", 18));
            g.Columns.Add(NewCol("drv", "Драйвер", 26));

            return g;
        }

        private static DataGridViewTextBoxColumn NewCol(string name, string header, int fillWeight)
        {
            return new DataGridViewTextBoxColumn
            {
                Name = name,
                HeaderText = header,
                FillWeight = fillWeight,
                SortMode = DataGridViewColumnSortMode.NotSortable,
                Resizable = DataGridViewTriState.False,
            };
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            Native.UseDarkControl(_grid.Handle);
        }

        public void ShowInventory(HardwareInventory inv)
        {
            _inv = inv;
            Populate();
        }

        private void Populate()
        {
            if (_inv == null) return;
            _grid.Rows.Clear();

            bool only = _onlyProblems.Checked;
            int shown = 0;
            foreach (var grp in _inv.ByCategory())
            {
                foreach (var d in grp)
                {
                    if (only && !d.NeedsAttention) continue;
                    int idx = _grid.Rows.Add(grp.Key.Title(), d.Name, d.HealthText, d.DriverSummary);
                    var row = _grid.Rows[idx];
                    row.Cells[2].Style.ForeColor = Ui.HealthColor(d.Health);
                    row.Cells[2].Style.SelectionForeColor = Ui.HealthColor(d.Health);
                    row.Cells[0].Style.ForeColor = Palette.TextMuted;
                    if (d.NeedsAttention)
                        row.Cells[1].Style.Font = Fonts.BodyBold;
                    shown++;
                }
            }

            bool any = shown > 0;
            _grid.Visible = any;
            _empty.Visible = !any;
            _empty.Text = only && _inv.TotalDevices > 0
                ? "Устройств, требующих внимания, не найдено."
                : "Нажмите «Проверить компьютер» на вкладке «Обзор».";
        }
    }
}
