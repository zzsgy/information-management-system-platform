package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.text.Normalizer;
import java.util.*;

/** 公盘发布和查询的最小业务闭环；检测通过、上传完成和正式发布分别提交。 */
@Service
public class DriveDocumentService {
    private final Sql db;private final IdentityService identity;private final DriveFilePolicy policy;private final StorageBoundaries boundaries;private final StorageFiles files;private final Audit audit;
    private final TransactionTemplate transaction;
    public DriveDocumentService(Sql db,IdentityService identity,DriveFilePolicy policy,StorageBoundaries boundaries,StorageFiles files,Audit audit,PlatformTransactionManager manager) {
        this.db=db;this.identity=identity;this.policy=policy;this.boundaries=boundaries;this.files=files;this.audit=audit;this.transaction=new TransactionTemplate(manager);
    }
    /** 文件校验在事务外，发布前再核验原目录、当前上传权、干净对象和保护状态。 */
    public Map<String,Object> publish(Actor actor,Map<String,Object> body,UUID operation,UUID request) {
        Inputs.only(body,"folderId","uploadSessionId","name"); UUID folder=Inputs.uuid(body.get("folderId")),upload=Inputs.uuid(body.get("uploadSessionId")); String name=Inputs.text(body,"name",512);
        if(name.indexOf('/')>=0||name.indexOf('\\')>=0||name.chars().anyMatch(Character::isISOControl)) throw ApiFault.invalid("文档名称不合法");
        policy.requireFolder(actor,folder,"UPLOAD"); boundaries.requireWritable("DRIVE","PUBLISH");
        var object=cleanUpload(actor,folder,upload);
        if(!files.get().verified((String)object.get("storage_key"),((Number)object.get("byte_size")).longValue(),(String)object.get("sha256"))) throw ApiFault.protection();
        return transaction.execute(status->{
            identity.lockActor(actor); policy.requireFolder(actor,folder,"UPLOAD");boundaries.requireWritable("DRIVE","PUBLISH");
            String key="DRIVE_PUBLISH:"+operation,digest=Crypto.hash(Crypto.json(body));
            db.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor.userId()+":"+key);
            var receipt=audit.receipt(actor.userId(),key);
            if(receipt!=null) {
                if(!Crypto.equal(digest,(String)receipt.get("request_digest"))) throw ApiFault.conflict("同一发布操作号不能用于不同内容");
                Map<?,?> saved=Crypto.JSON.readValue((String)receipt.get("operation_result"),Map.class);return detail(actor,Inputs.uuid(saved.get("objectId")));
            }
            var current=cleanUpload(actor,folder,upload); if(!current.get("id").equals(object.get("id"))) throw ApiFault.conflict("上传对象发生变化");
            var target=db.required("SELECT * FROM drive.folder WHERE id=? FOR SHARE",folder);
            if(target.get("department_id")==null) throw ApiFault.denied(); // 公司共享根不能产生无发布部门的内容。
            UUID document=UUID.randomUUID(),version=UUID.randomUUID();String nameKey=Normalizer.normalize(name,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            db.update("INSERT INTO drive.document(id,folder_id,publishing_department_id,uploaded_by,name,name_key) VALUES(?,?,?,?,?,?)",document,folder,target.get("department_id"),actor.userId(),name,nameKey);
            db.update("INSERT INTO drive.document_version(id,document_id,version_no,object_id,source_type,created_by) VALUES(?,?,1,?,'LOCAL_UPLOAD',?)",version,document,object.get("id"),actor.userId());
            db.update("UPDATE drive.document SET current_version_id=? WHERE id=?",version,document);
            db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) VALUES(?,?,'DRIVE_VERSION',?,'BUSINESS')",UUID.randomUUID(),object.get("id"),version);
            audit.event(actor.userId(),"DRIVE","DOCUMENT",document,"PUBLISH","SUCCEEDED",request);
            db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,resource_id,action,result,request_id,record_kind,operation_key,request_digest,operation_result) VALUES(?,?,CURRENT_TIMESTAMP,?,'DRIVE','DOCUMENT',?,'PUBLISH','SUCCEEDED',?,'IDEMPOTENCY_RECEIPT',?,?,?::jsonb)",UUID.randomUUID(),UUID.randomUUID(),actor.userId(),document,request,key,digest,Crypto.json(Sql.map("objectId",document)));
            return detail(actor,document);
        });
    }
    /** 正式版本必须来自本人、同一目录、同一模块并已完成真实检测。 */
    private Map<String,Object> cleanUpload(Actor actor,UUID folder,UUID upload) {
        return db.required("SELECT o.* FROM storage.upload_session s JOIN storage.object o ON o.id=s.object_id WHERE s.id=? AND s.uploader_id=? AND s.module='DRIVE' AND s.purpose='DRIVE_FILE' AND s.business_context_id=? AND s.state='COMPLETE' AND o.state='READY' AND o.check_result_ref IS NOT NULL",upload,actor.userId(),folder);
    }
    /** 返回受权当前版本，不含实体路径、服务凭据和内部存储键。 */
    public Map<String,Object> detail(Actor actor,UUID document) {
        var doc=policy.requireDocument(actor,document,"VIEW");
        var version=db.required("SELECT v.id,v.version_no,o.media_type,o.byte_size,o.sha256 FROM drive.document_version v JOIN storage.object o ON o.id=v.object_id WHERE v.id=? AND v.document_id=? AND o.state='READY'",doc.get("current_version_id"),document);
        var result=Sql.view(doc,"id,folder_id,publishing_department_id,uploaded_by,name,current_version_id,status,row_version,collaboration_enabled,created_at,updated_at");
        result.put("uploadedByName",db.required("SELECT name FROM iam.user_account WHERE id=?",doc.get("uploaded_by")).get("name"));
        result.put("currentVersion",Sql.view(version,"id,version_no,media_type,byte_size,sha256"));result.put("actions",actions(actor,document));return result;
    }
    /** 当前动作仅帮助电脑端显示按钮，实际写入和下载仍重新授权。 */
    public List<String> actions(Actor actor,UUID document) {return db.rows("SELECT act FROM unnest(ARRAY['DOWNLOAD','EDIT','DELETE','MOVE','COLLABORATE']) AS a(act) WHERE drive.can_document(?,?,act)",actor.userId(),document).stream().map(r->(String)r.get("act")).toList();}
    /** 文件描述联合筛选在权限过滤后、分页前执行，目录名称也遵守独立目录可见范围。 */
    public Map<String,Object> documents(Actor actor,Map<String,String> query) {
        actor.require("DRIVE.ACCESS");Inputs.only(query,"folderId","page","pageSize","keyword","departmentId","fileType","uploader","folderName","uploadedFrom","uploadedTo","space");
        UUID folder=query.get("folderId")==null?null:Inputs.uuid(query.get("folderId"));if(folder!=null)policy.requireFolder(actor,folder,"VIEW");
        UUID department=query.get("departmentId")==null?null:Inputs.uuid(query.get("departmentId"));
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);String keyword=query.getOrDefault("keyword",""),type=query.getOrDefault("fileType","").toLowerCase(Locale.ROOT);
        String uploader=query.getOrDefault("uploader","").trim(),folderName=query.getOrDefault("folderName","").trim();
        if(keyword.length()>200||uploader.length()>100||folderName.length()>128||!type.matches("[a-z0-9]{0,12}"))throw ApiFault.invalid("检索条件不合法");
        var dates=ListDateRange.parse(query,"uploadedFrom","uploadedTo");
        String prefix="WITH visible AS MATERIALIZED (SELECT * FROM drive.visible_documents(?,?)) ";
        List<Object> parameters=new ArrayList<>();
        Collections.addAll(parameters,actor.userId(),folder,keyword,department,department,type,type,uploader,uploader,folderName,actor.userId(),folderName,dates.from(),dates.from(),dates.until(),dates.until());
        String where=" FROM visible d JOIN drive.document_version v ON v.id=d.current_version_id AND v.document_id=d.id JOIN storage.object o ON o.id=v.object_id AND o.state='READY' JOIN iam.user_account uploader ON uploader.id=d.uploaded_by JOIN drive.folder f ON f.id=d.folder_id WHERE position(lower(?) in lower(d.name))>0 AND (?::uuid IS NULL OR d.publishing_department_id=?) AND (?='' OR lower(d.name) LIKE '%.'||?) AND (?='' OR position(lower(?) in lower(uploader.name||' '||uploader.employee_no))>0) AND (?='' OR (drive.can_folder(?,d.folder_id,'VIEW') AND position(lower(?) in lower(f.name))>0)) AND (?::timestamptz IS NULL OR d.created_at>=?) AND (?::timestamptz IS NULL OR d.created_at<?)"+DriveSpace.from(query).condition(actor,parameters);
        long total=db.count(prefix+"SELECT count(*)"+where,parameters.toArray());
        Collections.addAll(parameters,size,(page-1)*size);
        // 仅对当前页补充人员及目录信息；单文件授权不能顺带公开未授权的目录名。
        parameters.add(actor.userId());
        var rows=db.rows(prefix+", listed AS (SELECT d.*,v.id AS version_id,v.version_no,o.media_type,o.byte_size,o.sha256"+where+" ORDER BY d.updated_at DESC,d.id LIMIT ? OFFSET ?) SELECT p.*,u.name AS uploaded_by_name,CASE WHEN drive.can_folder(?,p.folder_id,'VIEW') THEN f.name END AS folder_name FROM listed p JOIN iam.user_account u ON u.id=p.uploaded_by JOIN drive.folder f ON f.id=p.folder_id ORDER BY p.updated_at DESC,p.id",parameters.toArray());
        return Sql.map("items",rows.stream().map(row->{var item=Sql.view(row,"id,folder_id,folder_name,publishing_department_id,uploaded_by,uploaded_by_name,name,current_version_id,status,row_version,collaboration_enabled,created_at,updated_at");item.put("currentVersion",Sql.map("id",row.get("version_id"),"versionNo",row.get("version_no"),"mediaType",row.get("media_type"),"byteSize",row.get("byte_size"),"sha256",row.get("sha256")));return item;}).toList(),"page",page,"pageSize",size,"total",total,"hasNext",(long)page*size<total);
    }
}
