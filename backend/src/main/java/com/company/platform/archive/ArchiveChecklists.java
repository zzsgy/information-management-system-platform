package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.intake.*;
import com.company.platform.storage.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Semaphore;

/** 电子清单、唯一文档对应和真实处理结果；只在明确的档案盒范围内维护。 */
@Service
public class ArchiveChecklists implements StorageDomainPolicy {
    private final Audit audit;private final Sql db;private final IdentityService identity;private final ArchiveAuthority authority;private final ArchiveCatalogService catalog;private final ArchiveCommands commands;
    private final StorageFiles files;private final StorageBoundaries protection;private final ReadingRuntime runtime;private final OfficeGateway office;private final FileScanEngine scanner;private final ServiceIdentities services;private final TransactionTemplate tx;private final String scannerId;
    private final Semaphore conversions=new Semaphore(2);
    public ArchiveChecklists(Audit audit,Sql db,IdentityService identity,ArchiveAuthority authority,ArchiveCatalogService catalog,ArchiveCommands commands,StorageFiles files,StorageBoundaries protection,ReadingRuntime runtime,OfficeGateway office,FileScanEngine scanner,ServiceIdentities services,PlatformTransactionManager manager,@Value("${platform.scanner.archive-service-id:}")String scannerId){this.audit=audit;this.db=db;this.identity=identity;this.authority=authority;this.catalog=catalog;this.commands=commands;this.files=files;this.protection=protection;this.runtime=runtime;this.office=office;this.scanner=scanner;this.services=services;this.tx=new TransactionTemplate(manager);this.scannerId=scannerId;}
    @Override public boolean supports(String module,String purpose){return module.equals("ARCHIVE")&&Set.of("ARCHIVE_CHECKLIST","ARCHIVE_ATTACHMENT","ARCHIVE_REGISTRATION_CHECKLIST").contains(purpose);}
    @Override public void require(Actor actor,String module,String purpose,UUID context,String action){
        Actor current=identity.background(actor.sessionId());
        switch(purpose){
            case "ARCHIVE_REGISTRATION_CHECKLIST" -> authority.requireManage(current,context);
            case "ARCHIVE_CHECKLIST" -> editableBox(current,context,false);
            default -> {
                var record=authority.record(context);
                editableBox(current,(UUID)record.get("box_id"),false);
                if(!"DRAFT".equals(record.get("state")))throw ApiFault.conflict("已送审或已归档的电子文件不能直接替换，请先办理更正");
            }
        }
    }

    /** 新建登记预览仅使用本人在获权部门上传并完成检测的工作簿，不占用盒号。 */
    public Map<String,Object> registrationPreview(Actor actor,UUID department,UUID uploadId){
        authority.requireManage(actor,department);
        var source=upload(actor,uploadId,department,"ARCHIVE_REGISTRATION_CHECKLIST");
        return Sql.map("items",ArchiveChecklistWorkbook.read(path(source)));
    }

    /** 建盒、关联检测原件和写入所有清单项由同一个登记命令提交。 */
    public Map<String,Object> registerBox(Commands.Context context,Map<String,Object> body){
        UUID department=Inputs.uuid(body.get("departmentId"));
        UUID uploadId=Inputs.uuid(body.get("checklistUploadSessionId"));
        var boxBody=new LinkedHashMap<>(body);
        boxBody.remove("checklistUploadSessionId");
        return commands.run(context,body,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",true,
            ()->authority.requireManage(context.actor(),department),()->{
                var source=upload(context.actor(),uploadId,department,"ARCHIVE_REGISTRATION_CHECKLIST");
                var entries=ArchiveChecklistWorkbook.read(path(source));
                var box=catalog.insertRegisteredBox(context.actor(),boxBody);
                UUID id=Inputs.uuid(box.get("id"));
                var parent=editableBox(context.actor(),id,true);
                var plan=plan(id,parent,entries);
                UUID checklist=saveSource(context.actor(),id,source,entries.size());
                insertEntries(context.actor(),id,parent,checklist,plan.items());
                touchBox(context.actor(),id);
                return catalog.boxView(id);
            },catalog::boxView);
    }

