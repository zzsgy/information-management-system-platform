/** 只记录固定页面和控件类型，输入值、按钮文字、文件名与URL参数均不采集。 */
const pages = new Set([
  "workspace",
  "announcements",
  "feedback",
  "tasks",
  "drive",
  "archive",
  "workflow",
  "physical",
  "evidence",
  "audit",
  "security",
  "users",
  "departments",
  "roles",
  "config",
]);
/** 同一次用户动作及紧随的接口使用同一请求号；后台轮询显式使用独立编号。 */
let current = "",
  expires = 0;
export function interactionRequest(activity = true): string {
  return activity && current && performance.now() < expires
    ? current
    : crypto.randomUUID();
}
/** 全局事件代理覆盖键盘激活和动态页面；点击空白不产生意图。 */
export function trackInteractions(csrf: () => string): () => void {
  let count = 0,
    windowStart = performance.now();
  const record = (action: string, control: string) => {
    current = crypto.randomUUID();
    expires = performance.now() + 5000;
    if (!csrf()) return;
    if (performance.now() - windowStart > 60000) {
      count = 0;
      windowStart = performance.now();
    }
    if (++count > 120) return;
    const raw = location.hash.slice(2).split(/[?\/]/)[0] || "workspace";
    const aliases: Record<string, string> = {
      home: "workspace",
      todos: "tasks",
      inbox: "tasks",
      people: "users",
      personnel: "users",
      settings: "config",
      "portal-admin": "config",
      "message-service": "config",
      operations: "config",
      "operations-console": "config",
      catalog: "archive",
      imports: "archive",
      scanning: "archive",
    };
    const route = aliases[raw] || raw;
    void fetch("/api/v1/me/interactions", {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "X-CSRF-Token": csrf(),
        "X-Request-Id": current,
      },
      body: JSON.stringify({
        id: crypto.randomUUID(),
        requestId: current,
        page: pages.has(route) ? route : "other",
        action,
        control,
      }),
    }).catch(() => {
      /* 网络中断由实际业务请求展示，不把意图误记为成功。 */
    });
  };
  const click = (event: Event) => {
    const element =
      event.target instanceof Element
        ? event.target.closest("button,a,summary,[role=button]")
        : null;
    if (element)
      record(
        element.tagName === "SUMMARY" ? "EXPAND" : "ACTIVATE",
        element.tagName === "A"
          ? "LINK"
          : element.tagName === "SUMMARY"
            ? "DETAILS"
            : "BUTTON",
      );
  };
  const submit = () => record("SUBMIT", "FORM");
  const change = (event: Event) => {
    if (
      event.target instanceof HTMLInputElement ||
      event.target instanceof HTMLSelectElement
    )
      record(
        "CHANGE",
        event.target instanceof HTMLSelectElement
          ? "SELECT"
          : event.target.type === "search"
            ? "SEARCH"
            : "INPUT",
      );
  };
  const navigate = () => record("NAVIGATE", "ROUTE");
  const cancel = (event: KeyboardEvent) => {
    if (
      event.key === "Escape" &&
      document.querySelector("dialog[open],[role=dialog]")
    )
      record("CANCEL", "DIALOG");
  };
  document.addEventListener("click", click, true);
  document.addEventListener("submit", submit, true);
  document.addEventListener("change", change, true);
  document.addEventListener("keydown", cancel, true);
  window.addEventListener("hashchange", navigate);
  return () => {
    document.removeEventListener("click", click, true);
    document.removeEventListener("submit", submit, true);
    document.removeEventListener("change", change, true);
    document.removeEventListener("keydown", cancel, true);
    window.removeEventListener("hashchange", navigate);
  };
}
