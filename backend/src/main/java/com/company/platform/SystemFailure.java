package com.company.platform;

/** 技术故障的稳定错误码与可公开处理建议；详细异常仅保留在后台。 */
public enum SystemFailure {
    INTERNAL_ERROR(500, "服务处理出现内部异常，请将请求编号提供给 IT 管理员排查"),
    DATABASE_UNAVAILABLE("数据库连接不可用，请联系 IT 管理员检查数据库服务"),
    DATABASE_ACCESS_DENIED("服务账号未获数据库操作权限，请联系 IT 管理员核查数据库授权"),
    DATABASE_QUERY_FAILED(500, "数据库处理出现异常，请将请求编号提供给 IT 管理员核查应用与数据库版本"),
    DATABASE_TIMEOUT("数据库处理超时，请稍后查询本次操作结果再重试"),
    AUDIT_WRITE_FAILED("操作审计记录写入失败，本次操作未能正常确认，请联系 IT 管理员"),
    SECURITY_LEDGER_UNAVAILABLE("账号安全账本不可用，请联系 IT 管理员检查账本目录和权限"),
    SECURITY_LEDGER_INVALID("账号安全账本完整性校验失败，访问已停止，请联系 IT 管理员"),
    SECURITY_VERSION_REVOKED(401, "账号安全状态已变更，请重新登录"),
    STORAGE_NOT_CONFIGURED("文件存储目录尚未配置完整，请联系 IT 管理员"),
    STORAGE_PATH_INVALID("文件存储目录配置冲突或不符合要求，请联系 IT 管理员检查目录配置"),
    STORAGE_INSTANCE_MISMATCH("文件存储目录与当前数据库实例不匹配，请联系 IT 管理员核对迁移配置"),
    STORAGE_UNAVAILABLE("文件存储目录不可访问，请联系 IT 管理员检查磁盘、目录权限和可用空间"),
    STORAGE_WRITE_FAILED("文件写入存储失败，请联系 IT 管理员检查磁盘空间和写入权限"),
    STORAGE_INTEGRITY_FAILED("文件副本的大小或摘要校验失败，已停止交付，请联系 IT 管理员核查文件副本"),
    STORAGE_POLICY_UNAVAILABLE("文件业务授权策略未正确配置，请联系 IT 管理员"),
    PROTECTION_PROVIDER_UNAVAILABLE("存储保护检查服务未正确配置，请联系 IT 管理员检查保护服务是否启用"),
    PROTECTION_EVIDENCE_UNAVAILABLE("存储保护状态文件或验证密钥不可读取，请联系 IT 管理员检查保护代理和文件权限"),
    PROTECTION_EVIDENCE_INVALID("存储保护状态校验失败，请联系 IT 管理员核对保护代理配置"),
    PROTECTION_EVIDENCE_EXPIRED("存储保护状态已过期或时间无效，请联系 IT 管理员检查保护代理和服务器时间"),
    PROTECTION_INSTANCE_MISMATCH("存储保护状态与当前节点或数据库实例不匹配，请联系 IT 管理员核对迁移配置"),
    PROTECTION_EPOCH_INVALID("存储保护接管代次校验失败，请联系 IT 管理员核查主备接管状态"),
    PROTECTION_READ_BLOCKED("当前存储保护模式禁止读取，请联系 IT 管理员检查恢复或隔离状态"),
    PROTECTION_WRITE_BLOCKED("当前存储处于只读或隔离状态，暂不允许写入，请联系 IT 管理员"),
    PROTECTION_MODULE_BLOCKED("当前业务模块未获准写入存储，请联系 IT 管理员检查保护配置"),
    REPLICA_VERIFICATION_EXPIRED("文件副本核验结果已超时，请联系 IT 管理员检查副本核验服务"),
    BACKUP_PROTECTION_EXPIRED("完整恢复点的保护状态已超时，请联系 IT 管理员检查备份保护服务"),
    READING_NOT_CONFIGURED("文档预览程序或临时目录尚未配置，请联系 IT 管理员"),
    READING_RUNTIME_UNAVAILABLE("文档预览运行环境不可用，请联系 IT 管理员检查程序和临时目录权限"),
    READING_BUSY(503, "文档预览或导出任务较多，请稍后重试"),
    RENDER_TIMEOUT(504, "文档解析超时，请稍后重试；持续失败时请联系 IT 管理员"),
    RENDER_FAILED("文档解析程序执行失败，请将请求编号提供给 IT 管理员排查"),
    RENDER_OUTPUT_INVALID("文档解析返回的页数、图像或输出大小异常，请联系 IT 管理员核查解析程序"),
    PREVIEW_NOT_READY(409, "文档预览尚未准备完成，请稍后重新打开"),
    RECEIPT_STORAGE_UNAVAILABLE("文件交付回执目录不可用，请联系 IT 管理员检查目录配置和权限"),
    RECEIPT_WRITE_FAILED("文件交付回执保存失败，已停止交付，请联系 IT 管理员"),
    UPLOAD_ASSEMBLY_FAILED("上传分片合并失败，文件尚未发布，请联系 IT 管理员核查处理日志"),
    FILE_WORKER_DISABLED("后台文件处理已停用，请联系 IT 管理员启用后台文件处理"),
    FILE_CHECK_FAILED("文件检查执行失败，文件尚未发布，请联系 IT 管理员核查检测任务"),
    SCANNER_UNAVAILABLE("文件检测程序或病毒库不可用，文件尚未发布，请联系 IT 管理员"),
    SCANNER_NOT_CONFIGURED("文件检测程序、病毒库、临时目录或程序摘要尚未配置完整，请联系 IT 管理员"),
    SCANNER_EXECUTABLE_UNAVAILABLE("文件检测程序不可访问，请联系 IT 管理员检查安装路径和执行权限"),
    SCANNER_EXECUTABLE_MISMATCH("文件检测程序的摘要与配置不一致，请联系 IT 管理员核对检测器版本"),
    SCANNER_DATABASE_UNAVAILABLE("文件检测所需的官方病毒库缺失或不可访问，请联系 IT 管理员"),
    SCANNER_DATABASE_EXPIRED("文件检测的病毒库已超过有效期，请联系 IT 管理员更新病毒库"),
    SCANNER_TEMP_UNAVAILABLE("文件检测临时目录不可访问，请联系 IT 管理员检查目录和权限"),
    SCANNER_TIMEOUT(504, "文件检测超时，文件尚未发布，请稍后重试或联系 IT 管理员"),
    SCANNER_RESPONSE_INVALID("文件检测程序未返回完整有效的扫描结果，文件尚未发布，请联系 IT 管理员"),
    SCANNER_EXECUTION_FAILED("文件检测程序执行失败，文件尚未发布，请联系 IT 管理员核查检测程序"),
    JOB_HANDLER_UNAVAILABLE("文件任务处理程序未正确配置，请联系 IT 管理员"),
    JOB_RESULT_INVALID("文件任务结果不完整，请联系 IT 管理员核查处理日志"),
    OFFICE_CONFIGURATION_INVALID("Office 服务地址、版本或密钥配置无效，请联系 IT 管理员"),
    OFFICE_REQUEST_FAILED("Office 服务请求失败，请联系 IT 管理员检查 Office 服务状态"),
    OFFICE_RESPONSE_INVALID("Office 服务返回的文件或响应无效，请联系 IT 管理员"),
    EXPORT_STORAGE_INVALID("导出目录配置不符合要求，请联系 IT 管理员检查目录路径"),
    EXPORT_TOO_LARGE(413, "导出结果超过大小上限，请减少文件或页数后重新导出"),
    EXPORT_FAILED("档案副本导出失败，请联系 IT 管理员核查导出任务"),
    EXPORT_RESULT_INVALID("导出任务与实际文件不一致，已停止交付，请联系 IT 管理员"),
    WORKBOOK_GENERATION_FAILED("Excel 模板或汇总生成失败，请将请求编号提供给 IT 管理员"),
    WORKBOOK_STORAGE_FAILED("Excel 临时文件读取或写入失败，请联系 IT 管理员检查临时目录"),
    WORKFLOW_CONFIGURATION_INVALID("审批流程或办理职责配置不完整，请联系 IT 管理员检查流程配置"),
    WORKFLOW_EVIDENCE_INVALID("审批凭据文件核验失败，请联系 IT 管理员核查凭据记录"),
    CLASSIFICATION_INVALID("档案保密级别配置无效，请联系档案管理员核查档案信息"),
    ARCHIVE_PAGE_DATA_INVALID("档案页码信息无效，请联系档案管理员核查页数和使用范围"),
    CREDENTIAL_DELIVERY_FAILED("初始账号凭据交付失败，请联系 IT 管理员核查交付服务"),
    ACCOUNT_MESSAGE_ENCRYPTION_FAILED("账号通知加密或解密失败，请联系 IT 管理员核查通知密钥配置"),
    MESSAGE_DELIVERY_UNAVAILABLE("通知投递服务不可用，请联系 IT 管理员检查投递配置"),
    MESSAGE_RECEIPT_INVALID("通知投递回执无效，请联系 IT 管理员核查投递结果"),
    MESSAGE_STORAGE_FAILED("通知投递记录无法保存，请联系 IT 管理员检查投递目录"),
    SCAN_KEY_UNAVAILABLE("扫描设备密钥目录不可用，请联系 IT 管理员检查设备接入配置"),
    SCAN_CHECK_FAILED("扫描文件检测失败，请联系 IT 管理员核查扫描任务"),
    DRIVE_POLICY_INVALID("公盘文件授权记录不一致，请联系 IT 管理员核查公盘配置");

