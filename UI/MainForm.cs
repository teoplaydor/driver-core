using System;
using System.Collections.Generic;
using System.Drawing;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using DriverCore.Controls;
using DriverCore.Models;
using DriverCore.Services;
using DriverCore.Theme;

namespace DriverCore.UI
{
    internal sealed class MainForm : CoffeeForm
    {
        private readonly OverviewPanel _overview = new OverviewPanel();
        private readonly HardwarePanel _hardware = new HardwarePanel();
        private readonly DriversPanel _drivers = new DriversPanel();
        private readonly ComponentsPanel _components = new ComponentsPanel();

        private readonly List<NavButton> _navButtons = new List<NavButton>();
        private readonly List<Control> _pages = new List<Control>();

        private HardwareInventory _inventory;
        private List<InstallComponent> _catalog;
        private CancellationTokenSource _cts;
        private bool _busy;

        public MainForm()
        {
            Text = "DriverCore"; // подпись окна в панели задач / Alt-Tab
            TitleText = "DriverCore — проверка железа и драйверов";
            TitleGlyph = "";
            TitleGlyph = string.Empty; // текстовый заголовок без иконки
            MinimumSize = new Size(940, 620);
            Size = new Size(1060, 700);

            _catalog = ComponentCatalog.Build();

            BuildLayout();
            WireEvents();
            ShowPage(0);

            ComponentCatalog.Refresh(_catalog);
            _components.ShowComponents(_catalog);
        }

        private bool _autoScanned;

        /// <summary>Режим снимка: не запускать авто-проверку (данные готовятся вручную).</summary>
        public bool ShotMode { get; set; }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            if (ShotMode || _autoScanned) return;
            _autoScanned = true;
            // Из коробки: сразу проверяем компьютер, без лишних действий.
            RunFullScanAsync();
        }

        /// <summary>Синхронно сканирует и наполняет панели — для снимка экрана.</summary>
        public void PrepareForShot()
        {
            var inv = new HardwareScanner().Scan(s => _overview.Log(s));
            ComponentCatalog.Refresh(_catalog);
            _inventory = inv;
            _hardware.ShowInventory(inv);
            _drivers.ShowInventory(inv);
            _components.ShowComponents(_catalog);
            _overview.ShowSummary(inv, _catalog);
        }

        public void ShowPagePublic(int index) => ShowPage(index);

        private void BuildLayout()
        {
            var content = new Panel { Dock = DockStyle.Fill, BackColor = Palette.WindowBg };

            foreach (var p in new Control[] { _overview, _hardware, _drivers, _components })
            {
                p.Dock = DockStyle.Fill;
                p.Visible = false;
                content.Controls.Add(p);
                _pages.Add(p);
            }

            var nav = new Panel { Dock = DockStyle.Left, Width = 212, BackColor = Palette.TitleBar };

            var navStack = new VStackPanel { Dock = DockStyle.Fill, Padding = new Padding(0, 12, 0, 0), AutoScroll = false };
            AddNav(navStack, "", "Обзор");
            AddNav(navStack, "", "Оборудование");
            AddNav(navStack, "", "Драйверы");
            AddNav(navStack, "", "Компоненты");

            nav.Controls.Add(navStack);
            nav.Controls.Add(BuildAdminBadge());

            ContentHost.Controls.Add(content);
            ContentHost.Controls.Add(nav);
        }

        private void AddNav(VStackPanel stack, string glyph, string text)
        {
            int index = _navButtons.Count;
            var b = new NavButton { Glyph = "", Text = text, Margin = new Padding(0, 0, 0, 2) };
            b.Click += (s, e) => ShowPage(index);
            _navButtons.Add(b);
            stack.Controls.Add(b);
        }

        private Panel BuildAdminBadge()
        {
            var panel = new Panel { Dock = DockStyle.Bottom, Height = 66, BackColor = Palette.TitleBar, Padding = new Padding(16, 8, 16, 12) };
            bool admin = ElevationHelper.IsAdministrator();

            var lbl = Ui.Banner(admin ? "Режим: администратор" : "Режим: обычный",
                Fonts.Small, admin ? Palette.Success : Palette.TextMuted, DockStyle.Top, 22);
            panel.Controls.Add(lbl);

            if (!admin)
            {
                var btn = new CoffeeButton { Text = "Повысить права", Secondary = true, Glyph = "", Dock = DockStyle.Top, Height = 30 };
                btn.Click += (s, e) => { if (ElevationHelper.RelaunchAsAdmin()) Application.Exit(); };
                panel.Controls.Add(btn);
            }
            return panel;
        }

        private void ShowPage(int index)
        {
            for (int i = 0; i < _pages.Count; i++)
            {
                _pages[i].Visible = i == index;
                _navButtons[i].Selected = i == index;
            }
        }

        private void WireEvents()
        {
            _overview.ScanRequested += () => RunFullScanAsync();
            _drivers.UpdateRequested += install => UpdateDriversAsync(install);
            _drivers.RescanRequested += () => RescanHardwareAsync();
            _drivers.OpenVendorRequested += g => Sys.OpenUrl(g.VendorDriverUrl);
            _components.InstallRequested += () => InstallComponentsAsync();
            _components.RefreshRequested += () => RefreshComponentsAsync();
        }

