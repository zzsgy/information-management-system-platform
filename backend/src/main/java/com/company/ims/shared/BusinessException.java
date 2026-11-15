package com.company.ims.shared;

/** 可向用户展示的业务错误；不包含数据库口令、磁盘路径等内部信息。 */
public class BusinessException extends RuntimeException {
    /** HTTP 状态，用于客户端区分未登录、无权限和业务冲突。 */
    private final int status;
    /** 创建业务错误，message 必须是可公开的中文说明。 */
    public BusinessException(int status, String message) { super(message); this.status = status; }
    /** 取得对应的 HTTP 状态码。 */
    public int getStatus() { return status; }
    /** 提供记录式访问器，便于身份过滤器统一处理异常。 */
    public int status() { return status; }
}
