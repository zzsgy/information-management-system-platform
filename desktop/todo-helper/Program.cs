using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyVersion("1.1.0.0")]

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (HelperUpdater.HandleStartup(args)) return;
        bool first;
        using (var mutex = new Mutex(true, @"Local\BioLink.Ims.TodoHelper", out first))
        {
            if (!first) return;
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            Application.Run(new TrayHelper());
        }
    }
}

/// <summary>保存配对、维护在线状态并在交互桌面显示提醒。</summary>
internal sealed class TrayHelper : ApplicationContext
{
    private static readonly string ConfigPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BioLink", "TodoHelper", "pairing.json");
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunName = "BioLinkImsTodoHelper";
    private readonly JavaScriptSerializer json = new JavaScriptSerializer();
    private readonly HttpClient http = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false })
    { Timeout = TimeSpan.FromSeconds(7), MaxResponseContentBufferSize = HelperUpdater.MaxPackageBytes };
    private readonly NotifyIcon tray;
    private readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer { Interval = 1000 };
    private readonly ToolStripMenuItem autostart;
    private Pairing pairing;
    private ReminderWindow popup;
    private Reminder shown;
    private DateTime nextPoll;
    private bool polling;
    private bool settingsOpen;
    private bool initialSetup;
    private bool wasLocked;
    private bool checkingUpdate;
    private DateTime nextUpdateCheck;
    private string announcedVersion;

    internal TrayHelper()
    {
        pairing = ReadPairing();
        initialSetup = pairing == null;
        var menu = new ContextMenuStrip();
        menu.Items.Add("配对设置", null, (sender, args) => OpenSettings());
        autostart = new ToolStripMenuItem("登录 Windows 时启动") { Checked = StartupEnabled() };
        autostart.Click += (sender, args) =>
        {
            try { SetStartup(!autostart.Checked); }
            catch (Exception) { Notice("无法修改启动设置，请检查 Windows 用户权限。"); }
        };
        menu.Items.Add(autostart);
        menu.Items.Add("检查更新", null, async (sender, args) => await CheckUpdate(true));
        menu.Items.Add("退出助手", null, (sender, args) => ExitThread());
        tray = new NotifyIcon { Icon = SystemIcons.Information, Visible = true, ContextMenuStrip = menu, Text = "百林科待办助手" };
        tray.DoubleClick += (sender, args) => OpenSettings();
        timer.Tick += async (sender, args) => await Tick();
        timer.Start();
    }

    /// <summary>锁屏时只维持心跳，不展示或确认；解锁后立即重新查询。</summary>
    private async Task Tick()
    {
        bool unlocked = DesktopAccess.IsInteractive();
        if (!unlocked)
        {
            if (popup != null) popup.Hide();
            wasLocked = true;
        }
        else if (wasLocked)
        {
            nextPoll = DateTime.MinValue;
            wasLocked = false;
        }
        if (initialSetup && unlocked)
        {
            initialSetup = false;
            OpenSettings();
            return;
        }
        if (unlocked && pairing != null && !settingsOpen && DateTime.UtcNow >= nextUpdateCheck)
            await CheckUpdate(false);
        if (pairing == null || polling || settingsOpen || DateTime.UtcNow < nextPoll) return;
        await Refresh(null, false);
    }

    private async Task Refresh(Reminder acknowledged, bool open)
    {
        if (polling || pairing == null) return;
        var current = pairing;
        polling = true;
        nextPoll = DateTime.UtcNow.AddSeconds(10);
        try
        {
            Reminder due = await Fetch(current, acknowledged);
            if (current != pairing) return;
            tray.Text = "百林科待办助手 · 已连接";
            if (open && acknowledged != null)
                Process.Start(new ProcessStartInfo(current.origin + "/platform.html#/todos?item=" + acknowledged.id) { UseShellExecute = true });
            Present(due);
        }
        catch (UnauthorizedAccessException)
        {
            if (current != pairing) return;
            pairing = null;
            ClosePopup();
            tray.Text = "百林科待办助手 · 需要重新配对";
            Notice("桌面提醒已解除或配对失效，请在系统中生成新配对码。");
        }
        catch (Exception)
        {
            tray.Text = "百林科待办助手 · 等待连接";
            if (acknowledged != null && popup != null) popup.SetError("暂时无法连接系统，请重试。");
        }
        finally
        {
            polling = false;
            if (popup != null) popup.SetWaiting(false);
        }
    }

    private async Task<Reminder> Fetch(Pairing device, Reminder acknowledged)
    {
        string body = acknowledged == null ? "{}" : json.Serialize(new { acknowledged.id, acknowledged.remindAt });
        using (var request = new HttpRequestMessage(HttpMethod.Post, device.origin + "/api/v1/desktop/todo-reminders"))
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", device.token);
            request.Headers.Add("X-Todo-Helper-Version", HelperUpdater.CurrentVersion.ToString(3));
            request.Content = new StringContent(body, Encoding.UTF8, "application/json");
            using (var response = await http.SendAsync(request))
            {
                if (response.StatusCode == HttpStatusCode.Unauthorized) throw new UnauthorizedAccessException();
                response.EnsureSuccessStatusCode();
                var result = json.Deserialize<Envelope>(await response.Content.ReadAsStringAsync());
                if (result == null || result.code != "OK" || result.data == null)
                    throw new InvalidDataException("提醒接口返回异常");
                return result.data.item;
            }
        }
    }

    /** 后台只提示可更新，员工确认后才下载、替换和重启。 */
    private async Task CheckUpdate(bool interactive)
    {
        if (checkingUpdate) return;
        var device = pairing;
        if (device == null)
        {
            if (interactive) Notice("请先配对到信息管理系统。");
            return;
        }
        checkingUpdate = true;
        nextUpdateCheck = DateTime.UtcNow.AddHours(6);
        try
        {
            var release = await HelperUpdater.Check(http, device.origin);
            if (device != pairing) return;
            if (!interactive)
            {
                if (release != null && announcedVersion != release.version)
                {
                    announcedVersion = release.version;
                    Notice("发现新版 " + release.version + "，可右键托盘图标检查更新。");
                }
                return;
            }
            if (release == null)
            {
                MessageBox.Show("当前版本 " + HelperUpdater.CurrentVersion.ToString(3) + " 已是最新版。", "百林科待办助手");
                return;
            }
            if (MessageBox.Show("发现新版 " + release.version + "。现在下载并重启助手吗？",
                "百林科待办助手", MessageBoxButtons.YesNo, MessageBoxIcon.Information) != DialogResult.Yes) return;
            await HelperUpdater.Stage(http, device.origin, release);
            ExitThread();
        }
        catch (Exception)
        {
            if (interactive) MessageBox.Show("暂时无法更新。请检查系统连接和助手文件夹权限，或从网页重新下载。",
                "百林科待办助手", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally { checkingUpdate = false; }
    }

    private void Present(Reminder due)
    {
        if (due == null) { ClosePopup(); return; }
        if (!DesktopAccess.IsInteractive()) return;
        if (shown != null && shown.id == due.id && shown.remindAt == due.remindAt &&
            shown.title == due.title && shown.description == due.description)
        {
            if (!popup.Visible) popup.Show();
            return;
        }
        ClosePopup();
        shown = due;
        popup = new ReminderWindow(due, async open =>
        {
            if (polling) return;
            popup.SetWaiting(true);
            await Refresh(due, open);
        });
        popup.Show();
    }

    private void ClosePopup()
    {
        if (popup != null) popup.Dispose();
        popup = null;
        shown = null;
    }

    private void OpenSettings()
    {
        if (settingsOpen) return;
        settingsOpen = true;
        try
        {
            using (var form = new Form { Text = "百林科待办助手 · 配对", ClientSize = new Size(470, 208),
                StartPosition = FormStartPosition.CenterScreen, FormBorderStyle = FormBorderStyle.FixedDialog,
                MaximizeBox = false, MinimizeBox = false, Font = new Font("Microsoft YaHei UI", 10) })
            {
                var tip = new Label { Text = "在系统「我的待办 → 桌面提醒」生成配对码并粘贴。", AutoSize = true, Left = 16, Top = 16 };
                var input = new TextBox { Left = 16, Top = 48, Width = 438, UseSystemPasswordChar = true, MaxLength = 1024 };
                var start = new CheckBox { Left = 16, Top = 86, AutoSize = true, Text = "登录 Windows 时启动", Checked = autostart.Checked };
                var error = new Label { Left = 16, Top = 118, Width = 438, Height = 34, ForeColor = Color.Firebrick };
                var save = new Button { Text = "连接", Left = 286, Top = 158, Width = 80, Height = 32 };
                var cancel = new Button { Text = "关闭", Left = 374, Top = 158, Width = 80, Height = 32, DialogResult = DialogResult.Cancel };
                save.Click += async (sender, args) =>
                {
                    save.Enabled = cancel.Enabled = false;
                    error.Text = "";
                    try
                    {
                        var candidate = ParsePairing(input.Text);
                        await Fetch(candidate, null);
                        SavePairing(candidate);
                        pairing = candidate;
                        ClosePopup();
                        nextPoll = DateTime.MinValue;
                        SetStartup(start.Checked);
                        input.Clear();
                        form.Close();
                    }
                    catch (UnauthorizedAccessException) { error.Text = "配对码已失效，请在系统中重新生成。"; }
                    catch (FormatException) { error.Text = "请粘贴系统生成的完整 HTTPS 配对码。"; }
                    catch (Exception) { error.Text = "无法连接或保存设置，请检查网络和系统证书。"; }
                    finally { save.Enabled = cancel.Enabled = true; }
                };
                form.Controls.AddRange(new Control[] { tip, input, start, error, save, cancel });
                form.AcceptButton = save;
                form.CancelButton = cancel;
                form.ShowDialog();
            }
        }
        finally { settingsOpen = false; }
    }

    /// <summary>仅允许 HTTPS 源地址，凭据由 Windows 当前用户的数据保护机制保存。</summary>
    private Pairing ParsePairing(string raw)
    {
        Pairing value;
        try { value = json.Deserialize<Pairing>(raw); }
        catch (ArgumentException) { throw new FormatException(); }
        Uri origin;
        if (value == null || !Uri.TryCreate(value.origin, UriKind.Absolute, out origin) ||
            origin.Scheme != "https" || origin.AbsolutePath != "/" || origin.UserInfo.Length != 0 ||
            origin.Query.Length != 0 || origin.Fragment.Length != 0 ||
            !Regex.IsMatch(value.token ?? "", @"\A[A-Za-z0-9_-]{43}\z")) throw new FormatException();
        value.origin = origin.GetLeftPart(UriPartial.Authority);
        return value;
    }

    private Pairing ReadPairing()
    {
        try
        {
            return ParsePairing(Encoding.UTF8.GetString(ProtectedData.Unprotect(
                Convert.FromBase64String(File.ReadAllText(ConfigPath)), null, DataProtectionScope.CurrentUser)));
        }
        catch (Exception) { return null; }
    }

    private void SavePairing(Pairing value)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath));
        File.WriteAllText(ConfigPath, Convert.ToBase64String(ProtectedData.Protect(
            Encoding.UTF8.GetBytes(json.Serialize(value)), null, DataProtectionScope.CurrentUser)));
    }

    private bool StartupEnabled()
    {
        using (var key = Registry.CurrentUser.OpenSubKey(RunKey))
            return key != null && Equals(key.GetValue(RunName), "\"" + Application.ExecutablePath + "\"");
    }

    private void SetStartup(bool enabled)
    {
        using (var key = Registry.CurrentUser.CreateSubKey(RunKey))
        {
            if (enabled) key.SetValue(RunName, "\"" + Application.ExecutablePath + "\"");
            else key.DeleteValue(RunName, false);
        }
        autostart.Checked = enabled;
    }

    private void Notice(string message) { tray.ShowBalloonTip(5000, "百林科待办助手", message, ToolTipIcon.Info); }

    protected override void ExitThreadCore()
    {
        timer.Dispose();
        ClosePopup();
        tray.Visible = false;
        tray.Dispose();
        http.Dispose();
        base.ExitThreadCore();
    }
}

