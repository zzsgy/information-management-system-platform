package com.company.platform.intake;

import com.company.platform.*;
import com.company.platform.storage.UploadRules;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.*;

/** 专用设备 HTTPS 边界；签名不会创建员工会话，公共档案查询仍要求员工 Cookie。 */
@Component @Order(2)
public class ScanHttpBoundary extends OncePerRequestFilter {
    /** 仅当前请求可使用的已验证设备身份。 */
    public static final String SERVICE="scan.service";
    /** 签名及重放账本。 */
    private final ScanIdentity identity;
    public ScanHttpBoundary(ScanIdentity identity){this.identity=identity;}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        if(!r.getRequestURI().startsWith("/internal/archive/")){chain.doFilter(r,response);return;}
        UUID request=UUID.randomUUID();r.setAttribute(HttpBoundary.REQUEST,request);response.setHeader("X-Request-Id",request.toString());response.setHeader("Cache-Control","no-store");
        try{
            if(!r.isSecure()||r.getHeader("Origin")!=null||r.getQueryString()!=null)throw ApiFault.denied();
            boolean part=r.getMethod().equals("PUT")&&r.getRequestURI().matches("/internal/archive/scan-upload-sessions/[0-9a-f-]+/parts/[0-9]+");long limit=part?UploadRules.PART_SIZE:65536;
            if(r.getContentLengthLong()>limit||(r.getContentLengthLong()<0&&r.getHeader("Transfer-Encoding")!=null))throw ApiFault.invalid("扫描请求超过受控上限");
            String hash=UploadRules.sha(r.getHeader("X-Content-Sha256"));if(r.getMethod().equals("GET")&&!hash.equals(Crypto.hash("")))throw ApiFault.invalid("查询不能携带正文");
            UUID service=identity.authenticate(r.getHeader("X-Service-Id"),r.getHeader("X-Service-Time"),r.getHeader("X-Service-Nonce"),r.getMethod(),r.getRequestURI(),hash,r.getHeader("X-Service-Signature"));r.setAttribute(SERVICE,service);chain.doFilter(r,response);
        }catch(Exception error){ApiFault failure=ApiErrors.classify(error);FailureDiagnostics.log(request,"SCAN_HTTP",failure);response.setStatus(failure.status);response.setContentType("application/json;charset=UTF-8");response.getWriter().write(Crypto.JSON.writeValueAsString(ApiController.envelope(failure.code,failure.getMessage(),null,request)));}
    }
}