    /** 在盒锁下按存放内容查重、分配下一序号，并保留清单来源。 */
    public Map<String,Object> appendItem(Commands.Context context,UUID box,Map<String,Object> body){
        Inputs.only(body,"title","date","signingEntity","contractNumber","paperCopies","notes");
        String title=Inputs.text(Sql.map("存放内容",body.get("title")),"存放内容",512);
        if(body.get("paperCopies")==null)throw ApiFault.invalid("请填写纸质份数");
        int copies=Inputs.integer(body.get("paperCopies"),0,1,1000000);
        String rawDate=optionalItemText(body,"date",10);
        String year=rawDate.matches("[1-9][0-9]{3}")?rawDate:null;
        LocalDate date=null;
        if(!rawDate.isEmpty()&&year==null){
            try{date=LocalDate.parse(rawDate);}
            catch(DateTimeParseException invalid){throw ApiFault.invalid("日期请输入四位年份或有效的 YYYY-MM-DD");}
        }
        var entry=new ArchiveChecklistWorkbook.Entry(1,1,title,date,copies,optionalItemText(body,"notes",2000),null,
            optionalItemText(body,"signingEntity",512),optionalItemText(body,"contractNumber",256),year);
        return commands.run(context,body,"ARCHIVE_BOX",box,"APPEND_CHECKLIST_ITEM",true,
            ()->editableBox(context.actor(),box,false),()->{
                var parent=editableBox(context.actor(),box,true);
                Commands.version(context,parent);
                if(db.one("SELECT id FROM archive.record WHERE box_id=? AND title=? AND state<>'WITHDRAWN' LIMIT 1",box,title)!=null)
                    throw ApiFault.conflict("当前档案盒已有同名清单条目，请核对存放内容");
                var plan=plan(box,parent,List.of(entry));
                UUID checklist=UUID.randomUUID();
                db.update("INSERT INTO archive.electronic_checklist(id,box_id,row_count,created_by) VALUES(?,?,1,?)",
                    checklist,box,context.actor().userId());
                insertEntries(context.actor(),box,parent,checklist,plan.items());
                touchBox(context.actor(),box);
                return items(context.actor(),box);
            },id->items(context.actor(),id));
    }

    /** 手工补录的选填项留空时沿用清单解析结果的空字符串。 */
    private String optionalItemText(Map<String,Object> body,String key,int max){
        Object value=body.get(key);
        return value==null || value instanceof String text && text.isBlank() ? "" : Inputs.text(body,key,max);
    }

    /** 查询只返回当前可维护盒中的记录；数字序号在SQL中排序，旧目录也保留。 */
    public Map<String,Object> items(Actor actor,UUID box){manageBox(actor,box,false);var rows=db.rows("SELECT r.*,archive.action_allowed(r.id,?,'VIEW') AS can_view,archive.action_allowed(r.id,?,'DOWNLOAD') AS can_download,archive.action_allowed(r.id,?,'PRINT') AS can_print,cv.pdf_version_id AS candidate_pdf_version_id,CASE WHEN v.id IS NOT NULL AND v.state<>'READY' THEN NULL ELSE p.page_count END AS page_count,p.review_state,v.original_object_id,v.state AS electronic_state,v.error_message,o.original_name,o.byte_size FROM archive.record r LEFT JOIN archive.record_revision cv ON cv.id=r.candidate_revision_id LEFT JOIN archive.pdf_version p ON p.id=COALESCE(r.current_pdf_version_id,cv.pdf_version_id) LEFT JOIN archive.electronic_version v ON v.id=r.electronic_version_id LEFT JOIN storage.object o ON o.id=v.original_object_id WHERE r.box_id=? AND r.state<>'WITHDRAWN' ORDER BY r.item_no,r.id LIMIT 1000",actor.userId(),actor.userId(),actor.userId(),box);
        return Sql.map("id",box,"box",catalog.boxView(box),"items",rows.stream().map(row->{var result=Sql.view(row,"id,file_no,box_id,item_no,title,formed_date,notes,extension_values,state,row_version,carrier_type,department_id,classification,current_revision_id,current_pdf_version_id,candidate_revision_id,candidate_pdf_version_id,page_count,electronic_version_id,electronic_state,error_message,original_name,byte_size,can_view,can_download,can_print");result.put("extensionValues",CatalogTemplates.json(row.get("extension_values")));return result;}).toList());}
    /** 预览只读取已检测的清单，展示实际追加序号和跳过的重复数。 */
    public Map<String,Object> preview(Actor actor,UUID box,UUID upload){
        var parent=editableBox(actor,box,false);
        var object=upload(actor,upload,box,"ARCHIVE_CHECKLIST");
        var plan=plan(box,parent,ArchiveChecklistWorkbook.read(path(object),true));
        return Sql.map("id",box,"rowVersion",parent.get("row_version"),"digest",digest(parent,object,plan),
            "items",plan.items(),"skipped",plan.skipped(),"append",plan.append());
    }

