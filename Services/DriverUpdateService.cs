using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;
using DriverCore.Models;

namespace DriverCore.Services
{
    /// <summary>
    /// Поиск и установка драйверов через агент Windows Update (WUApiLib, late binding —
    /// без COM-ссылок и PIA). Только официальный каталог Microsoft. Плюс pnputil
    /// для повторного опроса оборудования и ссылки на сайты производителей GPU.
    /// </summary>
    internal sealed class DriverUpdateService
    {
        private const int ssWindowsUpdate = 2;

        public sealed class Result
        {
            public int Found;
            public int Installed;
            public bool RebootRequired;
            public bool PolicyBlocked;
            public string Message = "";
        }

        /// <param name="install">false — только поиск; true — скачать и установить.</param>
        public Result SearchAndInstall(bool install, Action<string> log, CancellationToken ct)
        {
            void L(string m) => log?.Invoke(m);
            var result = new Result();

            Type sessionType = Type.GetTypeFromProgID("Microsoft.Update.Session");
            if (sessionType == null)
            {
                result.Message = "Агент Windows Update недоступен в этой системе.";
                L(result.Message);
                return result;
            }

            dynamic session = null;
            try
            {
                session = Activator.CreateInstance(sessionType);
                try { session.ClientApplicationID = "DriverCore"; } catch { }

                dynamic searcher = session.CreateUpdateSearcher();
                try { searcher.ServerSelection = ssWindowsUpdate; } catch { }
                try { searcher.IncludePotentiallySupersededUpdates = false; } catch { }

                L("Ищу драйверы в Windows Update…");
                ct.ThrowIfCancellationRequested();

                dynamic searchResult = searcher.Search("IsInstalled=0 and Type='Driver' and IsHidden=0");
                dynamic updates = searchResult.Updates;
                result.Found = (int)updates.Count;
                L($"Найдено обновлений драйверов: {result.Found}.");

                if (result.Found == 0)
                {
                    result.Message = "Через Windows Update новых драйверов не найдено — всё актуально.";
                    return result;
                }

                Type collType = Type.GetTypeFromProgID("Microsoft.Update.UpdateColl");
                dynamic toProcess = Activator.CreateInstance(collType);
                for (int i = 0; i < result.Found; i++)
                {
                    dynamic u = updates.Item[i];
                    L("  • " + (string)u.Title);
                    try { if (u.EulaAccepted == false) u.AcceptEula(); } catch { }
                    toProcess.Add(u);
                }

                if (!install)
                {
                    result.Message = $"Доступно драйверов: {result.Found}. Нажмите «Установить», чтобы применить.";
                    return result;
                }

                ct.ThrowIfCancellationRequested();
                L("Скачиваю драйверы…");
                dynamic downloader = session.CreateUpdateDownloader();
                downloader.Updates = toProcess;
                dynamic dl = downloader.Download();
                L($"Результат загрузки: код {(int)dl.ResultCode}.");

                dynamic installColl = Activator.CreateInstance(collType);
                for (int i = 0; i < (int)toProcess.Count; i++)
                {
                    dynamic u = toProcess.Item[i];
                    if (u.IsDownloaded == true) installColl.Add(u);
                }

                if ((int)installColl.Count == 0)
                {
                    result.Message = "Ни одно обновление не удалось скачать.";
                    L(result.Message);
                    return result;
                }

                ct.ThrowIfCancellationRequested();
                L("Устанавливаю драйверы…");
                dynamic installer = session.CreateUpdateInstaller();
                installer.Updates = installColl;
                dynamic ir = installer.Install();

                result.Installed = (int)installColl.Count;
                result.RebootRequired = (bool)ir.RebootRequired;
                L($"Установка завершена: код {(int)ir.ResultCode}, перезагрузка нужна — {(result.RebootRequired ? "да" : "нет")}.");

                result.Message = $"Установлено драйверов: {result.Installed}." +
                                 (result.RebootRequired ? " Нужна перезагрузка." : "");
                return result;
            }
            catch (OperationCanceledException)
            {
                result.Message = "Операция отменена.";
                L(result.Message);
                return result;
            }
            catch (COMException ex)
            {
                uint code = unchecked((uint)ex.ErrorCode);
                if (code == 0x8024500C || code == 0x8024002B || code == 0x8024401C)
                {
                    result.PolicyBlocked = true;
                    result.Message = "Обновление драйверов через Windows Update недоступно " +
                                     "(ограничено политикой/WSUS или нет связи со службой).";
                }
                else if (code == 0x80070005)
                {
                    result.Message = "Нет прав администратора для установки драйверов.";
                }
                else
                {
                    result.Message = $"Ошибка Windows Update: 0x{code:X8}.";
                }
                L(result.Message);
                return result;
            }
            catch (Exception ex)
            {
                result.Message = "Ошибка обновления драйверов: " + ex.Message;
                L(result.Message);
                return result;
            }
            finally
            {
                if (session != null && Marshal.IsComObject(session))
                {
                    try { Marshal.FinalReleaseComObject(session); } catch { }
                }
            }
        }

        /// <summary>Повторный опрос оборудования (как «Обновить конфигурацию» в диспетчере устройств).</summary>
        public void ScanForHardwareChanges(Action<string> log)
        {
            try
            {
                log?.Invoke("Повторно опрашиваю оборудование (pnputil /scan-devices)…");
                var psi = new ProcessStartInfo
                {
                    FileName = "pnputil.exe",
                    Arguments = "/scan-devices",
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                };
                using (var p = Process.Start(psi))
                {
                    p.WaitForExit(60000);
                    log?.Invoke($"pnputil завершился с кодом {p.ExitCode}.");
                }
            }
            catch (Exception ex)
            {
                log?.Invoke("Не удалось выполнить pnputil: " + ex.Message);
            }
        }
    }
}
