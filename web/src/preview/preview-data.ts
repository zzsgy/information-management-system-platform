/** 设计工具条中的模拟身份，不用于正式登录认证或接口授权。 */
export type PreviewIdentity = "user" | "it-admin";

/** 应用卡片的展示字段；这里只描述设计状态，不代表系统已经开通。 */
export interface PreviewApplication {
  /** 原型内的稳定编号，不对应数据库主键。 */
  id: string;
  /** 用户看到的系统名称。 */
  name: string;
  /** 卡片图标与配色名称。 */
  icon: string;
  tone: string;
  /** 卡片右上角显示的业务类型或规划状态。 */
  category: string;
  /** 规划中的系统显示不同的入口状态。 */
  planned?: boolean;
  /** 仅控制设计稿中的管理员入口展示；正式权限由后端校验。 */
  administratorOnly?: boolean;
}

/** 普通用户显示两个业务入口，管理员视角另有角色权限入口。 */
export const applications: PreviewApplication[] = [
  { id: "drive", name: "公盘", icon: "folder", tone: "jade", category: "文件协作" },
  { id: "archive", name: "档案管理", icon: "archive", tone: "blue", category: "档案业务" },
  { id: "roles", name: "权限角色设置", icon: "shield", tone: "violet", category: "管理", administratorOnly: true },
];

/** 待办事项仅用于展示排版、类别和筛选状态，全部为虚构示例。 */
export const tasks = [
  { id: 1, title: "设备技术资料借阅申请", source: "档案管理", person: "张楠", department: "设备管理部", time: "09:20", kind: "待审批", icon: "archive", tone: "blue", urgent: true },
  { id: 2, title: "项目竣工资料归档审核", source: "档案管理", person: "陈晨", department: "工程管理部", time: "09:05", kind: "待审批", icon: "archive", tone: "blue", urgent: false },
  { id: 3, title: "部门共享目录访问申请", source: "公盘", person: "王宇", department: "综合管理部", time: "昨天", kind: "待审批", icon: "folder", tone: "jade", urgent: false },
  { id: 4, title: "借阅档案到期归还提醒", source: "档案管理", person: "系统提醒", department: "借阅管理", time: "今天", kind: "待处理", icon: "clock", tone: "amber", urgent: false },
  { id: 5, title: "共享资料分类整理", source: "公盘", person: "刘洋", department: "综合管理部", time: "昨天", kind: "待处理", icon: "folder", tone: "jade", urgent: false },
  { id: 6, title: "归档材料信息补充", source: "档案管理", person: "陈晨", department: "工程管理部", time: "昨天", kind: "待处理", icon: "file", tone: "blue", urgent: false },
];

/** 首页公告的展示字段，包含标题、标签以及发布来源信息。 */
export interface PreviewNotice {
  /** 公告完整标题，同时用于当前静态列表的稳定标识。 */
  title: string;
  /** 标题下方展示的公告分类标签。 */
  category: string;
  /** 发布公告的示例人员姓名，不是真实账号数据。 */
  publisher: string;
  /** 示例发布人所属的部门名称。 */
  department: string;
  /** 带北京时间偏移的发布时间；界面显示年月日及小时、分钟。 */
  publishedAt: string;
  /** 是否在标题前展示置顶标记。 */
  pinned: boolean;
}

/** 公告、姓名、部门及发布时间均为设计示例，不读取实际公告接口。 */
export const notices: PreviewNotice[] = [
  { title: "信息管理平台使用指南", category: "平台公告", publisher: "李明", department: "信息技术部", publishedAt: "2026-10-19T09:00:00+08:00", pinned: true },
  { title: "关于规范档案借阅与归还的说明", category: "档案管理", publisher: "陈晨", department: "工程管理部", publishedAt: "2026-10-18T14:30:00+08:00", pinned: false },
  { title: "公盘目录及文件命名建议", category: "使用帮助", publisher: "王宇", department: "综合管理部", publishedAt: "2026-10-16T10:15:00+08:00", pinned: false },
];

/** 常用网站仅展示名称；地址只用于用户主动点击后的跳转。 */
export interface PreviewWebsite {
  /** 用户可识别的网站名称，同时用作列表的稳定标识。 */
  name: string;
  /** 经用户确认的网站入口，使用浏览器在新标签页打开。 */
  url: string;
}

/** 用户确认的常用网站；不猜测企业邮箱或其他内部系统的地址。 */
export const websites: PreviewWebsite[] = [
  { name: "百林客", url: "https://www.biolink.city/" },
];

