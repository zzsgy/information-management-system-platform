package com.company.ims.identity;

import java.util.Optional;

/** 平台统一身份适配边界，后续接入信息管理平台时替换实现而无需修改档案业务。 */
public interface IdentityProvider {
    /** 验证凭证并返回当前有效身份；无效、已撤销或账号停用时返回空值。 */
    Optional<Actor> resolve(String bearerToken);
}
