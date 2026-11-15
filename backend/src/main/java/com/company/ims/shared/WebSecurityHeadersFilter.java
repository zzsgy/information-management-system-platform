package com.company.ims.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 为完整Web应用设置同源资源策略；接口认证仍使用显式Bearer头，不放开Cookie写请求。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class WebSecurityHeadersFilter extends OncePerRequestFilter {
    /** 静态页面禁止嵌入第三方站点，业务API不缓存；保留旧/web只读页的既有策略。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 路径只用于选择固定策略，不能影响身份解析与业务授权。
        String path = request.getServletPath();
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (path.startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        else if (!path.startsWith("/web")) {
            // Element控件需要内联样式，脚本只允许本地构建资源；附件预览使用临时blob地址。
            response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; frame-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Cache-Control", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
