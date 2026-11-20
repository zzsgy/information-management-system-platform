package com.company.platform;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/** HTTP 安全边界：请求号、同源、大小限制、Cookie 会话及 CSRF；不记录请求正文。 */
@Component
@Order(1)
public class HttpBoundary extends OncePerRequestFilter {
    /** 当前主体和追踪号只保存在本次请求，不使用线程全局登录状态。 */
    public static final String ACTOR="platform.actor", REQUEST="platform.request";
    private final IdentityService identity;
    private final Audit audit;
    private final AuditSecurity security;
    private final com.company.platform.storage.OfficeGateway office;
    /** 过滤器只依赖身份与审计，不直接执行领域写操作。 */
    public HttpBoundary(IdentityService identity,Audit audit,com.company.platform.storage.OfficeGateway office,AuditSecurity security) { this.identity=identity; this.audit=audit;this.office=office;this.security=security; }
    /** 安全响应头同时覆盖网页与接口；文件使用链路在第二批继续收紧。 */
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        UUID requestId=UUID.randomUUID();
        response.setHeader("X-Content-Type-Options","nosniff"); response.setHeader("Referrer-Policy","same-origin");
        response.setHeader("X-Frame-Options","DENY"); response.setHeader("Strict-Transport-Security","max-age=31536000");
        String editorOrigin=office.browserOrigin();
        response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self' "+editorOrigin+"; worker-src 'self'; frame-src 'self' "+editorOrigin+"; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
        if (!request.getRequestURI().startsWith("/api/")) { chain.doFilter(request,response); return; }
        response.setHeader("Cache-Control","no-store");
        try {
            request.setAttribute(REQUEST,requestId); response.setHeader("X-Request-Id",requestId.toString());
            if (request.getHeader("X-Request-Id")!=null) requestId=Inputs.uuid(request.getHeader("X-Request-Id"));
            request.setAttribute(REQUEST,requestId); response.setHeader("X-Request-Id",requestId.toString());
            String path=request.getRequestURI(), method=request.getMethod();
            boolean write=!Set.of("GET","HEAD","OPTIONS").contains(method);
            if (write) {
                String origin=request.getHeader("Origin"), expected=request.getScheme()+"://"+request.getHeader("Host");
                if ("cross-site".equals(request.getHeader("Sec-Fetch-Site")) || (origin!=null && !origin.equals(expected))) throw ApiFault.denied();
                // 上传分片限 8 MiB，其余 JSON 限 64 KiB。
                boolean part=method.equals("PUT") && path.matches("/api/v1/storage/upload-sessions/[0-9a-f-]+/parts/[0-9]+");
                // 纪要包含整场会前材料及待办，使用独立正文上限。
                boolean minutes=method.equals("PUT") && path.matches("/api/v1/meetings/[0-9a-f-]+/minutes");
                // 回复兼容旧客户端的合并提交，独立提交可携带 30 条事项正文。
                boolean meetingReply=method.equals("POST") && path.matches("/api/v1/meetings/[0-9a-f-]+/reply");
                boolean meetingMaterials=method.equals("PUT") && path.matches(
                        "/api/v1/meetings/[0-9a-f-]+/(materials|weekly-report/rows/[0-9a-f-]+)");
                // 千行员工表的批量拼音邮箱作为预检参数提交。
                boolean importBatch=method.equals("POST")&&path.equals("/api/v1/imports/batches");
                // 单机员工表专用入口接收 4 MiB XLSX 及其 Base64 包装。
                boolean employeeWorkbook=method.equals("POST")&&path.matches("/api/v1/imports/employee-workbook/(inspect|batches)");
                // 会议 XLSX 同样使用 4 MiB 文件与 Base64 包装的正文上限。
                boolean meetingWorkbook=method.equals("POST")&&path.matches("/api/v1/meetings/[0-9a-f-]+/workbooks/import");
                // 意见反馈和会前材料可携带三张内联图片的 Base64 内容。
                boolean feedback=method.equals("POST")&&(path.equals("/api/v1/portal/feedback")
                        ||path.matches("/api/v1/admin/portal/feedback/[0-9a-f-]+/replies"));
                long limit=feedback||meetingMaterials?9L*1024*1024:employeeWorkbook||meetingWorkbook?6L*1024*1024:minutes?16L*1024*1024:part?8L*1024*1024:meetingReply?1024L*1024:importBatch?512L*1024:65536;
                if (request.getContentLengthLong()>limit || (request.getContentLengthLong()<0 && request.getHeader("Transfer-Encoding")!=null)) throw ApiFault.invalid("请求正文超过该接口上限");
            }
            // 托盘助手不依赖浏览器会话，仅这一条 POST 由提醒服务校验专用凭据。
            if(method.equals("POST")&&path.equals("/api/v1/desktop/todo-reminders")) {
                chain.doFilter(request,response);
                return;
            }
            boolean login=path.equals("/api/v1/auth/login") && method.equals("POST");
            boolean logout=path.equals("/api/v1/auth/logout") && method.equals("POST");
            String token=cookie(request);
            boolean recovery=method.equals("POST")&&Set.of("/api/v1/auth/password-recovery","/api/v1/auth/password-recovery/complete").contains(path);
            if (!login && !recovery) {
                Actor actor;
                try { actor=identity.authenticate(token); }
                catch (ApiFault error) { if (logout && error.status==401) { chain.doFilter(request,response); return; } throw error; }
                request.setAttribute(ACTOR,actor);
                if (write && !Crypto.equal(actor.csrfToken(),request.getHeader("X-CSRF-Token"))) throw new ApiFault(403,"CSRF_INVALID","页面验证已失效，请刷新后重试");
                if (actor.setupRequired() && !Set.of("/api/v1/auth/initial-password","/api/v1/auth/me","/api/v1/auth/logout").contains(path)) throw ApiFault.denied();
                // 连续越权只暂停业务访问，仍可退出、查看消息或由IT核对安全队列。
                if(!path.startsWith("/api/v1/auth/")&&!path.startsWith("/api/v1/me/")&&!path.startsWith("/api/v1/admin/security")&&!path.startsWith("/api/v1/admin/audit"))security.requireAvailable(actor.userId(),"ACCESS_DENIED");
                if (write || ("1".equals(request.getHeader("X-User-Activity")) && Crypto.equal(actor.csrfToken(),request.getHeader("X-CSRF-Token")))) identity.activity(actor);
            }
            chain.doFilter(request,response);
        } catch (Exception failure) {
            Actor actor=(Actor)request.getAttribute(ACTOR);
            ApiFault error=ApiErrors.record(audit,actor,ApiErrors.classify(failure),requestId,ApiErrors.route(request));
            // 文件流已经开始交付时不能向文件尾部追加 JSON 错误正文。
            if(response.isCommitted()) { if(failure instanceof IOException io) throw io; throw new ServletException(failure); }
            response.setStatus(error.status); response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(Crypto.JSON.writeValueAsString(ApiController.envelope(error.code,error.getMessage(),null,requestId)));
        } finally {
            Actor actor=(Actor)request.getAttribute(ACTOR);
            // 模板路由来自Spring，绝不记录用户提供的路径、查询词和正文；意图上报不递归产生日志。
            boolean reminderPoll=request.getMethod().equals("GET")&&request.getRequestURI().equals("/api/v1/portal/todo-reminders")&&response.getStatus()==200;
            if(actor!=null&&!request.getRequestURI().equals("/api/v1/me/interactions")&&!reminderPoll){
                String route=Objects.toString(request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),"UNMAPPED");
                try { audit.http(actor.userId(),request.getMethod(),response.getStatus(),route,requestId); }
                catch(Exception failure) { FailureDiagnostics.log(requestId,"HTTP_AUDIT",SystemFailure.AUDIT_WRITE_FAILED.fault(failure)); }
            }
        }
    }
    /** 只从命名 Cookie 获取登录凭据，不接受旧 Bearer、查询参数或浏览器存储令牌。 */
    public static String cookie(HttpServletRequest request) {
        if (request.getCookies()!=null) for(Cookie cookie:request.getCookies()) if(cookie.getName().equals("IMS_SESSION")) return cookie.getValue();
        return null;
    }
}
