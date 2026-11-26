package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Office转换保留不可变原件；仅将真实检测通过的双副本结果交给编辑或受控阅读。 */
@Service
public class OfficeConversions {
    private final Sql db;private final IdentityService identity;private final DriveFilePolicy policy;private final StorageFiles files;private final StorageBoundaries protection;
    private final OfficeGateway gateway;private final FileScanEngine scanner;private final DriveCommands commands;private final DriveLifecycle lifecycle;private final Audit audit;private final TransactionTemplate transaction;
    // 转换与PDF逐页渲染各用自己的工作位，慢Office转换不会占满普通PDF阅读能力。
    private final Semaphore slots=new Semaphore(2);
    private final ServiceIdentities services;
    /** 仅用于公盘预览，在线编辑转换继续使用既有 Office 协议。 */
    private final LocalOfficePreview localPreview;
    /** 同一源版本与输出格式只由一个线程转换，避免重复写入未被数据库引用的双副本。 */
    private record ConversionKey(UUID version,String output,String converter) { }
    private final ConcurrentHashMap<ConversionKey,CompletableFuture<UUID>> pending=new ConcurrentHashMap<>();
    public OfficeConversions(Sql db,IdentityService identity,DriveFilePolicy policy,StorageFiles files,StorageBoundaries protection,OfficeGateway gateway,FileScanEngine scanner,DriveCommands commands,DriveLifecycle lifecycle,Audit audit,PlatformTransactionManager manager,ServiceIdentities services,LocalOfficePreview localPreview){this.db=db;this.identity=identity;this.policy=policy;this.files=files;this.protection=protection;this.gateway=gateway;this.scanner=scanner;this.commands=commands;this.lifecycle=lifecycle;this.audit=audit;this.transaction=new TransactionTemplate(manager);this.services=services;this.localPreview=localPreview;}

