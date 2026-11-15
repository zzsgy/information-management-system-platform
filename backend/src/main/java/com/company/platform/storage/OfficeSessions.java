package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Office协作状态机：完整参与者、保存序号与固定版本均由平台掌握，回调只作为外部结果。 */
@Service
public class OfficeSessions {
    /** 身份、当前权限、不可变原件和实际检测共享既有边界。 */
    private final Sql db;private final IdentityService identity;private final DriveFilePolicy policy;private final StorageBoundaries protection;private final StorageFiles files;
    private final OfficeGateway gateway;private final FileScanEngine scanner;private final DriveLifecycle lifecycle;private final Audit audit;private final TransactionTemplate transaction;
    private final ServiceIdentities services;
    public OfficeSessions(Sql db,IdentityService identity,DriveFilePolicy policy,StorageBoundaries protection,StorageFiles files,OfficeGateway gateway,FileScanEngine scanner,DriveLifecycle lifecycle,Audit audit,PlatformTransactionManager manager,ServiceIdentities services) {
        this.db=db;this.identity=identity;this.policy=policy;this.protection=protection;this.files=files;this.gateway=gateway;this.scanner=scanner;this.lifecycle=lifecycle;this.audit=audit;this.transaction=new TransactionTemplate(manager);this.services=services;
    }
    /** 仅当前已授权的OOXML文档可加入；不创建空白文件，不自动开放整目录协作。 */
    public Map<String,Object> open(Actor actor,UUID document,Map<String,Object> body,UUID request) {
        Inputs.only(body,"baseVersionId");UUID version=Inputs.uuid(body.get("baseVersionId"));policy.requireDocument(actor,document,"VIEW");policy.requireDocument(actor,document,"EDIT");gateway.require();gateway.verifyVersion();
        refreshPendingSession(document);
        return transaction.execute(status->{identity.lockActor(actor);var doc=db.required("SELECT * FROM drive.document WHERE id=? FOR UPDATE",document);policy.requireDocument(actor,document,"EDIT");protection.requireWritable("DRIVE","OFFICE_OPEN");
            if(!Boolean.TRUE.equals(doc.get("collaboration_enabled"))||!version.equals(doc.get("current_version_id")))throw ApiFault.conflict("协作未开启或文档版本已变化");
            String ext=DriveLifecycle.extension((String)doc.get("name"));if(!Set.of("docx","xlsx","pptx").contains(ext))throw new ApiFault(409,"OFFICE_CONVERSION_REQUIRED","旧Office格式须先生成并检测OOXML转换版本");
            var object=db.required("SELECT o.* FROM drive.document_version v JOIN storage.object o ON o.id=v.object_id WHERE v.id=? AND o.state='READY'",version);if(((Number)object.get("byte_size")).longValue()>gateway.maximum())throw new ApiFault(413,"OFFICE_FILE_TOO_LARGE","文件超过在线编辑处理上限，可继续按原件管理");service(doc);
            var session=db.one("SELECT *,expires_at>CURRENT_TIMESTAMP AS valid FROM drive.edit_session WHERE document_id=? AND state IN ('OPEN','CLOSING','UNKNOWN') FOR UPDATE",document);
            if(session==null){UUID id=UUID.randomUUID();db.update("INSERT INTO drive.edit_session(id,document_id,base_version_id,last_version_id,edit_epoch,editor_key,state,created_by,expires_at) VALUES(?,?,?,?,?,?,'OPEN',?,CURRENT_TIMESTAMP+interval '2 hours')",id,document,version,version,doc.get("edit_epoch"),"ims-"+UUID.randomUUID(),actor.userId());session=db.required("SELECT *,true AS valid FROM drive.edit_session WHERE id=?",id);}
            if("CLOSING".equals(session.get("state")))throw new ApiFault(409,"OFFICE_FINAL_SAVE_PENDING","正在确认上次编辑的最终保存，请稍候");
            if("UNKNOWN".equals(session.get("state")))throw new ApiFault(409,"OFFICE_REVIEW_REQUIRED","上次编辑的保存结果待核对，请返回文件详情查看协作与保存记录");
            if(!"OPEN".equals(session.get("state"))||!Boolean.TRUE.equals(session.get("valid"))||!session.get("edit_epoch").equals(doc.get("edit_epoch")))throw ApiFault.conflict("上次协作授权已失效，请在协作与保存记录中核对");
            UUID id=(UUID)session.get("id");if(db.count("SELECT count(*) FROM drive.edit_participant WHERE session_id=?",id)>=20&&db.count("SELECT count(*) FROM drive.edit_participant WHERE session_id=? AND user_id=?",id,actor.userId())==0)throw ApiFault.conflict("当前协作人数已达到20人上限");
            db.update("INSERT INTO drive.edit_participant(session_id,user_id,login_session_id,auth_version,state) VALUES(?,?,?,?,'ACTIVE') ON CONFLICT(session_id,user_id) DO UPDATE SET login_session_id=EXCLUDED.login_session_id,auth_version=EXCLUDED.auth_version,state='ACTIVE'",id,actor.userId(),actor.sessionId(),actor.authVersion());
            audit.event(actor.userId(),"DRIVE","DOCUMENT",document,"OFFICE_JOIN","SUCCEEDED",request);
            var config=Sql.map("type","desktop","documentType",ext.equals("docx")?"word":ext.equals("xlsx")?"cell":"slide","document",Sql.map("fileType",ext,"key",session.get("editor_key"),"title",doc.get("name"),"url",gateway.source(id),"permissions",Sql.map("edit",true,"download",false,"print",false,"review",false,"comment",false)),"editorConfig",Sql.map("mode","edit","lang","zh-CN","callbackUrl",gateway.callback(id),"user",Sql.map("id",actor.userId().toString(),"name",actor.user().get("name")),"customization",Sql.map("forcesave",false)),"exp",Instant.now().plusSeconds(7200).getEpochSecond());
            config.put("token",gateway.sign(config));return Sql.map("state","READY","sessionId",id,"checkpointEnabled",gateway.checkpointEnabled(),"editorScriptUrl",gateway.browserOrigin()+"/web-apps/apps/api/documents/api.js","editorConfig",config);
        });
    }
    /** 请求原生服务重发真实状态；网络调用不能持有会话行锁，否则回调无法结束旧会话。 */
    private void refreshPendingSession(UUID document){
        var row=db.one("SELECT editor_key FROM drive.edit_session WHERE document_id=? AND state IN ('CLOSING','UNKNOWN')",document);
        if(row!=null)gateway.command(Sql.map("c","info","key",row.get("editor_key")));
    }
    /** 会话查询始终重验本人的当前阅读与编辑权；完整参与者不取自回调users。 */
    public Map<String,Object> detail(Actor actor,UUID session) {
        identity.background(actor.sessionId());var row=member(actor,session);
        // 参与者离开后仍属于本批稿件来源；其中任何登录失效都不能继续显示可保存。
        if(Set.of("OPEN","CLOSING").contains(row.get("state"))&&!valid(row)){unknown(session,null,"AUTH_OR_VERSION_CHANGED");row=db.required("SELECT * FROM drive.edit_session WHERE id=?",session);}
        var result=Sql.view(row,"id,document_id,state,edit_epoch,last_version_id,next_save_seq,expires_at,row_version");
        result.put("participants",db.rows("SELECT p.user_id,u.name,p.state FROM drive.edit_participant p JOIN iam.user_account u ON u.id=p.user_id WHERE p.session_id=? ORDER BY p.joined_at",session).stream().map(r->Sql.view(r,"user_id,name,state")).toList());
        result.put("saveReceipts",receipts(actor,session));return result;
    }
    /** 一批次最多一个在途保存；同一操作号只返回原回执，不重复发命令。 */
    public Map<String,Object> checkpoint(Actor actor,UUID session,UUID operation,UUID request) {
        gateway.requireCheckpoint();
        var receipt=transaction.execute(status->{identity.lockActor(actor);member(actor,session);var row=db.required("SELECT * FROM drive.edit_session WHERE id=? FOR UPDATE",session);protection.requireWritable("DRIVE","OFFICE_SAVE");
            var prior=db.one("SELECT * FROM drive.save_receipt WHERE session_id=? AND operation_id=?",session,operation);if(prior!=null)return Sql.map("receipt",prior,"send",false);
            if(!"OPEN".equals(row.get("state"))||!valid(row))throw ApiFault.conflict("协作授权已变化或当前不可保存");
            if(db.count("SELECT count(*) FROM drive.edit_participant WHERE session_id=? AND user_id=? AND login_session_id=? AND state='ACTIVE'",session,actor.userId(),actor.sessionId())!=1)throw ApiFault.conflict("本人已退出本次编辑，请重新加入后保存");
            if(db.count("SELECT count(*) FROM drive.save_receipt WHERE session_id=? AND state IN ('REQUESTED','RECEIVING')",session)>0)throw ApiFault.conflict("上一次保存仍在处理中");
            UUID id=UUID.randomUUID();db.update("INSERT INTO drive.save_receipt(id,session_id,save_seq,operation_id,state,requested_by) VALUES(?,?,?,?,'REQUESTED',?)",id,session,row.get("next_save_seq"),operation,actor.userId());db.update("UPDATE drive.edit_session SET next_save_seq=next_save_seq+1,row_version=row_version+1 WHERE id=?",session);return Sql.map("receipt",db.required("SELECT * FROM drive.save_receipt WHERE id=?",id),"send",true);
        });
        @SuppressWarnings("unchecked") var row=(Map<String,Object>)receipt.get("receipt");UUID id=(UUID)row.get("id");
        if(Boolean.TRUE.equals(receipt.get("send"))){try{var sessionRow=db.required("SELECT editor_key FROM drive.edit_session WHERE id=?",session);var response=gateway.command(Sql.map("c","forcesave","key",sessionRow.get("editor_key"),"userdata",id.toString()));int error=((Number)response.getOrDefault("error",-1)).intValue();if(error==4)db.update("UPDATE drive.save_receipt SET state='NO_CHANGES' WHERE id=? AND state='REQUESTED'",id);else if(error!=0)unknown(session,id,"OFFICE_COMMAND_REJECTED");}catch(RuntimeException failure){unknown(session,id,"OFFICE_COMMAND_UNKNOWN");}}
        audit.event(actor.userId(),"DRIVE","EDIT_SESSION",session,"CHECKPOINT_REQUESTED","SUCCEEDED",request);return receipt(id);
    }
    /** 退出只记录本人离开，最后一人退出进入CLOSING，最终保存另待原生回调确认。 */
    public Map<String,Object> leave(Actor actor,UUID session) {return transaction.execute(status->{identity.lockActor(actor);member(actor,session);db.required("SELECT id FROM drive.edit_session WHERE id=? FOR UPDATE",session);db.update("UPDATE drive.edit_participant SET state='LEFT' WHERE session_id=? AND user_id=? AND state='ACTIVE'",session,actor.userId());db.update("UPDATE drive.edit_session SET state='CLOSING',row_version=row_version+1 WHERE id=? AND state='OPEN' AND NOT EXISTS(SELECT 1 FROM drive.edit_participant WHERE session_id=? AND state='ACTIVE')",session,session);return Sql.view(db.required("SELECT * FROM drive.edit_session WHERE id=?",session),"id,state,row_version");});}
    /** 回调已经完成签名验证；原生users不作为完整参与者，也不能改变本地权限。 */
    public int callback(UUID id,Map<String,Object> payload) {
        gateway.require();String key=Inputs.text(payload,"key",100);int status=Inputs.integer(payload.get("status"),0,1,7);var original=db.required("SELECT * FROM drive.edit_session WHERE id=? AND editor_key=?",id,key);
        if(status==1)return 0;
        if(status==4){closeUnchanged(id);return 0;}
        if(!Set.of(2,3,6,7).contains(status))throw ApiFault.invalid("不支持的Office回调状态");
        boolean finalSave=status==2||status==3;UUID receiptId,lastEditor=finalSave?lastEditor(id,payload,(UUID)original.get("created_by")):null;
        if(finalSave){receiptId=transaction.execute(tx->{var session=db.required("SELECT * FROM drive.edit_session WHERE id=? FOR UPDATE",id);String event=Crypto.hash(key+":FINAL");var prior=db.one("SELECT id FROM drive.save_receipt WHERE session_id=? AND event_key=?",id,event);if(prior!=null)return (UUID)prior.get("id");if(db.count("SELECT count(*) FROM drive.save_receipt WHERE session_id=? AND state IN ('REQUESTED','RECEIVING')",id)>0)throw ApiFault.conflict("保存点尚未确认，请重试最终保存回调");UUID created=UUID.randomUUID();db.update("INSERT INTO drive.save_receipt(id,session_id,save_seq,operation_id,state,event_key,requested_by) VALUES(?,?,?,?,'REQUESTED',?,?)",created,id,session.get("next_save_seq"),UUID.randomUUID(),event,lastEditor);db.update("UPDATE drive.edit_session SET next_save_seq=next_save_seq+1 WHERE id=?",id);return created;});}
        else {receiptId=Inputs.uuid(payload.get("userdata"));db.required("SELECT id FROM drive.save_receipt WHERE id=? AND session_id=?",receiptId,id);}
        var prior=receipt(receiptId);String state=(String)prior.get("state");if(Set.of("SAVED","RESOLVED","REJECTED").contains(state)||("UNKNOWN".equals(state)&&prior.get("objectId")!=null))return 0;
        if(db.update("UPDATE drive.save_receipt SET state='RECEIVING' WHERE id=? AND state IN ('REQUESTED','UNKNOWN') AND object_id IS NULL",receiptId)!=1)return 1;
        if(payload.get("url")==null){unknown(id,receiptId,"OFFICE_SAVE_FAILED");return 0;}
        Path candidate=null;
        try {
            var doc=db.required("SELECT * FROM drive.document WHERE id=?",original.get("document_id"));UUID service=service(doc);protection.requireWritable("DRIVE","OFFICE_CANDIDATE");
            candidate=gateway.candidate(Inputs.text(payload,"url",8192));String ext=DriveLifecycle.extension((String)doc.get("name")),media=FileMediaCheck.detect(candidate,"candidate."+ext,"application/octet-stream");
            ClamFileScanner.Verdict verdict=scanner.scan(candidate);long size=Files.size(candidate);String hash=hash(candidate);String stored;
            try(var input=Files.newInputStream(candidate)){stored=files.get().receive(input,size,hash);}UUID object=UUID.randomUUID();boolean clean=media!=null&&verdict==ClamFileScanner.Verdict.CLEAN;
            transaction.executeWithoutResult(tx->{
                // 先按稳定顺序锁所有参与者，再锁文档与会话，与撤权写入保持一致的顺序。
                db.rows("SELECT u.id FROM iam.user_account u JOIN drive.edit_participant p ON p.user_id=u.id WHERE p.session_id=? ORDER BY u.id FOR SHARE OF u",id);
                var currentDoc=db.required("SELECT * FROM drive.document WHERE id=? FOR UPDATE",original.get("document_id"));var session=db.required("SELECT * FROM drive.edit_session WHERE id=? FOR UPDATE",id);service(currentDoc);protection.requireWritable("DRIVE","OFFICE_CANDIDATE");
                db.update("INSERT INTO storage.object(id,module,storage_key,sha256,byte_size,media_type,original_name,object_kind,state,service_identity_id,check_result_ref) VALUES(?,'DRIVE',?,?,?,?,?,'ORIGINAL',?,?,?)",object,stored,hash,size,media==null?"application/octet-stream":media,"candidate."+ext,clean?"READY":"QUARANTINED",service,"OFFICE_SCAN:"+receiptId);
                db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) VALUES(?,?,'OFFICE_CANDIDATE',?,'REVIEW')",UUID.randomUUID(),object,receiptId);
                db.update("UPDATE drive.save_receipt SET object_id=? WHERE id=?",object,receiptId);
                // 负责人可能在接收期间明确舍弃；仍保留收到的对象，但不得再发布。
                if(!"RECEIVING".equals(db.required("SELECT state FROM drive.save_receipt WHERE id=? FOR UPDATE",receiptId).get("state")))return;
                if(!clean||status==3||status==7||!valid(session)||!session.get("last_version_id").equals(currentDoc.get("current_version_id"))){unknown(id,receiptId,!clean?"CANDIDATE_CHECK_FAILED":"AUTH_OR_VERSION_CHANGED");return;}
                Actor author=identity.background((UUID)db.required("SELECT p.login_session_id FROM drive.edit_participant p JOIN drive.save_receipt r ON r.session_id=p.session_id AND r.requested_by=p.user_id WHERE r.id=?",receiptId).get("login_session_id"));
                UUID version=lifecycle.version(author,currentDoc,object,(UUID)session.get("last_version_id"),"OFFICE_SAVE");db.update("UPDATE drive.document SET row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",currentDoc.get("id"));
                db.update("UPDATE drive.save_receipt SET state='SAVED',published_version_id=?,result_code=NULL WHERE id=?",version,receiptId);db.update("UPDATE drive.edit_session SET last_version_id=?,state=?,row_version=row_version+1 WHERE id=?",version,finalSave?"CLOSED":session.get("state"),id);
                audit.event(author.userId(),"DRIVE","DOCUMENT",(UUID)currentDoc.get("id"),"OFFICE_VERSION_SAVED","SUCCEEDED",receiptId);
            });return 0;
        }catch(Exception failure){unknown(id,receiptId,"CANDIDATE_RECEIVE_FAILED");return 1;}finally{if(candidate!=null)try{Files.deleteIfExists(candidate);}catch(IOException ignored){/* 候选仅删除本次随机临时文件，失败由隔离目录运维处理。 */}}
    }
    /** 签名回调已确认没有修改，结束会话无需旧登录继续有效；任何未确认稿件仍阻断关闭。 */
    private void closeUnchanged(UUID id){transaction.executeWithoutResult(tx->{
        var row=db.required("SELECT * FROM drive.edit_session WHERE id=? FOR UPDATE",id);
        if(!Set.of("OPEN","CLOSING","UNKNOWN").contains(row.get("state")))return;
        if(db.count("SELECT count(*) FROM drive.save_receipt WHERE session_id=? AND state IN ('REQUESTED','RECEIVING','UNKNOWN')",id)>0){unknown(id,null,"FINAL_SAVE_UNKNOWN");return;}
        db.update("UPDATE drive.edit_participant SET state='LEFT' WHERE session_id=? AND state='ACTIVE'",id);
        db.update("UPDATE drive.edit_session SET state='CLOSED',row_version=row_version+1 WHERE id=?",id);
        audit.event(null,"DRIVE","EDIT_SESSION",id,"OFFICE_CLOSED_NO_CHANGES","SUCCEEDED",UUID.randomUUID());
    });}
    /** 服务取原件每次重验全部参与者与当前代次；浏览器没有服务器outbox签名不能调用。 */
    public Map<String,Object> source(UUID id,Map<String,Object> payload) {
        if(!gateway.source(id).equals(payload.get("url")))throw ApiFault.denied();var session=db.required("SELECT * FROM drive.edit_session WHERE id=?",id);if(!valid(session))throw ApiFault.denied();
        var doc=db.required("SELECT * FROM drive.document WHERE id=?",session.get("document_id"));service(doc);protection.requireReadable("DRIVE");return db.required("SELECT o.* FROM drive.document_version v JOIN storage.object o ON o.id=v.object_id WHERE v.id=? AND o.state='READY'",session.get("base_version_id"));
    }
    /** 仅服务端调用，流式交付时持续核权，内部路径不回传浏览器。 */
    public void streamSource(UUID id,Map<String,Object> payload,OutputStream output) throws IOException {var object=source(id,payload);Path file=files.get().verifiedPrimary((String)object.get("storage_key"),((Number)object.get("byte_size")).longValue(),(String)object.get("sha256"));try(var input=Files.newInputStream(file)){byte[] block=new byte[1048576];int count;while((count=input.read(block))!=-1){source(id,payload);output.write(block,0,count);}}}
    /** 回执只展示平台已确认状态，不把编辑器本地同步提示当成保存成功。 */
    public List<Map<String,Object>> receipts(Actor actor,UUID session){member(actor,session);return db.rows("SELECT * FROM drive.save_receipt WHERE session_id=? ORDER BY save_seq DESC LIMIT 100",session).stream().map(this::receiptView).toList();}
    /** 单条回执返回白名单，不暴露候选下载URL、签名或内部存储键。 */
    private Map<String,Object> receipt(UUID id){return receiptView(db.required("SELECT * FROM drive.save_receipt WHERE id=?",id));}
    private Map<String,Object> receiptView(Map<String,Object> row){return Sql.view(row,"id,session_id,save_seq,state,object_id,published_version_id,result_code,created_at");}
    /** 原生最终回调users仅表示最后编辑者，用于署名；完整授权名单始终保留在平台。 */
    private UUID lastEditor(UUID session,Map<String,Object> payload,UUID fallback) {
        if(payload.get("users")==null)return fallback;
        if(!(payload.get("users") instanceof List<?> users)||users.isEmpty())throw ApiFault.invalid("最终保存缺少编辑者");
        UUID author=Inputs.uuid(users.get(0));
        if(db.count("SELECT count(*) FROM drive.edit_participant WHERE session_id=? AND user_id=?",session,author)!=1)throw ApiFault.denied();
        return author;
    }
    /** 完整参与者任一撤权、禁用、会话失效或文档代次变化，都不能提交为正式版本。 */
    private boolean valid(Map<String,Object> session) {
        if(!Set.of("OPEN","CLOSING").contains(session.get("state")))return false;
        return db.count("SELECT count(*) FROM drive.edit_session s JOIN drive.document d ON d.id=s.document_id WHERE s.id=? AND s.expires_at>CURRENT_TIMESTAMP AND s.edit_epoch=d.edit_epoch AND d.status='ACTIVE' AND d.collaboration_enabled AND EXISTS(SELECT 1 FROM drive.edit_participant WHERE session_id=s.id) AND NOT EXISTS(SELECT 1 FROM drive.edit_participant p JOIN iam.user_account u ON u.id=p.user_id JOIN iam.session login ON login.id=p.login_session_id WHERE p.session_id=s.id AND (p.state='REVOKED' OR u.status<>'ACTIVE' OR u.auth_version<>p.auth_version OR login.revoked_at IS NOT NULL OR login.expires_at<=CURRENT_TIMESTAMP OR login.last_activity_at<=CURRENT_TIMESTAMP-interval '30 minutes' OR login.auth_version<>u.auth_version OR login.session_version<>u.session_version OR NOT drive.can_document(p.user_id,d.id,'EDIT') OR NOT drive.can_document(p.user_id,d.id,'VIEW')))",session.get("id"))==1;
    }
    /** 当前本人参与关系与当前文档授权都必须有效，不能借会话ID读取别人的稿件。 */
    private Map<String,Object> member(Actor actor,UUID id){var row=db.required("SELECT s.* FROM drive.edit_session s JOIN drive.edit_participant p ON p.session_id=s.id WHERE s.id=? AND p.user_id=?",id,actor.userId());policy.requireDocument(actor,(UUID)row.get("document_id"),"VIEW");policy.requireDocument(actor,(UUID)row.get("document_id"),"EDIT");return row;}
    /** 独立服务身份按文档发布部门约束，不能仅凭全局JWT跨部门读取。 */
    private UUID service(Map<String,Object> doc){return services.require(gateway.service(),"OFFICE","DRIVE",(UUID)doc.get("publishing_department_id"),"EDIT");}
    /** 不确定结果必须留下待处理状态；保留已收到候选，不自动覆盖正式版本。 */
    private void unknown(UUID session,UUID receipt,String code){if(receipt!=null)db.update("UPDATE drive.save_receipt SET state='UNKNOWN',result_code=? WHERE id=? AND state IN ('REQUESTED','RECEIVING')",code,receipt);db.update("UPDATE drive.edit_session SET state='UNKNOWN',row_version=row_version+1 WHERE id=? AND state IN ('OPEN','CLOSING')",session);}
    /** 流式摘要不随候选文件大小增加内存占用。 */
    private String hash(Path file) throws Exception {var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] block=new byte[65536];int count;while((count=input.read(block))!=-1)digest.update(block,0,count);}return HexFormat.of().formatHex(digest.digest());}
}
