package com.company.ims.identity;

import com.company.ims.shared.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 保护API和网页入口；公开路径采用精确白名单，其他请求必须有有效身份。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthenticationFilter extends OncePerRequestFilter {
    /** 允许匿名访问的登录和健康检查路径。 */
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login", "/health", "/actuator/health", "/web/login");
    /** 可替换的平台身份适配器。 */
    private final IdentityProvider identities;

    /** 注入当前启用的身份提供方。 */
    public AuthenticationFilter(IdentityProvider identities) { this.identities = identities; }

    /** 仅精确放行公开路径，受保护的API与网页仍进入校验。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 采用容器解析后的servlet路径，兼容应用部署上下文前缀。
        String path = request.getServletPath();
        return PUBLIC_PATHS.contains(path) || !(path.startsWith("/api/") || path.equals("/web") || path.startsWith("/web/"));
    }

    /** 从Bearer请求头读取令牌；只有网页路径接受会话Cookie，REST写操作不能借用Cookie。 */
    public static String token(HttpServletRequest request) {
        // 显式请求头优先，避免Cookie覆盖桌面客户端传入的身份。
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) return authorization.substring(7).trim();
        // 网页会话仅在/web路径内读取；REST接口要求显式Authorization头。
        if (request.getServletPath().startsWith("/web") && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) if ("ims_session".equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    /** 未认证请求返回统一错误，网页GET引导至登录页；成功后将身份绑定到当前请求。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 身份来自会话数据库的实时结果，不能信任客户端自报角色或部门。
        Optional<Actor> actor = identities.resolve(token(request));
        if (actor.isEmpty()) {
            if ("GET".equals(request.getMethod()) && request.getServletPath().startsWith("/web")) {
                response.sendRedirect(request.getContextPath() + "/web/login");
            } else {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(Json.write(Map.of("code", 401, "message", "登录已过期或账号已停用，请重新登录", "detail", "登录已过期或账号已停用，请重新登录")));
            }
            return;
        }
        request.setAttribute(CurrentUser.ATTRIBUTE, actor.get());
        chain.doFilter(request, response);
    }
}
