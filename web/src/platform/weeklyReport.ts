/** 在线周会的工作表、行草稿和提交快照。 */
export type WeeklyKind = "REPORT" | "WEEKLY_PLAN" | "COORDINATION";

export interface WeeklyRow {
  id: string;
  departmentId: string;
  kind: WeeklyKind;
  authorName: string;
  employeeNo: string;
  updatedBy: string;
  updatedAt: string;
  version: number;
  partner?: string | null;
  assigneeName?: string | null;
  assigneeNo?: string | null;
  data: {
    markdown?: string | null;
    note?: string | null;
    imageKeys?: string[];
    subject?: string | null;
    support?: string | null;
    expected?: string | null;
    category?: "COMPANY" | "DEPARTMENT";
    departmentId?: string | null;
    personId?: string | null;
    department?: string | null;
    assigneeName?: string | null;
  };
}

export interface WeeklyDepartment {
  departmentId: string;
  name: string;
  headName: string | null;
  headInvited: boolean;
  active: boolean;
  submittedVersion: number | null;
  extractedVersion: number | null;
  canEdit: boolean;
  canSubmit: boolean;
  canReopen: boolean;
}

export interface WeeklyVersion {
  departmentId: string;
  version: number;
  submittedBy: string;
  submittedAt: string;
}

export interface WeeklySheet {
  departments: WeeklyDepartment[];
  rows: WeeklyRow[];
  versions: WeeklyVersion[];
  ownDepartmentId: string | null;
  canExtract: boolean;
}

/** 发起人确认前看到待提取部门、缺报和失效来源。 */
export interface WeeklyPreview {
  ready: { departmentId: string; name: string; submittedVersion: number }[];
  missing: { departmentId: string; name: string }[];
  errors: string[];
  token: string;
}

export const weeklyLabels: Record<WeeklyKind, string> = {
  REPORT: "上周总结",
  WEEKLY_PLAN: "本周计划",
  COORDINATION: "协调事宜",
};