    /** 公开 HTTP 状态和中文提示。 */
    public final int status;
    public final String message;
    SystemFailure(String message) { this(503, message); }
    SystemFailure(int status, String message) { this.status = status; this.message = message; }
    public ApiFault fault() { return new ApiFault(status, name(), message); }
    /** 保留原始异常链供后台诊断，不将异常消息写入响应。 */
    public ApiFault fault(Throwable cause) { return new ApiFault(status, name(), message, cause); }

    /** 异步任务沿用已有业务拒绝码，历史失败记录也能得到中文说明。 */
    public static String taskMessage(String code) {
        for (var failure : values()) if (failure.name().equals(code)) return failure.message;
        return switch (code) {
            case "SCANNER_UNAVAILABLE" -> SCANNER_UNAVAILABLE.message;
            case "FILE_SUSPICIOUS" -> "文件检测发现可疑内容，未发布；请更换可信文件或联系 IT 管理员核查";
            case "MEDIA_TYPE_NOT_ALLOWED" -> "文件实际内容与声明格式不符或结构不受支持，未发布；请检查文件后重新上传";
            case "SERVICE_IDENTITY_EXPIRED" -> "文件处理服务的授权已到期，请联系 IT 管理员续期后重试";
            case "SERVICE_IDENTITY_UNAVAILABLE", "SERVICE_IDENTITY_INVALID", "SERVICE_IDENTITY_DENIED" -> "文件处理服务身份尚未配置或授权范围不符，请联系 IT 管理员检查服务授权";
            case "SERVICE_IDENTITY_SCOPE_MISMATCH" -> "文件处理服务未获当前部门或操作的授权，请联系 IT 管理员检查服务授权范围";
            case "REQUESTER_AUTHORIZATION_INVALID" -> "申请人的会话或业务权限已失效，请重新登录并检查当前权限";
            case "ASSEMBLY_FAILED" -> UPLOAD_ASSEMBLY_FAILED.message;
            case "CHECK_FAILED" -> FILE_CHECK_FAILED.message;
            case "PREVIEW_FAILED", "ARCHIVE_PREVIEW_FAILED" -> RENDER_FAILED.message;
            case "ARCHIVE_EXPORT_FAILED" -> EXPORT_FAILED.message;
            case "LEASE_EXPIRED" -> "处理任务的执行租约已过期，请刷新任务状态或联系 IT 管理员核查";
            case "HASH_OR_PART_MISMATCH" -> "上传分片或文件摘要不一致，请重新上传文件";
            case "OFFICE_VERSION_MISMATCH" -> "Office 服务版本与平台配置不一致，请联系 IT 管理员核对部署版本";
            case "OFFICE_UNAVAILABLE", "OFFICE_CONVERSION_UNAVAILABLE" -> "Office 服务暂不可用，请联系 IT 管理员检查服务连接";
            case "OFFICE_CONVERSION_FAILED" -> "Office 无法转换该文件，请检查文件格式、密码或原件内容";
            case "OFFICE_CONVERSION_PENDING" -> "Office 转换结果尚未确认，请稍后重新查询任务状态";
            default -> "任务未能完成，请将错误码、任务编号和请求编号提供给 IT 管理员排查";
        };
    }
}
