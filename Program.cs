using System;
using System.Windows.Forms;
using DriverCore.Services;
using DriverCore.UI;

namespace DriverCore
{
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            if (args != null)
            {
                foreach (var a in args)
                    if (string.Equals(a, "--scan", StringComparison.OrdinalIgnoreCase))
                        return HeadlessScan.Run();

                if (args.Length >= 2 && string.Equals(args[0], "--shot", StringComparison.OrdinalIgnoreCase))
                {
                    Application.EnableVisualStyles();
                    Application.SetCompatibleTextRenderingDefault(false);
                    return ShotMode.Run(args[1]);
                }
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            try
            {
                Application.Run(new MainForm());
            }
            catch (Exception ex)
            {
                try
                {
                    System.IO.File.WriteAllText(
                        System.IO.Path.Combine(System.IO.Path.GetTempPath(), "drivercore-error.txt"),
                        ex.ToString());
                }
                catch { }
                MessageBox.Show(ex.ToString(), "DriverCore — ошибка",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
            return 0;
        }
    }
}
