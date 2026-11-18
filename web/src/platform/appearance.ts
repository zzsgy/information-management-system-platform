import { ref } from "vue";

/** 外观仅保存浏览器配色偏好，不包含账号和业务资料。 */
export const appearanceThemes = [
  { id: "blue", name: "标准蓝", description: "蓝白灰，通用办公配色", primary: "#2563eb", soft: "#eff6ff" },
  { id: "teal", name: "清朗青", description: "青色强调，清爽简洁", primary: "#087f88", soft: "#e8f5f5" },
  { id: "green", name: "沉静绿", description: "柔和绿色，沉稳自然", primary: "#287451", soft: "#edf7f0" },
  { id: "violet", name: "雅致紫", description: "低饱和紫色，柔和清晰", primary: "#7254b3", soft: "#f4f0fb" },
  { id: "paper", name: "暖纸墨金", description: "暖纸白、深墨蓝与金色点缀", primary: "#1a1a2e", soft: "#f5ead6" },
] as const;
export type AppearanceTheme = typeof appearanceThemes[number]["id"];
export const appearanceTheme = ref<AppearanceTheme>("blue");
const storageKey = "ims-appearance-theme";

export function selectAppearance(value: AppearanceTheme) {
  appearanceTheme.value = value;
  document.documentElement.dataset.theme = value;
  try { localStorage.setItem(storageKey, value); } catch { /* 禁用存储时仍可即时切换。 */ }
}

export function initializeAppearance() {
  let value: string | null = null;
  try { value = localStorage.getItem(storageKey); } catch { /* 使用默认配色。 */ }
  const theme = appearanceThemes.find((theme) => theme.id === value)?.id ?? "blue";
  appearanceTheme.value = theme;
  document.documentElement.dataset.theme = theme;
  window.addEventListener("storage", (event) => {
    if (event.key !== storageKey) return;
    const current = appearanceThemes.find((theme) => theme.id === event.newValue)?.id ?? "blue";
    appearanceTheme.value = current;
    document.documentElement.dataset.theme = current;
  });
}
