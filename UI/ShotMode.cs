using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Threading;
using System.Windows.Forms;

namespace DriverCore.UI
{
    /// <summary>Рендерит вкладки приложения в PNG (для проверки внешнего вида без интерактивного доступа).</summary>
    internal static class ShotMode
    {
        public static int Run(string dir)
        {
            try
            {
                Directory.CreateDirectory(dir);
                using (var f = new MainForm { ShotMode = true })
                {
                    f.StartPosition = FormStartPosition.Manual;
                    f.Location = new Point(-4000, -4000);
                    f.Size = new Size(1140, 760);
                    f.Show();
                    Application.DoEvents();

                    f.PrepareForShot();
                    Application.DoEvents();

                    string[] names = { "1-overview", "2-hardware", "3-drivers", "4-components" };
                    for (int i = 0; i < 4; i++)
                    {
                        f.ShowPagePublic(i);
                        f.Refresh();
                        Application.DoEvents();
                        Thread.Sleep(200);
                        Application.DoEvents();

                        using (var bmp = new Bitmap(f.Width, f.Height))
                        {
                            f.DrawToBitmap(bmp, new Rectangle(0, 0, f.Width, f.Height));
                            bmp.Save(Path.Combine(dir, names[i] + ".png"), ImageFormat.Png);
                        }
                    }

                    f.Close();
                }
                Console.WriteLine("Snapshots saved to " + dir);
                return 0;
            }
            catch (Exception ex)
            {
                try
                {
                    File.WriteAllText(Path.Combine(Path.GetTempPath(), "drivercore-shot-error.txt"), ex.ToString());
                }
                catch { }
                return 1;
            }
        }
    }
}
