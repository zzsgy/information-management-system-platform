import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";

/** 在内存模拟偏好存储和页面根节点，不连接服务或修改真实浏览器偏好。 */
function createEnvironment() {
  const values = new Map<string, string>();
  const style = new Map<string, string>();
  const dataset: Record<string, string> = {};
  const listeners: ((event: { key: string | null; newValue: string | null }) => void)[] = [];
  const storage = { getItem: vi.fn((key: string) => values.get(key) ?? null), setItem: vi.fn((key: string, value: string) => { values.set(key, value); }) };
  vi.stubGlobal("localStorage", storage);
  vi.stubGlobal("document", { createElement: () => ({}), documentElement: { dataset, style: { setProperty: (key: string, value: string) => style.set(key, value) } } });
  vi.stubGlobal("window", { addEventListener: vi.fn((_name: string, listener: typeof listeners[number]) => listeners.push(listener)) });
  return { values, style, dataset, storage, listeners };
}

describe("浏览器外观偏好", () => {
  beforeEach(() => vi.resetModules());
  afterEach(() => vi.unstubAllGlobals());

  it("兼容原主题偏好并在缺省时使用系统字体", async () => {
    const env = createEnvironment();
    env.values.set("ims-appearance-theme", "paper");
    const settings = await import("../../src/platform/appearance");
    settings.initializeAppearance();
    expect(env.dataset).toMatchObject({ theme: "paper", font: "system", wallpaper: "false" });
    expect(settings.appearanceThemes.filter((theme) => !theme.background).map((theme) => theme.id)).toEqual(["blue", "teal", "green", "violet", "paper"]);
  });

  it("主题和字体独立保存，刷新恢复两项选择", async () => {
    const env = createEnvironment();
    const settings = await import("../../src/platform/appearance");
    settings.selectAppearance("watercolor");
    settings.selectInterfaceFont("wenkai");
    settings.initializeAppearance();
    expect(env.dataset).toMatchObject({ theme: "watercolor", font: "wenkai", wallpaper: "true" });
    expect(env.values.get("ims-appearance-theme")).toBe("watercolor");
    expect(env.values.get("ims-appearance-font")).toBe("wenkai");
    expect(env.style.get("--app-wallpaper")).toContain("watercolor.png");
  });

  it("回到纯色主题时清除图片且保留字体", async () => {
    const env = createEnvironment();
    const settings = await import("../../src/platform/appearance");
    settings.selectInterfaceFont("noto-serif");
    settings.selectAppearance("aurora");
    settings.selectAppearance("teal");
    expect(env.style.get("--app-wallpaper")).toBe("none");
    expect(env.dataset).toMatchObject({ theme: "teal", font: "noto-serif", wallpaper: "false" });
  });

  it("存储被禁用时仍可切换，非法偏好安全回退", async () => {
    const env = createEnvironment();
    env.storage.getItem.mockImplementation(() => { throw new Error("blocked"); });
    env.storage.setItem.mockImplementation(() => { throw new Error("blocked"); });
    const settings = await import("../../src/platform/appearance");
    expect(() => settings.initializeAppearance()).not.toThrow();
    expect(() => settings.selectAppearance("sunrise")).not.toThrow();
    expect(() => settings.selectInterfaceFont("noto-sans")).not.toThrow();
    expect(env.dataset).toMatchObject({ theme: "sunrise", font: "noto-sans" });
  });

  it("跨标签页修改和清除时同步，忽略无关存储且不循环写入", async () => {
    const env = createEnvironment();
    const settings = await import("../../src/platform/appearance");
    settings.initializeAppearance();
    settings.initializeAppearance();
    expect(env.listeners).toHaveLength(1);
    env.listeners[0]({ key: "ims-appearance-theme", newValue: "botanical" });
    env.listeners[0]({ key: "ims-appearance-font", newValue: "noto-serif" });
    env.listeners[0]({ key: "other-setting", newValue: "test" });
    expect(env.dataset).toMatchObject({ theme: "botanical", font: "noto-serif" });
    expect(env.storage.setItem).not.toHaveBeenCalled();
    env.listeners[0]({ key: null, newValue: null });
    expect(env.dataset).toMatchObject({ theme: "blue", font: "system", wallpaper: "false" });
  });

  it("未知主题与字体不会注入样式或路径", async () => {
    const env = createEnvironment();
    env.values.set("ims-appearance-theme", "url(https://invalid.example)");
    env.values.set("ims-appearance-font", "unknown-font");
    const settings = await import("../../src/platform/appearance");
    settings.initializeAppearance();
    expect(env.dataset).toMatchObject({ theme: "blue", font: "system", wallpaper: "false" });
    expect(env.style.get("--app-wallpaper")).toBe("none");
  });
});