    /** 旧格式显式转换追加版本；与编辑一样要求本人再验证，重复操作不重复生成版本。 */
    public Map<String,Object> convert(Commands.Context ctx,UUID document,Map<String,Object> body){
        Inputs.only(body,"sourceVersionId","reason");UUID source=Inputs.uuid(body.get("sourceVersionId"));Inputs.text(body,"reason",1000);policy.requireDocument(ctx.actor(),document,"EDIT");
        if(db.count("SELECT count(*) FROM ops.audit_event WHERE actor_id=? AND operation_key=? AND record_kind='IDEMPOTENCY_RECEIPT'",ctx.actor().userId(),ctx.route()+":"+ctx.operation())>0)return commands.run(ctx,document,"EDIT",body,doc->doc);
        var original=original(document,source);String input=DriveLifecycle.extension((String)original.get("original_name"));String output=switch(input){case "doc"->"docx";case "xls"->"xlsx";case "ppt"->"pptx";default->throw ApiFault.invalid("仅旧DOC、XLS、PPT需要转换为可编辑格式");};
        UUID converted=ensure(ctx.actor(),document,source,input,output,"EDIT",ctx.request(),null);
        return commands.run(ctx,document,"EDIT",body,doc->{if(!"ACTIVE".equals(doc.get("status"))||!source.equals(doc.get("current_version_id")))throw ApiFault.conflict("当前版本已变化，请刷新后重新确认转换");
            lifecycle.version(ctx.actor(),doc,converted,source,"OFFICE_CONVERSION");String name=DriveLifecycle.versionName((String)doc.get("name"),"converted."+output);db.update("UPDATE drive.document SET name=?,name_key=? WHERE id=?",name,DriveLifecycle.nameKey(name),document);return doc;});
    }
    /** 仅VIEW即可生成内部PDF，不产生浏览器原件地址，也不要求DOWNLOAD或开启协作。 */
    public UUID previewPdf(Actor actor,UUID document,UUID version,UUID request){
        var original=original(document,version);String input=DriveLifecycle.extension((String)original.get("original_name"));if(!Set.of("doc","docx","xls","xlsx","ppt","pptx").contains(input))throw ApiFault.invalid("该格式不属于Office受控预览范围");
        LocalOfficePreview.Configuration local = localPreview.configuration();
        if (local == null && !Boolean.TRUE.equals(gateway.status().get("enabled"))) {
            throw SystemFailure.LOCAL_OFFICE_NOT_CONFIGURED.fault();
        }
        return ensure(actor,document,version,input,"pdf","VIEW",request,local);
    }
    private UUID ensure(Actor actor,UUID document,UUID version,String input,String output,String action,UUID request,
                        LocalOfficePreview.Configuration local){
        authorize(actor,document,action,local);
        String converter = local == null ? Objects.toString(gateway.status().get("expectedVersion"),"") : local.cacheKey();
        var key=new ConversionKey(version,output,converter);
        var own=new CompletableFuture<UUID>();var active=pending.putIfAbsent(key,own);
        if(active!=null){
            // 等待不持有数据库连接；最多五秒，防止大量预览挤占文档服务回调所需的HTTP线程。
            try{UUID object=active.get(5,TimeUnit.SECONDS);authorize(actor,document,action,local);verify(db.required("SELECT * FROM storage.object WHERE id=? AND state='READY'",object));return object;}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ApiFault(503,"OFFICE_CONVERSION_BUSY","Office转换仍在进行，请稍后重试");}
            catch(TimeoutException timeout){throw new ApiFault(503,"OFFICE_CONVERSION_BUSY","Office转换仍在进行，请稍后重试");}
            catch(ExecutionException failure){if(failure.getCause() instanceof RuntimeException cause)throw cause;throw new IllegalStateException("Office转换未完成",failure.getCause());}
        }
        try{UUID result=perform(actor,document,version,input,output,action,request,converter,local);own.complete(result);return result;}
        catch(RuntimeException failure){own.completeExceptionally(failure);throw failure;}
        finally{pending.remove(key,own);}
    }
    /** 转换工作位只由实际执行者占用；失败移除合并记录，后续请求可重新尝试。 */
    private UUID perform(Actor actor,UUID document,UUID version,String input,String output,String action,UUID request,
                         String converter, LocalOfficePreview.Configuration local){
        var original=original(document,version);Path originalFile=verify(original);if(((Number)original.get("byte_size")).longValue()>gateway.maximum())throw new ApiFault(413,"OFFICE_FILE_TOO_LARGE","文件超过Office在线处理上限");
        var cached=db.one("SELECT * FROM drive.office_conversion WHERE source_version_id=? AND output_type=? AND converter_version=?",version,output,converter);
        if(cached!=null&&"READY".equals(cached.get("state"))){var ready=db.required("SELECT * FROM storage.object WHERE id=? AND state='READY'",cached.get("object_id"));verify(ready);return (UUID)ready.get("id");}
        if(!slots.tryAcquire())throw new ApiFault(503,"OFFICE_CONVERSION_BUSY","Office转换繁忙，请稍后重试");Path result=null;
        try{
            if(local==null) gateway.verifyVersion();
            UUID id=UUID.randomUUID();db.update("INSERT INTO drive.office_conversion(id,document_id,source_version_id,input_type,output_type,converter_version,state,requester_id,login_session_id,required_action,expires_at) VALUES(?,?,?,?,?,?,'PENDING',?,?,?,CURRENT_TIMESTAMP+interval '10 minutes') ON CONFLICT(source_version_id,output_type,converter_version) DO NOTHING",id,document,version,input,output,converter,actor.userId(),actor.sessionId(),action);
            var conversion=db.required("SELECT * FROM drive.office_conversion WHERE source_version_id=? AND output_type=? AND converter_version=?",version,output,converter);id=(UUID)conversion.get("id");
            if("REJECTED".equals(conversion.get("state")))throw new ApiFault(422,"OFFICE_CONVERSION_REJECTED","该转换结果未通过内容检测，原件与历史保持不变");
            if("READY".equals(conversion.get("state"))){var ready=db.required("SELECT * FROM storage.object WHERE id=? AND state='READY'",conversion.get("object_id"));verify(ready);return (UUID)ready.get("id");}
            db.update("UPDATE drive.office_conversion SET requester_id=?,login_session_id=?,required_action=?,expires_at=CURRENT_TIMESTAMP+interval '10 minutes' WHERE id=? AND state='PENDING'",actor.userId(),actor.sessionId(),action,id);
            result = local == null ? gateway.convert(id,input,output) : localPreview.convert(local,originalFile,input,gateway.maximum());
            String media=FileMediaCheck.detect(result,"converted."+output,"application/octet-stream");var verdict=scanner.scan(result);if(verdict==ClamFileScanner.Verdict.UNAVAILABLE)throw new ApiFault(503,"SCANNER_UNAVAILABLE","检测器暂不可用，转换结果不能发布");
            boolean clean=media!=null&&verdict==ClamFileScanner.Verdict.CLEAN;long size=Files.size(result);String digest=hash(result),key;try(var stream=Files.newInputStream(result)){key=files.get().receive(stream,size,digest);}UUID object=UUID.randomUUID(),conversionId=id;String originalName=DriveLifecycle.versionName((String)original.get("original_name"),"converted."+output);
            UUID stored=transaction.execute(tx->{identity.lockActor(actor);var doc=policy.requireDocument(actor,document,action);protection.requireReadable("DRIVE");UUID service=local==null?service(doc,action):null;var current=db.required("SELECT * FROM drive.office_conversion WHERE id=? FOR UPDATE",conversionId);
                if("READY".equals(current.get("state")))return (UUID)current.get("object_id");
                db.update("INSERT INTO storage.object(id,module,storage_key,sha256,byte_size,media_type,original_name,object_kind,state,created_by,service_identity_id,check_result_ref) VALUES(?,'DRIVE',?,?,?,?,?,'DERIVED',?,?,?,?)",object,key,digest,size,media==null?"application/octet-stream":media,originalName,clean?"READY":"QUARANTINED",local==null?null:actor.userId(),service,"OFFICE_CONVERSION:"+conversionId);
                db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) VALUES(?,?,'OFFICE_CONVERSION',?,'DERIVED')",UUID.randomUUID(),object,conversionId);
                db.update("UPDATE drive.office_conversion SET state=?,object_id=?,result_code=? WHERE id=?",clean?"READY":"REJECTED",object,clean?"CLEAN":"CONVERSION_CHECK_FAILED",conversionId);audit.event(actor.userId(),"DRIVE","DOCUMENT",document,"OFFICE_CONVERSION",clean?"SUCCEEDED":"DENIED",request);return object;});
            if(!clean)throw new ApiFault(422,"OFFICE_CONVERSION_REJECTED","转换结果未通过内容检测，原件与历史保持不变");return stored;
        }catch(IOException failure){
            if(local!=null)throw SystemFailure.LOCAL_OFFICE_UNAVAILABLE.fault(failure);
            throw new ApiFault(503,"OFFICE_CONVERSION_PENDING","转换结果尚未确认，请稍后重试",failure);
        }finally{slots.release();if(result!=null)try{Files.deleteIfExists(result);}catch(IOException ignored){/* 只清理本次随机临时结果；失败保留供隔离目录运维检查。 */}}
    }
    /** 仅原生outbox签名能读取源；链接还绑定固定版本、请求人当前会话和有限有效期。 */
    public Map<String,Object> source(UUID id,Map<String,Object> payload){
        if(!gateway.conversionSource(id).equals(payload.get("url")))throw ApiFault.denied();var row=db.required("SELECT * FROM drive.office_conversion WHERE id=? AND state='PENDING' AND expires_at>CURRENT_TIMESTAMP AND converter_version NOT LIKE 'libreoffice-v1:%'",id);
        Actor actor=identity.background((UUID)row.get("login_session_id"));if(!actor.userId().equals(row.get("requester_id")))throw ApiFault.denied();authorize(actor,(UUID)row.get("document_id"),(String)row.get("required_action"),null);return original((UUID)row.get("document_id"),(UUID)row.get("source_version_id"));
    }
    /** 原件只交给专用服务，并在流式传输期间继续检查实际授权。 */
    public void streamSource(UUID id,Map<String,Object> payload,OutputStream output)throws IOException{var object=source(id,payload);Path path=verify(object);try(var input=Files.newInputStream(path)){byte[] block=new byte[1048576];int size;while((size=input.read(block))!=-1){source(id,payload);output.write(block,0,size);}}}
    private void authorize(Actor actor,UUID document,String action,LocalOfficePreview.Configuration local){
        Actor current=identity.background(actor.sessionId());
        var doc=policy.requireDocument(current,document,action);
        protection.requireReadable("DRIVE");
        if(action.equals("EDIT"))protection.requireWritable("DRIVE","OFFICE_CONVERSION");
        if(local==null)service(doc,action);
    }
    private UUID service(Map<String,Object> doc,String action){return services.require(gateway.service(),"OFFICE","DRIVE",(UUID)doc.get("publishing_department_id"),action);}
    private Map<String,Object> original(UUID document,UUID version){return db.required("SELECT o.* FROM drive.document_version v JOIN storage.object o ON o.id=v.object_id WHERE v.document_id=? AND v.id=? AND o.state='READY' AND o.check_result_ref IS NOT NULL",document,version);}
    private Path verify(Map<String,Object> object){return files.get().verifiedPrimary((String)object.get("storage_key"),((Number)object.get("byte_size")).longValue(),(String)object.get("sha256"));}
    private String hash(Path file)throws IOException{try{var digest=MessageDigest.getInstance("SHA-256");try(var stream=Files.newInputStream(file)){byte[] block=new byte[65536];int size;while((size=stream.read(block))!=-1)digest.update(block,0,size);}return HexFormat.of().formatHex(digest.digest());}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
