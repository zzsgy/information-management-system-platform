import { ref } from "vue";
import watercolor from "./assets/themes/watercolor.png";
import sunrise from "./assets/themes/sunrise.png";
import aurora from "./assets/themes/aurora.png";
import botanical from "./assets/themes/botanical.png";

/** 外观只保存当前浏览器的界面偏好，不读取或改变账号、权限和业务资料。 */
export const appearanceThemes = [
  { id: "blue", name: "标准蓝", description: "蓝白灰，通用办公配色", primary: "#2563eb", soft: "#eff6ff", background: "", dark: false },
  { id: "teal", name: "清朗青", description: "青色强调，清爽简洁", primary: "#087f88", soft: "#e8f5f5", background: "", dark: false },
  { id: "green", name: "沉静绿", description: "柔和绿色，沉稳自然", primary: "#287451", soft: "#edf7f0", background: "", dark: false },
  { id: "violet", name: "雅致紫", description: "低饱和紫色，柔和清晰", primary: "#7254b3", soft: "#f4f0fb", background: "", dark: false },
  { id: "paper", name: "暖纸墨金", description: "暖纸白、深墨蓝与金色点缀", primary: "#1a1a2e", soft: "#f5ead6", background: "", dark: false },
  { id: "watercolor", name: "青岚水彩", description: "蓝绿水彩，搭配清透青色", primary: "#277c83", soft: "#e4f3ef", background: watercolor, dark: false },
  { id: "sunrise", name: "暖阳流金", description: "柔暖光影，搭配橙金与暖白", primary: "#99502f", soft: "#faeadb", background: sunrise, dark: false },
  { id: "aurora", name: "星河极光", description: "深色星空，搭配青色极光", primary: "#66d5ce", soft: "#203e48", background: aurora, dark: true },
  { id: "botanical", name: "浅纸草木", description: "草木纸纹，搭配低饱和橄榄绿", primary: "#617044", soft: "#edf0e2", background: botanical, dark: false },
] as const;
/** 沿用原有五个主题标识，兼容旧浏览器偏好。 */
export type AppearanceTheme = typeof appearanceThemes[number]["id"];
export const appearanceTheme = ref<AppearanceTheme>("blue");

/** 内置字体原文件与 OFL 许可证随网页分发；系统默认项使用本机已安装字体。 */
export const appearanceFonts = [
  { id: "system", name: "系统默认", description: "沿用设备字体，清晰熟悉", face: "", license: "" },
  { id: "noto-sans", name: "思源黑体", description: "Noto Sans SC · 简洁现代，适合日常办公", face: "IMS Noto Sans SC", license: "/fonts/noto-sans-sc/OFL.txt" },
  { id: "noto-serif", name: "思源宋体", description: "Noto Serif SC · 衬线分明，阅读沉稳", face: "IMS Noto Serif SC", license: "/fonts/noto-serif-sc/OFL.txt" },
  { id: "wenkai", name: "霞鹜文楷", description: "LXGW WenKai · 自然手写，温润舒展", face: "IMS LXGW WenKai", license: "/fonts/lxgw-wenkai/OFL.txt" },
] as const;
/** 字体与主题独立保存，更换其中一项不会覆盖另一项。 */
export type AppearanceFont = typeof appearanceFonts[number]["id"];
export const appearanceFont = ref<AppearanceFont>("system");
/** 主题键沿用旧版本；字体使用单独键，避免覆盖已有配色选择。 */
const themeStorageKey = "ims-appearance-theme";
const fontStorageKey = "ims-appearance-font";
/** 避免初始化重复绑定跨标签页事件。 */
let storageListenerInstalled = false;

/** 无效偏好回退到默认项，不将存储内容用作资源地址或样式。 */
function resolveTheme(value: string | null): AppearanceTheme {
  return appearanceThemes.find((theme) => theme.id === value)?.id ?? "blue";
}
/** 字体偏好只接受内置标识，未知或已删除的字体使用设备默认字体。 */
function resolveFont(value: string | null): AppearanceFont {
  return appearanceFonts.find((font) => font.id === value)?.id ?? "system";
}
/** 禁用本地存储时仍允许当前页面即时切换。 */
function readPreference(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
/** 保存浏览器偏好；受隐私模式限制时不影响当前页面使用。 */
function savePreference(key: string, value: string) {
  try { localStorage.setItem(key, value); } catch { /* 保存失败不阻断已生效的外观和业务界面。 */ }
}
/** 应用固定主题及本机图片；纯色主题清除上一次的背景图。 */
function applyTheme(value: AppearanceTheme) {
  const theme = appearanceThemes.find((item) => item.id === value)!;
  appearanceTheme.value = value;
  document.documentElement.dataset.theme = value;
  document.documentElement.dataset.wallpaper = theme.background ? "true" : "false";
  document.documentElement.style.setProperty("--app-wallpaper", theme.background ? `url("${theme.background}")` : "none");
}
/** 根节点只记录内置字体标识，实际字体及回退规则统一由样式表控制。 */
function applyFont(value: AppearanceFont) {
  appearanceFont.value = value;
  document.documentElement.dataset.font = value;
}
/** 用户主动选择才保存；跨标签页同步不会反复写回。 */
export function selectAppearance(value: AppearanceTheme) {
  applyTheme(resolveTheme(value));
  savePreference(themeStorageKey, appearanceTheme.value);
}
/** 用户选择只更新界面字体，不影响主题与任何文档原文件。 */
export function selectInterfaceFont(value: AppearanceFont) {
  applyFont(resolveFont(value));
  savePreference(fontStorageKey, appearanceFont.value);
}
/** 启动恢复偏好；其他标签页修改或清除存储时同步显示。 */
export function initializeAppearance() {
  applyTheme(resolveTheme(readPreference(themeStorageKey)));
  applyFont(resolveFont(readPreference(fontStorageKey)));
  if (storageListenerInstalled) return;
  storageListenerInstalled = true;
  window.addEventListener("storage", (event) => {
    if (event.key === themeStorageKey || event.key === null) applyTheme(resolveTheme(event.newValue));
    if (event.key === fontStorageKey || event.key === null) applyFont(resolveFont(event.newValue));
  });
}
