package com.company.platform.storage;

import com.company.platform.Actor;
import java.util.UUID;

/** 由公盘、档案、流程和导入领域分别实现的对象级授权；模块角色不自动授予文件正文。 */
public interface StorageDomainPolicy {
    /** 当前实现能否判定指定用途；不得把未知用途降级为通用允许。 */
    boolean supports(String module,String purpose);
    /** 检查当前主体对固定业务上下文执行动作的权限；拒绝时抛出安全业务异常。 */
    void require(Actor actor,String module,String purpose,UUID context,String action);
}