    /** 确认时锁住档案盒，重新计算去重和序号，既有条目及附件无需重新写入。 */
    public Map<String,Object> importChecklist(Commands.Context context,UUID box,Map<String,Object> body){
        Inputs.only(body,"uploadSessionId","digest");
        UUID uploadId=Inputs.uuid(body.get("uploadSessionId"));
        String confirmedDigest=Inputs.text(body,"digest",64);
        var source=upload(context.actor(),uploadId,box,"ARCHIVE_CHECKLIST");
        var entries=ArchiveChecklistWorkbook.read(path(source),true);
        return commands.run(context,body,"ARCHIVE_BOX",box,"IMPORT_CHECKLIST",true,()->editableBox(context.actor(),box,false),()->{
            var parent=editableBox(context.actor(),box,true);
            Commands.version(context,parent);
            var plan=plan(box,parent,entries);
            if(!Crypto.equal(confirmedDigest,digest(parent,source,plan)))throw ApiFault.conflict("档案盒或清单已变化，请重新预检");
            if(plan.items().isEmpty())return items(context.actor(),box);

            upload(context.actor(),uploadId,box,"ARCHIVE_CHECKLIST");
            UUID checklist=saveSource(context.actor(),box,source,entries.size());
            insertEntries(context.actor(),box,parent,checklist,plan.items());
            touchBox(context.actor(),box);
            return items(context.actor(),box);
        },id->items(context.actor(),id));
    }

