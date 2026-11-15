package com.company.ims.identity;

import com.company.ims.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 兼容原有/api/auth协议；业务模块不直接依赖本地账号表。 */
@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {
    /** 本地登录和会话生命周期服务。 */
    private final AuthenticationService authentication;

    /** 注入认证服务。 */
    public AuthenticationController(AuthenticationService authentication) { this.authentication = authentication; }

    /** 登录并返回不透明Bearer凭证，客户端不应解析令牌格式。 */
    @PostMapping("/login")
    public Object login(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(authentication.login(body.get("username"), body.get("password")));
    }

    /** 查询本次请求已验证的账号、部门和实时权限。 */
    @GetMapping("/me")
    public Object me() { return ApiResponse.ok(authentication.userInfo(CurrentUser.require())); }

    /** 校验旧密码并更新，成功后包含本次在内的全部会话立即失效。 */
    @PostMapping("/change-password")
    public Object changePassword(@RequestBody Map<String, String> body) {
        authentication.changePassword(CurrentUser.require(), body.get("old_password"), body.get("new_password"));
        return ApiResponse.ok(null, "密码修改成功，请重新登录");
    }

    /** 撤销当前请求使用的会话凭证。 */
    @PostMapping("/logout")
    public Object logout(HttpServletRequest request) {
        authentication.logout(CurrentUser.require(), AuthenticationFilter.token(request));
        return ApiResponse.ok(null, "已退出登录");
    }
}
