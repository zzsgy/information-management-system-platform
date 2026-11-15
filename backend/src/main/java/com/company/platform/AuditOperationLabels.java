package com.company.platform;

import java.util.*;

/** 只把固定路由模板及对象类别转换为中文，不返回原始路径、搜索词或对象内容。 */
final class AuditOperationLabels {
    private AuditOperationLabels() {}

    /** 同一路径的读写共用对象名称，具体业务动作可按请求方法单独覆盖。 */
    private static final Map<String,String> OBJECTS = Map.ofEntries(
        Map.entry("/auth/me", "登录状态"),
        Map.entry("/auth/profile", "个人资料"),
        Map.entry("/auth/reauthentications", "本人身份"),
        Map.entry("/auth/initial-password", "初始密码"),
        Map.entry("/auth/password", "密码"),
        Map.entry("/admin/users", "员工列表"),
        Map.entry("/admin/users/{id}", "员工资料"),
        Map.entry("/admin/users/{id}/status", "员工账号状态"),
        Map.entry("/admin/users/{id}/roles", "员工角色"),
        Map.entry("/admin/departments", "部门列表"),
        Map.entry("/admin/departments/{id}", "部门资料"),
        Map.entry("/admin/roles", "角色列表"),
        Map.entry("/admin/roles/{id}", "角色详情"),
        Map.entry("/admin/roles/{id}/permissions", "角色权限"),
        Map.entry("/admin/permissions", "权限清单"),
        Map.entry("/admin/organization-duties", "组织审批职责"),
        Map.entry("/admin/organization-duties/candidates", "审批职责候选人"),
        Map.entry("/admin/personnel-department-options", "人员交接部门选项"),
        Map.entry("/admin/archive/archivist-handover-options", "档案员交接人员"),
        Map.entry("/admin/workflow/assignment-events", "审批改派记录"),
        Map.entry("/admin/audit-events", "操作记录"),
        Map.entry("/admin/audit-events/{id}/count", "操作记录总数"),
        Map.entry("/admin/audit-storage", "日志存储与归档"),
        Map.entry("/admin/audit-retention", "日志保留期限"),
        Map.entry("/admin/audit-storage/runs/{id}", "日志归档任务详情"),
        Map.entry("/admin/operations/lists/{section}", "运维记录"),
        Map.entry("/admin/operations/runs/{id}", "运维任务详情"),
        Map.entry("/admin/operations/backup-plan", "备份计划"),
        Map.entry("/admin/security", "安全告警"),
        Map.entry("/admin/security/{id}/history", "安全告警处置记录"),
        Map.entry("/admin/account-messages", "账号消息投递记录"),
        Map.entry("/admin/operations/service-health", "服务运行状态"),
        Map.entry("/admin/archive/notification-deliveries", "通知投递队列"),
        Map.entry("/portal/home", "工作台"),
        Map.entry("/portal/dashboard-tasks", "工作台待办"),
        Map.entry("/portal/tasks", "审批待办"),
        Map.entry("/portal/requests", "我的借阅申请"),
        Map.entry("/portal/inbox", "我的消息"),
        Map.entry("/portal/business-notifications", "业务通知"),
        Map.entry("/portal/notifications", "借阅到期提醒"),
        Map.entry("/portal/todo-reminders", "待办提醒"),
        Map.entry("/portal/todo-reminders/desktop", "桌面提醒设置"),
        Map.entry("/portal/todos", "待办事项"),
        Map.entry("/portal/todos/{id}", "待办详情"),
        Map.entry("/portal/todos/candidates", "待办办理人"),
        Map.entry("/portal/sites", "常用网站"),
        Map.entry("/admin/portal/sites", "常用网站配置"),
        Map.entry("/admin/portal/sites/{id}", "常用网站配置"),
        Map.entry("/portal/feedback", "我的反馈"),
        Map.entry("/portal/feedback/{id}", "反馈详情"),
        Map.entry("/admin/portal/feedback", "员工反馈"),
        Map.entry("/admin/portal/feedback/{id}", "员工反馈详情"),
        Map.entry("/portal/announcements", "通知公告"),
        Map.entry("/portal/announcements/{id}", "公告详情"),
        Map.entry("/admin/portal/announcements", "公告管理列表"),
        Map.entry("/admin/portal/announcements/{id}", "公告内容"),
        Map.entry("/workflow/progress", "审批进度"),
        Map.entry("/workflow/attention", "异常审批节点"),
        Map.entry("/workflow/steps/{id}", "审批事项详情"),
        Map.entry("/workflow/steps/{id}/assignment-options", "审批改派候选人"),
        Map.entry("/archive/boxes", "档案盒台账"),
        Map.entry("/archive/box-filter-options", "档案盒筛选选项"),
        Map.entry("/archive/boxes/{box}/checklist-items", "盒内电子清单"),
        Map.entry("/archive/records", "档案文件"),
        Map.entry("/archive/records/{id}", "档案文件详情"),
        Map.entry("/archive/records/{id}/revisions", "档案文件修订历史"),
        Map.entry("/archive/boxes/{id}/revisions", "档案盒修订历史"),
        Map.entry("/archive/catalog-options", "档案登记选项"),
        Map.entry("/archive/owners", "档案负责人"),
        Map.entry("/archive/department-options", "档案部门选项"),
        Map.entry("/archive/catalog/{type}", "档案目录维护列表"),
        Map.entry("/archive/catalog/{type}/{id}", "档案目录详情"),
        Map.entry("/archive/catalog/{type}/{id}/changes", "档案目录更正记录"),
        Map.entry("/archive/catalog/{type}/{id}/draft", "档案登记草稿"),
        Map.entry("/archive/catalog-changes/{id}", "档案更正事项"),
        Map.entry("/archive/retention", "档案保管期限"),
        Map.entry("/archive/borrowable-boxes", "可借档案盒"),
        Map.entry("/archive/borrow-options", "档案借阅选项"),
        Map.entry("/archive/borrow-batches/{id}", "档案借阅批次"),
        Map.entry("/archive/borrow-requests/{id}", "档案借阅子单"),
        Map.entry("/archive/physical-items", "实物台账"),
        Map.entry("/archive/physical-items/{id}/location", "纸质档案存放位置"),
        Map.entry("/archive/physical-delivery-requests", "实物待交付清单"),
        Map.entry("/archive/physical-loans", "实物借还记录"),
        Map.entry("/archive/physical-loans/{id}", "实物借还详情"),
        Map.entry("/archive/physical-notifications", "实物借还提醒"),
        Map.entry("/archive/access-policy-subjects", "档案开放对象"),
        Map.entry("/archive/access-grants", "档案使用授权"),
        Map.entry("/admin/archive/access-policies", "档案开放策略"),
        Map.entry("/admin/archive/grant-subjects", "档案授权人员"),
        Map.entry("/admin/workflow/evidence", "审批凭据队列"),
        Map.entry("/workflow/evidence-submissions/{id}", "审批凭据详情"),
        Map.entry("/workflow/steps/{id}/evidence", "审批节点凭据"),
        Map.entry("/admin/archive/directory-templates", "档案目录模板"),
        Map.entry("/admin/archive/directory-template-versions", "档案模板版本"),
        Map.entry("/admin/archive/file-types", "档案文件类型"),
        Map.entry("/admin/archive/scan-options", "档案扫描选项"),
        Map.entry("/admin/archive/scan-devices", "档案扫描设备"),
        Map.entry("/admin/archive/scan-devices/{id}", "档案扫描设备"),
        Map.entry("/archive/scan-batches", "档案扫描批次"),
        Map.entry("/archive/scan-batches/{id}", "档案扫描批次详情"),
        Map.entry("/archive/scan-items/{id}", "档案扫描条目"),
        Map.entry("/archive/view-sessions/{id}", "档案在线阅读进度"),
        Map.entry("/archive/view-sessions/{id}/pages/{page}", "档案阅读页面"),
        Map.entry("/archive/export-requests", "档案导出申请"),
        Map.entry("/archive/download-requests/{id}", "档案下载申请进度"),
        Map.entry("/archive/print-requests/{id}", "档案打印申请进度"),
        Map.entry("/imports/templates/{type}", "批量导入模板"),
        Map.entry("/imports/options/{type}", "批量导入选项"),
        Map.entry("/imports/batches", "批量导入记录"),
        Map.entry("/imports/batches/{id}", "导入批次详情"),
        Map.entry("/imports/batches/{id}/rows", "导入校验明细"),
        Map.entry("/drive/folders", "公盘文件夹列表"),
        Map.entry("/drive/folders/{id}", "公盘文件夹"),
        Map.entry("/drive/documents", "公盘文件列表"),
        Map.entry("/drive/documents/{id}", "公盘文件资料"),
        Map.entry("/drive/documents/{id}/versions", "公盘文件版本"),
        Map.entry("/drive/documents/{id}/office-history", "公盘文件编辑历史"),
        Map.entry("/drive/documents/{documentId}/previews/{id}", "公盘文件预览进度"),
        Map.entry("/drive/documents/{documentId}/previews/{id}/pages/{number}", "公盘文件预览页面"),
        Map.entry("/drive/office/status", "公盘在线编辑状态"),
        Map.entry("/drive/edit-sessions/{id}", "公盘文件编辑会话"),
        Map.entry("/drive/save-receipts", "公盘文件保存记录"),
        Map.entry("/drive/access-options", "公盘授权选项"),
        Map.entry("/drive/resource-grants", "公盘文件授权"),
        Map.entry("/admin/drive/delegations", "公盘管理委托"),
        Map.entry("/drive/recycle", "公盘回收站"),
        Map.entry("/drive/recycle/folders", "公盘已删除文件夹"),
        Map.entry("/storage/upload-sessions/{uploadId}", "文件上传进度"),
        Map.entry("/jobs/{jobId}", "后台处理任务")
    );

