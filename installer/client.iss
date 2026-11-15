; 企业档案管理系统 - 客户端安装包脚本（Inno Setup 7）
; 由 ISCC.exe 编译生成 Setup.exe
#define MyAppName "企业档案管理系统客户端"
#define MyAppVersion "1.06"
#define MyAppPublisher "ArchiveSystem"
#define SourceDir "C:/Apython/ArchiveSystem/client/dist/ArchiveClient"
#define EnvTemplate "C:/Apython/ArchiveSystem/client/client.env"
#define OutputBase "C:/Apython/ArchiveSystem/installer/client_release/客户端V1.06"

[Setup]
AppId={{7C2B1A9D-4E5F-4A6B-8C1D-2E3F4A5B6C7D}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName=C:/ArchiveSystem/Client
DefaultGroupName=ArchiveSystem
OutputDir={#OutputBase}
OutputBaseFilename=Setup
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
WizardStyle=modern
UninstallDisplayIcon={app}\ArchiveClient.exe
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64

[Languages]
Name: "chinese"; MessagesFile: "C:/Apython/InnoSetup/Languages/ChineseSimplified.isl"

[Files]
; 打包后的客户端程序目录（含 ArchiveClient.exe 与 _internal 依赖）
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; 配置文件模板：仅当目标不存在时才写入，避免覆盖用户已修改的配置
Source: "{#EnvTemplate}"; DestDir: "{app}"; DestName: ".env"; Flags: onlyifdoesntexist

[Dirs]
; 客户端运行日志目录，授予普通用户修改权限
Name: "{app}\logs"; Permissions: users-modify

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\ArchiveClient.exe"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\ArchiveClient.exe"; Description: "立即启动客户端"; Flags: postinstall nowait
