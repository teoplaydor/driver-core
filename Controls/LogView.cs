using System;
using System.Windows.Forms;
using DriverCore.Theme;

namespace DriverCore.Controls
{
    /// <summary>Тёмное лог-поле с автопрокруткой и потокобезопасным добавлением строк.</summary>
    internal sealed class LogView : RichTextBox
    {
        public LogView()
        {
            ReadOnly = true;
            BorderStyle = BorderStyle.None;
            BackColor = Palette.TitleBar;
            ForeColor = Palette.TextSecondary;
            Font = Fonts.Mono;
            WordWrap = false;
            ScrollBars = RichTextBoxScrollBars.Vertical;
            TabStop = false;
            DetectUrls = false;
        }

        public void AppendLine(string text)
        {
            if (IsDisposed) return;
            if (InvokeRequired)
            {
                try { BeginInvoke(new Action<string>(AppendLine), text); } catch { }
                return;
            }
            AppendText((Text.Length > 0 ? Environment.NewLine : "") + text);
            SelectionStart = TextLength;
            ScrollToCaret();
        }

        public void Clear2()
        {
            if (InvokeRequired) { try { BeginInvoke(new Action(Clear2)); } catch { } return; }
            Clear();
        }
    }
}
