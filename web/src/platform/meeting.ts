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
  /** 受邀记录 ID，用于定位协调事项的发起人。 */
  id?: string;
  userId: string;
  name: string;
  employeeNo: string;
  reply?: string;
  declineReason?: string;
  materialRequired?: boolean;
  /** 补录材料归属人不计入受邀员工。 */
  materialOnly?: boolean;
  report?: string | null;
  topics?: string | null;
  materialAt?: string | null;
  materials?: MeetingMaterial[];
  /** 本人或发起人可见的会前资料阅读状态。 */
  readingRequired?: boolean;
  readMaterialAt?: string;
}
export interface MeetingItemDraft {
  content: string;
  trackers: MeetingOption[];
  dueAt: string;
}
/** 同一待办的个人反馈入口及可见状态。 */
export interface MeetingTracker {
  id: string;
  assigneeId: string;
  assigneeName: string;
  state: string | null;
  canOpen: boolean;
}
export interface MeetingItemGroup {
  id: string;
  groupId: string;
  content: string;
  dueAt: string;
  trackers: MeetingTracker[];
  /** 由协调结论生成的待办通过原协调结果更正。 */
  coordinationKey?: string | null;
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
  liveDecisions?: string | null;
  /** 员工当前仍待确认的共享资料版本。 */
  readingRevision?: string;
  /** 已保存的会中事项结论。 */
  coordinationResults?: CoordinationResult[];
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
    /** 发布前额外加入的协调事项跟踪人员。 */
    coordinationTrackers?: { key: string; trackers: MeetingOption[] }[];
  } | null;
  items: MeetingItemGroup[];
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
  CORRECTED: "发起人更正",
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
  REPORT: "上周工作内容",
  WEEKLY_PLAN: "本周工作计划",
  TOPICS: "会议议题",
  COORDINATION: "协调事宜",
} as const;
/** 用于填写事项及按类汇总，旧事项按部门配合展示。 */
export const coordinationLabels = {
  COMPANY: "需公司协调",
  DEPARTMENT: "需其他部门配合",
} as const;
export type MaterialKind = keyof typeof materialLabels;
export type CoordinationCategory = keyof typeof coordinationLabels;
export interface MaterialRow {
  subject: string;
  expected: string;
  category?: CoordinationCategory;
  support?: string;
  departmentId?: string | null;
  department?: string;
  assigneeId?: string | null;
  assigneeName?: string | null;
  /** 周会汇总中原有的截止时间。 */
  dueAt?: string;
}
/** 调用本场事项结论接口和展示纪要所需的字段。 */
export interface CoordinationResult {
  key: string;
  subject: string;
  conclusion: string | null;
  /** 改派后的配合部门，旧协调结果可能没有这两个字段。 */
  departmentId?: string | null;
  department?: string | null;
  assigneeId: string | null;
  assigneeName: string | null;
  dueAt: string | null;
  followUp: boolean;
}
export interface MeetingMaterial {
  kind: MaterialKind;
  /** 汇总行按受邀记录与材料内顺序关联会中结论。 */
  attendeeId?: string;
  submittedAt: string;
  data: {
    html?: string;
    text?: string;
    markdown?: string;
    imageKeys?: string[];
    source?: "EXCEL";
    rows?: MaterialRow[];
    author: { name: string; department: string };
  };
}

/** 图片访问路径绑定到具体的会议、受邀人和材料类型。 */
export function meetingMaterialImageBase(meetingId: string, material?: MeetingMaterial) {
  return material?.attendeeId
    ? `/api/v1/meetings/${meetingId}/materials/${material.attendeeId}/${material.kind}/images`
    : undefined;
}
