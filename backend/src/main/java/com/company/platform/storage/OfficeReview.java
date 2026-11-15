package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 不确定稿件由现任负责人查看原稿后处置，不能让撤权前的迟到回调自动覆盖正式版本。 */
@Service
public class OfficeReview {
    private final Sql db;private final IdentityService identity;private final DriveFilePolicy policy;private final StorageBoundaries protection;private final StorageFiles files;private final DriveLifecycle lifecycle;private final OfficeGateway gateway;private final Audit audit;private final TransactionTemplate transaction;
    public OfficeReview(Sql db,IdentityService identity,DriveFilePolicy policy,StorageBoundaries protection,StorageFiles files,DriveLifecycle lifecycle,OfficeGateway gateway,Audit audit,PlatformTransactionManager manager){this.db=db;this.identity=identity;this.policy=policy;this.protection=protection;this.files=files;this.lifecycle=lifecycle;this.gateway=gateway;this.audit=audit;this.transaction=new TransactionTemplate(manager);}
    /** 姓名、参与时间和保存署名取自平台记录；参与过编辑不代表实际提交过修改。 */
    public Map<String,Object> list(Actor actor,UUID document,Map<String,String> query){
        var doc=policy.requireDocument(actor,document,"VIEW");Inputs.only(query,"sessionPage","receiptPage","pageSize");
        int sessionPage=Inputs.integer(query.get("sessionPage"),1,1,100000),receiptPage=Inputs.integer(query.get("receiptPage"),1,1,100000),size=Inputs.integer(query.get("pageSize"),10,1,100);
        boolean manage=canManage(actor,document);
        String visible="s.document_id=? AND (? OR EXISTS(SELECT 1 FROM drive.edit_participant p WHERE p.session_id=s.id AND p.user_id=?))";
        Object[] args={document,manage,actor.userId()};
        long sessionTotal=db.count("SELECT count(*) FROM drive.edit_session s WHERE "+visible,args),receiptTotal=db.count("SELECT count(*) FROM drive.save_receipt r JOIN drive.edit_session s ON s.id=r.session_id WHERE "+visible,args);
        var sessions=db.rows("SELECT s.*,u.name AS created_by_name FROM drive.edit_session s JOIN iam.user_account u ON u.id=s.created_by WHERE "+visible+" ORDER BY s.created_at DESC,s.id DESC LIMIT ? OFFSET ?",document,manage,actor.userId(),size,(sessionPage-1)*size);
        Map<Object,List<Map<String,Object>>> participants=new HashMap<>();
        if(!sessions.isEmpty()){
            String placeholders=String.join(",",Collections.nCopies(sessions.size(),"?"));
            for(var row:db.rows("SELECT p.session_id,p.user_id,u.name,p.joined_at FROM drive.edit_participant p JOIN iam.user_account u ON u.id=p.user_id WHERE p.session_id IN ("+placeholders+") ORDER BY p.joined_at,p.user_id",sessions.stream().map(s->s.get("id")).toArray()))
                participants.computeIfAbsent(row.get("session_id"),key->new ArrayList<>()).add(Sql.view(row,"user_id,name,joined_at"));
        }
        var receipts=db.rows("SELECT r.*,u.name AS requested_by_name,handler.name AS resolved_by_name,v.version_no,v.created_at AS published_at FROM drive.save_receipt r JOIN drive.edit_session s ON s.id=r.session_id JOIN iam.user_account u ON u.id=r.requested_by LEFT JOIN iam.user_account handler ON handler.id=r.resolved_by LEFT JOIN drive.document_version v ON v.id=r.published_version_id WHERE "+visible+" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",document,manage,actor.userId(),size,(receiptPage-1)*size);
        return Sql.map("canManage",manage,"currentVersionId",doc.get("current_version_id"),"hasPending",db.count("SELECT count(*) FROM drive.edit_session s WHERE "+visible+" AND s.state IN ('OPEN','CLOSING')",args)>0,
            "sessions",sessions.stream().map(s->{var item=Sql.view(s,"id,state,created_by,created_by_name,created_at,last_version_id,row_version");item.put("participants",participants.getOrDefault(s.get("id"),List.of()));return item;}).toList(),
            "receipts",receipts.stream().map(r->Sql.view(r,"id,session_id,save_seq,state,object_id,published_version_id,version_no,published_at,result_code,resolution,requested_by,requested_by_name,resolved_by_name,resolved_at,created_at")).toList(),
            "sessionPage",sessionPage,"receiptPage",receiptPage,"pageSize",size,"sessionTotal",sessionTotal,"receiptTotal",receiptTotal);
    }
    public Map<String,Object> terminate(Commands.Context ctx,UUID sessionId,Map<String,Object> body){Inputs.only(body,"reason");Inputs.text(body,"reason",1000);
        var result=transaction.execute(status->{identity.lockActor(ctx.actor());var before=db.required("SELECT * FROM drive.edit_session WHERE id=?",sessionId);var doc=db.required("SELECT * FROM drive.document WHERE id=? FOR UPDATE",before.get("document_id"));requireManager(ctx.actor(),(UUID)doc.get("id"));protection.requireWritable("DRIVE","OFFICE_TERMINATE");
            if("REVOKED".equals(before.get("state"))||"CLOSED".equals(before.get("state")))return Sql.view(before,"id,state");
            Commands.version(ctx,before);identity.consume(ctx.actor(),ctx.reauth(),"EDIT_SESSION",sessionId,"TERMINATE");
            db.update("UPDATE drive.document SET edit_epoch=edit_epoch+1,row_version=row_version+1 WHERE id=?",doc.get("id"));db.update("UPDATE drive.edit_session SET state='UNKNOWN',row_version=row_version+1 WHERE id=?",sessionId);
            db.update("UPDATE drive.save_receipt SET state='UNKNOWN',result_code='MANAGER_TERMINATED' WHERE session_id=? AND state IN ('REQUESTED','RECEIVING')",sessionId);
            if(db.count("SELECT count(*) FROM drive.save_receipt WHERE session_id=? AND state='UNKNOWN'",sessionId)==0){db.update("INSERT INTO drive.save_receipt(id,session_id,save_seq,operation_id,state,result_code,requested_by,reason) VALUES(?,?,?,?,'UNKNOWN','TERMINATED_WITHOUT_CANDIDATE',?,?)",UUID.randomUUID(),sessionId,before.get("next_save_seq"),ctx.operation(),ctx.actor().userId(),body.get("reason"));db.update("UPDATE drive.edit_session SET next_save_seq=next_save_seq+1 WHERE id=?",sessionId);}
            audit.event(ctx.actor().userId(),"DRIVE","EDIT_SESSION",sessionId,"TERMINATE","SUCCEEDED",ctx.request());return Sql.map("id",sessionId,"state","UNKNOWN");});
        try{gateway.command(Sql.map("c","drop","key",db.required("SELECT editor_key FROM drive.edit_session WHERE id=?",sessionId).get("editor_key")));}catch(RuntimeException unavailable){/* 本地代次已关闭；远端不可用仍保留待确认状态。 */}return result;
    }
    public Map<String,Object> candidate(Actor actor,UUID receipt){var row=db.required("SELECT r.object_id,s.document_id,o.* FROM drive.save_receipt r JOIN drive.edit_session s ON s.id=r.session_id JOIN storage.object o ON o.id=r.object_id WHERE r.id=? AND r.state='UNKNOWN' AND o.state='READY'",receipt);requireManager(actor,(UUID)row.get("document_id"));policy.requireDocument(actor,(UUID)row.get("document_id"),"DOWNLOAD");protection.requireReadable("DRIVE");return row;}
    /** 原件审阅也须单独DOWNLOAD权；完成实际交付后才留下可接受候选的审阅凭据。 */
    public void stream(Actor actor,UUID receipt,OutputStream output,UUID request) throws IOException {var object=candidate(actor,receipt);Path path=files.get().verifiedPrimary((String)object.get("storage_key"),((Number)object.get("byte_size")).longValue(),(String)object.get("sha256"));try(var input=Files.newInputStream(path)){byte[] block=new byte[1048576];int count;while((count=input.read(block))!=-1){identity.background(actor.sessionId());candidate(actor,receipt);output.write(block,0,count);}}audit.event(actor.userId(),"DRIVE","OFFICE_CANDIDATE",receipt,"REVIEW_DELIVERED","SUCCEEDED",request);}
    public Map<String,Object> resolve(Commands.Context ctx,UUID receiptId,Map<String,Object> body){Inputs.only(body,"resolution","reviewedObjectId","reason");String resolution=Inputs.choice(body.get("resolution"),"ACCEPT","DISCARD");Inputs.text(body,"reason",1000);
        UUID reviewed=resolution.equals("ACCEPT")?Inputs.uuid(body.get("reviewedObjectId")):null;
        if(reviewed!=null){var candidate=candidate(ctx.actor(),receiptId);if(!reviewed.equals(candidate.get("object_id")))throw ApiFault.conflict("审阅对象不匹配");files.get().verifiedPrimary((String)candidate.get("storage_key"),((Number)candidate.get("byte_size")).longValue(),(String)candidate.get("sha256"));}
        return transaction.execute(status->{identity.lockActor(ctx.actor());var before=db.required("SELECT r.*,s.document_id FROM drive.save_receipt r JOIN drive.edit_session s ON s.id=r.session_id WHERE r.id=?",receiptId);var doc=db.required("SELECT * FROM drive.document WHERE id=? FOR UPDATE",before.get("document_id"));requireManager(ctx.actor(),(UUID)doc.get("id"));protection.requireWritable("DRIVE","OFFICE_RESOLVE");
            if("RESOLVED".equals(before.get("state"))) {if(!resolution.equals(before.get("resolution")))throw ApiFault.conflict("候选已按另一结论处置");return Sql.map("id",receiptId,"state","RESOLVED");}
            if(!"UNKNOWN".equals(before.get("state")))throw ApiFault.conflict("此回执无需人工处置");Commands.version(ctx,doc);identity.consume(ctx.actor(),ctx.reauth(),"SAVE_RECEIPT",receiptId,"RESOLVE");UUID published=null;
            if(reviewed!=null){candidate(ctx.actor(),receiptId);if(db.count("SELECT count(*) FROM ops.audit_event WHERE actor_id=? AND resource_type='OFFICE_CANDIDATE' AND resource_id=? AND action='REVIEW_DELIVERED' AND result='SUCCEEDED'",ctx.actor().userId(),receiptId)==0)throw ApiFault.conflict("请先下载并检查候选稿件");published=lifecycle.version(ctx.actor(),doc,reviewed,(UUID)doc.get("current_version_id"),"OFFICE_RESOLVE");db.update("UPDATE drive.document SET edit_epoch=edit_epoch+1,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",doc.get("id"));}
            db.update("UPDATE drive.save_receipt SET state='RESOLVED',resolution=?,resolved_by=?,resolved_at=CURRENT_TIMESTAMP,reason=?,published_version_id=? WHERE id=?",resolution,ctx.actor().userId(),body.get("reason"),published,receiptId);
            db.update("UPDATE drive.edit_session s SET state='CLOSED',row_version=row_version+1 WHERE s.id=? AND NOT EXISTS(SELECT 1 FROM drive.save_receipt r WHERE r.session_id=s.id AND r.state IN ('REQUESTED','RECEIVING','UNKNOWN'))",before.get("session_id"));
            audit.event(ctx.actor().userId(),"DRIVE","SAVE_RECEIPT",receiptId,"RESOLVE_"+resolution,"SUCCEEDED",ctx.request());return Sql.map("id",receiptId,"state","RESOLVED","publishedVersionId",published);
        });
    }
    private boolean canManage(Actor actor,UUID document){return Boolean.TRUE.equals(db.required("SELECT drive.can_document(?,?,'COLLABORATE') AS allowed",actor.userId(),document).get("allowed"));}
    private void requireManager(Actor actor,UUID document){policy.requireDocument(actor,document,"VIEW");if(!canManage(actor,document))throw ApiFault.denied();}
}
