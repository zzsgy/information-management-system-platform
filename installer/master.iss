; 档案管理系统 - 一体安装包（Inno Setup 7，自包含）
; 本安装包直接打包服务端/客户端程序（PyInstaller 产物），
; 不再依赖单独的“服务器端/客户端”安装包，一次编译即得唯一安装程序。
;
; 版本号规则：基础版本（随功能递增，当前 1.15）+ 编译日期，
; 例如 档案管理系统1.15-20260831.exe。每次编译生成新文件名，旧安装包自动保留。
;
; 编译：ISCC.exe master.iss
; 前置：已用 PyInstaller 生成 server/dist/ArchiveServer 与 client/dist/ArchiveClient

#define MyAppName "档案管理系统"
#define MyAppVersion "1.15"
#define BuildDate GetDateTimeString('yyyymmdd', '', '')
#define MyAppPublisher "ArchiveSystem"

#define ServerDist  "C:\Apython\ArchiveSystem\server\dist\ArchiveServer"
#define ClientDist  "C:\Apython\ArchiveSystem\client\dist\ArchiveClient"
#define ServerEnv   "C:\Apython\ArchiveSystem\server\server.env"
#define ClientEnv   "C:\Apython\ArchiveSystem\client\client.env"
#define VCRedist    "C:\Apython\ArchiveSystem\installer\vc_redist.x64.exe"
#define OutputDir   "C:\Apython\ArchiveSystem\installer\master_release"
#define OutputBase  "档案管理系统" + MyAppVersion + "-" + BuildDate

[Setup]
AppId={{A1B2C3D4-2E3F-4A5B-6C7D-8E9F0A1B2C3D}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName=C:\ArchiveSystem
DefaultGroupName=ArchiveSystem
OutputDir={#OutputDir}
OutputBaseFilename={#OutputBase}
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
WizardStyle=modern
UninstallDisplayIcon={app}\Server\ArchiveServer.exe
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
; 安装前尝试关闭占用待更新文件的进程；若仍有占用，由 PrepareToInstall 强制结束本系统进程
CloseApplications=yes
CloseApplicationsFilter=*.*

[Languages]
Name: "chinese"; MessagesFile: "C:\Apython\InnoSetup\Languages\ChineseSimplified.isl"

[Files]
; 服务端程序目录（含 ArchiveServer.exe 与 _internal 依赖）
Source: "{#ServerDist}\*"; DestDir: "{app}\Server"; Flags: ignoreversion recursesubdirs createallsubdirs; Components: server
; 客户端程序目录（含 ArchiveClient.exe 与 _internal 依赖）
Source: "{#ClientDist}\*"; DestDir: "{app}\Client"; Flags: ignoreversion recursesubdirs createallsubdirs; Components: client
; 配置文件模板：仅当目标不存在时才写入，避免覆盖用户已修改的配置
Source: "{#ServerEnv}"; DestDir: "{app}\Server"; DestName: ".env"; Flags: onlyifdoesntexist; Components: server
Source: "{#ClientEnv}"; DestDir: "{app}\Client"; DestName: ".env"; Flags: onlyifdoesntexist; Components: client

#ifexist VCRedist
; 可选：提供 vc_redist.x64.exe 时自动静默安装 VC++ 运行库
Source: "{#VCRedist}"; DestDir: "{tmp}"; DestName: "vc_redist.x64.exe"; Flags: deleteafterinstall
#endif

[Dirs]
; 运行时需可写的目录，授予普通用户修改权限
Name: "{app}\Server\storage"; Permissions: users-modify; Components: server
Name: "{app}\Server\logs"; Permissions: users-modify; Components: server
Name: "{app}\Client\logs"; Permissions: users-modify; Components: client

[Components]
; 默认均不勾选（由 [Code] 在初始化时取消勾选），运行时必须由用户选择至少一项
Name: "server"; Description: "服务端 (Server) —— 部署在服务器 / 管理机"
Name: "client"; Description: "客户端 (Client) —— 业务人员电脑安装此项即可"

[Icons]
Name: "{group}\档案管理系统服务端"; Filename: "{app}\Server\ArchiveServer.exe"; Components: server
Name: "{group}\档案管理系统客户端"; Filename: "{app}\Client\ArchiveClient.exe"; Components: client
Name: "{group}\卸载档案管理系统"; Filename: "{uninstallexe}"
; 桌面快捷方式（用户要求：安装即在桌面生成图标）
Name: "{commondesktop}\档案管理系统-服务端"; Filename: "{app}\Server\ArchiveServer.exe"; Components: server
Name: "{commondesktop}\档案管理系统-客户端"; Filename: "{app}\Client\ArchiveClient.exe"; Components: client

[Run]
; 升级时先结束可能仍在运行的旧服务端进程（避免占用 8000 端口 / 旧版本残留）
Filename: "taskkill.exe"; Parameters: "/F /IM ArchiveServer.exe"; Description: "停止旧版服务端进程"; Components: server; Flags: runhidden
; 仅当勾选「服务端」时安装（runhidden 隐藏可能的控制台窗口）
Filename: "{app}\Server\ArchiveServer.exe"; Description: "立即启动服务端"; Components: server; Flags: postinstall nowait runhidden
; 仅当勾选「客户端」时安装
Filename: "{app}\Client\ArchiveClient.exe"; Description: "立即启动客户端"; Components: client; Flags: postinstall nowait

#ifexist VCRedist
; 依赖：仅在系统缺失 VC++ 时自动静默安装（与组件选择无关）
Filename: "{tmp}\vc_redist.x64.exe"; Parameters: "/install /quiet /norestart"; StatusMsg: "正在安装 VC++ 运行库..."; Check: VCNeedsInstall; Flags: waituntilterminated
#endif

[Code]
procedure InitializeWizard();
var
  i: Integer;
begin
  // 仅交互模式默认取消勾选，强制用户手动选择；静默模式保留 /COMPONENTS 指定的选择
  if not WizardSilent() then
  begin
    for i := 0 to WizardForm.ComponentsList.Items.Count - 1 do
      WizardForm.ComponentsList.CheckItem(i, coUncheck);
  end;
end;

function VCNeedsInstall(): Boolean;
var
  Installed: DWord;
begin
  Result := True;
  if RegQueryDWordValue(HKLM, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Installed', Installed) then
    if Installed = 1 then Result := False;
  if RegQueryDWordValue(HKLM, 'SOFTWARE\WOW6432Node\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Installed', Installed) then
    if Installed = 1 then Result := False;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Result := '';
  // 在解压文件之前强制结束可能占用旧版文件的服务端/客户端进程，避免「安装程序无法自动关闭所有应用程序」警告
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM ArchiveServer.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM ArchiveClient.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(500);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  // vc_redist.x64.exe 已随安装包分发，[Run] 段会在系统缺失时自动静默安装，此处不再弹窗提示。
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = wpSelectComponents then
  begin
    if (not WizardIsComponentSelected('server')) and (not WizardIsComponentSelected('client')) then
    begin
      MsgBox('请至少选择一项要安装的组件：' + #13#10 +
             '· 服务端（部署在服务器 / 管理机）' + #13#10 +
             '· 客户端（业务人员电脑）', mbInformation, MB_OK);
      Result := False;
    end;
  end;
end;
