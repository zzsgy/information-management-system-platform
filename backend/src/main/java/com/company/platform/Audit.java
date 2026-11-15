package com.company.platform;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** 关键事实与业务在同一事务提交；拒绝事件独立提交，不随失败业务回滚。 */
@Service
public class Audit {
    /** 仅追加审计的数据库访问。 */
    private final Sql db;
    /** 注入统一查询模板，受调用方事务或显式独立审计事务管理。 */
    public Audit(Sql db) { this.db=db; }
    /** 全局键先定位唯一月份；各模块仍须先授权，不能把历史回执当成当前操作许可。 */
    public Map<String,Object> receipt(UUID actor,String key){
        return db.one("SELECT e.request_digest,e.operation_result::text FROM ops.audit_identity k JOIN ops.audit_event e ON e.id=k.id AND e.occurred_at=k.occurred_at WHERE k.actor_id=? AND k.operation_key=? AND k.operation_key IS NOT NULL AND e.record_kind='IDEMPOTENCY_RECEIPT'",actor,key);
    }
    /** 不保存请求正文或凭据，只记录对象、动作、实际结果和追踪号。 */
    public void event(UUID actor, String module, String type, UUID target, String action, String result, UUID request) {
        // 入库时点使用真实时钟；事务开始时间会让本次查询结束后追加的审计混入旧票据。
        db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,resource_id,action,result,request_id,record_kind,severity,ingested_at) VALUES(?,?,CURRENT_TIMESTAMP,?,?,?,?,?,?,?,'BUSINESS_EVENT',?,clock_timestamp())",
            UUID.randomUUID(),UUID.randomUUID(),actor,module,type,target,action,result,request,"DENIED".equals(result)?"WARN":Set.of("FAILED","MANUAL").contains(result)?"ERROR":"INFO");
    }
    /** 拒绝事件使用独立事务，避免业务回滚抹掉越权与失败证据。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void denied(UUID actor, String action, UUID request) {
        db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,action,result,request_id,record_kind,severity,ingested_at) VALUES(?,?,CURRENT_TIMESTAMP,?,'PLATFORM','REQUEST',?,'DENIED',?,'SECURITY_EVENT',?,clock_timestamp())",UUID.randomUUID(),UUID.randomUUID(),actor,action,request,action.equals("PROTECTION_NOT_READY")?"CRITICAL":"WARN");
    }
    /** HTTP结果与业务成功分开：200只表示响应完成，异步任务仍以各领域实际事件为准。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void http(UUID actor,String method,int status,String route,UUID request){
        db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,action,result,request_id,record_kind,severity,authorization_source,ingested_at) VALUES(?,?,CURRENT_TIMESTAMP,?,'PLATFORM','HTTP_REQUEST',?,?,?,'SECURITY_EVENT',?,?,clock_timestamp())",
            UUID.randomUUID(),UUID.randomUUID(),actor,"HTTP_"+method,status<400?"HTTP_"+status:"HTTP_FAILED_"+status,request,status>=500?"ERROR":status>=400?"WARN":"INFO",route.substring(0,Math.min(64,route.length())));
    }
}
