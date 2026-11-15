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
  ) {
    super(message);
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
  const response = await fetch(`/api/v1${path}`, {
    ...options,
    headers,
    credentials: "same-origin",
    cache: "no-store",
  });
  const result = (await response.json()) as Envelope<T>;
  if (!response.ok || result.code !== "OK") {
    if (result.code === "SESSION_INVALID") {
      // 首次匿名打开页面是正常登录入口；只有已有身份失效才提示用户。
      const previouslySignedIn = profile.value !== null;
      setProfile(null);
      if (previouslySignedIn)
        window.dispatchEvent(new Event("ims-session-expired"));
    }
    throw new ApiError(
      result.code,
      result.message || "操作未完成，请稍后重试",
      result.requestId,
    );
  }
  return result.data;
}
/** 仅从受控逐页API取得PNG，失败清除会话；不保存响应正文到持久缓存。 */
export async function pageImage(
  path: string,
  signal?: AbortSignal,
): Promise<Blob> {
  const response = await fetch(`/api/v1${path}`, {
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
    const result = (await response.json()) as Envelope<never>;
    if (result.code === "SESSION_INVALID") {
      setProfile(null);
      window.dispatchEvent(new Event("ims-session-expired"));
    }
    throw new ApiError(
      result.code,
      result.message || "页面读取失败",
      result.requestId,
    );
  }
  if (!response.headers.get("Content-Type")?.startsWith("image/png"))
    throw new Error("页面格式异常，请稍后重试");
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
      !["PROTECTION_NOT_READY"].includes(error.code)
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
