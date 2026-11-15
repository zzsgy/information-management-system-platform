package com.company.platform.intake;

import com.company.platform.*;
import com.company.platform.archive.*;
import com.company.platform.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.*;

/** 台账预检、确认和撤回；档案原子新增与员工部门结果分别记录，不通过 Excel 更正已有目录。 */
@Service
public class ImportService implements StorageDomainPolicy {
    /** 领域服务与共享文件保护边界。 */
    private final Sql db;private final IdentityService identity;private final DirectoryService directory;private final ArchiveImport archive;
    private final Commands commands;private final ArchiveCommands archiveCommands;private final StorageFiles files;private final StorageBoundaries protection;
    public ImportService(Sql db,IdentityService identity,DirectoryService directory,ArchiveImport archive,Commands commands,ArchiveCommands archiveCommands,StorageFiles files,StorageBoundaries protection){this.db=db;this.identity=identity;this.directory=directory;this.archive=archive;this.commands=commands;this.archiveCommands=archiveCommands;this.files=files;this.protection=protection;}
    /** 导入附件仅属于平台或档案模块，公盘文件不可转作导入。 */
    @Override public boolean supports(String module,String purpose){return purpose.equals("IMPORT_XLSX")&&Set.of("ARCHIVE","PLATFORM").contains(module);}
    /** 每片上传、技术检测和读取状态都重验当前维护权限和固定部门。 */
    @Override public void require(Actor actor,String module,String purpose,UUID context,String action){Actor live=identity.background(actor.sessionId());if(module.equals("ARCHIVE"))archive.authorize(live,List.of(context));else{if(!live.actions().contains("PLATFORM.USERS_MANAGE")&&!live.actions().contains("PLATFORM.DEPARTMENTS_MANAGE"))throw ApiFault.denied();db.required("SELECT id FROM iam.department WHERE id=? AND status='ACTIVE'",context);}}
    /** 模板下载仅向当前有对应领域维护权的用户开放。 */
    public byte[] template(Actor actor,String type){authorize(actor,ImportWorkbook.type(type),List.of());return ImportWorkbook.template(type);}
    /** 档案盒使用用户确认的通用十八列台账；件登记继续使用有盒件关联的原模板。 */
    public byte[] template(Actor actor,String type,String format){authorize(actor,ImportWorkbook.type(type),List.of());return boxFormat(type,format)?ArchiveBoxWorkbook.template():ImportWorkbook.template(type);}
    /** 上传使用的部门选择器只返回已授权范围，模板字段定义不泄露人员清单。 */
    public Map<String,Object> options(Actor actor,String type){authorize(actor,ImportWorkbook.type(type),List.of());return Sql.map("version",ImportWorkbook.VERSION,"columns",ImportWorkbook.headers(type),"departments",type.equals("ARCHIVE")?List.of():db.rows("SELECT id,code,name FROM iam.department WHERE status='ACTIVE' ORDER BY code LIMIT 1000"));}
    public Map<String,Object> options(Actor actor,String type,String format){var result=options(actor,type);if(boxFormat(type,format))result.put("columns",ArchiveBoxWorkbook.LABELS);return result;}
    private boolean boxFormat(String type,String format){Inputs.choice(format,"BOX_LEDGER","STANDARD");return type.equals("ARCHIVE")&&format.equals("BOX_LEDGER");}
    /** 从已检测的固定对象解析台账；解析在事务外，预检事务只写批次和错误，不创建业务目录。 */
    public Map<String,Object> create(Commands.Context context,Map<String,Object> body){
        Inputs.only(body,"importType","uploadObjectId","templateVersion","departmentScope");String type=ImportWorkbook.type(body.get("importType"));List<UUID> scope=scope(body.get("departmentScope"));authorize(context.actor(),type,scope);
        if(!ImportWorkbook.VERSION.equals(body.get("templateVersion")))throw ApiFault.conflict("导入模板已失效，请下载当前版本");
        UUID object=Inputs.uuid(body.get("uploadObjectId"));var file=source(context.actor(),type,scope,object);
        List<ImportWorkbook.Row> parsed;String parseError=null;
        try{parsed=ImportWorkbook.read(files.get().verifiedPrimary((String)file.get("storage_key"),((Number)file.get("byte_size")).longValue(),(String)file.get("sha256")),type);}catch(ApiFault failure){if(failure.status!=400)throw failure;parsed=List.of();parseError=failure.getMessage();}
        final List<ImportWorkbook.Row> rows=parsed;final String structuralError=parseError;
        return run(context,body,type,ApiController.COLLECTION,"CREATE_IMPORT",false,()->{
            source(context.actor(),type,scope,object);var prior=db.one("SELECT id FROM ops.import_batch WHERE created_by=? AND upload_object_id=? AND import_type=?",context.actor().userId(),object,type);if(prior!=null)return detail(context.actor(),(UUID)prior.get("id"));
            var checked=structuralError==null?validate(context.actor(),type,rows,scope):List.of(new ArchiveImport.Prepared(0,Map.of(),Map.of(),List.of(Sql.map("field","工作簿","message",structuralError))));
            long errors=checked.stream().filter(row->!row.errors().isEmpty()).count();UUID id=UUID.randomUUID();String digest=digest(type,scope,checked);
            db.update("INSERT INTO ops.import_batch(id,import_type,upload_object_id,template_version,department_scope,created_by,state,row_count,error_count,validation_digest) VALUES(?,?,?,?,ARRAY(SELECT jsonb_array_elements_text(?::jsonb)::uuid),?,?,?,?,?)",id,type,object,ImportWorkbook.VERSION,Crypto.json(scope),context.actor().userId(),errors==0?"READY":"INVALID",rows.size(),errors,digest);
            for(var row:checked)db.update("INSERT INTO ops.import_row(batch_id,row_no,input_data,errors) VALUES(?,?,?::jsonb,?::jsonb)",id,row.number(),Crypto.json(row.input()),Crypto.json(row.errors()));
            db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) VALUES(?,?,'IMPORT_BATCH',?,'BUSINESS')",UUID.randomUUID(),object,id);return detail(context.actor(),id);
        });
    }
    /** 确认时再次校验整批输入和实时关联；任何错误均回滚，档案返回全部新增待人工复核。 */
    @Transactional
    public Map<String,Object> confirm(Commands.Context context,UUID id,Map<String,Object> body){
        Inputs.only(body,"validationDigest");db.rows("SELECT pg_advisory_xact_lock(61427002)");var batch=visible(context.actor(),id);String type=(String)batch.get("import_type");
        return run(context,body,type,id,"CONFIRM_IMPORT",true,()->{
            var locked=db.required("SELECT * FROM ops.import_batch WHERE id=? FOR UPDATE",id);Commands.version(context,locked);if(!"READY".equals(locked.get("state"))||!Crypto.equal((String)locked.get("validation_digest"),Inputs.text(body,"validationDigest",64)))throw ApiFault.conflict("本批尚未通过预检或预检摘要已变化");
            List<UUID> scope=scope(locked.get("department_scope"));source(context.actor(),type,scope,(UUID)locked.get("upload_object_id"));
            var rows=db.rows("SELECT row_no,input_data FROM ops.import_row WHERE batch_id=? ORDER BY row_no",id).stream().map(row->new ImportWorkbook.Row(((Number)row.get("row_no")).intValue(),json(row.get("input_data")))).toList();
            var checked=validate(context.actor(),type,rows,scope);if(checked.stream().anyMatch(row->!row.errors().isEmpty())||!Crypto.equal(digest(type,scope,checked),(String)locked.get("validation_digest")))throw ApiFault.conflict("人员、部门、模板或目录已变化，请重新上传预检；本批未写入任何业务记录");
            if(type.equals("ARCHIVE"))archive.apply(context.actor(),id,checked);else applyDirectory(context,id,type,checked);
            db.update("UPDATE ops.import_batch SET state='CONFIRMED',confirmed_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=?",id);return detail(context.actor(),id);
        });
    }
    /** 已发布或存在外部引用时拒绝整批撤回；从不物理删除历史。 */
    public Map<String,Object> withdraw(Commands.Context context,UUID id,Map<String,Object> body){Inputs.only(body,"reason");Inputs.text(body,"reason",1000);var batch=visible(context.actor(),id);if(!"ARCHIVE".equals(batch.get("import_type")))throw ApiFault.invalid("员工部门导入不支持整批删除回退");return run(context,body,"ARCHIVE",id,"WITHDRAW_IMPORT",true,()->{var locked=db.required("SELECT * FROM ops.import_batch WHERE id=? FOR UPDATE",id);Commands.version(context,locked);if(!"CONFIRMED".equals(locked.get("state")))throw ApiFault.conflict("当前批次不能撤回");archive.withdraw(context,id);db.update("UPDATE ops.import_batch SET state='WITHDRAWN',reason=?,withdrawn_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=?",body.get("reason"),id);return detail(context.actor(),id);});}
    /** 当前版本仅列本人批次；先按领域授权过滤再统计分页。 */
    public Map<String,Object> list(Actor actor,Map<String,String> query){Inputs.only(query,"importType","page","pageSize","state","createdFrom","createdTo");String type=ImportWorkbook.type(query.get("importType"));authorize(actor,type,List.of());
        // 全部部门仍可管理的批次才进入统计和分页，避免旧批次撤权后拖垮列表或泄露数量。
        String where=" FROM ops.import_batch b WHERE b.created_by=? AND b.import_type=?";
        List<Object> parameters=new ArrayList<>(List.of(actor.userId(),type));
        if(type.equals("ARCHIVE")){where+=" AND NOT EXISTS (SELECT 1 FROM unnest(b.department_scope) d(id) WHERE NOT archive.catalog_manager(?,d.id::uuid))";parameters.add(actor.userId());}
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);where+=new ListConditions(query,parameters)
            .choice("state","b.state","INVALID","READY","CONFIRMED","WITHDRAWN").dates("created","b.created_at").sql();
        long total=db.count("SELECT count(*)"+where,parameters.toArray());parameters.add(size);parameters.add((page-1)*size);return Sql.map("items",db.rows("SELECT b.id"+where+" ORDER BY b.created_at DESC,b.id LIMIT ? OFFSET ?",parameters.toArray()).stream().map(row->detail(actor,(UUID)row.get("id"))).toList(),"total",total);}
    /** 详情不返回台账磁盘键，批次所属范围撤销后连摘要也不可读。 */
    public Map<String,Object> detail(Actor actor,UUID id){var row=visible(actor,id);var result=Sql.view(row,"id,import_type,template_version,department_scope,state,row_count,error_count,validation_digest,row_version,created_at,confirmed_at,withdrawn_at,reason");result.put("outcome",row.get("state").equals("CONFIRMED")?(row.get("import_type").equals("ARCHIVE")?"ALL_CREATED_AWAITING_REVIEW":"ALL_CREATED"):row.get("state"));return result;}
    /** 每页最多 100 行，错误和业务对象映射不会跨批次返回。 */
    public Map<String,Object> rows(Actor actor,UUID id,Map<String,String> query){visible(actor,id);Inputs.only(query,"page","pageSize","onlyErrors");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);boolean errors=Boolean.parseBoolean(query.getOrDefault("onlyErrors","false"));var rows=db.rows("SELECT * FROM ops.import_row WHERE batch_id=? AND (NOT ? OR errors<>'[]'::jsonb) ORDER BY row_no LIMIT ? OFFSET ?",id,errors,size,(page-1)*size);return Sql.map("items",rows.stream().map(row->{var view=Sql.view(row,"row_no,object_type,object_id,result");view.put("input",json(row.get("input_data")));view.put("errors",Crypto.JSON.readValue(row.get("errors").toString(),List.class));return view;}).toList(),"total",db.count("SELECT count(*) FROM ops.import_row WHERE batch_id=? AND (NOT ? OR errors<>'[]'::jsonb)",id,errors));}
    /** 批次权限不由类型或对象 ID 自动授予。 */
    private Map<String,Object> visible(Actor actor,UUID id){var row=db.required("SELECT * FROM ops.import_batch WHERE id=? AND created_by=?",id,actor.userId());authorize(actor,(String)row.get("import_type"),scope(row.get("department_scope")));return row;}
    /** 各类型对应已有独立权限，IT 导入账号不会得到档案维护能力。 */
    private void authorize(Actor actor,String type,List<UUID> scope){Actor live=identity.background(actor.sessionId());if(type.equals("ARCHIVE")){live.require("ARCHIVE.CATALOG_MANAGE");if(!scope.isEmpty())archive.authorize(live,scope);}else live.require(type.equals("EMPLOYEE")?"PLATFORM.USERS_MANAGE":"PLATFORM.DEPARTMENTS_MANAGE");}
    /** 确认原件来自本人、同模块、同用途、批准的部门上下文，并仍有两份可校验副本。 */
    private Map<String,Object> source(Actor actor,String type,List<UUID> scope,UUID object){String module=type.equals("ARCHIVE")?"ARCHIVE":"PLATFORM";var row=db.required("SELECT o.*,s.business_context_id FROM storage.object o JOIN storage.upload_session s ON s.object_id=o.id WHERE o.id=? AND s.uploader_id=? AND s.module=? AND s.purpose='IMPORT_XLSX' AND s.state='COMPLETE' AND o.state='READY' AND o.check_result_ref IS NOT NULL AND o.media_type=?",object,actor.userId(),module,ImportWorkbook.MEDIA);if(!scope.contains(row.get("business_context_id")))throw ApiFault.denied();files.get().verifiedPrimary((String)row.get("storage_key"),((Number)row.get("byte_size")).longValue(),(String)row.get("sha256"));return row;}
    /** 导入命令归属实际模块；再次验证与回执都绑定当前批次，重复确认不再次创建人员或目录。 */
    private Map<String,Object> run(Commands.Context ctx,Map<String,Object> body,String type,UUID target,String action,boolean reauth,Supplier<Map<String,Object>> mutation){Runnable auth=()->{authorize(ctx.actor(),type,target.equals(ApiController.COLLECTION)?scope(body.get("departmentScope")):scope(visible(ctx.actor(),target).get("department_scope")));protection.requireWritable(type.equals("ARCHIVE")?"ARCHIVE":"PLATFORM",action);};return type.equals("ARCHIVE")?archiveCommands.run(ctx,body,"IMPORT_BATCH",target,action,reauth,auth,mutation,id->detail(ctx.actor(),id)):commands.run(ctx,body,"IMPORT_BATCH",target,action,auth,mutation,id->detail(ctx.actor(),id));}
    /** 部门范围去重并排序形成稳定摘要；不允许空范围或无界集合。 */
    private List<UUID> scope(Object input){if(!(input instanceof List<?> list)||list.isEmpty()||list.size()>200)throw ApiFault.invalid("部门范围应为 1 至 200 个部门");return list.stream().map(value->Inputs.uuid(value.toString())).distinct().sorted().toList();}
    /** 规范摘要不包含密码或服务凭据。 */
    private String digest(String type,List<UUID> scope,List<ArchiveImport.Prepared> rows){return Crypto.hash(Crypto.json(Sql.map("type",type,"scope",scope,"version",ImportWorkbook.VERSION,"rows",rows.stream().map(row->Sql.map("number",row.number(),"input",row.input(),"body",row.body(),"errors",row.errors())).toList())));}
    /** JSON 行值只在内部解码，输出字段由响应方法选择。 */
    @SuppressWarnings("unchecked") private Map<String,Object> json(Object value){return Crypto.JSON.readValue(value.toString(),Map.class);}
    /** 员工部门与档案分别预检，部门支持任意行顺序并识别本批循环。 */
    private List<ArchiveImport.Prepared> validate(Actor actor,String type,List<ImportWorkbook.Row> rows,List<UUID> scope){
        if(type.equals("ARCHIVE"))return archive.validate(actor,rows,scope);
        Set<String> keys=new HashSet<>(),emails=new HashSet<>();Map<String,String> parents=new HashMap<>();if(type.equals("DEPARTMENT"))for(var row:rows)parents.put(Objects.toString(row.values().get("code")),Objects.toString(row.values().get("parentCode"),""));
        List<ArchiveImport.Prepared> result=new ArrayList<>();
        for(var row:rows){var input=row.values();Map<String,Object> body=new LinkedHashMap<>();String field=type.equals("EMPLOYEE")?"employeeNo":"code";
            try{String key=Inputs.text(input,field,64);if(!keys.add(key)||db.count("SELECT count(*) FROM "+(type.equals("EMPLOYEE")?"iam.user_account WHERE employee_no=?":"iam.department WHERE code=?"),key)>0)throw ApiFault.invalid("编号已存在或本批重复");body.put(field,key);
                field="name";body.put(field,Inputs.text(input,field,128));
                if(type.equals("EMPLOYEE")){field="departmentCode";var department=db.one("SELECT id FROM iam.department WHERE code=? AND status='ACTIVE'",Inputs.text(input,field,64));if(department==null||!scope.contains(department.get("id")))throw ApiFault.invalid("部门不存在或不在批准范围");body.put("departmentId",department.get("id").toString());field="companyEmail";String email=Inputs.email(input);if(!emails.add(email)||db.count("SELECT count(*) FROM iam.user_account WHERE company_email=?",email)>0)throw ApiFault.invalid("邮箱重复");body.put(field,email);}
                else{if(!key.matches("[A-Z0-9]{1,12}"))throw ApiFault.invalid("部门编码请使用 1 至 12 位大写字母或数字，以兼容档案编号");field="sortOrder";body.put(field,Inputs.integer(input.get(field),0,0,100000));field="parentCode";String parent=Objects.toString(input.get(field),"");Set<String> chain=new HashSet<>();String cursor=key;while(parents.containsKey(cursor)){if(!chain.add(cursor))throw ApiFault.invalid("本批部门存在循环层级");cursor=parents.get(cursor);}if(!parent.isEmpty()&&!parents.containsKey(parent)){var target=db.one("SELECT id FROM iam.department WHERE code=? AND status='ACTIVE'",parent);if(target==null)throw ApiFault.invalid("上级部门不存在");body.put("parentId",target.get("id").toString());}}
                result.add(new ArchiveImport.Prepared(row.number(),input,body,List.of()));
            }catch(ApiFault failure){result.add(new ArchiveImport.Prepared(row.number(),input,body,List.of(Sql.map("field",field,"message",failure.getMessage()))));}
        }return result;
    }
    /** 部门按拓扑次序新增，员工临时凭据继续走既有受控投递渠道。 */
    private void applyDirectory(Commands.Context context,UUID batch,String type,List<ArchiveImport.Prepared> rows){Map<String,UUID> created=new HashMap<>();List<ArchiveImport.Prepared> remaining=new ArrayList<>(rows);while(!remaining.isEmpty()){boolean progressed=false;for(var iterator=remaining.iterator();iterator.hasNext();){var row=iterator.next();var body=new LinkedHashMap<>(row.body());String parent=Objects.toString(row.input().get("parentCode"),"");if(type.equals("DEPARTMENT")&&!parent.isEmpty()&&!body.containsKey("parentId")){if(!created.containsKey(parent))continue;body.put("parentId",created.get(parent).toString());}var object=type.equals("EMPLOYEE")?directory.createUser(context,body):directory.createDepartment(body);UUID id=Inputs.uuid(object.get("id"));created.put(Objects.toString(row.input().get("code")),id);db.update("UPDATE ops.import_row SET object_type=?,object_id=?,result='CREATED' WHERE batch_id=? AND row_no=?",type,id,batch,row.number());iterator.remove();progressed=true;}if(!progressed)throw ApiFault.conflict("部门关联已变化，未提交本批");}}
}
