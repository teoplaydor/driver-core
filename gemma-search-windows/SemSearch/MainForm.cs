using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;
using SemSearch.Core;

namespace SemSearch
{
    /// <summary>Main window: Search · Index · Model pages, tray icon and a global hotkey (Ctrl+Alt+Space).</summary>
    public sealed class MainForm : Form
    {
        // Coffee palette shared with DriverCore.
        internal static readonly Color WindowBg = ColorTranslator.FromHtml("#1E1712");
        internal static readonly Color Header = ColorTranslator.FromHtml("#160F0A");
        internal static readonly Color Surface = ColorTranslator.FromHtml("#2A211A");
        internal static readonly Color SurfaceHover = ColorTranslator.FromHtml("#362A20");
        internal static readonly Color SurfaceActive = ColorTranslator.FromHtml("#43352A");
        internal static readonly Color Accent = ColorTranslator.FromHtml("#C8A06A");
        internal static readonly Color AccentHover = ColorTranslator.FromHtml("#DBB67E");
        internal static readonly Color AccentText = ColorTranslator.FromHtml("#241B12");
        internal static readonly Color TextPrimary = ColorTranslator.FromHtml("#F2E6D4");
        internal static readonly Color TextMuted = ColorTranslator.FromHtml("#A8957E");
        internal static readonly Color Border = ColorTranslator.FromHtml("#43352A");

        private static readonly Font FontBody = new Font("Segoe UI", 10f);
        private static readonly Font FontSmall = new Font("Segoe UI", 9f);
        private static readonly Font FontTitle = new Font("Segoe UI", 15f, FontStyle.Bold);
        private static readonly Font FontHeading = new Font("Segoe UI", 11.5f, FontStyle.Bold);
        private static readonly Font FontQuery = new Font("Segoe UI", 14f);

        private const int HotkeyId = 0x5353;
        private const int ThumbSize = 150;

        private readonly Engine engine;
        private readonly bool demo;
        private readonly Label headerStatus = new Label();
        private readonly List<Button> navButtons = new List<Button>();
        private readonly List<Control> pages = new List<Control>();

        // search
        private readonly TextBox query = new TextBox();
        private readonly CheckBox fImages = new CheckBox(), fDocs = new CheckBox();
        private readonly Label searchStatus = new Label();
        private readonly ListView results = new ListView();
        private readonly ImageList thumbs = new ImageList();
        private readonly Label emptyHint = new Label();
        private int searchGeneration;

        // index
        private readonly ListBox folders = new ListBox();
        private readonly CheckBox docsBox = new CheckBox();
        private readonly ComboBox detail = new ComboBox(), accelBox = new ComboBox(), bridgeBox = new ComboBox(), dimsBox = new ComboBox();
        private readonly Button indexButton = new Button();
        private readonly ProgressBar indexProgress = new ProgressBar();
        private readonly Label indexStatus = new Label(), indexStats = new Label(), accelInfo = new Label();

        // model
        private readonly Label modelStatus = new Label(), planText = new Label();
        private readonly ProgressBar dlProgress = new ProgressBar();
        private readonly TextBox repoBox = new TextBox(), tokenBox = new TextBox();
        private readonly CheckBox visionBox = new CheckBox();
        private readonly Button dlButton = new Button(), cancelButton = new Button(), deleteButton = new Button(), copyError = new Button();

        private readonly NotifyIcon tray = new NotifyIcon();
        private bool reallyExit;

        public MainForm(Engine engine, bool demo = false)
        {
            this.engine = engine;
            this.demo = demo;
            Text = "Смысловой поиск";
            BackColor = WindowBg;
            ForeColor = TextPrimary;
            Font = FontBody;
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(1120, 760);
            MinimumSize = new Size(820, 560);
            StartPosition = FormStartPosition.CenterScreen;
            AllowDrop = true;
            try { Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath); }
            catch (Exception) { }

            var content = new Panel { Dock = DockStyle.Fill, BackColor = WindowBg };
            pages.Add(BuildSearch());
            pages.Add(BuildIndex());
            pages.Add(BuildModel());
            foreach (var p in pages)
            {
                p.Dock = DockStyle.Fill;
                content.Controls.Add(p);
            }
            Controls.Add(content);
            Controls.Add(BuildHeader());

            tray.Text = "Смысловой поиск";
            tray.Icon = Icon ?? SystemIcons.Application;
            tray.Visible = !demo;
            var menu = MakeMenu();
            menu.Items.Add("Открыть поиск (Ctrl+Alt+Space)", null, (s, e) => ShowSearch());
            menu.Items.Add("Индексировать", null, (s, e) => engine.StartIndex());
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Выход", null, (s, e) => { reallyExit = true; Close(); });
            tray.ContextMenuStrip = menu;
            tray.DoubleClick += (s, e) => ShowSearch();

            engine.Changed += OnEngineChanged;
            SelectPage(engine.Current == Engine.State.NoModel ? 2 : 0);
            Refresh2();
        }

