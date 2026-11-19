package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;

/** 领域授权和保护条件不可由客户端提交；没有唯一可用适配器时明确拒绝。 */
@Component
public class StorageBoundaries {
    /** 可扩展领域策略与独立保护状态提供者。 */
    private final ObjectProvider<StorageDomainPolicy> domains;
    private final ObjectProvider<StorageProtectionPolicy> protections;
    public StorageBoundaries(ObjectProvider<StorageDomainPolicy> domains,ObjectProvider<StorageProtectionPolicy> protections) { this.domains=domains; this.protections=protections; }
    /** 每个阶段重新核对当前授权；多个策略同时匹配属于配置错误。 */
    public void require(Actor actor,String module,String purpose,UUID context,String action,boolean write) {
        List<StorageDomainPolicy> matches=domains.orderedStream().filter(p->p.supports(module,purpose)).toList();
        if(matches.size()!=1) throw SystemFailure.STORAGE_POLICY_UNAVAILABLE.fault();
        matches.getFirst().require(actor,module,purpose,context,action);
        if(write) requireWritable(module,action);
    }
    /** 业务发布及授权配置复用同一实时保护门槛，不能绕过文件上传保护。 */
    public void requireWritable(String module,String action) {
            List<StorageProtectionPolicy> gates=protections.orderedStream().toList();
            if(gates.size()!=1) throw SystemFailure.PROTECTION_PROVIDER_UNAVAILABLE.fault();
            gates.getFirst().requireWritable(module,action);
    }
    /** 无唯一可信保护提供者时，不能返回任何正文页面。 */
    public void requireReadable(String module) {
        List<StorageProtectionPolicy> gates=protections.orderedStream().toList();
        if(gates.size()!=1) throw SystemFailure.PROTECTION_PROVIDER_UNAVAILABLE.fault();
        gates.getFirst().requireReadable(module);
    }
}
