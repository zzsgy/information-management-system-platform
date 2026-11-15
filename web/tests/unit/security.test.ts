import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { csvCell } from "../../src/lib/ui";

/** 每个测试独立的页签存储，不读取开发者真实凭据。 */
beforeEach(() => {
  vi.resetModules();
  const values = new Map<string, string>();
  vi.stubGlobal("sessionStorage", {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  });
});
afterEach(() => vi.unstubAllGlobals());

describe("浏览器认证和导出边界", () => {
  /** 角色语义不能偷偷扩大为独立权限，菜单的任一权限判断也必须精确。 */
  it("档案高级管理不自动授予下载或销毁权限", async () => {
    const { session, can } = await import("../../src/lib/api");
    session.user = {
      user_id: 1,
      username: "test",
      real_name: "测试",
      dept_id: 1,
      permissions: ["archive.admin", "borrow.apply"],
      roles: [],
    };
    expect(can("file.download")).toBe(false);
    expect(can("destroy.execute")).toBe(false);
    expect(can("borrow.apply|borrow.approve")).toBe(true);
  });
  /** 旧身份的延迟响应不能在退出之后成为业务页面数据。 */
  it("退出后丢弃尚未返回的旧请求", async () => {
    const { api, clearSession } = await import("../../src/lib/api");
    let finish!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve;
          }),
      ),
    );
    const pending = api("/archives");
    clearSession();
    finish(
      new Response(JSON.stringify({ code: 0, data: { secret: "旧账号数据" } })),
    );
    await expect(pending).rejects.toThrow("登录身份已变化");
  });
  /** HTML错误页面不呈现为成功，也不重复提交可能已经写入的业务。 */
  it("维护阻断只发送一次写请求", async () => {
    const { api } = await import("../../src/lib/api");
    const fetcher = vi.fn(
      async () =>
        new Response(JSON.stringify({ message: "维护中" }), { status: 503 }),
    );
    vi.stubGlobal("fetch", fetcher);
    await expect(
      api("/archives", "POST", { archive_name: "测试" }),
    ).rejects.toThrow("维护中");
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  /** 认证过期主动清除本页签状态，后续页面不继续显示特权导航。 */
  it("401清理身份和页签令牌", async () => {
    sessionStorage.setItem("ims.token", "fake-test-token");
    const { api, session } = await import("../../src/lib/api");
    session.user = {
      user_id: 1,
      username: "test",
      real_name: "测试",
      dept_id: 1,
      permissions: ["*"],
      roles: [],
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ message: "会话失效" }), {
            status: 401,
          }),
      ),
    );
    await expect(api("/auth/me")).rejects.toThrow("会话失效");
    expect(session.user).toBeNull();
    expect(sessionStorage.getItem("ims.token")).toBeNull();
  });
  /** 二进制读取期间切换身份也应拒绝交付旧附件。 */
  it("附件下载同样绑定发起身份", async () => {
    const { fileBlob, clearSession } = await import("../../src/lib/api");
    let finish!: (blob: Blob) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => ({
        ok: true,
        blob: () =>
          new Promise<Blob>((resolve) => {
            finish = resolve;
          }),
      })),
    );
    const pending = fileBlob("/files/1/download");
    await vi.waitFor(() => expect(finish).toBeTypeOf("function"));
    clearSession();
    finish(new Blob(["旧账号附件"]));
    await expect(pending).rejects.toThrow("登录身份已变化");
  });
  /** 主机和协议地址不得通过通用请求工具带走认证头。 */
  it("拒绝外站和路径穿越接口", async () => {
    const { api } = await import("../../src/lib/api");
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    await expect(api("//example.org/path")).rejects.toThrow("无效的接口");
    await expect(api("/files/../external")).rejects.toThrow("无效的接口");
    expect(fetcher).not.toHaveBeenCalled();
  });
  /** Excel导出防止档案名称或备注变成可执行公式，同时保持CSV引号正确。 */
  it("CSV防公式注入且保留中文逗号和双引号", () => {
    expect(csvCell('=HYPERLINK("x")')).toBe('"\'=HYPERLINK(""x"")"');
    expect(csvCell("  +1")).toBe('"\'  +1"');
    expect(csvCell('中文,"备注"')).toBe('"中文,""备注"""');
  });
});
