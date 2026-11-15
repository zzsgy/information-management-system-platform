; 企业档案管理系统 - 服务端安装包脚本（Inno Setup 7）
; 由 ISCC.exe 编译生成 Setup.exe
#define MyAppName "企业档案管理系统服务端"
#define MyAppVersion "1.06"
#define MyAppPublisher "ArchiveSystem"
#define SourceDir "C:\Apython\ArchiveSystem\server\dist\ArchiveServer"
#define EnvTemplate "C:\Apython\ArchiveSystem\server\server.env"
#define OutputBase "C:\Apython\ArchiveSystem\installer\server_release\服务器端V1.06"

[Setup]
AppId={{8F3A2B1C-5D6E-4F7A-9B8C-1D2E3F4A5B6C}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName=C:\ArchiveSystem\Server
DefaultGroupName=ArchiveSystem
OutputDir={#OutputBase}
OutputBaseFilename=Setup
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
WizardStyle=modern
UninstallDisplayIcon={app}\ArchiveServer.exe
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64

[Languages]
Name: "chinese"; MessagesFile: "C:\Apython\InnoSetup\Languages\ChineseSimplified.isl"

[Files]
; 打包后的服务端程序目录（含 ArchiveServer.exe 与 _internal 依赖）
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; 配置文件模板：仅当目标不存在时才写入，避免覆盖用户已修改的配置
Source: "{#EnvTemplate}"; DestDir: "{app}"; DestName: ".env"; Flags: onlyifdoesntexist

[Dirs]
; 运行时需可写的目录，授予普通用户修改权限
Name: "{app}\storage"; Permissions: users-modify
Name: "{app}\logs"; Permissions: users-modify

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\ArchiveServer.exe"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\ArchiveServer.exe"; Description: "立即启动服务端（控制台窗口）"; Flags: postinstall nowait
