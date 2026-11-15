package com.company.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import java.util.*;

/** 统一错误映射；数据库错误和秘密参数只转换成安全错误码，不回显原始异常文本。 */
@RestControllerAdvice
public class ApiErrors {
    /** 拒绝事实单独持久化，业务失败不抹掉证据。 */
    private final Audit audit;
    /** 接入拒绝事件留痕，异常正文不向浏览器暴露内部细节。 */
    public ApiErrors(Audit audit) { this.audit=audit; }
    @ExceptionHandler(ApiFault.class)
    public ResponseEntity<Map<String,Object>> business(ApiFault error,HttpServletRequest request) { return respond(error,request); }
    /** 唯一键与引用冲突只返回可理解的并发提示。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String,Object>> conflict(Exception error,HttpServletRequest request) { return respond(ApiFault.conflict("记录重复或仍有业务引用，请刷新后重试"),request); }
    /** 输入绑定、日期和 JSON 结构错误都不能降级为内部异常。 */
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,Object>> invalid(Exception error,HttpServletRequest request) { return respond(ApiFault.invalid("请求字段或数据格式不正确"),request); }
    /** 分片必须采用受控二进制媒体类型；不把媒体类型错误误报成保护系统故障。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String,Object>> unsupportedMedia(Exception error,HttpServletRequest request) { return respond(new ApiFault(415,"MEDIA_TYPE_NOT_ALLOWED","请求媒体类型不受支持"),request); }
    /** 未实现的路由明确返回404，不用模拟成功掩盖缺失。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String,Object>> missing(Exception error,HttpServletRequest request) { return respond(ApiFault.hidden(),request); }
    /** 连接、锁超时及未知异常保持失败，不包含驱动内部 SQL 或路径。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> unexpected(Exception error,HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("请求 {} 失败，类型 {}",ApiController.requestId(request),error.getClass().getSimpleName());
        return respond(ApiFault.protection(),request);
    }
    /** 失败响应与审计使用同一个 requestId，方便定位又不暴露凭据。 */
    private ResponseEntity<Map<String,Object>> respond(ApiFault error,HttpServletRequest request) {
        Actor actor=(Actor)request.getAttribute(HttpBoundary.ACTOR);
        try { audit.denied(actor==null?null:actor.userId(),error.code,ApiController.requestId(request)); }
        catch(Exception ignored) { error=ApiFault.protection(); }
        return ResponseEntity.status(error.status).body(ApiController.envelope(error.code,error.getMessage(),null,ApiController.requestId(request)));
    }
}
