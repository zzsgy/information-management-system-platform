package com.company.ims.shared;

/** 兼容现有客户端的响应信封：code 为业务状态，message 为中文提示，data 为业务数据。 */
public record ApiResponse(int code, String message, Object data) {
    /** 返回查询或写入的成功数据；data 可以为空。 */
    public static ApiResponse ok(Object data) { return ok(data, "操作成功"); }
    /** 返回成功结果并指定面向用户的中文提示。 */
    public static ApiResponse ok(Object data, String message) { return new ApiResponse(0, message, data); }
}