    /** 同一检测原件在同一盒复用来源记录，首次登记时保留业务存储引用。 */
    private UUID saveSource(Actor actor,UUID box,Map<String,Object> source,int rowCount){
        UUID checklist=UUID.randomUUID();
        int inserted=db.update("INSERT INTO archive.electronic_checklist(id,box_id,object_id,row_count,created_by) VALUES(?,?,?,?,?) ON CONFLICT(box_id,object_id) DO NOTHING",
            checklist,box,source.get("id"),rowCount,actor.userId());
        if(inserted==0)
            return (UUID)db.required("SELECT id FROM archive.electronic_checklist WHERE box_id=? AND object_id=?",box,source.get("id")).get("id");
        db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) VALUES(?,?,'ARCHIVE_CHECKLIST',?,'BUSINESS')",
            UUID.randomUUID(),source.get("id"),checklist);
        return checklist;
    }

    /** 上传与补录共用盒内文件模板及来源关联，一条清单对应一个档案件。 */
    private void insertEntries(Actor actor,UUID box,Map<String,Object> parent,UUID checklist,List<ArchiveChecklistWorkbook.Entry> entries){
        UUID template=(UUID)db.required("SELECT id FROM archive.directory_template WHERE template_key='COMMON_RECORD_LEDGER' AND state='ACTIVE' ORDER BY version_no DESC LIMIT 1").get("id");
        boolean electronic="ELECTRONIC".equals(parent.get("carrier_type"));
        for(var entry:entries){
            var values=Sql.map("copyCount",entry.paperCopies());
            if(!entry.signingEntity().isBlank())values.put("signingEntity",entry.signingEntity());
            if(!entry.contractNumber().isBlank())values.put("contractNumber",entry.contractNumber());
            if(entry.year()!=null)values.put("documentYear",entry.year());
            var data=Sql.map("boxId",box.toString(),"itemNo",entry.sequence(),"title",entry.title(),"categoryCode","COMMON",
                "carrierType",electronic?"ELECTRONIC":"MIXED","ownerId",parent.get("owner_id").toString(),
                "archiveDate",parent.get("archive_date").toString(),"retentionCode",parent.get("retention_code"),
                "templateVersionId",template.toString(),"extensionValues",values,"keywords",List.of("电子清单"));
            if(entry.date()!=null)data.put("formedDate",entry.date().toString());
            if(!entry.notes().isBlank())data.put("notes",entry.notes());
            if(!electronic)data.put("location",parent.get("location"));
            var record=catalog.insertChecklistRecord(actor,data);
            db.update("UPDATE archive.record SET checklist_id=? WHERE id=?",checklist,record.get("id"));
        }
    }
    /** 每个上传明确对应一条记录，最多一百份；确认时锁住同盒记录并再次核验检测结果。 */
    public Map<String,Object> bind(Commands.Context context,UUID box,Map<String,Object> body){Inputs.only(body,"items");var inputs=Inputs.objects(body.get("items"),100);if(inputs.isEmpty())throw ApiFault.invalid("请选择待上传的电子文件");Set<UUID> records=new HashSet<>(),uploads=new HashSet<>();for(var input:inputs){Inputs.only(input,"recordId","uploadSessionId","expectedVersion");if(!records.add(Inputs.uuid(input.get("recordId")))||!uploads.add(Inputs.uuid(input.get("uploadSessionId"))))throw ApiFault.invalid("一条清单只能对应一份电子文件，同一上传不能分配两次");}
        return commands.run(context,body,"ARCHIVE_BOX",box,"UPLOAD_CHECKLIST_DOCUMENTS",true,()->editableBox(context.actor(),box,false),()->{var parent=editableBox(context.actor(),box,true);Commands.version(context,parent);
            for(var input:inputs){UUID recordId=Inputs.uuid(input.get("recordId"));var record=db.required("SELECT * FROM archive.record WHERE id=? AND box_id=? AND state='DRAFT' FOR UPDATE",recordId,box);if(((Number)record.get("row_version")).intValue()!=Inputs.integer(input.get("expectedVersion"),0,1,Integer.MAX_VALUE))throw ApiFault.conflict("清单条目已变化，请重新对应文件");var object=upload(context.actor(),Inputs.uuid(input.get("uploadSessionId")),recordId,"ARCHIVE_ATTACHMENT");
                UUID version=UUID.randomUUID();int number=(int)db.count("SELECT COALESCE(max(version_no),0)+1 FROM archive.electronic_version WHERE record_id=?",recordId);db.update("INSERT INTO archive.electronic_version(id,record_id,version_no,original_object_id,created_by) VALUES(?,?,?,?,?)",version,recordId,number,object.get("id"),context.actor().userId());db.update("UPDATE archive.record SET electronic_version_id=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",version,recordId);db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) VALUES(?,?,'ARCHIVE_ELECTRONIC_VERSION',?,'BUSINESS')",UUID.randomUUID(),object.get("id"),version);}
            touchBox(context.actor(),box);return items(context.actor(),box);},id->items(context.actor(),id));}

    /** 真实Office转换或PDF解析在事务外；最终固定原件、候选及版本再提交，错误可重试。 */
    public Map<String,Object> prepare(Actor actor,UUID recordId){protection.requireWritable("ARCHIVE","PREPARE_ELECTRONIC");var record=authority.record(recordId);editableBox(actor,(UUID)record.get("box_id"),false);if(!record.get("state").equals("DRAFT"))throw ApiFault.conflict("当前文件不可处理");UUID version=Inputs.uuid(record.get("electronic_version_id"));var row=db.required("SELECT v.*,o.storage_key,o.sha256,o.byte_size,o.media_type,o.original_name FROM archive.electronic_version v JOIN storage.object o ON o.id=v.original_object_id WHERE v.id=? AND v.record_id=? AND o.state='READY'",version,recordId);if(row.get("state").equals("READY"))return items(actor,(UUID)record.get("box_id"));
        if(!conversions.tryAcquire())throw new ApiFault(503,"ARCHIVE_CONVERSION_BUSY","电子文件处理繁忙，请稍后重试");Path temporary=null;
        try{Map<String,Object> pdf=row;
            if(!row.get("media_type").equals("application/pdf")){
                office.require();office.verifyVersion();UUID conversion=UUID.randomUUID();db.update("INSERT INTO archive.office_conversion(id,version_id,session_id,requester_id,expires_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP+interval '10 minutes') ON CONFLICT(version_id) DO UPDATE SET session_id=EXCLUDED.session_id,requester_id=EXCLUDED.requester_id,expires_at=EXCLUDED.expires_at",conversion,version,actor.sessionId(),actor.userId());conversion=(UUID)db.required("SELECT id FROM archive.office_conversion WHERE version_id=?",version).get("id");String name=(String)row.get("original_name");temporary=office.convertArchive(conversion,name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT));
                var verdict=scanner.scan(temporary);if(verdict!=ClamFileScanner.Verdict.CLEAN||!"application/pdf".equals(FileMediaCheck.detect(temporary,"preview.pdf","application/pdf")))throw new ApiFault(422,"ARCHIVE_PREVIEW_REJECTED","转换结果未通过PDF检测，原件保留，请核对文件后重试");long size=Files.size(temporary);String sha=hash(temporary),key;try(var in=Files.newInputStream(temporary)){key=files.get().receive(in,size,sha);}UUID object=UUID.randomUUID();UUID service=services.require(Inputs.uuid(services.configured("platform.scanner.archive-service-id")),"FILE_CHECK","ARCHIVE",(UUID)record.get("department_id"),"FILE_CHECK");db.update("INSERT INTO storage.object(id,module,storage_key,sha256,byte_size,media_type,original_name,object_kind,state,service_identity_id,check_result_ref) VALUES(?,'ARCHIVE',?,?,?,'application/pdf',?,'DERIVED','READY',?,?)",object,key,sha,size,name+".pdf",service,"ARCHIVE_OFFICE:"+conversion);pdf=db.required("SELECT * FROM storage.object WHERE id=?",object);
            }else{pdf=new LinkedHashMap<>(row);pdf.put("id",row.get("original_object_id"));}
            Map<String,Object> prepared=pdf;int pages=runtime.withClient(client->client.pageCount(path(prepared),(String)prepared.get("sha256")));
            tx.executeWithoutResult(status->{identity.lockActor(actor);protection.requireWritable("ARCHIVE","PREPARE_ELECTRONIC");var parent=editableBox(actor,(UUID)record.get("box_id"),true);var current=db.required("SELECT * FROM archive.record WHERE id=? AND state='DRAFT' FOR UPDATE",recordId);if(!version.equals(current.get("electronic_version_id")))throw ApiFault.conflict("电子文件已被替换，本次处理结果不挂接");var actual=db.required("SELECT * FROM archive.electronic_version WHERE id=? FOR UPDATE",version);if(actual.get("state").equals("READY"))return;path(prepared);UUID pdfId=UUID.randomUUID();int next=(int)db.count("SELECT COALESCE(max(version_no),0)+1 FROM archive.pdf_version WHERE record_id=?",recordId);db.update("INSERT INTO archive.pdf_version(id,record_id,version_no,object_id,source,created_by,page_count) VALUES(?,?,?,?,'ELECTRONIC_CHECKLIST',?,?)",pdfId,recordId,next,prepared.get("id"),actor.userId(),pages);UUID revision=UUID.randomUUID();int revisionNo=(int)db.count("SELECT COALESCE(max(revision_no),0)+1 FROM archive.record_revision WHERE record_id=?",recordId);db.update("INSERT INTO archive.record_revision(id,record_id,revision_no,pdf_version_id,metadata_snapshot,template_version_id,created_by) VALUES(?,?,?,?,?::jsonb,?,?)",revision,recordId,revisionNo,pdfId,Crypto.json(catalog.recordView(recordId)),current.get("template_version_id"),actor.userId());db.update("UPDATE archive.record SET candidate_revision_id=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",revision,recordId);db.update("UPDATE archive.electronic_version SET state='READY',error_message=NULL,pdf_version_id=? WHERE id=?",pdfId,version);db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) VALUES(?,?,'ARCHIVE_ELECTRONIC_VERSION',?,'DERIVED')",UUID.randomUUID(),prepared.get("id"),version);touchBox(actor,(UUID)record.get("box_id"));audit.event(actor.userId(),"ARCHIVE","ARCHIVE_RECORD",recordId,"PREPARE_ELECTRONIC","SUCCEEDED",UUID.randomUUID());commands.event("ARCHIVE_RECORD",recordId,"ELECTRONIC_FILE_PREPARED");});
            return items(actor,(UUID)record.get("box_id"));
        }catch(Exception error){String message=error instanceof ApiFault?error.getMessage():"电子文件处理未完成，请稍后重试";db.update("UPDATE archive.electronic_version SET state='FAILED',error_message=? WHERE id=? AND state<>'READY'",message.length()>512?message.substring(0,512):message,version);if(error instanceof ApiFault fault)throw fault;throw new ApiFault(503,"ARCHIVE_PREPARE_FAILED",message);}finally{conversions.release();if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    /** 文档服务取源必须有签名、限时任务、当前维护资格和当前电子版本，不向浏览器提供原件直链。 */
    public Map<String,Object> conversionSource(UUID id,Map<String,Object> payload){if(!office.archiveConversionSource(id).equals(payload.get("url")))throw ApiFault.denied();var conversion=db.required("SELECT c.*,v.record_id,v.original_object_id FROM archive.office_conversion c JOIN archive.electronic_version v ON v.id=c.version_id WHERE c.id=? AND c.expires_at>CURRENT_TIMESTAMP",id);Actor actor=identity.background((UUID)conversion.get("session_id"));if(!actor.userId().equals(conversion.get("requester_id")))throw ApiFault.denied();var record=authority.record((UUID)conversion.get("record_id"));editableBox(actor,(UUID)record.get("box_id"),false);if(!conversion.get("version_id").equals(record.get("electronic_version_id")))throw ApiFault.denied();return db.required("SELECT * FROM storage.object WHERE id=? AND state='READY'",conversion.get("original_object_id"));}
    public void streamSource(UUID id,Map<String,Object> payload,OutputStream output)throws IOException{var object=conversionSource(id,payload);try(var in=Files.newInputStream(path(object))){byte[] bytes=new byte[1048576];int read;while((read=in.read(bytes))!=-1){conversionSource(id,payload);output.write(bytes,0,read);}}}
    private Map<String,Object> manageBox(Actor actor,UUID box,boolean lock){var row=db.required("SELECT * FROM archive.box WHERE id=?"+(lock?" FOR UPDATE":""),box);authority.requireManage(identity.background(actor.sessionId()),(UUID)row.get("department_id"));return row;}
    private Map<String,Object> editableBox(Actor actor,UUID box,boolean lock){var row=manageBox(actor,box,lock);if(!row.get("state").equals("DRAFT")||db.count("SELECT count(*) FROM archive.catalog_change WHERE object_type='BOX' AND object_id=? AND state IN ('DRAFT','PENDING')",box)>0)throw ApiFault.conflict("电子清单及文件须在档案盒送审前维护，已归档资料请走更正流程");return row;}
    private Map<String,Object> upload(Actor actor,UUID upload,UUID context,String purpose){var result=db.required("SELECT o.* FROM storage.upload_session s JOIN storage.object o ON o.id=s.object_id WHERE s.id=? AND s.uploader_id=? AND s.module='ARCHIVE' AND s.purpose=? AND s.business_context_id=? AND s.state='COMPLETE' AND o.state='READY' AND o.check_result_ref IS NOT NULL",upload,actor.userId(),purpose,context);path(result);return result;}
    private Path path(Map<String,Object> row){protection.requireReadable("ARCHIVE");return files.get().verifiedPrimary((String)row.get("storage_key"),((Number)row.get("byte_size")).longValue(),(String)row.get("sha256"));}
    /** 比对清单业务字段；序号是盒内顺序，存放编号只用于核对归属。 */
    private record ChecklistFields(String title,String date,String signingEntity,String contractNumber,BigDecimal paperCopies,String notes) { }

    /** 预览与确认使用同一份追加结果，跳过的条目不会占用编号。 */
    private record ChecklistPlan(List<ArchiveChecklistWorkbook.Entry> items,int skipped,boolean append) { }

    private ChecklistPlan plan(UUID box,Map<String,Object> parent,List<ArchiveChecklistWorkbook.Entry> entries){
        var extras=CatalogTemplates.map(parent.get("extension_values"));
        for(var entry:entries){
            if(entry.boxNumber()!=null&&!entry.boxNumber().equals(parent.get("box_no"))&&!entry.boxNumber().equals(extras.get("originalNumber")))
                throw ApiFault.invalid("第 "+entry.row()+" 行存放编号为“"+entry.boxNumber()+"”，与当前档案盒“"+parent.get("box_no")+"”"
                    +(extras.get("originalNumber")==null?"":"（原档案编号“"+extras.get("originalNumber")+"”）")
                    +"不一致。请选择对应档案盒，或核对Excel中的存放编号；留空时按当前档案盒归属。");
        }

        // 撤回记录仍占用旧序号，但不参与有效内容的去重。
        int lastSequence=(int)db.count("SELECT coalesce(max(item_no),0) FROM archive.record WHERE box_id=?",box);
        if(lastSequence==0){
            var used=new HashSet<Integer>();
            for(var entry:entries)
                if(!used.add(entry.sequence()))throw ApiFault.invalid("第 "+entry.row()+" 行：序号重复："+entry.sequence());
            return new ChecklistPlan(entries,0,false);
        }

        var existing=db.rows("SELECT title,formed_date,notes,extension_values FROM archive.record WHERE box_id=? AND state<>'WITHDRAWN'",box);
        var known=new HashSet<ChecklistFields>();
        for(var row:existing){
            var values=CatalogTemplates.map(row.get("extension_values"));
            BigDecimal copies=values.get("copyCount") instanceof Number number?new BigDecimal(number.toString()).stripTrailingZeros():null;
            known.add(new ChecklistFields(row.get("title").toString(),Objects.toString(row.get("formed_date"),Objects.toString(values.get("documentYear"),"")),
                Objects.toString(values.get("signingEntity"),""),Objects.toString(values.get("contractNumber"),""),copies,Objects.toString(row.get("notes"),"")));
        }

        var appended=new ArrayList<ArchiveChecklistWorkbook.Entry>();
        for(var entry:entries){
            var fields=new ChecklistFields(entry.title(),Objects.toString(entry.date(),Objects.toString(entry.year(),"")),
                entry.signingEntity(),entry.contractNumber(),BigDecimal.valueOf(entry.paperCopies()).stripTrailingZeros(),entry.notes());
            if(known.add(fields)){
                appended.add(new ArchiveChecklistWorkbook.Entry(entry.row(),++lastSequence,entry.title(),entry.date(),entry.paperCopies(),
                    entry.notes(),entry.boxNumber(),entry.signingEntity(),entry.contractNumber(),entry.year()));
            }
        }
        if(!appended.isEmpty()&&existing.size()+appended.size()>1000)
            throw ApiFault.invalid("每个档案盒最多登记1000条有效清单，请分盒管理");
        if(!appended.isEmpty()&&lastSequence>1000000)
            throw ApiFault.invalid("当前档案盒序号已达上限，请分盒管理");
        return new ChecklistPlan(appended,entries.size()-appended.size(),true);
    }

    private String digest(Map<String,Object> box,Map<String,Object> object,ChecklistPlan plan){
        return Crypto.hash(Crypto.json(Sql.map("box",box.get("id"),"version",box.get("row_version"),"sha",object.get("sha256"),"plan",plan)));
    }
    private void touchBox(Actor actor,UUID box){db.update("UPDATE archive.box SET row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",box);var snapshot=catalog.boxView(box);UUID revision=UUID.randomUUID();int number=(int)db.count("SELECT COALESCE(max(revision_no),0)+1 FROM archive.box_revision WHERE box_id=?",box);db.update("INSERT INTO archive.box_revision(id,box_id,revision_no,metadata_snapshot,template_version_id,created_by) VALUES(?,?,?,?::jsonb,?,?)",revision,box,number,Crypto.json(snapshot),Inputs.uuid(snapshot.get("templateVersionId")),actor.userId());db.update("UPDATE archive.box SET candidate_revision_id=? WHERE id=?",revision,box);}
    private String hash(Path path)throws IOException{try{var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] bytes=new byte[65536];int size;while((size=in.read(bytes))!=-1)digest.update(bytes,0,size);}return HexFormat.of().formatHex(digest.digest());}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