    /** 对不能从通用动词准确推断的业务操作使用明确名称。 */
    private static final Map<String,String> ACTIONS = Map.ofEntries(
        Map.entry("POST /auth/logout", "退出登录"),
        Map.entry("POST /auth/reauthentications", "验证本人身份"),
        Map.entry("POST /auth/password-recovery", "申请密码找回"),
        Map.entry("POST /auth/password-recovery/complete", "通过验证码重置密码"),
        Map.entry("POST /admin/users", "新增员工账号"),
        Map.entry("POST /admin/departments", "新增部门"),
        Map.entry("POST /admin/users/{id}/password-reset", "重置员工密码"),
        Map.entry("DELETE /admin/users/{id}/session", "结束员工登录会话"),
        Map.entry("POST /admin/audit-exports/{id}", "申请导出操作记录"),
        Map.entry("GET /admin/audit-exports/{id}/file", "下载操作记录"),
        Map.entry("POST /admin/archive/notification-deliveries/{id}/retry", "重试业务通知投递"),
        Map.entry("POST /portal/feedback", "提交意见反馈"),
        Map.entry("POST /portal/todos", "创建待办事项"),
        Map.entry("PUT /portal/todos/{id}", "编辑待办事项"),
        Map.entry("POST /portal/inbox/{id}/read", "将消息标记为已读"),
        Map.entry("POST /portal/announcements/{id}/read", "确认已读公告"),
        Map.entry("POST /workflow/steps/{id}/decisions", "提交审批决定"),
        Map.entry("POST /workflow/steps/{id}/reassign", "改派审批节点"),
        Map.entry("POST /archive/boxes", "登记档案盒"),
        Map.entry("POST /archive/records", "登记档案文件"),
        Map.entry("POST /archive/boxes/{box}/checklist-items", "补录盒内电子清单"),
        Map.entry("POST /archive/catalog/{type}/{id}/changes", "发起档案目录更正"),
        Map.entry("POST /archive/catalog/{type}/{id}/delete", "撤回档案登记草稿"),
        Map.entry("POST /archive/catalog-changes/{id}/submit", "送审档案更正事项"),
        Map.entry("POST /archive/catalog-changes/{id}/cancel", "撤回档案更正事项"),
        Map.entry("POST /archive/registration-reviews", "提交档案归档复核"),
        Map.entry("POST /archive/quality-checks", "确认档案质量"),
        Map.entry("POST /archive/borrow-batches", "提交档案借阅申请"),
        Map.entry("POST /archive/borrow-requests/{id}/deliveries", "登记纸质档案交付"),
        Map.entry("POST /archive/physical-loans/{id}/return-requests", "申请归还纸质档案"),
        Map.entry("POST /archive/physical-loans/{id}/return-reviews", "复核纸质档案实收"),
        Map.entry("POST /archive/physical-loans/{id}/renewals", "申请续借纸质档案"),
        Map.entry("POST /archive/access-policy-requests", "申请档案题名开放"),
        Map.entry("POST /archive/view-sessions", "打开档案在线阅读"),
        Map.entry("POST /archive/download-requests", "申请下载档案"),
        Map.entry("POST /archive/print-requests", "申请打印档案"),
        Map.entry("POST /drive/folders", "新建公盘文件夹"),
        Map.entry("POST /drive/documents", "上传公盘文件"),
        Map.entry("POST /drive/documents/{id}/edit-sessions", "打开公盘文件在线编辑"),
        Map.entry("GET /drive/documents/{id}/download", "下载公盘文件"),
        Map.entry("POST /imports/batches", "创建批量导入预检")
    );

