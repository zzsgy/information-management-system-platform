using System;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

/// <summary>仅从配对的 HTTPS 平台下载固定文件，核对版本和摘要后更新本机助手。</summary>
internal static class HelperUpdater
{
    internal const int MaxPackageBytes = 8 * 1024 * 1024;
    internal static readonly Version CurrentVersion = Assembly.GetExecutingAssembly().GetName().Version;
    private const string Download = "/downloads/ims-todo-helper.exe";
    private const string Manifest = "/downloads/ims-todo-helper-version.json";

    internal static async Task<HelperRelease> Check(HttpClient http, string origin)
    {
        var release = new JavaScriptSerializer().Deserialize<HelperRelease>(
            await ReadString(http, origin + Manifest));
        Version version;
        if (release == null || !Version.TryParse(release.version, out version) || version.Revision >= 0 ||
            version.Build < 0 || release.bytes <= 0 || release.bytes > MaxPackageBytes ||
            !Regex.IsMatch(release.sha256 ?? "", @"\A[0-9a-fA-F]{64}\z"))
            throw new InvalidDataException("更新清单无效");
        return version.CompareTo(new Version(CurrentVersion.ToString(3))) > 0 ? release : null;
    }

    /** 核对下载内容后启动独立更新进程，当前助手只在新进程启动成功时退出。 */
    internal static async Task Stage(HttpClient http, string origin, HelperRelease release)
    {
        byte[] contents;
        using (var request = NewRequest(origin + Download))
        using (var response = await http.SendAsync(request))
        {
            response.EnsureSuccessStatusCode();
            contents = await response.Content.ReadAsByteArrayAsync();
        }
        if (contents.Length != release.bytes || !String.Equals(Digest(contents), release.sha256, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("下载校验失败");

        string target = Application.ExecutablePath;
        string staged = Path.Combine(Path.GetDirectoryName(target), ".ims-todo-update-" + Guid.NewGuid().ToString("N") + ".exe");
        try
        {
            File.WriteAllBytes(staged, contents);
            var package = AssemblyName.GetAssemblyName(staged);
            if (package.Name != Assembly.GetExecutingAssembly().GetName().Name || package.Version.ToString(3) != release.version)
                throw new InvalidDataException("更新包版本不符");
            using (var current = Process.GetCurrentProcess())
                Launch(staged, "--apply-update " + Quote(target) + " " + current.Id + " " + release.sha256);
        }
        catch
        {
            DeleteUpdateFile(staged);
            throw;
        }
    }

    /** 等待旧进程释放 EXE 后替换，启动失败时恢复旧文件。 */
    internal static bool HandleStartup(string[] args)
    {
        if (args.Length == 4 && args[0] == "--apply-update")
        {
            string staged = Application.ExecutablePath;
            string target = Path.GetFullPath(args[1]);
            int oldPid;
            if (IsStagedFile(staged, target) && int.TryParse(args[2], out oldPid))
                ReplaceAndRestart(staged, target, oldPid, args[3]);
            return true;
        }
        int updaterPid;
        if (args.Length == 3 && args[0] == "--finish-update" && int.TryParse(args[2], out updaterPid)
            && IsStagedFile(args[1], Application.ExecutablePath))
        {
            Task.Run(() =>
            {
                if (!WaitForExit(updaterPid)) return;
                DeleteUpdateFile(args[1]);
                DeleteUpdateFile(args[1] + ".previous");
            });
        }
        return false;
    }

    private static void ReplaceAndRestart(string staged, string target, int oldPid, string expectedHash)
    {
        bool oldExited = false, replaced = false;
        string next = staged + ".next", backup = staged + ".previous";
        try
        {
            oldExited = WaitForExit(oldPid);
            if (!oldExited) throw new IOException("原助手未退出");
            File.Copy(staged, next);
            if (!String.Equals(Digest(File.ReadAllBytes(next)), expectedHash, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("更新文件校验失败");
            File.Replace(next, target, backup);
            replaced = true;
            using (var updater = Process.GetCurrentProcess())
                Launch(target, "--finish-update " + Quote(staged) + " " + updater.Id);
        }
        catch (Exception)
        {
            bool restored = !replaced;
            try
            {
                if (replaced)
                {
                    File.Replace(backup, target, null);
                    restored = true;
                }
                if (oldExited) Launch(target, "");
            }
            catch (Exception) { }
            DeleteUpdateFile(next);
            MessageBox.Show(restored ? "更新未完成，请重试，或从系统重新下载助手。"
                : "更新未完成，请从系统重新下载助手。原版本备份仍在助手目录。",
                "百林科待办助手", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private static async Task<string> ReadString(HttpClient http, string url)
    {
        using (var request = NewRequest(url))
        using (var response = await http.SendAsync(request))
        {
            response.EnsureSuccessStatusCode();
            return await response.Content.ReadAsStringAsync();
        }
    }

    private static HttpRequestMessage NewRequest(string url)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
        return request;
    }

    private static bool WaitForExit(int pid)
    {
        try { using (var process = Process.GetProcessById(pid)) return process.WaitForExit(12000); }
        catch (ArgumentException) { return true; }
    }

    private static bool IsStagedFile(string staged, string target)
    {
        return String.Equals(Path.GetDirectoryName(staged), Path.GetDirectoryName(target), StringComparison.OrdinalIgnoreCase)
            && Regex.IsMatch(Path.GetFileName(staged), @"\A\.ims-todo-update-[a-f0-9]{32}\.exe\z")
            && !String.Equals(staged, target, StringComparison.OrdinalIgnoreCase)
            && String.Equals(Path.GetExtension(target), ".exe", StringComparison.OrdinalIgnoreCase);
    }

    private static string Digest(byte[] contents)
    {
        using (var hash = SHA256.Create()) return BitConverter.ToString(hash.ComputeHash(contents)).Replace("-", "");
    }

    private static void Launch(string path, string arguments)
    {
        using (var process = Process.Start(new ProcessStartInfo(path, arguments) { UseShellExecute = false, CreateNoWindow = true }))
            if (process == null) throw new IOException("无法启动助手");
    }

    private static string Quote(string path) { return "\"" + path + "\""; }

    private static void DeleteUpdateFile(string path)
    {
        try { File.Delete(path); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}

/// <summary>网页发布时由实际 EXE 生成版本和下载校验信息。</summary>
internal sealed class HelperRelease
{
    public string version { get; set; }
    public string sha256 { get; set; }
    public int bytes { get; set; }
}
