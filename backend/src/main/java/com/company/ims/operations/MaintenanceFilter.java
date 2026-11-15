package com.company.ims.operations;

import com.company.ims.shared.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

/** 覆盖认证与整个同步响应链的维护门禁，上传落盘与事务提交必须全部结束才允许备份。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MaintenanceFilter extends OncePerRequestFilter {
    /** 与运维服务共用的单实例门禁。 */
    private final MaintenanceGate gate;
    /** 注入共享维护门禁。 */
    public MaintenanceFilter(MaintenanceGate gate) { this.gate=gate; }

    /** 只有需要取得独占锁的两个入口跳过共享锁，避免读锁升级死锁。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath(); // 容器规范化路径，不使用客户端原始URL。
        return "POST".equals(request.getMethod()) && "/api/security/db-backup".equals(path)
                || "GET".equals(request.getMethod()) && "/api/security/integrity/check".equals(path);
    }

    /** 共享锁覆盖认证、控制器、上传、事务完成和同步响应写出。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        MaintenanceGate.Lease lease; // 只捕获门禁拒绝，业务异常留给统一异常处理器。
        try { lease=gate.enterRequest(); }
        catch (BusinessException busy) {
            response.setStatus(busy.status()); response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After","30");
            response.getWriter().write(Json.write(Map.of("code",busy.status(),"message",busy.getMessage(),"detail",busy.getMessage())));
            return;
        }
        try (lease) { chain.doFilter(request,response); }
    }
}