        // ------------------------------------------------------------------ window plumbing

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            Native.DarkTitleBar(Handle);
            Refresh2(); // engine notifications before this point were dropped
            if (!demo) Native.RegisterHotKey(Handle, HotkeyId, Native.MOD_CONTROL | Native.MOD_ALT | Native.MOD_NOREPEAT, Native.VK_SPACE);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == Native.WM_HOTKEY && (int)m.WParam == HotkeyId)
            {
                if (Visible && WindowState != FormWindowState.Minimized && ContainsFocus) Hide();
                else ShowSearch();
            }
            base.WndProc(ref m);
        }

        public void ShowSearch()
        {
            Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
            Native.SetForegroundWindow(Handle);
            SelectPage(engine.Current == Engine.State.NoModel ? 2 : 0);
            query.Focus();
            query.SelectAll();
        }

        protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
        {
            // Esc hides the search window from anywhere on the search page (the settings pages keep Esc for their lists).
            if (keyData == Keys.Escape && pages[0].Visible && !demo)
            {
                Hide();
                return true;
            }
            return base.ProcessCmdKey(ref msg, keyData);
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!reallyExit && e.CloseReason == CloseReason.UserClosing && !demo)
            {
                // Keep running in the tray: indexing continues and the hotkey opens search instantly.
                e.Cancel = true;
                Hide();
                if (!engine.S.TrayHintShown)
                {
                    tray.ShowBalloonTip(5000, "Смысловой поиск работает в фоне",
                        "Ctrl+Alt+Space — открыть поиск. Выход — правой кнопкой по значку в трее.", ToolTipIcon.Info);
                    engine.S.TrayHintShown = true;
                    engine.S.Save();
                }
                return;
            }
            tray.Visible = false;
            if (!demo) Native.UnregisterHotKey(Handle, HotkeyId);
            base.OnFormClosing(e);
        }

        protected override void OnDragEnter(DragEventArgs e)
        {
            base.OnDragEnter(e);
            if (e.Data.GetDataPresent(DataFormats.FileDrop)) e.Effect = DragDropEffects.Copy;
        }

        protected override void OnDragDrop(DragEventArgs e)
        {
            base.OnDragDrop(e);
            var paths = e.Data.GetData(DataFormats.FileDrop) as string[];
            if (paths == null || paths.Length == 0) return;
            string p = paths[0];
            if (Directory.Exists(p))
            {
                AddFolder(p);
                SelectPage(1);
            }
            else if (ImageFile.Extensions.Contains(Path.GetExtension(p)))
            {
                SelectPage(0);
                RunSearch(engine.SearchByImageAsync(p, fImages.Checked, fDocs.Checked), "Ищу похожие на " + Path.GetFileName(p) + "…");
            }
        }

        private void OnEngineChanged()
        {
            if (IsDisposed || !IsHandleCreated) return;
            try { BeginInvoke(new Action(Refresh2)); }
            catch (Exception) { }
        }

        // ------------------------------------------------------------------ helpers

        private sealed class MenuColors : ProfessionalColorTable
        {
            public override Color ToolStripDropDownBackground => Surface;
            public override Color ImageMarginGradientBegin => Surface;
            public override Color ImageMarginGradientMiddle => Surface;
            public override Color ImageMarginGradientEnd => Surface;
            public override Color MenuBorder => Border;
            public override Color MenuItemBorder => SurfaceHover;
            public override Color MenuItemSelected => SurfaceHover;
            public override Color MenuItemSelectedGradientBegin => SurfaceHover;
            public override Color MenuItemSelectedGradientEnd => SurfaceHover;
            public override Color SeparatorDark => Border;
            public override Color SeparatorLight => Surface;
        }

        private static ContextMenuStrip MakeMenu()
        {
            return new ContextMenuStrip
            {
                Renderer = new ToolStripProfessionalRenderer(new MenuColors()) { RoundedEdges = false },
                BackColor = Surface, ForeColor = TextPrimary, Font = FontBody, ShowImageMargin = false
            };
        }

        private static Button MakeButton(string text, bool accent)
        {
            var b = new Button
            {
                Text = text, AutoSize = true, FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand,
                BackColor = accent ? Accent : SurfaceActive, ForeColor = accent ? AccentText : TextPrimary,
                Padding = new Padding(12, 5, 12, 5), Margin = new Padding(0, 4, 8, 4), Font = FontBody,
                UseVisualStyleBackColor = false
            };
            b.FlatAppearance.BorderSize = 0;
            b.FlatAppearance.MouseOverBackColor = accent ? AccentHover : SurfaceHover;
            return b;
        }

        private static Label MakeLabel(string text, Font font, Color color, int width = 0)
        {
            var l = new Label { Text = text, Font = font, ForeColor = color, AutoSize = true, Margin = new Padding(0, 4, 0, 4) };
            if (width > 0) l.MaximumSize = new Size(width, 0);
            return l;
        }

        private static void StyleCombo(ComboBox c, IEnumerable<string> items, int width)
        {
            c.DropDownStyle = ComboBoxStyle.DropDownList;
            c.FlatStyle = FlatStyle.Flat;
            c.BackColor = Surface;
            c.ForeColor = TextPrimary;
            c.Width = width;
            c.Items.AddRange(items.Cast<object>().ToArray());
        }

        private static void StyleCheck(CheckBox c, string text, bool on)
        {
            c.Text = text;
            c.Checked = on;
            c.AutoSize = true;
            c.ForeColor = TextPrimary;
            c.Margin = new Padding(0, 6, 16, 4);
        }

        private static FlowLayoutPanel Card(Control parent, string title, int width)
        {
            var card = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true, BackColor = Surface,
                Padding = new Padding(16, 12, 16, 14), Margin = new Padding(0, 0, 0, 12), MinimumSize = new Size(width, 0),
                MaximumSize = new Size(width, 0)
            };
            if (title != null) card.Controls.Add(MakeLabel(title, FontHeading, TextPrimary));
            parent.Controls.Add(card);
            return card;
        }

        private static FlowLayoutPanel Row()
        {
            return new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, AutoSize = true, WrapContents = true, Margin = new Padding(0) };
        }

        // ------------------------------------------------------------------ header & navigation

        private Control BuildHeader()
        {
            var header = new Panel { Dock = DockStyle.Top, Height = 64, BackColor = Header, Padding = new Padding(18, 8, 18, 8) };
            var title = MakeLabel("Смысловой поиск", FontTitle, TextPrimary);
            title.Location = new Point(18, 8);
            headerStatus.Font = FontSmall;
            headerStatus.ForeColor = TextMuted;
            headerStatus.AutoSize = true;
            headerStatus.Location = new Point(20, 38);
            header.Controls.Add(title);
            header.Controls.Add(headerStatus);
            var nav = new FlowLayoutPanel { Dock = DockStyle.Right, FlowDirection = FlowDirection.LeftToRight, WrapContents = false, Padding = new Padding(0, 12, 0, 0) };
            string[] names = { "Поиск", "Индекс", "Модель" };
            for (int i = 0; i < names.Length; i++)
            {
                int idx = i;
                var b = MakeButton(names[i], false);
                b.Click += (s, e) => SelectPage(idx);
                navButtons.Add(b);
                nav.Controls.Add(b);
            }
            // A docked FlowLayoutPanel does not auto-size its width reliably: size it to the buttons.
            nav.Width = nav.GetPreferredSize(new Size(int.MaxValue, header.Height)).Width;
            header.Controls.Add(nav);
            return header;
        }

        private void SelectPage(int idx)
        {
            for (int i = 0; i < pages.Count; i++)
            {
                pages[i].Visible = i == idx;
                navButtons[i].BackColor = i == idx ? Accent : SurfaceActive;
                navButtons[i].ForeColor = i == idx ? AccentText : TextPrimary;
                navButtons[i].FlatAppearance.MouseOverBackColor = i == idx ? AccentHover : SurfaceHover;
                navButtons[i].FlatAppearance.MouseDownBackColor = i == idx ? AccentHover : SurfaceHover;
            }
            if (idx == 0) query.Focus();
        }

        // ------------------------------------------------------------------ search page

        private Control BuildSearch()
        {
            var page = new Panel { BackColor = WindowBg, Padding = new Padding(18, 14, 18, 10) };

            var top = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, FlowDirection = FlowDirection.LeftToRight, WrapContents = false };
            var qbox = new Panel { BackColor = Surface, Padding = new Padding(12, 9, 12, 9), Size = new Size(640, 46), Margin = new Padding(0, 0, 10, 0) };
            query.BorderStyle = BorderStyle.None;
            query.BackColor = Surface;
            query.ForeColor = TextPrimary;
            query.Font = FontQuery;
            query.Dock = DockStyle.Fill;
            query.PlaceholderText = "Что ищем? Например: кот на диване, чек из кафе, договор аренды";
            query.KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.Enter)
                {
                    e.SuppressKeyPress = true;
                    DoSearch();
                }
            };
            qbox.Controls.Add(query);
            top.Controls.Add(qbox);
            var go = MakeButton("Найти", true);
            go.Click += (s, e) => DoSearch();
            top.Controls.Add(go);
            var byImage = MakeButton("По картинке…", false);
            byImage.Click += (s, e) =>
            {
                using (var d = new OpenFileDialog { Filter = "Изображения|*.jpg;*.jpeg;*.png;*.bmp;*.gif;*.tif;*.tiff" })
                    if (d.ShowDialog(this) == DialogResult.OK)
                        RunSearch(engine.SearchByImageAsync(d.FileName, fImages.Checked, fDocs.Checked), "Ищу похожие…");
            };
            top.Controls.Add(byImage);

            var filters = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(0, 6, 0, 2) };
            StyleCheck(fImages, "Фото", true);
            StyleCheck(fDocs, "Документы и код", true);
            filters.Controls.Add(fImages);
            filters.Controls.Add(fDocs);
            searchStatus.AutoSize = true;
            searchStatus.ForeColor = TextMuted;
            searchStatus.Margin = new Padding(8, 7, 0, 0);
            filters.Controls.Add(searchStatus);

            thumbs.ImageSize = new Size(ThumbSize, ThumbSize);
            thumbs.ColorDepth = ColorDepth.Depth32Bit;
            results.View = View.LargeIcon;
            results.LargeImageList = thumbs;
            results.BackColor = WindowBg;
            results.ForeColor = TextPrimary;
            results.BorderStyle = BorderStyle.None;
            results.Dock = DockStyle.Fill;
            results.MultiSelect = false;
            results.ShowItemToolTips = true;
            results.HandleCreated += (s, e) => Native.DarkControl(results.Handle);
            results.ItemActivate += (s, e) => OpenSelected();
            var ctx = MakeMenu();
            ctx.Items.Add("Открыть", null, (s, e) => OpenSelected());
            ctx.Items.Add("Показать в папке", null, (s, e) =>
            {
                if (Selected() is string p) Process.Start("explorer.exe", "/select,\"" + p + "\"");
            });
            ctx.Items.Add("Найти похожие", null, (s, e) =>
            {
                if (Selected() is string p) RunSearch(engine.SimilarAsync(p, fImages.Checked, fDocs.Checked), "Ищу похожие…");
            });
            ctx.Items.Add("Копировать путь", null, (s, e) =>
            {
                if (Selected() is string p) Clipboard.SetText(p);
            });
            results.ContextMenuStrip = ctx;

            emptyHint.Text = "Найдите фото, документы и код по смыслу — на любом языке.\n\n"
                             + "1. Скачайте модель на вкладке «Модель» (один раз, несколько сотен МБ).\n"
                             + "2. На вкладке «Индекс» выберите папки и нажмите «Начать индексацию».\n"
                             + "3. Пишите запрос словами, перетащите картинку в окно, чтобы найти похожие.\n\n"
                             + "Ctrl+Alt+Space — открыть поиск из любого окна. Enter — найти, Esc — спрятать.\n"
                             + "Число под файлом — косинусное сходство: для «текст ↔ фото» обычно 0,1–0,4, важен порядок.";
            emptyHint.ForeColor = TextMuted;
            emptyHint.Font = FontBody;
            emptyHint.Dock = DockStyle.Fill;
            emptyHint.Padding = new Padding(4, 24, 4, 0);

            var area = new Panel { Dock = DockStyle.Fill };
            area.Controls.Add(results);
            area.Controls.Add(emptyHint);
            emptyHint.BringToFront();

            page.Controls.Add(area);
            page.Controls.Add(filters);
            page.Controls.Add(top);
            return page;
        }

        private string Selected() => results.SelectedItems.Count > 0 ? results.SelectedItems[0].Tag as string : null;

        private void OpenSelected()
        {
            if (Selected() is string p)
            {
                try { Process.Start(new ProcessStartInfo(p) { UseShellExecute = true }); }
                catch (Exception e) { MessageBox.Show(this, e.Message, "Не удалось открыть файл"); }
            }
        }

        private void DoSearch()
        {
            string q = query.Text.Trim();
            if (q.Length == 0) return;
            if (!engine.Ready)
            {
                searchStatus.Text = "Сначала скачайте модель (вкладка «Модель»)";
                return;
            }
            RunSearch(engine.SearchAsync(q, fImages.Checked, fDocs.Checked), "Ищу…");
        }

        private async void RunSearch(Task<Engine.SearchResult> task, string pending)
        {
            int gen = ++searchGeneration;
            searchStatus.Text = pending;
            try
            {
                var r = await task;
                if (gen != searchGeneration) return;
                ShowResults(r);
            }
            catch (Exception e)
            {
                if (gen == searchGeneration) searchStatus.Text = "Ошибка: " + e.Message;
            }
        }

        private void ShowResults(Engine.SearchResult r)
        {
            int gen = searchGeneration;
            results.BeginUpdate();
            results.Items.Clear();
            thumbs.Images.Clear();
            AddThumb("_loading", Placeholder(null));
            foreach (var h in r.Hits)
            {
                var item = new ListViewItem(Path.GetFileName(h.Item.Path) + "\n" + h.Score.ToString("0.00"))
                {
                    Tag = h.Item.Path, ImageKey = "_loading", ToolTipText = h.Item.Path
                };
                results.Items.Add(item);
            }
            results.EndUpdate();
            emptyHint.Visible = r.Hits.Count == 0;
            int total = engine.Index.Count(VectorIndex.KindImage) + engine.Index.Count(VectorIndex.KindDocument);
            searchStatus.Text = r.Hits.Count == 0 ? "Ничего не найдено — индекс пуст? Вкладка «Индекс»."
                : $"{r.Label} · топ-{r.Hits.Count} из {total} · {r.Millis} мс · {engine.SearchDims} изм.";
            var paths = r.Hits.Select(h => (h.Item.Path, h.Item.Kind)).ToList();
            Task.Run(() =>
            {
                for (int i = 0; i < paths.Count; i++)
                {
                    if (gen != searchGeneration) return;
                    Bitmap bmp;
                    try { bmp = paths[i].Kind == VectorIndex.KindDocument ? DocTile(paths[i].Path) : PhotoTile(paths[i].Path); }
                    catch (Exception) { bmp = Placeholder(Path.GetExtension(paths[i].Path)); }
                    int idx = i;
                    try
                    {
                        BeginInvoke(new Action(() =>
                        {
                            if (gen != searchGeneration || idx >= results.Items.Count) { bmp.Dispose(); return; }
                            string key = "t" + idx;
                            AddThumb(key, bmp);
                            results.Items[idx].ImageKey = key;
                        }));
                    }
                    catch (Exception) { return; }
                }
            });
        }

        /// <summary>The image list copies the bitmap into its native list once it has a handle; the original can go.</summary>
        private void AddThumb(string key, Bitmap bmp)
        {
            thumbs.Images.Add(key, bmp);
            if (thumbs.HandleCreated) bmp.Dispose();
        }

        private static Bitmap Square(Action<Graphics> draw)
        {
            var bmp = new Bitmap(ThumbSize, ThumbSize);
            using (var g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.Clear(Surface);
                draw(g);
            }
            return bmp;
        }

        private static Bitmap PhotoTile(string path)
        {
            using (var img = ImageFile.Open(path))
            using (var t = img.Thumbnail(ThumbSize))
                return Square(g => g.DrawImage(t, (ThumbSize - t.Width) / 2, (ThumbSize - t.Height) / 2, t.Width, t.Height));
        }

        private static Bitmap DocTile(string path)
        {
            string snippet = "";
            try { snippet = (TextExtract.Extract(path) ?? "").Replace("\r", ""); }
            catch (Exception) { }
            if (snippet.Length > 220) snippet = snippet.Substring(0, 220);
            return Square(g =>
            {
                using (var ext = new Font("Segoe UI", 9f, FontStyle.Bold))
                using (var body = new Font("Segoe UI", 7.5f))
                using (var accent = new SolidBrush(Accent))
                using (var text = new SolidBrush(TextMuted))
                {
                    g.DrawString(Path.GetExtension(path).TrimStart('.').ToUpperInvariant(), ext, accent, 8, 6);
                    g.DrawString(snippet, body, text, new RectangleF(8, 26, ThumbSize - 16, ThumbSize - 32));
                }
            });
        }

        private static Bitmap Placeholder(string ext) => Square(g =>
        {
            using (var b = new SolidBrush(TextMuted))
            using (var f = new Font("Segoe UI", 9f))
                g.DrawString(ext ?? "…", f, b, 8, 8);
        });

        // ------------------------------------------------------------------ index page

        private Control BuildIndex()
        {
            var page = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, BackColor = WindowBg,
                Padding = new Padding(18, 14, 18, 14)
            };
            const int w = 760;

            var f = Card(page, "Папки для индексации", w);
            f.Controls.Add(MakeLabel("Подпапки тоже обходятся; скрытые, системные и облачные (не скачанные) файлы пропускаются. "
                                     + "Папку можно просто перетащить в окно.", FontSmall, TextMuted, w - 40));
            folders.BackColor = WindowBg;
            folders.ForeColor = TextPrimary;
            folders.BorderStyle = BorderStyle.None;
            folders.Size = new Size(w - 40, 120);
            folders.Items.AddRange(engine.S.Folders.Cast<object>().ToArray());
            f.Controls.Add(folders);
            var fr = Row();
            var add = MakeButton("Добавить папку…", false);
            add.Click += (s, e) =>
            {
                using (var d = new FolderBrowserDialog { ShowNewFolderButton = false })
                    if (d.ShowDialog(this) == DialogResult.OK) AddFolder(d.SelectedPath);
            };
            var rem = MakeButton("Убрать выбранную", false);
            rem.Click += (s, e) =>
            {
                if (folders.SelectedItem is string p)
                {
                    engine.S.Folders.Remove(p);
                    engine.S.Save();
                    folders.Items.Remove(p);
                }
            };
            fr.Controls.Add(add);
            fr.Controls.Add(rem);
            f.Controls.Add(fr);
            StyleCheck(docsBox, "Индексировать документы и код (txt, md, docx, исходники)", engine.S.IndexDocuments);
            docsBox.CheckedChanged += (s, e) =>
            {
                engine.S.IndexDocuments = docsBox.Checked;
                engine.S.Save();
            };
            f.Controls.Add(docsBox);

            var c = Card(page, "Индексация", w);
            c.Controls.Add(MakeLabel("Детализация фото: больше токенов — точнее мелкие детали и текст на скриншотах, но медленнее: "
                                     + "140 примерно в 2,5 раза быстрее 280, а 70 — ещё вдвое. Для обычных фото хватает 140.",
                FontSmall, TextMuted, w - 40));
            StyleCombo(detail, new[] { "Быстро — 70 токенов", "Средне — 140 токенов", "Максимум — 280 токенов" }, 300);
            detail.SelectedIndex = Math.Max(0, Array.IndexOf(Engine.PhotoBudgets, engine.S.PhotoBudget));
            detail.SelectedIndexChanged += (s, e) =>
            {
                engine.S.PhotoBudget = Engine.PhotoBudgets[detail.SelectedIndex];
                engine.S.Save();
            };
            c.Controls.Add(detail);
            var ir = Row();
            indexButton.Text = "Начать индексацию";
            Restyle(indexButton, true);
            indexButton.Click += (s, e) =>
            {
                if (engine.Indexing) engine.StopIndex();
                else if (!engine.Ready) SelectPage(2);
                else engine.StartIndex();
            };
            ir.Controls.Add(indexButton);
            var clear = MakeButton("Очистить индекс", false);
            clear.Click += (s, e) =>
            {
                if (MessageBox.Show(this, "Удалить все векторы? Сами файлы не трогаются.", "Очистить индекс",
                        MessageBoxButtons.OKCancel) == DialogResult.OK) engine.ClearIndex();
            };
            ir.Controls.Add(clear);
            c.Controls.Add(ir);
            indexProgress.Size = new Size(w - 40, 8);
            indexProgress.Maximum = 1000;
            c.Controls.Add(indexProgress);
            indexStatus.AutoSize = true;
            indexStatus.MaximumSize = new Size(w - 40, 0);
            indexStatus.ForeColor = TextMuted;
            c.Controls.Add(indexStatus);
            indexStats.AutoSize = true;
            indexStats.Font = FontHeading;
            c.Controls.Add(indexStats);

            var a = Card(page, "Ускорение", w);
            a.Controls.Add(MakeLabel("Где считается модель. «Подобрать» замерит процессор и видеокарту (WebGPU), обычный режим и int8, "
                                     + "для видеокарты — и размер пачки, и сохранит самое быстрое. Видеокарта подойдёт любая с DirectX 12; "
                                     + "для неё один раз скачается компилятор шейдеров (~8 МБ), без него тоже работает.",
                FontSmall, TextMuted, w - 40));
            var ar = Row();
            StyleCombo(accelBox, Engine.AccelNames, 260);
            accelBox.SelectedIndex = Math.Max(0, Math.Min(Engine.AccelNames.Length - 1, engine.S.Accel));
            accelBox.SelectedIndexChanged += (s, e) =>
            {
                if (accelBox.SelectedIndex == engine.S.Accel) return;
                engine.S.Accel = accelBox.SelectedIndex;
                engine.S.Save();
                if (engine.Ready && !engine.Indexing) _ = engine.LoadModelAsync();
            };
            ar.Controls.Add(accelBox);
            var bench = MakeButton("Подобрать самое быстрое (~1 мин)", true);
            bench.Click += async (s, e) =>
            {
                if (!engine.Ready || engine.Indexing)
                {
                    MessageBox.Show(this, engine.Indexing ? "Остановите индексацию." : "Сначала скачайте модель.", "Подбор ускорения");
                    return;
                }
                bench.Enabled = false;
                string report;
                try { report = await engine.BenchmarkAsync(); }
                catch (Exception ex) { report = ex.Message; }
                bench.Enabled = true;
                accelBox.SelectedIndex = engine.S.Accel;
                ShowReport("Скорость индексации", report);
            };
            ar.Controls.Add(bench);
            a.Controls.Add(ar);
            accelInfo.AutoSize = true;
            accelInfo.ForeColor = TextMuted;
            a.Controls.Add(accelInfo);

            var sc = Card(page, "Поиск", w);
            sc.Controls.Add(MakeLabel("Русские запросы к фото: модель лучше связывает картинки с английским текстом, поэтому запрос "
                                      + "дополнительно переводится по встроенному словарю. Документы ищутся по исходному тексту.",
                FontSmall, TextMuted, w - 40));
            StyleCombo(bridgeBox, Engine.BridgeModes, 420);
            bridgeBox.SelectedIndex = Math.Max(0, Math.Min(2, engine.S.BridgeMode));
            bridgeBox.SelectedIndexChanged += (s, e) =>
            {
                engine.S.BridgeMode = bridgeBox.SelectedIndex;
                engine.S.Save();
            };
            sc.Controls.Add(bridgeBox);
            sc.Controls.Add(MakeLabel("Длина вектора (Matryoshka): короче — меньше памяти, чуть ниже точность.", FontSmall, TextMuted, w - 40));
            int[] dims = { 768, 512, 256, 128 };
            StyleCombo(dimsBox, new[] { "768 (полная)", "512", "256", "128" }, 200);
            dimsBox.SelectedIndex = Math.Max(0, Array.IndexOf(dims, engine.S.Dims));
            dimsBox.SelectedIndexChanged += (s, e) =>
            {
                engine.S.Dims = dims[dimsBox.SelectedIndex];
                engine.S.Save();
            };
            sc.Controls.Add(dimsBox);
            return page;
        }

        private static void Restyle(Button b, bool accent)
        {
            var t = MakeButton(b.Text, accent);
            b.AutoSize = true;
            b.FlatStyle = FlatStyle.Flat;
            b.BackColor = t.BackColor;
            b.ForeColor = t.ForeColor;
            b.Padding = t.Padding;
            b.Margin = t.Margin;
            b.Cursor = Cursors.Hand;
            b.UseVisualStyleBackColor = false;
            b.FlatAppearance.BorderSize = 0;
            b.FlatAppearance.MouseOverBackColor = t.FlatAppearance.MouseOverBackColor;
            t.Dispose();
        }

        private void AddFolder(string p)
        {
            if (engine.S.Folders.Any(x => string.Equals(x, p, StringComparison.OrdinalIgnoreCase))) return;
            engine.S.Folders.Add(p);
            engine.S.Save();
            folders.Items.Add(p);
        }

        private void ShowReport(string title, string report)
        {
            using (var d = new Form
                   {
                       Text = title, BackColor = WindowBg, ForeColor = TextPrimary, Font = FontBody, Size = new Size(760, 480),
                       StartPosition = FormStartPosition.CenterParent, MinimizeBox = false, MaximizeBox = false
                   })
            {
                var box = new TextBox
                {
                    Text = report.Replace("\n", "\r\n"), Multiline = true, ReadOnly = true, Dock = DockStyle.Fill,
                    BackColor = Surface, ForeColor = TextPrimary, BorderStyle = BorderStyle.None, ScrollBars = ScrollBars.Vertical,
                    Font = new Font("Consolas", 10f)
                };
                var bar = new FlowLayoutPanel { Dock = DockStyle.Bottom, AutoSize = true, FlowDirection = FlowDirection.RightToLeft, Padding = new Padding(8) };
                var close = MakeButton("Закрыть", false);
                close.Click += (s, e) => d.Close();
                var copy = MakeButton("Скопировать", true);
                copy.Click += (s, e) => Clipboard.SetText(report);
                bar.Controls.Add(close);
                bar.Controls.Add(copy);
                d.Controls.Add(box);
                d.Controls.Add(bar);
                d.HandleCreated += (s, e) => Native.DarkTitleBar(d.Handle);
                d.ShowDialog(this);
            }
        }

        // ------------------------------------------------------------------ model page

        private Control BuildModel()
        {
            var page = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, BackColor = WindowBg,
                Padding = new Padding(18, 14, 18, 14)
            };
            const int w = 760;
            var about = Card(page, "EmbeddingGemma 2", w);
            about.Controls.Add(MakeLabel("Открытая модель Google DeepMind (740M, Apache 2.0). Переводит текст, код и фото в общее "
                                         + "векторное пространство на 768 чисел: текстовый запрос находит картинку, картинка — похожие "
                                         + "фото и документы. После загрузки всё работает офлайн, файлы никуда не отправляются.\n\n"
                                         + "Формат: ONNX (onnx-community), квантование q4, ONNX Runtime на процессоре или видеокарте (WebGPU).",
                FontSmall, TextMuted, w - 40));

            var st = Card(page, "Состояние", w);
            modelStatus.AutoSize = true;
            modelStatus.MaximumSize = new Size(w - 40, 0);
            st.Controls.Add(modelStatus);
            dlProgress.Size = new Size(w - 40, 8);
            dlProgress.Maximum = 1000;
            st.Controls.Add(dlProgress);
            copyError.Text = "Скопировать подробности ошибки";
            Restyle(copyError, false);
            copyError.Click += (s, e) => { if (engine.ErrorDetails != null) Clipboard.SetText(engine.ErrorDetails); };
            st.Controls.Add(copyError);

            var src = Card(page, "Загрузка", w);
            src.Controls.Add(MakeLabel("Репозиторий Hugging Face", FontSmall, TextMuted));
            foreach (var tb in new[] { repoBox, tokenBox })
            {
                tb.BackColor = WindowBg;
                tb.ForeColor = TextPrimary;
                tb.BorderStyle = BorderStyle.FixedSingle;
                tb.Width = w - 40;
            }
            repoBox.Text = engine.S.Repo;
            src.Controls.Add(repoBox);
            tokenBox.UseSystemPasswordChar = true;
            tokenBox.PlaceholderText = "Токен HF — только если доступ закрыт";
            tokenBox.Text = engine.S.Token;
            src.Controls.Add(tokenBox);
            StyleCheck(visionBox, "Фото (визуальный энкодер)", engine.S.Vision);
            src.Controls.Add(visionBox);
            planText.AutoSize = true;
            planText.MaximumSize = new Size(w - 40, 0);
            planText.ForeColor = TextMuted;
            src.Controls.Add(planText);
            var r = Row();
            var check = MakeButton("Проверить", false);
            check.Click += async (s, e) =>
            {
                planText.Text = "Проверяю репозиторий…";
                try
                {
                    var p = await engine.CheckRepoAsync(repoBox.Text, tokenBox.Text, visionBox.Checked);
                    planText.Text = $"Будет скачано {p.TotalBytes / 1048576.0:F0} МБ:\n" + string.Join("\n",
                        p.Files.Where(x => x.Size > 1 << 20).Select(x => $"• {x.Path} — {x.Size / 1048576.0:F0} МБ")) + "\n• конфиги и токенизатор";
                }
                catch (Exception ex)
                {
                    planText.Text = "Ошибка: " + ex.Message;
                }
            };
            r.Controls.Add(check);
            dlButton.Text = "Скачать";
            Restyle(dlButton, true);
            dlButton.Click += (s, e) =>
            {
                if (engine.Current == Engine.State.Error && engine.HasModelFiles) _ = engine.LoadModelAsync();
                else _ = engine.DownloadAsync(repoBox.Text, tokenBox.Text, visionBox.Checked);
            };
            r.Controls.Add(dlButton);
            cancelButton.Text = "Остановить загрузку";
            Restyle(cancelButton, false);
            cancelButton.Click += (s, e) => engine.CancelDownload();
            r.Controls.Add(cancelButton);
            deleteButton.Text = "Удалить модель";
            Restyle(deleteButton, false);
            deleteButton.Click += (s, e) =>
            {
                if (MessageBox.Show(this, "Удалить файлы модели? Индекс сохранится.", "Удалить модель", MessageBoxButtons.OKCancel)
                    == DialogResult.OK) _ = engine.DeleteModelAsync();
            };
            r.Controls.Add(deleteButton);
            src.Controls.Add(r);
            return page;
        }

        // ------------------------------------------------------------------ state → UI

        private void Refresh2()
        {
            var st = engine.Current;
            headerStatus.Text = st switch
            {
                Engine.State.Ready => "EmbeddingGemma 2 · на этом ПК · готово" + (engine.Indexing ? " · идёт индексация" : ""),
                Engine.State.Downloading => "Скачиваю модель…",
                Engine.State.Loading => "Загружаю модель…",
                Engine.State.Error => "Ошибка модели — вкладка «Модель»",
                _ => "Модель не скачана — вкладка «Модель»"
            };
            string ms = engine.Status ?? "";
            if (st == Engine.State.Downloading && engine.DlTotal > 0)
                ms += $"\n{engine.DlDone / 1048576.0:F0} из {engine.DlTotal / 1048576.0:F0} МБ";
            if (ms.Length == 0 && st == Engine.State.NoModel) ms = "Модель не скачана. Нажмите «Проверить», затем «Скачать».";
            modelStatus.Text = ms;
            bool busy = st == Engine.State.Downloading || st == Engine.State.Loading;
            dlProgress.Visible = busy;
            dlProgress.Style = st == Engine.State.Loading || engine.DlTotal <= 0 ? ProgressBarStyle.Marquee : ProgressBarStyle.Continuous;
            if (engine.DlTotal > 0) dlProgress.Value = (int)Math.Min(1000, 1000 * engine.DlDone / engine.DlTotal);
            dlButton.Enabled = !busy;
            dlButton.Text = st == Engine.State.Ready ? "Скачать заново"
                : st == Engine.State.Error && engine.HasModelFiles ? "Повторить загрузку" : "Скачать";
            cancelButton.Visible = st == Engine.State.Downloading;
            deleteButton.Enabled = !busy;
            copyError.Visible = st == Engine.State.Error && engine.ErrorDetails != null;

            indexButton.Text = engine.Indexing ? "Остановить" : "Начать индексацию";
            indexStatus.Text = engine.IdxStatus;
            indexProgress.Visible = engine.Indexing;
            indexProgress.Style = engine.IdxTotal == 0 ? ProgressBarStyle.Marquee : ProgressBarStyle.Continuous;
            if (engine.IdxTotal > 0) indexProgress.Value = Math.Min(1000, 1000 * engine.IdxDone / engine.IdxTotal);
            indexStats.Text = $"В индексе: фото {engine.Index.Count(VectorIndex.KindImage)} · документов {engine.Index.Count(VectorIndex.KindDocument)}";
            accelInfo.Text = engine.Ready
                ? "Сейчас: " + Engine.AccelNames[engine.LoadedAccel] + (engine.S.Threads > 0 ? ", потоков " + engine.S.Threads : ", потоков авто")
                  + (engine.S.Batch > 1 ? ", пачка " + engine.S.Batch : "") + (engine.S.AccelChosen ? "" : " · ещё не подбиралось")
                  + (engine.S.GpuBroken ? " · видеокарта отключена после сбоя драйвера" : "")
                : "";
        }

        // ------------------------------------------------------------------ screenshot mode (verification)

        /// <summary>Fills the search page with synthetic results so --shot can render it.</summary>
        internal void ShowDemoResults()
        {
            emptyHint.Visible = false;
            query.Text = "кот на диване";
            searchStatus.Text = "«кот на диване» → для фото «cat on sofa» · топ-8 из 1240 · 38 мс · 768 изм.";
            for (int i = 0; i < 8; i++)
            {
                var src = new PatternSource(400 + 60 * i, 300 + 40 * (i % 3), i);
                int[] px = src.Argb(ThumbSize, ThumbSize * 3 / 4);
                var bmp = new Bitmap(ThumbSize, ThumbSize * 3 / 4);
                for (int y = 0; y < bmp.Height; y++)
                    for (int x = 0; x < bmp.Width; x++) bmp.SetPixel(x, y, Color.FromArgb(px[y * bmp.Width + x]));
                thumbs.Images.Add("d" + i, Square(g => g.DrawImage(bmp, 0, (ThumbSize - bmp.Height) / 2)));
                results.Items.Add(new ListViewItem($"IMG_20{20 + i}0{i + 1}.jpg\n{0.34 - i * 0.02:0.00}") { ImageKey = "d" + i });
            }
        }

        internal void Shot(int page, string file)
        {
            SelectPage(page);
            PerformLayout();
            Refresh();
            for (int i = 0; i < 20; i++)
            {
                Application.DoEvents();
                System.Threading.Thread.Sleep(25);
            }
            // Capture what is actually on screen (DrawToBitmap skips some child controls).
            Rectangle r = RectangleToScreen(ClientRectangle);
            using (var bmp = new Bitmap(r.Width, r.Height))
            {
                using (var g = Graphics.FromImage(bmp)) g.CopyFromScreen(r.Location, Point.Empty, r.Size);
                bmp.Save(file, System.Drawing.Imaging.ImageFormat.Png);
            }
        }
    }
}
