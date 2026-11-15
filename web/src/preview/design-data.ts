/** 身份和页面都只用于本地原型，不用于真实认证与授权。 */
export type PreviewIdentity = "user" | "manager" | "general-manager" | "it-admin" | "archivist" | "archive-admin";
export type PreviewView = "login" | "home" | "drive" | "archive" | "admin";
/** 同一员工在两个业务模块中分别显示角色与使用范围。 */
export interface PreviewProfile {
  /** 工具条身份编号。 */
  id: PreviewIdentity;
  /** 预览身份名称。 */
  label: string;
  /** 虚构姓名。 */
  name: string;
  /** 示例部门。 */
  department: string;
  /** 公盘角色。 */
  driveRole: string;
  /** 档案角色。 */
  archiveRole: string;
  /** 公盘范围说明。 */
  driveScope: string;
  /** 档案范围说明。 */
  archiveScope: string;
  /** 公盘入口文案。 */
  driveCaption: string;
  /** 档案入口文案。 */
  archiveCaption: string;
}
/** 六种示例身份不隐含跨模块权限继承。 */
export const profiles: Record<PreviewIdentity, PreviewProfile> = {
  user: { id: "user", label: "普通职员", name: "林晓", department: "综合管理部", driveRole: "职员", archiveRole: "普通职员", driveScope: "共享文档与本部门内部文档", archiveScope: "本部门档案 · 跨部门标题检索", driveCaption: "查看与下载共享资料", archiveCaption: "档案查阅与借阅申请" },
  manager: { id: "manager", label: "部门经理", name: "张宁", department: "工程管理部", driveRole: "部门经理", archiveRole: "普通职员", driveScope: "本部门及下级部门文档", archiveScope: "本部门档案 · 跨部门标题检索", driveCaption: "上传及管理本人文档", archiveCaption: "档案查阅与借阅申请" },
  "general-manager": { id: "general-manager", label: "总经理", name: "周宁", department: "总经理办公室", driveRole: "总经理", archiveRole: "普通职员", driveScope: "全公司公盘文档", archiveScope: "本部门档案 · 跨部门标题检索", driveCaption: "全公司文档查看与下载", archiveCaption: "档案查阅与借阅申请" },
  "it-admin": { id: "it-admin", label: "IT 管理员", name: "李明", department: "信息技术部", driveRole: "IT 管理员", archiveRole: "普通职员", driveScope: "全部公盘文档与文件夹", archiveScope: "本部门档案 · 跨部门标题检索", driveCaption: "文档与共享目录管理", archiveCaption: "档案查阅与借阅申请" },
  archivist: { id: "archivist", label: "部门档案员", name: "陈晨", department: "工程管理部", driveRole: "职员", archiveRole: "部门档案员", driveScope: "共享文档与本部门内部文档", archiveScope: "本部门全部档案及借阅办理", driveCaption: "查看与下载共享资料", archiveCaption: "部门档案维护与借阅办理" },
  "archive-admin": { id: "archive-admin", label: "档案超级管理员", name: "王岚", department: "档案管理部", driveRole: "职员", archiveRole: "超级管理员", driveScope: "共享文档与本部门内部文档", archiveScope: "全公司档案及档案业务管理", driveCaption: "查看与下载共享资料", archiveCaption: "全公司档案与审批管理" },
};
/** 工具条中的身份顺序。 */
export const profileOptions = Object.values(profiles);
/** 是否展示管理后台，仅控制设计稿入口。 */
export function hasManagement(identity: PreviewIdentity): boolean { return identity === "it-admin" || identity === "archive-admin"; }
/** 待办示例字段，全部为虚构内容。 */
export interface PreviewTask {
  /** 待办标题。 */
  title: string;
  /** 来源模块。 */
  source: string;
  /** 虚构发起人。 */
  person: string;
  /** 发起部门。 */
  department: string;
  /** 展示时间。 */
  time: string;
  /** 展示类别。 */
  kind: "待审批" | "待处理";
  /** 本地图标名称。 */
  icon: string;
  /** 图标色调。 */
  tone: string;
}
/** 按身份选择示例待办，普通员工和公盘经理不产生审批入口。 */
export function getPreviewTasks(identity: PreviewIdentity): PreviewTask[] {
  if (identity === "archivist" || identity === "archive-admin") return [
    { title: "设备技术资料借阅申请", source: "档案管理", person: "张楠", department: "设备管理部", time: "09:20", kind: "待审批", icon: "archive", tone: "blue" },
    { title: "项目竣工档案下载申请", source: "档案管理", person: "刘洋", department: "工程管理部", time: "09:05", kind: "待审批", icon: "archive", tone: "blue" },
    { title: identity === "archive-admin" ? "档案编辑变更通知" : "已获批纸质档案待借出", source: "档案管理", person: "陈宇", department: "工程管理部", time: "今天", kind: "待处理", icon: "file", tone: "jade" },
    { title: identity === "archive-admin" ? "档案删除操作通知" : "纸质档案到期归还提醒", source: "档案管理", person: "系统提醒", department: "借阅管理", time: "今天", kind: "待处理", icon: "clock", tone: "amber" },
  ];
  if (identity === "it-admin") return [
    { title: "员工意见反馈待处理", source: "平台管理", person: "林晓", department: "综合管理部", time: "09:20", kind: "待处理", icon: "message", tone: "jade" },
    { title: "通知发送异常待查看", source: "平台管理", person: "系统提醒", department: "通知服务", time: "09:05", kind: "待处理", icon: "bell", tone: "amber" },
    { title: "备份执行记录待检查", source: "平台管理", person: "系统提醒", department: "备份恢复", time: "今天", kind: "待处理", icon: "shield", tone: "blue" },
  ];
  return [
    { title: "纸质档案归还提醒", source: "档案管理", person: "系统提醒", department: "借阅管理", time: "今天", kind: "待处理", icon: "clock", tone: "amber" },
    { title: "电子档案借阅即将到期", source: "档案管理", person: "系统提醒", department: "借阅管理", time: "今天", kind: "待处理", icon: "file", tone: "blue" },
  ];
}
/** 名称卡片只列示功能，不执行对应操作。 */
export interface PreviewModule {
  /** 功能名称。 */
  name: string;
  /** 本地图标名称。 */
  icon: string;
  /** 下一级功能名称。 */
  children: string[];
}
/** 侧栏与主内容共用的模块分组。 */
export interface PreviewModuleGroup {
  /** 分组编号。 */
  id: string;
  /** 分组名称。 */
  name: string;
  /** 分组图标。 */
  icon: string;
  /** 分组的功能卡片。 */
  modules: PreviewModule[];
}
/** 创建纯名称数据，不关联持久化业务记录。 */
function mod(name: string, icon: string, ...children: string[]): PreviewModule { return { name, icon, children }; }
/** 生成各身份的功能模块名称，不能用作实际系统的授权实现。 */
export function getModuleGroups(view: PreviewView, identity: PreviewIdentity): PreviewModuleGroup[] {
  const isIt = identity === "it-admin"; // IT 示例。
  const isManager = identity === "manager"; // 公盘经理示例。
  const isArchivist = identity === "archivist"; // 部门档案员示例。
  const isArchiveAdmin = identity === "archive-admin"; // 档案超级管理员示例。
  if (view === "drive") {
    const operations = [mod("在线预览", "eye", "PDF 预览", "Word 预览", "Excel 预览", "PPT 预览", "图片预览"), mod("文档下载", "download", "下载文档")]; // 默认阅读功能。
    if (isIt || isManager) operations.push(mod("上传文档", "upload", "办公文档", "PDF 与图片", "压缩包", "视频"), mod("在线编辑", "edit", "Word 编辑", "Excel 编辑", "多人协作"), mod("移动文档", "move", "选择目标文件夹"), mod("删除文档", "trash", "移入回收站"));
    const history = [mod("历史版本", "history", "版本列表", "查看历史内容")]; // 历史查看名称。
    if (isIt || isManager) history.push(mod("版本恢复", "history", "选择恢复版本"), mod("回收站", "trash", "已删除文档", "恢复文档"));
    return [
      { id: "spaces", name: "文档空间", icon: "folder", modules: [mod("公司共享", "people", "公司共享文档"), mod("部门共享", "folder", "各部门共享文档"), mod("部门内部", "lock", "部门内部文档")] },
      { id: "documents", name: "文档操作", icon: "file", modules: operations },
      { id: "history", name: "版本与恢复", icon: "history", modules: history },
      ...(isIt ? [{ id: "folders", name: "文件夹管理", icon: "folder", modules: [mod("新建文件夹", "plus", "创建目录"), mod("修改文件夹", "edit", "修改目录名称"), mod("删除文件夹", "trash", "删除目录")] }] : []),
    ];
  }
  if (view === "archive") {
    const groups: PreviewModuleGroup[] = [{ id: "reading", name: "档案查阅", icon: "archive", modules: [mod(isArchiveAdmin ? "全公司档案" : "本部门档案", "archive", "电子档案", "纸质档案", "纸电混合档案"), mod("跨部门标题检索", "search", "档案标题检索"), mod("在线阅读", "eye", "附件正文预览")] }]; // 默认查阅区域。
    if (!isArchiveAdmin) groups.push({ id: "borrowing", name: "借阅申请", icon: "file", modules: [mod("电子查看申请", "eye", "查看借阅"), mod("电子下载申请", "download", "下载借阅"), mod("纸质借阅申请", "file", "原件借阅"), mod("我的申请", "task", "申请进度", "历史申请"), mod("我的借阅", "clock", "电子借阅", "纸质借阅"), mod("续借申请", "history", "延长期限")] });
    if (isArchivist || isArchiveAdmin) {
      groups.push({ id: "maintenance", name: isArchiveAdmin ? "档案维护" : "本部门档案维护", icon: "edit", modules: [mod("档案登记", "plus", "登记台账", "关联附件"), mod("档案草稿", "file", "草稿列表"), mod("已发布档案", "check", "已发布列表"), mod("附件管理", "file", "附件上传", "在线编辑", "历史版本"), mod("档案移动", "move", "档案位置调整"), mod("档案删除", "trash", "删除记录"), mod("附件下载", "download", "下载附件")] });
      groups.push({ id: "lending", name: "审批与借还", icon: "task", modules: [mod("待我审批", "task", "待审批申请"), mod("已办审批", "check", "审批记录"), mod("借出登记", "upload", "待借出", "实际借出记录"), mod("归还登记", "download", "待归还", "归还记录"), mod("逾期借阅", "clock", "逾期记录"), mod(isArchiveAdmin ? "档案业务日志" : "本部门日志", "history", "操作记录")] });
    }
    return groups;
  }
  if (view === "admin" && isIt) return [
    { id: "platform", name: "平台管理", icon: "grid", modules: [mod("员工账号", "people", "员工列表", "创建账号", "批量导入", "重置密码", "账号停用", "强制下线"), mod("部门组织", "people", "部门结构", "员工归属", "部门经理"), mod("首页内容", "home", "公告管理", "常用网站", "帮助内容", "意见反馈"), mod("通知服务", "bell", "公司邮件配置", "通知模板", "发送记录", "失败重试"), mod("平台日志", "history", "登录日志", "账号变更日志", "配置变更日志"), mod("备份恢复", "database", "备份计划", "备份记录", "保留期限", "恢复验证记录"), mod("运行状态", "chart", "存储空间", "系统服务", "在线编辑服务", "异常记录")] },
    { id: "drive-admin", name: "公盘管理", icon: "folder", modules: [mod("角色与权限", "shield", "角色列表", "角色权限", "员工角色"), mod("个人特殊权限", "user", "个人授权", "权限限制"), mod("临时授权", "clock", "授权记录", "手动撤销"), mod("文件夹管理", "folder", "创建文件夹", "修改文件夹", "删除文件夹"), mod("文档管理", "file", "全部公盘文档"), mod("回收站", "trash", "恢复文档", "彻底清除"), mod("历史版本", "history", "版本列表", "恢复版本"), mod("上传限制", "upload", "允许文件类型", "文件大小上限"), mod("公盘审计日志", "history", "文档操作记录", "权限变更记录")] },
  ];
  if (view === "admin" && isArchiveAdmin) return [{ id: "archive-admin", name: "档案管理", icon: "archive", modules: [mod("档案角色分配", "shield", "超级管理员", "部门档案员", "普通职员"), mod("部门档案员", "people", "部门档案员设置", "人员调整"), mod("档案分类", "grid", "各部门分类"), mod("编号规则", "file", "自动编号", "既有编号"), mod("保管期限", "clock", "期限设置"), mod("纸质存放位置", "folder", "存放位置管理"), mod("审批管理", "task", "审批进度", "节点记录", "待办积压"), mod("借阅管理", "archive", "电子授权", "纸质借阅", "续借记录"), mod("逾期管理", "clock", "逾期记录", "归还提醒"), mod("档案审计日志", "history", "全部档案业务日志", "编辑与删除记录"), mod("业务提醒", "bell", "通知模板", "到期提醒", "逾期提醒")] }];
  return [];
}
