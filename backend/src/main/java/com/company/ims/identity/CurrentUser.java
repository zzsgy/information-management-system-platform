package com.company.ims.identity;

import com.company.ims.shared.BusinessException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/** 从当前HTTP请求读取过滤器已验证的身份，禁止由业务参数伪造用户。 */
public final class CurrentUser {
    /** 请求属性的唯一键，避免与普通业务字段冲突。 */
    public static final String ATTRIBUTE = Actor.class.getName();
    /** 工具类不允许实例化。 */
    private CurrentUser() { }

    /** 返回可选身份，适用于公共页面需要判断是否已登录的场景。 */
    public static Optional<Actor> optional() {
        // 请求上下文只在HTTP处理线程中存在，后台任务不能隐式借用用户身份。
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.ofNullable((Actor) attrs.getRequest().getAttribute(ATTRIBUTE));
        }
        return Optional.empty();
    }

    /** 要求有效身份，缺失时统一返回401而非空指针错误。 */
    public static Actor require() {
        return optional().orElseThrow(() -> new BusinessException(401, "请先登录"));
    }
}
