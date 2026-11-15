/** 会议表单收发的最小业务资料。 */
export interface MeetingOption {
  id: string;
  name: string;
}
export interface MeetingPerson extends MeetingOption {
  employeeNo: string;
  departmentId?: string;
}
export interface MeetingRoom extends MeetingOption {
  enabled: boolean;
}
export interface MeetingOptions {
  rooms: MeetingOption[];
  departments: MeetingOption[];
  canCreate: boolean;
  canWeekly: boolean;
  canUrgent: boolean;
  canManageRooms: boolean;
}
export interface MeetingSummary {
  id: string;
  subject: string;
  kind: "ORDINARY" | "WEEKLY";
  urgent: boolean;
  startsAt: string;
  endsAt: string;
  roomName: string;
  state: string;
}
export interface MeetingAttendee {
  userId: string;
  name: string;
  employeeNo: string;
  reply?: string;
  declineReason?: string;
  materialRequired?: boolean;
  report?: string | null;
  topics?: string | null;
  materialAt?: string | null;
  materials?: MeetingMaterial[];
}
export interface MeetingItemDraft {
  content: string;
  assigneeId: string;
  assigneeName?: string;
  dueAt: string;
}
export interface MeetingItem {
  id: string;
  content: string;
  assigneeId: string;
  assigneeName: string;
  dueAt: string;
  state: string;
  overdue: boolean;
  history: {
    state: string;
    actorName: string;
    text: string | null;
    at: string;
  }[];
  canSubmit: boolean;
  canReview: boolean;
}
export interface MeetingDetail extends MeetingSummary {
  organizerId: string;
  organizerName: string;
  roomId: string | null;
  description: string | null;
  cancelReason: string | null;
  collectMaterials: boolean;
  materialTypes: MaterialKind[] | null;
  materialAuthor?: { name: string; department: string };
  trackFollowUp: boolean;
  shareAll: boolean | null;
  liveNotes?: string | null;
  rowVersion: number;
  actualStartedAt: string | null;
  actualEndedAt: string | null;
  canStart: boolean;
  canEnd: boolean;
  canEdit: boolean;
  canClose: boolean;
  canReply: boolean;
  canSubmitMaterials: boolean;
  canWriteMinutes: boolean;
  attendees: MeetingAttendee[];
  minutes: {
    materialsText: string | null;
    materialTables?: MeetingMaterial[];
    summaryTypes?: MaterialKind[];
    notes?: string | null;
    noteReaderIds?: string[];
    sharedSummaries?: Partial<Record<MaterialKind, boolean>>;
    summary: string | null;
    decisions: string | null;
    publishedAt: string | null;
    items?: MeetingItemDraft[];
  } | null;
  items: (Omit<MeetingItem, "state"> & {
    canOpen: boolean;
    state: string | null;
  })[];
}

export const meetingStates: Record<string, string> = {
  SCHEDULED: "已预约",
  IN_PROGRESS: "进行中",
  AWAITING_MINUTES: "待发布纪要",
  FOLLOW_UP: "待办跟踪",
  CLOSED: "已办结",
  CANCELLED: "已取消",
  PENDING: "待反馈",
  SUBMITTED: "待确认",
  RETURNED: "退回补充",
  CONFIRMED: "已确认",
};

/** 日期输入与工作台同用上海时区，浏览器所在时区不改变预约时段。 */
export function meetingDateInput(value: string) {
  const time = new Date(new Date(value).getTime() + 8 * 60 * 60 * 1000);
  return time.toISOString().slice(0, 19);
}

export function meetingInstant(value: string) {
  return value ? new Date(value + "+08:00").toISOString() : "";
}

export const materialLabels = {
  REPORT: "工作汇报",
  TOPICS: "会议议题",
  COORDINATION: "部门协调事宜",
} as const;
export type MaterialKind = keyof typeof materialLabels;
export interface MaterialRow {
  subject: string;
  expected: string;
  departmentId?: string | null;
  personId?: string | null;
  department?: string;
  person?: string;
}
export interface MeetingMaterial {
  kind: MaterialKind;
  submittedAt: string;
  data: {
    html?: string;
    text?: string;
    rows?: MaterialRow[];
    author: { name: string; department: string };
  };
}

/** 只保留编辑器支持的格式。通过模板读取惰性 DOM，重建时不复制任何属性。 */
export function reportMarkup(html: string) {
  const source = document.createElement("template");
  source.innerHTML = html;
  const result = document.createElement("div");
  const allowed = new Set([
    "B",
    "STRONG",
    "I",
    "EM",
    "U",
    "P",
    "DIV",
    "BR",
    "UL",
    "OL",
    "LI",
  ]);
  function append(node: Node, parent: HTMLElement) {
    if (node.nodeType === Node.TEXT_NODE) {
      parent.append(document.createTextNode(node.textContent ?? ""));
    } else if (node instanceof Element) {
      if (["SCRIPT", "STYLE", "TEMPLATE"].includes(node.tagName)) return;
      const target = allowed.has(node.tagName)
        ? document.createElement(node.tagName)
        : parent;
      if (target !== parent) parent.append(target);
      node.childNodes.forEach((child) => append(child, target));
    }
  }
  source.content.childNodes.forEach((node) => append(node, result));
  return result.innerHTML;
}
