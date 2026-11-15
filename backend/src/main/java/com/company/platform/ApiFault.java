package com.company.platform;

/** 对外可公开的失败；不携带数据库错误、密码、令牌或内部路径。 */
public class ApiFault extends RuntimeException {
    /** HTTP 状态码。 */
    public final int status;
    /** V1.1 定义的稳定业务错误码。 */
    public final String code;
    /** 保存可以直接展示给用户的失败原因。 */
    public ApiFault(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    /** 请求格式或字段违反契约。 */
    public static ApiFault invalid(String message) { return new ApiFault(400, "VALIDATION_FAILED", message); }
    /** 服务端授权拒绝，不能以页面按钮状态代替。 */
    public static ApiFault denied() { return new ApiFault(403, "PERMISSION_DENIED", "当前账号无权执行此操作"); }
    /** 对不可见或不存在的对象使用同一响应。 */
    public static ApiFault hidden() { return new ApiFault(404, "RESOURCE_NOT_VISIBLE", "记录不存在或不可访问"); }
    /** 并发修改、重复提交异载荷及业务状态冲突。 */
    public static ApiFault conflict(String message) { return new ApiFault(409, "VERSION_OR_STATE_CONFLICT", message); }
    /** 当前登录失效，不泄露失效的安全细节。 */
    public static ApiFault session() { return new ApiFault(401, "SESSION_INVALID", "登录已失效，请重新登录"); }
    /** 账本、审计等保护基础不可用时停止操作。 */
    public static ApiFault protection() { return new ApiFault(503, "PROTECTION_NOT_READY", "保护检查未完成，请联系管理员"); }
}
