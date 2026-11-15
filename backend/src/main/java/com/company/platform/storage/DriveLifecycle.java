package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.*;

/** 公盘不可变版本、回收站与原件交付；逻辑清理不绕过对象引用或备份保留。 */
@Service
public class DriveLifecycle {
    /** 内容写入、领域授权、双副本和当前身份分别通过已有边界。 */
    private final AuditSecurity security; private final Sql db; private final DriveCommands commands; private final DriveFilePolicy policy; private final StorageFiles files;
    private final IdentityService identity; private final StorageBoundaries protection; private final Audit audit; private final TransactionTemplate transaction;
    public DriveLifecycle(AuditSecurity security,Sql db,DriveCommands commands,DriveFilePolicy policy,StorageFiles files,IdentityService identity,StorageBoundaries protection,Audit audit,PlatformTransactionManager manager) {
        this.security=security;this.db=db;this.commands=commands;this.policy=policy;this.files=files;this.identity=identity;this.protection=protection;this.audit=audit;this.transaction=new TransactionTemplate(manager);
    }
    /** 规范化只用于名称唯一性；保留用户展示名称但拒绝路径和控制字符。 */
    public static String name(Map<String,Object> body) {String name=Inputs.text(body,"name",512);if(name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl)) throw ApiFault.invalid("文件名称不合法");return name;}
    /** 同目录名称按兼容字符折叠与小写去重。 */
    public static String nameKey(String name) {return Normalizer.normalize(name,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    /** 重命名不改变原件内容或最初上传者；协作开关单独走负责人动作。 */
    public Map<String,Object> rename(Commands.Context ctx,UUID id,Map<String,Object> body) {
        Inputs.only(body,"name","reason");String name=name(body);Inputs.text(body,"reason",1000);
        return commands.run(ctx,id,"EDIT",body,doc->{active(doc);String before=(String)doc.get("name");if(!extension(before).equals(extension(name))) throw ApiFault.invalid("重命名不能修改文件扩展名");db.update("UPDATE drive.document SET name=?,name_key=? WHERE id=?",name,nameKey(name),id);return doc;});
    }
    /** 协作仅由当前有效委托负责人开启，普通编辑者不能再授权。 */
    public Map<String,Object> collaboration(Commands.Context ctx,UUID id,Map<String,Object> body) {
        Inputs.only(body,"collaborationEnabled","reason");Inputs.text(body,"reason",1000);if(!(body.get("collaborationEnabled") instanceof Boolean enabled)) throw ApiFault.invalid("协作开关必须为布尔值");
        return commands.run(ctx,id,"COLLABORATE",body,doc->{active(doc);if(!Set.of("doc","docx","xls","xlsx","ppt","pptx").contains(extension((String)doc.get("name")))) throw ApiFault.invalid("仅Office文档可启用协作");db.update("UPDATE drive.document SET collaboration_enabled=? WHERE id=?",enabled,id);return doc;});
    }
    /** 新版本必须使用文档级 EDIT 上传上下文，上传目录的权限不能替代编辑权限。 */
    public Map<String,Object> append(Commands.Context ctx,UUID id,Map<String,Object> body) {
        Inputs.only(body,"uploadSessionId","reason");Inputs.text(body,"reason",1000);UUID upload=Inputs.uuid(body.get("uploadSessionId"));policy.requireDocument(ctx.actor(),id,"EDIT");
        var object=upload(ctx.actor(),id,upload);verify(object);
        return commands.run(ctx,id,"EDIT",body,doc->{active(doc);var current=upload(ctx.actor(),id,upload);if(!current.get("id").equals(object.get("id"))) throw ApiFault.conflict("上传对象已经变化");
            if(!extension((String)doc.get("name")).equals(extension((String)current.get("original_name")))) throw ApiFault.invalid("新版本必须与文档格式一致");
            version(ctx.actor(),doc,(UUID)current.get("id"),(UUID)doc.get("current_version_id"),"LOCAL_UPLOAD");return doc;});
    }
    /** 历史恢复追加新版本并保留来源，不覆盖中间版本或修改历史摘要。 */
    public Map<String,Object> restoreVersion(Commands.Context ctx,UUID id,Map<String,Object> body) {
        Inputs.only(body,"sourceVersionId","reason");Inputs.text(body,"reason",1000);UUID source=Inputs.uuid(body.get("sourceVersionId"));policy.requireDocument(ctx.actor(),id,"EDIT");
        var object=object(id,source);verify(object);
        return commands.run(ctx,id,"EDIT",body,doc->{active(doc);var current=object(id,source);version(ctx.actor(),doc,(UUID)current.get("id"),source,"HISTORY_RESTORE");String name=versionName((String)doc.get("name"),(String)current.get("original_name"));db.update("UPDATE drive.document SET name=?,name_key=? WHERE id=?",name,nameKey(name),id);return doc;});
    }
    /** 已持有文档锁后创建单调递增版本号，所有版本分别保留对象引用。 */
    public UUID version(Actor actor,Map<String,Object> doc,UUID object,UUID parent,String source) {
        UUID id=UUID.randomUUID(),document=(UUID)doc.get("id");long number=db.count("SELECT COALESCE(max(version_no),0)+1 FROM drive.document_version WHERE document_id=?",document);
        db.update("INSERT INTO drive.document_version(id,document_id,version_no,object_id,parent_version_id,source_type,created_by) VALUES(?,?,?,?,?,?,?)",id,document,number,object,parent,source,actor.userId());
        db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) VALUES(?,?,'DRIVE_VERSION',?,'BUSINESS')",UUID.randomUUID(),object,id);
        db.update("UPDATE drive.document SET current_version_id=? WHERE id=?",id,document);return id;
    }
    /** 历史详情分页使用当前文档VIEW权限；下载仍另外检查DOWNLOAD。 */
    public Map<String,Object> versions(Actor actor,UUID id,Map<String,String> query) {
        policy.requireDocument(actor,id,"VIEW");Inputs.only(query,"page","pageSize");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);
        long total=db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",id);
        var rows=db.rows("SELECT v.*,u.name AS created_by_name,o.byte_size,o.media_type,o.sha256 FROM drive.document_version v JOIN iam.user_account u ON u.id=v.created_by JOIN storage.object o ON o.id=v.object_id WHERE v.document_id=? ORDER BY v.version_no DESC LIMIT ? OFFSET ?",id,size,(page-1)*size);
        return Sql.map("items",rows.stream().map(v->Sql.view(v,"id,version_no,parent_version_id,source_type,created_by,created_by_name,created_at,byte_size,media_type,sha256")).toList(),"page",page,"pageSize",size,"total",total,"hasNext",(long)page*size<total);
    }
    /** 移动前向操作者展示来源与目标分区，摘要绑定目录版本及其授权配置。 */
    public Map<String,Object> moveCheck(Actor actor,UUID id,UUID target) {
        var doc=policy.requireDocument(actor,id,"MOVE");var folder=policy.requireFolder(actor,target,"UPLOAD");
        if(!doc.get("publishing_department_id").equals(folder.get("department_id"))) throw ApiFault.invalid("文档须保留发布部门，请选择同部门目录");
        if(target.equals(doc.get("folder_id"))) throw ApiFault.invalid("目标目录与当前目录相同");
        var source=db.required("SELECT zone,name FROM drive.folder WHERE id=?",doc.get("folder_id"));
        String digest=Crypto.hash(Crypto.json(Sql.map("document",id,"version",doc.get("row_version"),"target",target,"targetVersion",folder.get("row_version"),"rules",db.rows("SELECT id,version,state FROM drive.resource_grant WHERE resource_type='FOLDER' AND resource_id IN (SELECT id FROM drive.active_path(?) UNION SELECT id FROM drive.active_path(?)) ORDER BY id",target,doc.get("folder_id")),"delegations",db.rows("SELECT id,version,state FROM drive.delegation WHERE department_id=? ORDER BY id",doc.get("publishing_department_id")))));
        return Sql.map("sourceZone",source.get("zone"),"targetZone",folder.get("zone"),"targetName",folder.get("name"),"visibilityMayChange",true,"confirmationDigest",digest);
    }
    /** 真正移动前重算确认摘要；文档ID、上传人、发布部门和完整历史保持不变。 */
    public Map<String,Object> move(Commands.Context ctx,UUID id,Map<String,Object> body) {
        Inputs.only(body,"targetFolderId","confirmationDigest","reason");Inputs.text(body,"reason",1000);UUID target=Inputs.uuid(body.get("targetFolderId"));String digest=Inputs.text(body,"confirmationDigest",64);
        return commands.run(ctx,id,"MOVE",body,doc->{active(doc);if(!Crypto.equal(digest,(String)moveCheck(ctx.actor(),id,target).get("confirmationDigest"))) throw ApiFault.conflict("目录或权限已变化，请重新确认可见范围");db.update("UPDATE drive.document SET folder_id=? WHERE id=?",target,id);return doc;});
    }
    /** 进入回收站先失效所有旧页面；不删除版本或物理副本。 */
    public Map<String,Object> recycle(Commands.Context ctx,UUID id,Map<String,Object> body) {
        Inputs.only(body,"reason");Inputs.text(body,"reason",1000);
        return commands.run(ctx,id,"DELETE",body,doc->{active(doc);db.update("UPDATE drive.document SET status='RECYCLED',deleted_at=CURRENT_TIMESTAMP,deleted_by=? WHERE id=?",ctx.actor().userId(),id);return doc;});
    }
    /** 回收恢复需要独立RESTORE动作；员工仅本人上传，负责人可恢复委托内文件。 */
    public Map<String,Object> restore(Commands.Context ctx,UUID id,Map<String,Object> body) {
        Inputs.only(body,"reason");Inputs.text(body,"reason",1000);
        return commands.run(ctx,id,"RESTORE",body,doc->{recycled(doc);db.update("UPDATE drive.document SET status='ACTIVE',deleted_at=NULL,deleted_by=NULL WHERE id=?",id);return doc;});
    }
    /** 永久清理只删除业务可见性；备份与历史引用仍保留，不能据此声称物理抹除。 */
    public Map<String,Object> purge(Commands.Context ctx,UUID id,Map<String,Object> body) {
        Inputs.only(body,"reason");Inputs.text(body,"reason",1000);
        return commands.run(ctx,id,"PURGE",body,doc->{recycled(doc);if(db.count("SELECT count(*) FROM drive.document WHERE id=? AND deleted_at<=CURRENT_TIMESTAMP-interval '30 days'",id)!=1) throw ApiFault.conflict("回收保留期为30天，请保留至到期后再清理");db.update("UPDATE drive.document SET status='PURGED' WHERE id=?",id);return doc;});
    }
    /** 回收站的统计只覆盖当前允许恢复或清理的文件；到期只标记待处理。 */
    public Map<String,Object> recycleList(Actor actor,Map<String,String> query) {
        actor.require("DRIVE.ACCESS");Inputs.only(query,"page","pageSize","keyword","deletedFrom","deletedTo","retention");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);
        String keyword=query.getOrDefault("keyword","");if(keyword.length()>200)throw ApiFault.invalid("名称超过长度限制");
        String retention=Inputs.choice(query.getOrDefault("retention",""),"","RETAINED","ELAPSED");var dates=ListDateRange.parse(query,"deletedFrom","deletedTo");
        String where=" FROM drive.document d WHERE status='RECYCLED' AND (drive.can_document(?,d.id,'RESTORE') OR drive.can_document(?,d.id,'PURGE')) AND position(lower(?) in lower(d.name))>0 AND (?::timestamptz IS NULL OR deleted_at>=?) AND (?::timestamptz IS NULL OR deleted_at<?) AND (?='' OR (deleted_at<=CURRENT_TIMESTAMP-interval '30 days')=(?='ELAPSED'))";
        Object[] args={actor.userId(),actor.userId(),keyword,dates.from(),dates.from(),dates.until(),dates.until(),retention,retention};
        long total=db.count("SELECT count(*)"+where,args);List<Object> parameters=new ArrayList<>(List.of(actor.userId(),actor.userId()));parameters.addAll(Arrays.asList(args));parameters.add(size);parameters.add((page-1)*size);
        var rows=db.rows("SELECT d.*,deleted_at<=CURRENT_TIMESTAMP-interval '30 days' AS retention_elapsed,drive.can_document(?,d.id,'RESTORE') AS can_restore,drive.can_document(?,d.id,'PURGE') AS can_purge"+where+" ORDER BY deleted_at DESC,id LIMIT ? OFFSET ?",parameters.toArray());
        return Sql.map("items",rows.stream().map(r->Sql.view(r,"id,name,folder_id,row_version,deleted_at,retention_elapsed,can_restore,can_purge")).toList(),"page",page,"pageSize",size,"total",total,"hasNext",(long)page*size<total);
    }
    /** 下载元数据与实际流传输都检查独立权限；历史版本不能复用旧授权。 */
    public Map<String,Object> download(Actor actor,UUID document,UUID version) {
        security.requireAvailable(actor.userId(),"BULK_DOWNLOAD");
        var doc=policy.requireDocument(actor,document,"DOWNLOAD");protection.requireReadable("DRIVE");var object=object(document,version==null?(UUID)doc.get("current_version_id"):version);object.put("document_name",versionName((String)doc.get("name"),(String)object.get("original_name")));return object;
    }
    /** 以1MiB缓冲传输，逐块重验当前登录、撤权、文档状态与保护机制；不把5GiB原件装入内存。 */
    public void stream(Actor actor,UUID document,UUID version,long offset,long length,OutputStream output,UUID request) throws IOException {
        var object=download(actor,document,version);var source=files.get().verifiedPrimary((String)object.get("storage_key"),((Number)object.get("byte_size")).longValue(),(String)object.get("sha256"));
        try(var channel=FileChannel.open(source,StandardOpenOption.READ)) {
            channel.position(offset);byte[] buffer=new byte[1024*1024];long remaining=length;
            while(remaining>0) {
                int read=channel.read(ByteBuffer.wrap(buffer,0,(int)Math.min(buffer.length,remaining)));if(read<0) throw new IOException("原件长度发生变化");
                transaction.executeWithoutResult(status->{identity.lockActor(actor);download(actor,document,version);});
                output.write(buffer,0,read);remaining-=read;
            }
        }
        audit.event(actor.userId(),"DRIVE","DOCUMENT",document,"DOWNLOAD_DELIVERED","SUCCEEDED",request);
    }
    /** 正式对象必须处于READY并有真实检测结果。 */
    private Map<String,Object> object(UUID document,UUID version) {return db.required("SELECT o.*,v.id AS version_id FROM drive.document_version v JOIN storage.object o ON o.id=v.object_id WHERE v.document_id=? AND v.id=? AND o.state='READY' AND o.check_result_ref IS NOT NULL",document,version);}
    /** 上传上下文固定本人、文档和版本用途，避免把其他目录文件拼接成新版本。 */
    private Map<String,Object> upload(Actor actor,UUID document,UUID upload) {return db.required("SELECT o.* FROM storage.upload_session s JOIN storage.object o ON o.id=s.object_id WHERE s.id=? AND s.uploader_id=? AND s.module='DRIVE' AND s.purpose='DRIVE_VERSION' AND s.business_context_id=? AND s.state='COMPLETE' AND o.state='READY' AND o.check_result_ref IS NOT NULL",upload,actor.userId(),document);}
    /** 慢摘要校验位于内容写事务之前，避免锁阻塞其他人的文件操作。 */
    private void verify(Map<String,Object> object) {files.get().verifiedPrimary((String)object.get("storage_key"),((Number)object.get("byte_size")).longValue(),(String)object.get("sha256"));}
    /** 后缀仅作格式一致性约束，实际内容仍由文件检测器识别。 */
    public static String extension(String name) {return name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";}
    /** 转换及历史恢复保持业务名称主干，后缀跟随该版本实际原件，避免DOC内容被标成DOCX。 */
    public static String versionName(String current,String original){String suffix=extension(original),stem=current.contains(".")?current.substring(0,current.lastIndexOf('.')):current;return suffix.isEmpty()?stem:stem+"."+suffix;}
    private void active(Map<String,Object> doc) {if(!"ACTIVE".equals(doc.get("status"))) throw ApiFault.conflict("文档不在可操作状态");}
    private void recycled(Map<String,Object> doc) {if(!"RECYCLED".equals(doc.get("status"))) throw ApiFault.conflict("文档不在回收站");}
}
