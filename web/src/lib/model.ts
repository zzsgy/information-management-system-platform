/** 服务端各模块的白名单行数据；动态字段只用于表格和表单，不直接转成 HTML。 */
export type Row = Record<string, any>;
/** 下拉选择项的稳定业务值和中文名称。 */
export interface Option {
  /** 保存到接口的值。 */ value: string | number;
  /** 用户可读名称。 */ label: string;
}
/** 表单字段声明，统一处理输入类型、必填条件和数值边界。 */
export interface Field {
  /** 接口字段名。 */ key: string;
  /** 中文标签。 */ label: string;
  /** 控件类型，默认短文本。 */ type?:
    | "text"
    | "textarea"
    | "number"
    | "date"
    | "password"
    | "select"
    | "multi";
  /** 必填约束。 */ required?: boolean;
  /** 可选值列表。 */ options?: Option[];
  /** 新建时的缺省值。 */ initial?: any;
  /** 说明文字，不代替标签。 */ help?: string;
  /** 数值最小值。 */ min?: number;
  /** 数值最大值。 */ max?: number;
  /** 字符长度上限。 */ length?: number;
}
/** 列定义，保持显示名与数据库字段分离。 */
export interface Column {
  /** 数据字段。 */ key: string;
  /** 中文列名。 */ label: string;
  /** 最小宽度。 */ width?: number;
}
/** 分页结果，列表接口总数均来自后端权限过滤后的查询。 */
export interface Page {
  /** 当前页业务记录。 */ items: Row[];
  /** 可见总数。 */ total: number;
}
/** 已登录用户的最小公开身份，不保存密码。 */
export interface User {
  /** 用户编号。 */ user_id: number;
  /** 登录名。 */ username: string;
  /** 姓名。 */ real_name: string;
  /** 部门编号。 */ dept_id: number;
  /** 精确权限编码。 */ permissions: string[];
  /** 角色列表。 */ roles: string[];
}
/** 将查询行映射为下拉选项，编号保持原始数值类型。 */
export function options(rows: Row[], label: string): Option[] {
  return rows.map((row) => ({
    value: row.id,
    label: String(row[label] ?? row.id),
  }));
}
/** 业务状态中文名称，未知状态保留原值以便定位兼容问题。 */
export const labels: Record<string, string> = {
  draft: "草稿",
  archive: "已归档",
  archived: "已归档",
  borrowed: "借出中",
  damaged: "异常",
  destroyed: "已销毁",
  executed: "已执行",
  pending: "待审批",
  approved: "已批准",
  rejected: "已驳回",
  returned: "已归还",
  checked_out: "已借出",
  reported: "已登记",
  processing: "处理中",
  resolved: "已处理",
  closed: "已关闭",
  active: "正常",
  disabled: "已禁用",
  locked: "已锁定",
  agree: "同意",
  reject: "驳回",
  borrow: "借阅",
  destroy: "销毁",
  physical: "纸质",
  electronic: "电子",
  both: "纸质及电子",
};
/** 统一格式化空值、枚举、时间和列表，不渲染服务器提供的 HTML。 */
export function display(value: any): string {
  if (value === null || value === undefined || value === "") return "—";
  if (Array.isArray(value)) return value.map(display).join("、");
  if (typeof value === "object") return JSON.stringify(value);
  return (
    labels[String(value)] ?? String(value).replace(/^(\d{4}-\d\d-\d\d)T/, "$1 ")
  );
}
