package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;

/** 公盘内容短事务：按当前账号、操作号和文档加锁，不串行化全公司的文件操作。 */
@Service
public class DriveCommands {
    /** 共享事务访问、身份和保护边界。 */
    private final Sql db; private final IdentityService identity; private final StorageBoundaries protection; private final Audit audit;
    public DriveCommands(Sql db,IdentityService identity,StorageBoundaries protection,Audit audit) {this.db=db;this.identity=identity;this.protection=protection;this.audit=audit;}
    /** 前置权限覆盖重试；期望版本只在首次执行检查，同号异参绝不再次执行。 */
    @Transactional
    public Map<String,Object> run(Commands.Context ctx,UUID document,String action,Map<String,Object> body,Function<Map<String,Object>,Map<String,Object>> mutation) {
        identity.lockActor(ctx.actor());ctx.actor().require("DRIVE.ACCESS");protection.requireWritable("DRIVE",action);
        String key=ctx.route()+":"+ctx.operation(),digest=Crypto.hash(Crypto.json(Sql.map("body",body,"expectedVersion",ctx.version())));
        db.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",ctx.actor().userId()+":"+key);
        var doc=db.required("SELECT * FROM drive.document WHERE id=? FOR UPDATE",document);
        if(!Boolean.TRUE.equals(db.required("SELECT drive.can_document(?,?,?) AS allowed",ctx.actor().userId(),document,action).get("allowed"))) throw ApiFault.denied();
        var prior=audit.receipt(ctx.actor().userId(),key);
        if(prior!=null) {if(!Crypto.equal(digest,(String)prior.get("request_digest"))) throw ApiFault.conflict("同一操作号不能用于不同内容");return view(document);}
        Commands.version(ctx,doc);identity.consume(ctx.actor(),ctx.reauth(),"DRIVE_DOCUMENT",document,action);
        // 最终保存未确认时不修改正式版本；旧协作结果仍可由负责人单独处置。
        if(db.count("SELECT count(*) FROM drive.edit_session WHERE document_id=? AND state IN ('OPEN','CLOSING','UNKNOWN')",document)>0) throw ApiFault.conflict("请先完成协作最终保存或处理待确认稿件");
        mutation.apply(doc);
        db.update("UPDATE drive.document SET row_version=row_version+1,edit_epoch=edit_epoch+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",document);
        db.update("UPDATE storage.preview_session SET expires_at=CURRENT_TIMESTAMP WHERE document_id=?",document);
        audit.event(ctx.actor().userId(),"DRIVE","DOCUMENT",document,action,"SUCCEEDED",ctx.request());
        db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,resource_id,action,result,request_id,record_kind,operation_key,request_digest,operation_result) VALUES(?,?,CURRENT_TIMESTAMP,?,'DRIVE','DOCUMENT',?,?,'SUCCEEDED',?,'IDEMPOTENCY_RECEIPT',?,?,?::jsonb)",UUID.randomUUID(),UUID.randomUUID(),ctx.actor().userId(),document,action,ctx.request(),key,digest,Crypto.json(Sql.map("objectId",document)));
        return view(document);
    }
    /** 变更回执只带实际文档状态，不夹带敏感文件或其他员工内容。 */
    private Map<String,Object> view(UUID document) {return Sql.view(db.required("SELECT * FROM drive.document WHERE id=?",document),"id,name,folder_id,current_version_id,status,row_version,collaboration_enabled,edit_epoch");}
}
