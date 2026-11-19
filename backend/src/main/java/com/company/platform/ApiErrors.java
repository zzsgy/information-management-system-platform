package com.company.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.sql.SQLException;
import java.util.*;

/** 全平台统一异常边界：按实际故障分类，响应与后台诊断共用请求编号。 */
@RestControllerAdvice
public class ApiErrors {
    private final Audit audit;
    public ApiErrors(Audit audit) { this.audit = audit; }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> handle(Exception error, HttpServletRequest request) {
        var failure = record(audit, (Actor) request.getAttribute(HttpBoundary.ACTOR), classify(error),
                ApiController.requestId(request), route(request));
        return ResponseEntity.status(failure.status).body(ApiController.envelope(
                failure.code, failure.getMessage(), null, ApiController.requestId(request)));
    }

    /** 不从异常消息拼接公开提示；数据库约束只使用标准 SQLState 分类。 */
    public static ApiFault classify(Throwable error) {
        if (error instanceof ApiFault fault) return fault;
        if (error instanceof DataIntegrityViolationException) {
            String state = null;
            for (Throwable cause = error; cause != null; cause = cause.getCause())
                if (cause instanceof SQLException sql) state = sql.getSQLState();
            return new ApiFault(409, "23505".equals(state) ? "DUPLICATE_RECORD" : "23503".equals(state) ? "RECORD_IN_USE" : "DATA_CONSTRAINT_FAILED",
                    "23505".equals(state) ? "记录已存在，请检查重复内容后重试" : "23503".equals(state) ? "关联记录不存在或仍被引用，请刷新并检查关联数据" : "数据未满足保存约束，请检查必填项和关联记录", error);
        }
        if (error instanceof QueryTimeoutException || error instanceof org.springframework.transaction.TransactionTimedOutException)
            return SystemFailure.DATABASE_TIMEOUT.fault(error);
        if (error instanceof ConcurrencyFailureException)
            return new ApiFault(409, "CONCURRENT_UPDATE", "记录正在被其他操作修改，请刷新后重试", error);
        if (error instanceof PermissionDeniedDataAccessException) return SystemFailure.DATABASE_ACCESS_DENIED.fault(error);
        if (error instanceof DataAccessResourceFailureException || error instanceof org.springframework.transaction.CannotCreateTransactionException)
            return SystemFailure.DATABASE_UNAVAILABLE.fault(error);
        if (error instanceof DataAccessException) return SystemFailure.DATABASE_QUERY_FAILED.fault(error);
        if (error instanceof MaxUploadSizeExceededException)
            return new ApiFault(413, "REQUEST_TOO_LARGE", "上传内容超过服务器接收上限，请减小文件后重试", error);
        if (error instanceof MissingServletRequestParameterException missing)
            return new ApiFault(400, "PARAMETER_REQUIRED", "缺少请求参数「" + missing.getParameterName() + "」", error);
        if (error instanceof MissingServletRequestPartException)
            return new ApiFault(400, "FILE_PART_REQUIRED", "请求缺少必需的文件内容，请重新选择文件后提交", error);
        if (error instanceof MethodArgumentTypeMismatchException mismatch)
            return new ApiFault(400, "PARAMETER_FORMAT_INVALID", "请求参数「" + mismatch.getName() + "」格式不正确", error);
        if (error instanceof HttpMessageNotReadableException)
            return new ApiFault(400, "REQUEST_BODY_INVALID", "请求正文格式不正确，请刷新页面后重新提交", error);
        if (error instanceof java.time.DateTimeException)
            return new ApiFault(400, "DATE_TIME_INVALID", "日期或时间格式不正确，请检查后重新提交", error);
        if (error instanceof IllegalArgumentException)
            return new ApiFault(400, "VALIDATION_FAILED", "请求字段格式不正确，请检查输入后重试", error);
        if (error instanceof HttpMediaTypeNotSupportedException)
            return new ApiFault(415, "MEDIA_TYPE_NOT_ALLOWED", "请求媒体类型不受支持，请使用本系统页面重新提交", error);
        if (error instanceof NoResourceFoundException || error instanceof org.springframework.web.servlet.NoHandlerFoundException)
            return new ApiFault(404, "ENDPOINT_NOT_FOUND", "请求的接口或资源不存在，请刷新页面后重试", error);
        if (error instanceof org.springframework.web.HttpRequestMethodNotSupportedException)
            return new ApiFault(405, "METHOD_NOT_ALLOWED", "该接口不支持当前请求方式，请刷新页面后重试", error);
        return SystemFailure.INTERNAL_ERROR.fault(error);
    }

    /** 审计失败单独记录，不再冒充存储保护失败，也不覆盖原始故障的日志。 */
    public static ApiFault record(Audit audit, Actor actor, ApiFault failure, UUID request, String operation) {
        FailureDiagnostics.log(request, operation, failure);
        try { audit.denied(actor == null ? null : actor.userId(), failure.code, request); }
        catch (Exception error) {
            var auditFailure = SystemFailure.AUDIT_WRITE_FAILED.fault(error);
            FailureDiagnostics.log(request, operation, auditFailure);
            // 原故障已经是服务端失败时保留其原因，避免数据库断连又被审计写入失败掩盖。
            return failure.status>=500 ? failure : auditFailure;
        }
        return failure;
    }

    /** 仅记录框架模板路由，排除查询词、对象内容和任意请求路径。 */
    public static String route(HttpServletRequest request) {
        return request.getMethod() + " " + Objects.toString(request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE), "UNMAPPED");
    }
}
