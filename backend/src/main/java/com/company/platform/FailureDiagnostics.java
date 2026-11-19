package com.company.platform;

import org.slf4j.LoggerFactory;
import java.sql.SQLException;
import java.util.UUID;

/** 请求与异步任务共用的诊断日志；保留异常链、SQL 状态和调用栈，排除原始消息中的凭据与正文。 */
public final class FailureDiagnostics {
    private FailureDiagnostics() { }

    public static void log(UUID request, String operation, ApiFault failure) {
        if (failure.status >= 500)
            LoggerFactory.getLogger(FailureDiagnostics.class).error("请求 {}，操作 {}，错误码 {}", request, operation, failure.code, sanitized(failure));
        else
            LoggerFactory.getLogger(FailureDiagnostics.class).warn("请求 {}，操作 {}，错误码 {}", request, operation, failure.code);
    }

    /** 异常消息可能含 SQL 参数、文件路径或外部服务正文，日志只复制可信类型与调用栈。 */
    static Throwable sanitized(Throwable source) {
        String detail = source.getClass().getName();
        if (source instanceof ApiFault fault) detail += " [" + fault.code + "]";
        if (source instanceof SQLException sql) detail += " SQLState=" + sql.getSQLState() + " vendorCode=" + sql.getErrorCode();
        var safe = new RuntimeException(detail, source.getCause() == null ? null : sanitized(source.getCause()));
        safe.setStackTrace(source.getStackTrace());
        return safe;
    }
}
