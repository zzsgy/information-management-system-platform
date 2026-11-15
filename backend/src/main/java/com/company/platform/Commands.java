package com.company.platform;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.*;

/** 管理写操作事务边界：重新授权、幂等、版本、再验证、业务与审计整体提交。 */
@Service
public class Commands {
    /** SQL、身份和审计共享同一数据库事务。 */
    private final Sql db;
    private final IdentityService identity;
    private final Audit audit;
    /** 注入同事务数据访问、实时身份核验和审计边界。 */
    public Commands(Sql db,IdentityService identity,Audit audit) { this.db=db; this.identity=identity; this.audit=audit; }
    /** 命令上下文全部由受控 HTTP 边界构造；body 禁止包含密码等秘密。 */
    public record Context(
        /** 当前主体。 */ Actor actor,
        /** 实际请求追踪号。 */ UUID request,
        /** 客户端重试复用的操作号。 */ UUID operation,
        /** HTTP 方法与完整路由。 */ String route,
        /** 一次性本人密码验证凭据，仅当前请求使用。 */ String reauth,
        /** 客户端读到的对象版本。 */ Integer version) { }
    /** 固定锁只串行化低频 IAM 管理变更，分页读、静态资源和业务文件不经过此锁。 */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String,Object> run(Context ctx,Map<String,Object> body,String type,UUID target,String action,
            Runnable authorize,Supplier<Map<String,Object>> mutation,Function<UUID,Map<String,Object>> replay) {
        db.rows("SELECT pg_advisory_xact_lock(61427002)");
        identity.lockActor(ctx.actor()); authorize.run();
        String key=ctx.route()+":"+ctx.operation();
        String digest=Crypto.hash(Crypto.json(Sql.map("body",body,"expectedVersion",ctx.version())));
        // 全局登记先定位唯一月份，再取回回执，避免重试时逐个探测所有历史分区。
        var receipt=audit.receipt(ctx.actor().userId(),key);
        if (receipt!=null) {
            if (!Crypto.equal(digest,(String)receipt.get("request_digest"))) throw ApiFault.conflict("同一操作号不能用于不同内容");
            Map<String,Object> saved=Crypto.JSON.readValue((String)receipt.get("operation_result"),Map.class);
            return replay.apply(Inputs.uuid(saved.get("objectId"))); // 每次重读受权对象，不返回过期授权快照。
        }
        identity.consume(ctx.actor(),ctx.reauth(),type,target,action);
        Map<String,Object> result=mutation.get();
        UUID objectId=Inputs.uuid(result.containsKey("id")?result.get("id"):result.getOrDefault("objectId",target));
        // 补录及人员变更的回执指向新证据/交接记录，审计对象仍是实际被操作的节点、部门或员工。
        UUID auditObject=Set.of("EVIDENCE_RECORD","REJECT_EVIDENCE","HANDOVER_ARCHIVIST","CHANGE_DEPARTMENT").contains(action)?target:objectId;
        // 角色变更由领域服务按实际模块分别留痕，避免档案配置记录落入平台日志可见范围。
        String module=Set.of("DRIVE_COLLECTION","FOLDER","DRIVE_GRANT","DRIVE_DELEGATION").contains(type)?"DRIVE":"PLATFORM";
        if(!Set.of("ASSIGN_ROLES","ROLE_PERMISSIONS").contains(action)) audit.event(ctx.actor().userId(),module,type,auditObject,action,"SUCCEEDED",ctx.request());
        db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,resource_id,action,result,request_id,record_kind,operation_key,request_digest,operation_result) VALUES(?,?,CURRENT_TIMESTAMP,?,?,?,?,?,'SUCCEEDED',?,'IDEMPOTENCY_RECEIPT',?,?,?::jsonb)",
            UUID.randomUUID(),UUID.randomUUID(),ctx.actor().userId(),module,type,auditObject,action,ctx.request(),key,digest,Crypto.json(Sql.map("objectId",objectId,"state","SUCCEEDED")));
        return result;
    }
    /** 可变对象使用客户端读取版本，省略版本也不能降级为盲写。 */
    public static void version(Context ctx,Map<String,Object> row) {
        if (ctx.version()==null) throw ApiFault.invalid("缺少 X-Expected-Version");
        if (ctx.version()!=((Number)row.get("row_version")).intValue()) throw ApiFault.conflict("记录已被其他人修改，请刷新后重试");
    }
    /** 通用命令响应包含真实对象标识和操作号。 */
    public static Map<String,Object> result(Context ctx,UUID id,int version) { return Sql.map("objectId",id,"state","SUCCEEDED","rowVersion",version,"operationId",ctx.operation()); }
}