/// <summary>置顶窗口不抢走当前软件的键盘焦点。</summary>
internal sealed class ReminderWindow : Form
{
    private static readonly Color Ink = Color.FromArgb(33, 49, 61);
    private static readonly Color Muted = Color.FromArgb(83, 101, 115);
    private static readonly Color Brand = Color.FromArgb(8, 127, 136);
    private static readonly Color BrandDark = Color.FromArgb(5, 100, 109);
    private static readonly Color BrandSoft = Color.FromArgb(232, 245, 245);
    private static readonly Color SurfaceMuted = Color.FromArgb(245, 248, 250);
    private static readonly Color Line = Color.FromArgb(220, 229, 235);
    private readonly Button confirm;
    private readonly Button open;
    private readonly Label error;
    protected override bool ShowWithoutActivation { get { return true; } }

    internal ReminderWindow(Reminder item, Action<bool> accept)
    {
        Text = "百林科 · 待办提醒";
        TopMost = true;
        ControlBox = false;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Microsoft YaHei UI", 10);
        AutoScaleDimensions = new SizeF(96, 96);
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Color.White;
        ForeColor = Ink;
        const int inset = 24, width = 500, contentWidth = width - 2 * inset;

        var icon = new Panel { Left = inset, Top = 22, Width = 42, Height = 42 };
        icon.Paint += PaintClock;
        var label = new Label { Text = "百林科工作台", Left = 78, Top = 23, Width = 340, Height = 19, ForeColor = Muted };
        var caption = new Label { Text = "待办提醒", Left = 78, Top = 43, Width = 340, Height = 24,
            Font = new Font(Font.FontFamily, 11, FontStyle.Bold), ForeColor = BrandDark };
        var heading = new Label { Text = item.title, Left = inset, Top = 82, Width = contentWidth,
            UseMnemonic = false, AutoEllipsis = true, Font = new Font(Font.FontFamily, 16, FontStyle.Bold) };
        heading.Height = Math.Min(62, Math.Max(31, TextRenderer.MeasureText(
            heading.Text, heading.Font, new Size(contentWidth, 0), TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix).Height));
        DateTimeOffset at;
        string time = DateTimeOffset.TryParse(item.remindAt, out at)
            ? at.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM-dd HH:mm") + "（上海时间）" : "";
        var when = new Label { Text = time, Left = inset, Top = heading.Bottom + 6, Width = contentWidth, Height = 24, ForeColor = Muted };
        Controls.AddRange(new Control[] { icon, label, caption, heading, when });

        int footerTop = when.Bottom + 14;
        if (!String.IsNullOrWhiteSpace(item.description))
        {
            var detail = new Panel { Left = inset, Top = footerTop, Width = contentWidth, BackColor = SurfaceMuted };
            var detailLabel = new Label { Text = "待办内容", Left = 12, Top = 10, Width = contentWidth - 24,
                Height = 20, ForeColor = Muted, Font = new Font(Font.FontFamily, 9, FontStyle.Bold) };
            var body = new TextBox { Text = item.description, Left = 12, Top = 34, Width = contentWidth - 24,
                Multiline = true, ReadOnly = true, TabStop = false, BorderStyle = BorderStyle.None, BackColor = SurfaceMuted, ForeColor = Ink };
            int measured = TextRenderer.MeasureText(body.Text, Font, new Size(body.Width, 0),
                TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix).Height;
            body.Height = Math.Min(108, Math.Max(24, measured + 4));
            if (measured + 4 > body.Height) body.ScrollBars = ScrollBars.Vertical;
            detail.Height = body.Bottom + 12;
            detail.Controls.AddRange(new Control[] { detailLabel, body });
            Controls.Add(detail);
            footerTop = detail.Bottom + 16;
        }

        Controls.Add(new Panel { Left = inset, Top = footerTop, Width = contentWidth, Height = 1, BackColor = Line });
        error = new Label { Left = inset, Top = footerTop + 14, Width = 216, Height = 42,
            ForeColor = Color.Firebrick, TextAlign = ContentAlignment.MiddleLeft };
        confirm = ActionButton("知道了", 264, footerTop + 15, 90, false);
        open = ActionButton("查看待办", 364, footerTop + 15, 112, true);
        confirm.Click += (sender, args) => accept(false);
        open.Click += (sender, args) => accept(true);
        Controls.AddRange(new Control[] { error, confirm, open });
        ClientSize = new Size(width, footerTop + 73);
        ActiveControl = confirm;
    }

