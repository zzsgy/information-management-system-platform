import { ref } from "vue";
import { interactionRequest } from "./interaction";

/** 账号可见资料；密码、Cookie、内部路径不进入该类型。 */
export interface User {
  /** 稳定账号标识。 */ id: string;
  /** 公司工号。 */ employeeNo: string;
  /** 员工姓名。 */ name: string;
  /** 单一所属部门。 */ departmentId: string;
  /** 公司邮箱。 */ companyEmail: string;
  /** 当前启停状态。 */ status: "ACTIVE" | "DISABLED";
  /** 首次设密限制。 */ passwordSetupRequired: boolean;
  /** 并发修改版本。 */ rowVersion: number;
}
/** 分模块角色，不含业务对象内容授权。 */
export interface Role {
  /** 角色标识。 */ id: string;
  /** 所属模块。 */ module: "PLATFORM" | "DRIVE" | "ARCHIVE";
  /** 稳定角色代码。 */ code: string;
  /** 显示名称。 */ name: string;
  /** 启停状态。 */ status: string;
  /** 权限配置代次。 */ permissionVersion: number;
  /** 记录版本。 */ rowVersion: number;
}
/** 部门管理视图；树关系在页面组织，不重复拉取每层数据。 */
export interface Department {
  /** 部门标识。 */ id: string;
  /** 业务编码。 */ code: string;
  /** 显示名称。 */ name: string;
  /** 上级部门；顶级为空。 */ parentId: string | null;
  /** 启停状态。 */ status: "ACTIVE" | "DISABLED";
  /** 排序权重。 */ sortOrder: number;
  /** 并发版本。 */ rowVersion: number;
}
/** 当前会话中的本人资料和按钮可见性；最终授权始终由服务端执行。 */
export interface Profile {
  /** 当前员工资料。 */
  user: User;
  /** 本会话可见的有效角色。 */
  roles: Role[];
  /** 当前有效能力代码，服务端仍会重新鉴权。 */
  actions: string[];
  /** 与当前 HttpOnly 会话绑定的防伪造值。 */
  csrfToken: string;
}
/** 高风险管理操作可在同一身份确认弹窗说明影响范围，避免额外叠加确认步骤。 */
export interface PasswordPromptOptions {
  /** 弹窗标题。 */ heading?: string;
  /** 提交按钮文字。 */ confirmLabel?: string;
  /** 使用现有危险操作样式。 */ danger?: boolean;
}
/** 所有列表使用同一有界分页模型。 */
export interface Page<T> {
  /** 当前页经过权限过滤的结果。 */
  items: T[];
  /** 页码，从 1 开始。 */
  page: number;
  /** 每页数量，上限 100。 */
  pageSize: number;
  /** 授权范围内匹配的总数。 */
  total: number;
  /** 是否还能读取下一页。 */
  hasNext: boolean;
}
/** 标准接口包装；requestId 用于用户反馈定位。 */
interface Envelope<T> {
  /** 稳定业务结果码。 */
  code: string;
  /** 可安全展示的中文说明。 */
  message: string;
  /** 当前接口声明的响应数据。 */
  data: T;
  /** 与服务端审计对应的追踪号。 */
  requestId: string;
}
/** 当前资料只放内存；刷新重新验证 HttpOnly Cookie，浏览器不保存登录令牌。 */
export const profile = ref<Profile | null>(null);
/** 防伪造请求值仅绑定当前会话。 */
let csrf = "";
/** 不确定结果的管理操作保留操作号，重试不会重复创建数据。 */
const operations = new Map<string, string>();
/** 稳定集合标识，与服务端创建操作再验证范围一致。 */
export const COLLECTION = "00000000-0000-0000-0000-000000000001";
/** 用户可理解的接口失败，保留机器码以区别错密码和会话失效。 */
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public requestId = "",
    public status = 0,
  ) {
    super(`${message}${code ? `（错误码：${code}）` : ""}${requestId ? `\n请求编号：${requestId}` : ""}`);
  }
}
/** JSON 与文件接口共用错误解析，代理 HTML 错误也保留响应头中的请求编号。 */
export async function responseError(response: Response, fallback = "操作未完成"): Promise<ApiError> {
  let result: Partial<Envelope<never>> = {};
  try {
    const body = await response.json();
    if (body && typeof body === "object") result = body;
  } catch { /* 网关故障可能返回 HTML 或空正文。 */ }
  if (result.code === "SESSION_INVALID" || result.code === "SECURITY_VERSION_REVOKED") {
    const previouslySignedIn = profile.value !== null;
    setProfile(null);
    if (previouslySignedIn) window.dispatchEvent(new Event("ims-session-expired"));
  }
  const message = (typeof result.message === "string" && result.message) || (response.status === 502 || response.status === 503
    ? "服务暂不可用，请联系 IT 管理员检查应用及代理服务"
    : response.status === 504 ? "服务响应超时，请先查询操作结果，避免重复提交"
    : `${fallback}：服务器未返回有效的错误说明（HTTP ${response.status}）`);
  return new ApiError(typeof result.code === "string" ? result.code : `HTTP_${response.status}`, message,
    typeof result.requestId === "string" ? result.requestId : response.headers.get("X-Request-Id") || "", response.status);
}
/** 网络失败保留客户端已发送的请求编号；主动取消仍交给页面按原流程处理。 */
export async function requestFetch(input: RequestInfo | URL, options: RequestInit): Promise<Response> {
  try { return await fetch(input, options); }
  catch (failure) {
    if ((failure as Error).name === "AbortError") throw failure;
    throw new ApiError("NETWORK_UNAVAILABLE", "无法连接服务器，请检查网络；若刚刚提交过，请先查询结果再重试",
      new Headers(options.headers).get("X-Request-Id") || "");
  }
}
/** 统一同源 JSON 请求；登录 Cookie 由浏览器管理，未知接口不得当成功。 */
export async function api<T>(
  path: string,
  options: RequestInit = {},
  activity = true,
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("X-Request-Id", interactionRequest(activity));
  if (options.body && !headers.has("Content-Type"))
    headers.set("Content-Type", "application/json");
  if (csrf) {
    headers.set("X-CSRF-Token", csrf);
    if (activity) headers.set("X-User-Activity", "1");
  }
  const response = await requestFetch(`/api/v1${path}`, {
    ...options,
    headers,
    credentials: "same-origin",
    cache: "no-store",
  });
  if (!response.ok) throw await responseError(response);
  let result: Envelope<T>;
  try { result = await response.json(); }
  catch { throw new ApiError("RESPONSE_FORMAT_INVALID", "服务器响应格式异常，请联系 IT 管理员",
    response.headers.get("X-Request-Id") || headers.get("X-Request-Id") || "", response.status); }
  if (!result || typeof result.code !== "string" || typeof result.message !== "string")
    throw new ApiError("RESPONSE_FORMAT_INVALID", "服务器响应缺少有效的结果码或说明，请联系 IT 管理员",
      response.headers.get("X-Request-Id") || headers.get("X-Request-Id") || "", response.status);
  if (result.code !== "OK") throw new ApiError(result.code, result.message || "服务器未能完成本次操作",
    result.requestId, response.status);
  return result.data;
}
/** 仅从受控逐页API取得PNG，失败清除会话；不保存响应正文到持久缓存。 */
export async function pageImage(
  path: string,
  signal?: AbortSignal,
): Promise<Blob> {
  const response = await requestFetch(`/api/v1${path}`, {
    credentials: "same-origin",
    cache: "no-store",
    signal,
    headers: {
      "X-Request-Id": crypto.randomUUID(),
      "X-CSRF-Token": csrf,
      "X-User-Activity": "1",
    },
  });
  if (!response.ok) {
    throw await responseError(response, "页面读取失败");
  }
  if (!response.headers.get("Content-Type")?.startsWith("image/png"))
    throw new ApiError("PAGE_FORMAT_INVALID", "服务器返回的页面不是 PNG 图像，请联系 IT 管理员",
      response.headers.get("X-Request-Id") || "");
  return response.blob();
}
/** 原子更新登录资料和 CSRF，避免退出后复用旧会话的前端状态。 */
export function setProfile(value: Profile | null) {
  profile.value = value;
  csrf = value?.csrfToken ?? "";
  if (!value) operations.clear();
}
/** 登录响应先建立受限资料，首次设密完成前不渲染业务页面。 */
export async function login(employeeNo: string, password: string) {
  const result = await api<{
    user: User;
    csrfToken: string;
    passwordSetupRequired: boolean;
  }>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ employeeNo, password }),
  });
  setProfile({
    user: result.user,
    csrfToken: result.csrfToken,
    roles: [],
    actions: [],
  });
  setProfile(await api<Profile>("/auth/me", {}, false));
}
/** 模块动作精确匹配；不使用 startsWith 或通配符放大按钮权限。 */
export function can(action: string) {
  return profile.value?.actions.includes(action) ?? false;
}
/** 页面通过一次性密码对话框取得再验证，口令不进入持久化请求缓存。 */
export async function mutate<T>(
  path: string,
  method: string,
  body: unknown,
  target: string,
  type: string,
  action: string,
  password: string,
  version?: number,
): Promise<T> {
  const key = JSON.stringify([path, method, body, version]);
  const operation = operations.get(key) ?? crypto.randomUUID();
  operations.set(key, operation);
  const verification = await api<{ verificationToken: string }>(
    "/auth/reauthentications",
    {
      method: "POST",
      body: JSON.stringify({
        password,
        purpose: "MANAGE",
        objectIds: [target],
        scopeItems: [{ objectType: type, objectId: target, actions: [action] }],
      }),
    },
  );
  const headers: Record<string, string> = {
    "X-Operation-Id": operation,
    "X-Reauth-Token": verification.verificationToken,
  };
  if (version !== undefined) headers["X-Expected-Version"] = String(version);
  try {
    const result = await api<T>(path, {
      method,
      body: JSON.stringify(body),
      headers,
    });
    operations.delete(key);
    return result;
  } catch (error) {
    // 确定性拒绝允许重新读取版本后重试；网络或保护失败保留同一操作号核查原请求。
    if (
      error instanceof ApiError &&
      error.status >= 400 && error.status < 500
    )
      operations.delete(key);
    throw error;
  }
}
/** 查询参数由 URLSearchParams 编码，禁止字符串拼接用户输入。 */
export function query(values: Record<string, string | number | undefined>) {
  return new URLSearchParams(
    Object.entries(values)
      .filter(([, value]) => value !== undefined && value !== "")
      .map(([name, value]) => [name, String(value)]),
  ).toString();
}
/** 公共显示格式只处理时间，不修改服务端时区或业务日期。 */
export function dateTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}
