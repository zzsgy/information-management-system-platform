package com.company.ims.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 统一错误边界：保留客户端可读提示，隐藏 SQL 与服务器内部路径。 */
@RestControllerAdvice
public class ApiErrors {
    /** 内部诊断日志，仅输出异常类别而非可能含凭据的原始请求。 */
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    /** 返回可预期的认证、权限、校验和状态冲突。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse> business(BusinessException error) {
        return ResponseEntity.status(error.status()).body(new ApiResponse(error.status(), error.getMessage(), null));
    }
    /** 唯一约束与引用约束冲突不能伪装成成功，也不泄露 SQL。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse> conflict(DataIntegrityViolationException error) {
        return ResponseEntity.status(409).body(new ApiResponse(409, "数据重复或仍有业务引用，请刷新后重试", null));
    }
    /** 附件超限时返回可理解的业务提示。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse> tooLarge(MaxUploadSizeExceededException error) {
        return ResponseEntity.status(413).body(new ApiResponse(413, "附件超过服务器允许的大小", null));
    }
    /** 格式错误统一拒绝，不尝试猜测用户意图。 */
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new ApiResponse(400, "请求字段或数据格式不正确", null));
    }
    /** 不存在的页面或静态资源返回404，避免浏览器图标探测被误报为服务故障。 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse> missingResource(Exception error) {
        return ResponseEntity.status(404).body(new ApiResponse(404, "请求的页面或资源不存在", null));
    }

    /** 意外故障仅向调用者展示固定提示，保留 HTTP 失败状态。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> unexpected(Exception error) {
        LOG.error("请求处理失败，异常类型：{}", error.getClass().getName());
        return ResponseEntity.internalServerError().body(new ApiResponse(500, "服务处理失败，请联系管理员查看日志", null));
    }
}
