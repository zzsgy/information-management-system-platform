/** 会议详情显示部门工作簿状态，内容由原生编辑器读取。 */
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