    /** 同一配色下区分主操作和确认操作。 */
    private Button ActionButton(string text, int left, int top, int width, bool primary)
    {
        var button = new Button { Text = text, Left = left, Top = top, Width = width, Height = 38,
            BackColor = primary ? Brand : Color.White, ForeColor = primary ? Color.White : Ink,
            FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand, UseVisualStyleBackColor = false };
        button.FlatAppearance.BorderColor = primary ? Brand : Line;
        button.FlatAppearance.MouseOverBackColor = primary ? BrandDark : BrandSoft;
        button.FlatAppearance.MouseDownBackColor = primary ? BrandDark : SurfaceMuted;
        return button;
    }

    /** 使用绘制的时钟标识，桌面无需额外字体或图标文件。 */
    private static void PaintClock(object sender, PaintEventArgs args)
    {
        var panel = (Panel)sender;
        args.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        args.Graphics.ScaleTransform(panel.Width / 42f, panel.Height / 42f);
        using (var fill = new SolidBrush(BrandSoft))
        using (var stroke = new Pen(Brand, 1.7f) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round })
        {
            args.Graphics.FillEllipse(fill, 0, 0, 41, 41);
            args.Graphics.DrawEllipse(stroke, 11, 11, 19, 19);
            args.Graphics.DrawLines(stroke, new[] { new Point(20, 15), new Point(20, 21), new Point(24, 23) });
        }
    }

    internal void SetWaiting(bool waiting) { confirm.Enabled = open.Enabled = !waiting; }
    internal void SetError(string message) { error.Text = message; }
}

/// <summary>锁屏或安全桌面无法访问时暂缓显示，恢复后由轮询补发。</summary>
internal static class DesktopAccess
{
    [DllImport("user32.dll")]
    private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
    [DllImport("user32.dll")]
    private static extern bool CloseDesktop(IntPtr desktop);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder buffer, int length, out int needed);

    internal static bool IsInteractive()
    {
        IntPtr desktop = OpenInputDesktop(0, false, 1);
        if (desktop == IntPtr.Zero) return false;
        try
        {
            var name = new StringBuilder(128);
            int needed;
            return GetUserObjectInformation(desktop, 2, name, name.Capacity * 2, out needed)
                && String.Equals(name.ToString(), "Default", StringComparison.OrdinalIgnoreCase);
        }
        finally { CloseDesktop(desktop); }
    }
}

// 序列化类型与提醒接口及本地加密文件保持一致。
internal sealed class Pairing { public string origin { get; set; } public string token { get; set; } }
internal sealed class Reminder
{
    public string id { get; set; }
    public string title { get; set; }
    public string description { get; set; }
    public string remindAt { get; set; }
}
internal sealed class ReminderResult { public Reminder item { get; set; } }
internal sealed class Envelope { public string code { get; set; } public ReminderResult data { get; set; } }