        private void UiPost(Action a)
        {
            if (IsDisposed) return;
            try { if (InvokeRequired) BeginInvoke(a); else a(); }
            catch { }
        }

        // ───────────────────────── Полная проверка ─────────────────────────

        private async void RunFullScanAsync()
        {
            if (_busy) return;
            _busy = true;
            _overview.SetScanning(true);
            _overview.Report("Проверяю компьютер…");
            try
            {
                var inv = await Task.Run(() =>
                {
                    var scanner = new HardwareScanner();
                    return scanner.Scan(s => _overview.Log(s));
                });

                await Task.Run(() => ComponentCatalog.Refresh(_catalog));

                _inventory = inv;
                _hardware.ShowInventory(inv);
                _drivers.ShowInventory(inv);
                _components.ShowComponents(_catalog);
                _overview.ShowSummary(inv, _catalog);
            }
            catch (Exception ex)
            {
                _overview.Report("Ошибка проверки: " + ex.Message);
            }
            finally
            {
                _busy = false;
                _overview.SetScanning(false);
            }
        }

        // ───────────────────────── Драйверы ─────────────────────────

        private async void UpdateDriversAsync(bool install)
        {
            if (_busy) { _drivers.Report("Дождитесь завершения текущей операции."); return; }
            if (install && !ElevationHelper.EnsureAdmin(this)) return;

            _busy = true;
            _drivers.SetBusy(true);
            _drivers.SetProgressStatus(ProgressStatus.Normal);
            _cts = new CancellationTokenSource();
            var ct = _cts.Token;
            try
            {
                var res = await Task.Run(() =>
                {
                    var svc = new DriverUpdateService();
                    return svc.SearchAndInstall(install, s => _drivers.Log(s), ct);
                });

                _drivers.Report(res.Message);
                _drivers.SetProgressStatus(res.PolicyBlocked ? ProgressStatus.Error : ProgressStatus.Success);

                if (res.RebootRequired)
                {
                    MessageBox.Show(this,
                        "Драйверы установлены. Чтобы завершить установку, перезагрузите компьютер.",
                        "DriverCore", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
            }
            catch (Exception ex)
            {
                _drivers.Report("Ошибка: " + ex.Message);
                _drivers.SetProgressStatus(ProgressStatus.Error);
            }
            finally
            {
                _busy = false;
                _drivers.SetBusy(false);
            }
        }

        private async void RescanHardwareAsync()
        {
            if (_busy) { _drivers.Report("Дождитесь завершения текущей операции."); return; }
            if (!ElevationHelper.EnsureAdmin(this)) return;

            _busy = true;
            _drivers.SetBusy(true);
            try
            {
                await Task.Run(() => new DriverUpdateService().ScanForHardwareChanges(s => _drivers.Log(s)));
                _drivers.Report("Опрос оборудования завершён. Запустите проверку на вкладке «Обзор», чтобы обновить список.");
                _drivers.SetProgressStatus(ProgressStatus.Success);
            }
            finally
            {
                _busy = false;
                _drivers.SetBusy(false);
            }
        }

        // ───────────────────────── Компоненты ─────────────────────────

        private async void InstallComponentsAsync()
        {
            var selected = _components.GetSelected();
            if (selected.Count == 0) { _components.Report("Не отмечено ни одного компонента."); return; }
            if (_busy) { _components.Report("Дождитесь завершения текущей операции."); return; }
            if (!ElevationHelper.EnsureAdmin(this)) return;

            _busy = true;
            _components.SetBusy(true);
            _components.SetOverall(0);
            _cts = new CancellationTokenSource();
            var ct = _cts.Token;

            var installer = new ComponentInstaller();
            int done = 0, ok = 0;
            try
            {
                foreach (var c in selected)
                {
                    var comp = c;
                    _components.Report($"Устанавливаю: {comp.DisplayName}…");
                    bool r = await installer.InstallAsync(comp,
                        pct => UiPost(() => _components.RowProgress(comp, pct)),
                        s => _components.Log(s), ct);
                    _components.RowFinish(comp, r);
                    if (r) ok++;
                    done++;
                    _components.SetOverall(done * 100 / selected.Count);
                }

                _components.RefreshStates();
                ComponentCatalog.Refresh(_catalog);
                if (_inventory != null) _overview.ShowSummary(_inventory, _catalog);

                _components.SetOverall(100, ok == selected.Count ? ProgressStatus.Success : ProgressStatus.Error);
                _components.Report($"Готово: успешно установлено {ok} из {selected.Count}.");
            }
            catch (Exception ex)
            {
                _components.Report("Ошибка установки: " + ex.Message);
            }
            finally
            {
                _busy = false;
                _components.SetBusy(false);
            }
        }

        private async void RefreshComponentsAsync()
        {
            if (_busy) return;
            _busy = true;
            _components.SetBusy(true);
            try
            {
                await Task.Run(() => ComponentCatalog.Refresh(_catalog));
                _components.ShowComponents(_catalog);
                _components.Report("Статус компонентов обновлён.");
                if (_inventory != null) _overview.ShowSummary(_inventory, _catalog);
            }
            finally
            {
                _busy = false;
                _components.SetBusy(false);
            }
        }
    }
}
