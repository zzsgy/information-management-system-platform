import { reactive } from "vue";
import type { User, Row } from "./model";

/** 页签级令牌：刷新保留，不写入长期localStorage；主动退出才撤销服务端会话。 */
let token =
  typeof sessionStorage === "undefined"
    ? ""
    : (sessionStorage.getItem("ims.token") ?? "");
/** 身份世代号，丢弃退出或切换账号后才返回的旧响应。 */
let epoch = 0;
/** 根组件与路由共享的登录状态；不包含登录密码。 */
export const session = reactive<{ user: User | null; ready: boolean }>({
  user: null,
  ready: false,
});
/** 业务错误携带 HTTP 状态，页面可准确区分权限、冲突和维护阻断。 */
export class ApiError extends Error {
  /** 保存服务端的安全提示，不回显请求凭据。 */
  constructor(
    message: string,
    /** HTTP 响应码。 */ public status: number,
  ) {
    super(message);
  }
}
/** 精确权限检查；archive.admin 等业务权限不等于全局通配。 */
export function can(permission: string): boolean {
  return permission
    .split("|")
    .some(
      (required) =>
        !!session.user?.permissions.some(
          (value) => value === "*" || value === required,
        ),
    );
}
/** 清除内存和当前页签身份，递增版本防止旧请求污染新会话。 */
export function clearSession(): void {
  token = "";
  epoch++;
  session.user = null;
  sessionStorage.removeItem("ims.token");
}
/** 获取 API 响应；仅允许本站 /api 路由，拒绝带令牌的重定向和无界等待。 */
async function response(
  path: string,
  method: string,
  body?: unknown,
): Promise<Response> {
  if (
    !/^\/[a-zA-Z0-9]/.test(path) ||
    path.includes("://") ||
    path.includes("..")
  )
    throw new Error("无效的接口地址");
  const currentEpoch = epoch; // 发起请求时绑定的账号世代。
  const headers: Record<string, string> = {}; // 认证只通过请求头传递，避免 URL 和日志泄露。
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined && !(body instanceof FormData))
    headers["Content-Type"] = "application/json";
  let result: Response; // fetch 失败与业务失败分开处理，写操作不自动重试。
  try {
    result = await fetch(`/api${path}`, {
      method,
      headers,
      body:
        body === undefined
          ? undefined
          : body instanceof FormData
            ? body
            : JSON.stringify(body),
      redirect: "error",
      cache: "no-store",
      signal: AbortSignal.timeout(300000),
    });
  } catch {
    throw new ApiError(
      "连接中断或请求超时。操作可能已提交，请刷新确认后再操作。",
      0,
    );
  }
  if (epoch !== currentEpoch)
    throw new ApiError("登录身份已变化，请重新加载。", 409);
  if (!result.ok) {
    const error = await result.json().catch(() => ({})); // 仅展示安全业务提示，网关 HTML 不显示。
    if (epoch !== currentEpoch)
      throw new ApiError("登录身份已变化，请重新加载。", 409);
    if (result.status === 401) clearSession();
    throw new ApiError(
      error.message || `请求失败（${result.status}）`,
      result.status,
    );
  }
  return result;
}
/** JSON 接口封装；参数 body 为受控表单数据，服务端仍为最终权限和规则来源。 */
export async function api<T = Row>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const currentEpoch = epoch; // 读取完整响应后再次检查身份，覆盖解析期间的账号变化。
  const result = await (await response(path, method, body)).json(); // 标准 code/message/data 响应。
  if (epoch !== currentEpoch)
    throw new ApiError("登录身份已变化，请重新加载。", 409);
  if (result.code !== 0) throw new ApiError(result.message || "操作失败", 400);
  return result.data as T;
}
/** 二进制响应同样需要认证和账号世代验证。 */
export async function fileBlob(path: string): Promise<Blob> {
  const currentEpoch = epoch; // 二进制下载所属会话。
  const blob = await (await response(path, "GET")).blob(); // 不把令牌放入附件链接。
  if (epoch !== currentEpoch)
    throw new ApiError("登录身份已变化，请重新加载。", 409);
  return blob;
}
/** 登录成功后才保存令牌，失败不产生半登录状态。 */
export async function login(username: string, password: string): Promise<void> {
  const data = await api<User & { access_token: string }>(
    "/auth/login",
    "POST",
    { username, password },
  ); // 密码只在本次请求中使用。
  epoch++;
  token = data.access_token;
  sessionStorage.setItem("ims.token", token);
  const { access_token: ignored, ...user } = data; // 响应中的令牌不复制到公开身份对象。
  session.user = user;
}
/** 刷新页面时向服务端验证令牌，过期/禁用账号立即回到登录页。 */
export async function restoreSession(): Promise<void> {
  try {
    if (token) session.user = await api<User>("/system/info");
  } catch {
    clearSession();
  } finally {
    session.ready = true;
  }
}
/** 主动注销须先让服务端撤销会话，再清除浏览器状态；失败时提示用户。 */
export async function logout(): Promise<void> {
  try {
    await api("/auth/logout", "POST");
  } finally {
    clearSession();
  }
}
/** 统一构建查询参数；空筛选值不送入后端整数解析器。 */
export function query(values: Row): string {
  const params = new URLSearchParams(); // 浏览器原生编码避免姓名、关键字破坏 URL。
  Object.entries(values).forEach(([key, value]) => {
    if (value !== "" && value !== null && value !== undefined)
      params.set(key, String(value));
  });
  return params.size ? `?${params}` : "";
}
/** 保存授权取得的 Blob，并在点击完成后撤销临时地址。 */
export function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob); // 地址只存在于浏览器内存。
  const link = document.createElement("a"); // 不把附件内容插入页面 HTML。
  link.href = url;
  link.download = name.replace(/[\\/:*?"<>|\r\n]/g, "_");
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
