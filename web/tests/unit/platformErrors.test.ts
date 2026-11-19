import { afterEach, describe, expect, it, vi } from "vitest";
import { usageBlob } from "../../src/platform/exports";
import { evidenceBlob } from "../../src/platform/evidence";
import { api, ApiError, requestFetch, responseError, responseRequestId, pageImage } from "../../src/platform/api";

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


/** 验证真实API解析器：缺字段必须在入口报契约错误，不能把undefined交给各页面。 */
describe("统一响应合约", () => {
  it.each([
    null, [], "OK", {code: "OK", data: []}, {message:"成功", data:[]},
    {code:"OK", message:"成功"}, {code:"", message:"成功", data:[]}, {code:"OK", message:"  ", data:[]},
  ])("拒绝缺字段或错误类型的成功响应：%j", async body => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(body), {headers:{"X-Request-Id":"trace-contract"}})));
    await expect(api("/test")).rejects.toMatchObject({code:"RESPONSE_FORMAT_INVALID", requestId:"trace-contract", status:200});
  });
  it.each([null, [], {items:[],total:0}])("保留合法成功数据：%j", async data => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({code:"OK",message:"操作成功",data,requestId:"trace-valid"}))));
    expect(await api("/test")).toEqual(data);
  });
  it("HTTP成功但业务失败仍保留原始代码和请求编号", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({code:"CONCURRENT_UPDATE",message:"记录已修改",data:null}), {headers:{"X-Request-Id":"trace-conflict"}})));
    await expect(api("/test")).rejects.toMatchObject({code:"CONCURRENT_UPDATE",requestId:"trace-conflict"});
  });
  it("网关省略响应编号时保留实际发送的客户端编号", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("upstream unavailable", {status:502})));
    const response=await requestFetch("/test", {headers:{"X-Request-Id":"trace-sent"}});
    expect(responseRequestId(response)).toBe("trace-sent");
    expect(await responseError(response)).toMatchObject({code:"HTTP_502",requestId:"trace-sent"});
  });
  it("服务端响应编号优先于客户端编号", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("upstream", {status:502,headers:{"X-Request-Id":"trace-server"}})));
    const response=await requestFetch("/test", {headers:{"X-Request-Id":"trace-sent"}});
    expect(responseRequestId(response)).toBe("trace-server");
  });
  it("HTTP成功返回损坏JSON且无响应编号时仍能追踪本次请求", async () => {
    let sent="";
    vi.stubGlobal("fetch", vi.fn(async (_:unknown, options:RequestInit) => {sent=new Headers(options.headers).get("X-Request-Id")!;return new Response("<html>bad gateway</html>");}));
    try {await api("/test");throw new Error("应拒绝损坏JSON");} catch(failure){expect(failure).toMatchObject({code:"RESPONSE_FORMAT_INVALID",requestId:sent});expect(sent).not.toBe("");}
  });
  it("页面与副本收到JSON不能当图片或文件交付", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", {headers:{"Content-Type":"application/json","X-Request-Id":"trace-binary"}})));
    await expect(pageImage("/test")).rejects.toMatchObject({code:"PAGE_FORMAT_INVALID",requestId:"trace-binary"});
    await expect(usageBlob("/test","operation")).rejects.toMatchObject({code:"EXPORT_FORMAT_INVALID",requestId:"trace-binary"});
  });
  it("副本为空或摘要不一致时拒绝并保留请求编号", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", {headers:{"Content-Type":"application/pdf","X-Request-Id":"trace-empty"}})));
    await expect(usageBlob("/test","operation")).rejects.toMatchObject({code:"EXPORT_CONTENT_EMPTY",requestId:"trace-empty"});
    vi.stubGlobal("fetch", vi.fn(async () => new Response("changed", {headers:{"Content-Type":"application/pdf","X-Content-SHA256":"0".repeat(64),"X-Request-Id":"trace-sha"}})));
    await expect(usageBlob("/test","operation")).rejects.toMatchObject({code:"EXPORT_INTEGRITY_FAILED",requestId:"trace-sha"});
  });
  it("补录凭据的格式和真实摘要核验失败均可追踪", async () => {
    const item={id:"fixture",format:"application/pdf",size:7,digest:"0".repeat(64)};
    vi.stubGlobal("fetch", vi.fn(async () => new Response("changed", {headers:{"Content-Type":"application/pdf","X-Request-Id":"trace-evidence"}})));
    await expect(evidenceBlob(item as Parameters<typeof evidenceBlob>[0])).rejects.toMatchObject({code:"EVIDENCE_INTEGRITY_FAILED",requestId:"trace-evidence"});
  });
});
