/** 公告列表保留全部已确认的发布元数据，正文仅在详情请求。 */
export interface Announcement {
  id: string;
  title: string;
  category: string;
  state: string;
  scope: string;
  validFrom: string;
  validUntil: string | null;
  pinned: boolean;
  publisherName: string | null;
  publisherDepartment: string | null;
  publishedAt: string | null;
  rowVersion: number;
  /** 工作台公告中当前账号是否打开过正文。 */
  viewed?: boolean;
  body?: string;
  departmentIds?: string[];
}
/** 管理中心和工作台共用网站分组名称。 */
export const siteCategories = {
  QUICK_LINK: "常用网站",
  ONLINE_TOOL: "在线工具",
  SOFTWARE_DOWNLOAD: "软件下载",
} as const;
export type SiteCategory = keyof typeof siteCategories;

/** 常用网站只用于浏览器外链，后台不抓取目标网页。 */
export interface Site {
  id: string;
  name: string;
  url: string;
  category: SiteCategory;
  sortOrder: number;
  enabled: boolean;
  rowVersion: number;
}
/** 首页时刻统一上海时区，完整到秒，不以相对时间掩盖发布时间。 */
export function portalTime(value: string | null) {
  return value
    ? new Intl.DateTimeFormat("zh-CN", {
        timeZone: "Asia/Shanghai",
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false,
      }).format(new Date(value))
    : "尚未发布";
}
/** 只有业务标识进入hash参数，不携带会话或密码。 */
export function portalParams() {
  return new URLSearchParams(location.hash.split("?")[1] || "");
}
/** 后端固定消息枚举，避免把外部文案当HTML或可执行链接。 */
export const messageNames: Record<string, string> = {
  FEEDBACK_REPLIED: "你的意见反馈有了回复",
  TASK_AVAILABLE: "有一项审批待你办理",
  TASK_OVERDUE: "有一项审批已超过办理时限",
  WORKFLOW_APPROVED: "你的申请已批准",
  WORKFLOW_REJECTED: "你的申请已驳回",
  WORKFLOW_WITHDRAWN: "你的申请已撤回",
  WORKFLOW_CLOSED: "你的申请已结束",
  ELECTRONIC_DUE: "电子借阅临近到期",
  DUE_TOMORROW: "原件临近应还日期",
  DUE_TODAY: "原件应还日期已到",
  OVERDUE: "原件已逾期，请办理归还或续借",
  RETURN_REQUESTED: "有原件等待实收核验",
  RETURN_EXCEPTION: "原件归还核验存在异常",
  RETURN_CONFIRMED: "原件已复核归还",
  EVIDENCE_RECORDED: "同意凭据已补录，请核对申请",
  CATALOG_CHANGED: "有一项目录更正已生效",
  RETENTION_SOON: "有档案临近保管期限",
  RETENTION_DUE: "有档案已达到保管期限",
};
