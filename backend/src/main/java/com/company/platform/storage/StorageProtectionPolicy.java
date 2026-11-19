package com.company.platform.storage;

import com.company.platform.SystemFailure;

/** 由正式保护点与主备隔离服务提供的实时写门槛；缺失、过期或不确定均拒绝。 */
public interface StorageProtectionPolicy {
    /** 对对应模块和动作检查双副本、统一恢复点及运行状态。 */
    void requireWritable(String module,String action);
    /** 敏感页面必须单独通过当前节点和恢复状态检查；旧写策略不能隐式允许阅读。 */
    default void requireReadable(String module) { throw com.company.platform.SystemFailure.PROTECTION_READ_BLOCKED.fault(); }
}