    /** 常见业务事件已有明确对象时，补充人可读的动作描述。 */
    private static final Map<String,String> EVENTS = Map.ofEntries(
        Map.entry("CREATE_BOX", "登记档案盒"),
        Map.entry("CREATE_RECORD", "登记档案文件"),
        Map.entry("CREATE_FOLDER", "新建公盘文件夹"),
        Map.entry("SUBMIT_REGISTRATION", "提交档案归档复核"),
        Map.entry("CREATE_CATALOG_CHANGE", "发起档案目录更正"),
        Map.entry("SUBMIT_CATALOG_CHANGE", "送审档案更正事项"),
        Map.entry("DECIDE_STEP", "提交审批决定"),
        Map.entry("DELIVER_PHYSICAL", "登记纸质档案交付"),
        Map.entry("REVIEW_PHYSICAL_RETURN", "复核纸质档案实收")
    );

    /** 历史行只保存路由前64字符，信息不足时只显示能确定的模块及动作。 */
    static String describe(Map<String,Object> row) {
        String action=(String)row.get("action");
        if(!"HTTP_REQUEST".equals(row.get("resource_type")) || !action.startsWith("HTTP_")){
            if("UPDATE_DRAFT".equals(action))return switch(Objects.toString(row.get("resource_type"),"")){
                case "ARCHIVE_BOX" -> "编辑档案盒草稿";
                case "ARCHIVE_RECORD" -> "编辑档案文件草稿";
                default -> "编辑档案登记草稿";
            };
            return EVENTS.get(action);
        }
        String route=Objects.toString(row.get("authorization_source"),"");
        if(!route.startsWith("/api/v1/"))return null;
        String path=route.substring("/api/v1".length());
        String method=action.substring(5);
        String exact=ACTIONS.get(method+" "+path);
        if(exact!=null)return exact;
        String object=OBJECTS.get(path);
        if(object==null){
            if(path.startsWith("/archive/") || path.startsWith("/admin/archive/"))object="档案业务（对象未细分）";
            else if(path.startsWith("/drive/") || path.startsWith("/admin/drive/"))object="公盘业务（对象未细分）";
            else if(path.startsWith("/workflow/") || path.startsWith("/admin/workflow/"))object="审批事项（对象未细分）";
            else if(path.startsWith("/portal/") || path.startsWith("/admin/portal/"))object="工作台业务（对象未细分）";
            else return null;
        }
        String verb=switch(method){
            case "GET","HEAD" -> "查询";
            case "POST" -> "提交";
            case "PUT","PATCH" -> "编辑";
            case "DELETE" -> "删除";
            default -> "操作";
        };
        return verb+object;
    }
}
