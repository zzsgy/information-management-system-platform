package com.company.ims.identity;

import com.company.ims.shared.BusinessException;

/** API入口的精确权限检查器，不承担档案的数据范围过滤。 */
public final class PermissionGuard {
    /** 工具类不允许实例化。 */
    private PermissionGuard() { }

    /** 所列权限必须全部满足，成功时返回当前身份供服务层执行范围检查。 */
    public static Actor require(String... permissions) {
        // 身份已由请求过滤器校验，不能从客户端字段获取。
        Actor actor = CurrentUser.require();
        // 逐项执行与关系检查，避免多权限操作只满足其中一项。
        for (String permission : permissions) {
            if (!actor.has(permission)) throw new BusinessException(403, "没有操作权限：" + permission);
        }
        return actor;
    }
}
