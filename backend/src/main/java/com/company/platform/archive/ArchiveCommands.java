package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.storage.StorageBoundaries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.*;

/** 档案短事务命令：账号核验、对象授权、幂等、可选再验证、业务/审计/outbox一起提交。 */
@Service
public class ArchiveCommands {
    /** 数据访问、有效会话、审计与实时保护均共享本次事务，不缓存正文权限。 */
    private final Sql db; private final IdentityService identity; private final Audit audit; private final StorageBoundaries protection;
    public ArchiveCommands(Sql db,IdentityService identity,Audit audit,StorageBoundaries protection) {this.db=db;this.identity=identity;this.audit=audit;this.protection=protection;}
    /** 所有入口提供固定对象及动作；回执重放也重查当前对象可见性，不重复业务决定。 */
    @Transactional
    public Map<String,Object> run(Commands.Context context,Map<String,Object> body,String type,UUID target,String action,boolean reauth,Runnable authorize,Supplier<Map<String,Object>> mutation,Function<UUID,Map<String,Object>> replay) {
        identity.lockActor(context.actor());identity.background(context.actor().sessionId()).require("ARCHIVE.ACCESS");
        // 已隔离旧主的只读运行仍允许明确安全收紧；此白名单不承接任何扩权或内容写入。
        if(Set.of("REVOKE_ARCHIVE_GRANT","REVOKE_ARCHIVE_POLICY").contains(action)) protection.requireReadable("ARCHIVE");else protection.requireWritable("ARCHIVE",action);authorize.run();
        // 同一主体操作号串行化；不同借阅与不同节点不经过IAM全局管理锁。
        String key=context.route()+":"+context.operation(),digest=Crypto.hash(Crypto.json(Sql.map("body",body,"expectedVersion",context.version())));
        db.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",context.actor().userId()+":"+key);
        var prior=audit.receipt(context.actor().userId(),key);
        if(prior!=null) {
            if(!Crypto.equal(digest,(String)prior.get("request_digest"))) throw ApiFault.conflict("同一操作号不能提交不同内容");
            Map<?,?> saved=Crypto.JSON.readValue((String)prior.get("operation_result"),Map.class);return replay.apply(Inputs.uuid(saved.get("objectId")));
        }
        if(reauth) identity.consume(context.actor(),context.reauth(),type,target,action);
        Map<String,Object> result=mutation.get();UUID object=Inputs.uuid(result.containsKey("id")?result.get("id"):result.get("objectId"));
        // 凭据提交或撤回返回提交批次，节点审计与 outbox 仍定位受影响的实际审批节点。
        UUID auditObject=Set.of("SUBMIT_EVIDENCE","CANCEL_EVIDENCE").contains(action)?target:object;
        audit.event(context.actor().userId(),"ARCHIVE",type,auditObject,action,"SUCCEEDED",context.request());
        db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,resource_id,action,result,request_id,record_kind,operation_key,request_digest,operation_result) VALUES(?,?,CURRENT_TIMESTAMP,?,'ARCHIVE',?,?,?,'SUCCEEDED',?,'IDEMPOTENCY_RECEIPT',?,?,?::jsonb)",UUID.randomUUID(),UUID.randomUUID(),context.actor().userId(),type,auditObject,action,context.request(),key,digest,Crypto.json(Sql.map("objectId",object)));
        event(type,auditObject,action);return result;
    }
    /** outbox只保存受控对象引用；异步通知失败不能重执行批准或扩大接收范围。 */
    public void event(String type,UUID object,String action) {
        db.update("INSERT INTO ops.outbox_event(id,event_id,module,aggregate_type,aggregate_id,aggregate_version,event_type,payload_ref) VALUES(?,?,'ARCHIVE',?,?,1,?,?)",UUID.randomUUID(),UUID.randomUUID(),type,object,action,"archive-event:"+object);
    }
}
