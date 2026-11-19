import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, requestFetch, responseError } from "../../src/platform/api";

afterEach(() => vi.unstubAllGlobals());

describe("平台错误与追踪编号", () => {
  it("保留服务端具体错误码、原因和请求编号", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      code: "STORAGE_INSTANCE_MISMATCH", message: "存储目录与数据库实例不匹配", requestId: "trace-storage",
    }), { status: 503 })));
    await expect(api("/test")).rejects.toMatchObject({
      code: "STORAGE_INSTANCE_MISMATCH", requestId: "trace-storage", status: 503,
    });
    await expect(api("/test")).rejects.toThrow("请求编号：trace-storage");
  });

  it("代理返回 HTML 时显示 HTTP 故障及响应头编号", async () => {
    const failure = await responseError(new Response("<html>private upstream host</html>", {
      status: 502, headers: { "X-Request-Id": "trace-proxy" },
    }));
    expect(failure.message).toContain("服务暂不可用");
    expect(failure.message).toContain("trace-proxy");
    expect(failure.message).not.toContain("private upstream");
  });

  it("断网保留客户端编号，取消请求保持取消语义", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("network"); }));
    await expect(requestFetch("/test", { headers: { "X-Request-Id": "trace-network" } }))
      .rejects.toMatchObject({ code: "NETWORK_UNAVAILABLE", requestId: "trace-network" });
    vi.stubGlobal("fetch", vi.fn(async () => { throw new DOMException("cancel", "AbortError"); }));
    await expect(requestFetch("/test", {})).rejects.toMatchObject({ name: "AbortError" });
  });

  it("成功 HTTP 返回损坏 JSON 时提供响应编号", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("not json", {
      headers: { "X-Request-Id": "trace-json" },
    })));
    await expect(api("/test")).rejects.toMatchObject({ code: "RESPONSE_FORMAT_INVALID", requestId: "trace-json" });
  });

  it("本地输入校验不伪造不存在的服务端请求编号", () => {
    expect(new ApiError("VALIDATION_FAILED", "请填写姓名").message).not.toContain("请求编号");
  });
});
