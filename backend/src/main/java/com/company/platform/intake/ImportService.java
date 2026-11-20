package com.company.platform.intake;

import com.company.platform.*;
import com.company.platform.archive.*;
import com.company.platform.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
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
    /** 仅在显式配置的单机部署启用员工工作簿入口。 */
    @Value("${platform.employee-import.local-workbook:false}")
    private boolean localEmployeeWorkbook;
    /** 导入附件仅属于平台或档案模块，公盘文件不可转作导入。 */
    @Override public boolean supports(String module,String purpose){return purpose.equals("IMPORT_XLSX")&&Set.of("ARCHIVE","PLATFORM").contains(module);}
    /** 每片上传、技术检测和读取状态都重验当前维护权限和固定部门。 */
    @Override public void require(Actor actor,String module,String purpose,UUID context,String action){Actor live=identity.background(actor.sessionId());if(module.equals("ARCHIVE"))archive.authorize(live,List.of(context));else{if(!live.actions().contains("PLATFORM.USERS_MANAGE")&&!live.actions().contains("PLATFORM.DEPARTMENTS_MANAGE"))throw ApiFault.denied();db.required("SELECT id FROM iam.department WHERE id=? AND status='ACTIVE'",context);}}
    /** 模板下载仅向当前有对应领域维护权的用户开放。 */
    public byte[] template(Actor actor,String type){authorize(actor,ImportWorkbook.type(type),List.of());return ImportWorkbook.template(type);}
    /** 档案盒使用用户确认的通用十八列台账；件登记继续使用有盒件关联的原模板。 */
    public byte[] template(Actor actor,String type,String format){authorize(actor,ImportWorkbook.type(type),List.of());return boxFormat(type,format)?ArchiveBoxWorkbook.template():ImportWorkbook.template(type);}
    /** 上传使用的部门选择器只返回已授权范围，模板字段定义不泄露人员清单。 */
    public Map<String,Object> options(Actor actor,String type){
        authorize(actor,ImportWorkbook.type(type),List.of());
        var result=Sql.map("version",ImportWorkbook.VERSION,"columns",ImportWorkbook.headers(type),"departments",type.equals("ARCHIVE")?List.of():db.rows("SELECT id,code,name FROM iam.department WHERE status='ACTIVE' ORDER BY code LIMIT 1000"));
        if(type.equals("EMPLOYEE")){result.put("employeeImportScopeId",employeeImportScopeId());result.put("localEmployeeWorkbook",localEmployeeWorkbook);}
        return result;
    }
    /** 公司节点是员工通用模板的上传上下文，不替代每行所属部门。 */
    private UUID employeeImportScopeId(){return (UUID)db.required("SELECT id FROM iam.department WHERE code='ROOT' AND status='ACTIVE'").get("id");}
    public Map<String,Object> options(Actor actor,String type,String format){var result=options(actor,type);if(boxFormat(type,format))result.put("columns",ArchiveBoxWorkbook.LABELS);return result;}
    private boolean boxFormat(String type,String format){Inputs.choice(format,"BOX_LEDGER","STANDARD");return type.equals("ARCHIVE")&&format.equals("BOX_LEDGER");}
    /** 从本人已检测的员工表读取缺邮箱的行，网页用与单人新增相同的姓名拼音规则生成。 */
    public List<Map<String,Object>> employeeEmailSources(Actor actor,UUID object){
        List<UUID> scope=List.of(employeeImportScopeId());
        authorize(actor,"EMPLOYEE",scope);
        var file=source(actor,"EMPLOYEE",scope,object);
        try{
            return missingEmployeeEmails(readWorkbook(file,"EMPLOYEE"));
        }catch(ApiFault failure){
            // 结构错误由原有预检界面逐批显示。
            if(failure.status!=400)throw failure;
            return List.of();
        }
    }

    /** 原表留空时将姓名交给页面复用单人新增的拼音规则。 */
    private List<Map<String,Object>> missingEmployeeEmails(List<ImportWorkbook.Row> rows){
        return rows.stream()
            .filter(row->!row.values().containsKey("companyEmail")&&row.values().get("name") instanceof String name&&name.length()<=128)
            .map(row->Sql.map("rowNo",row.number(),"name",row.values().get("name"))).toList();
    }

    /** 单机入口不提供文件下载、留存或发布。 */
    public List<Map<String,Object>> inspectEmployeeWorkbook(Actor actor,byte[] workbook){
        requireLocalEmployeeWorkbook(actor);
        try{return missingEmployeeEmails(ImportWorkbook.readEmployeeWorkbook(workbook));}
        catch(ApiFault error){if(error.status!=400)throw error;return List.of();}
    }

    /** 本人再验证后记录预检行和审计回执，原工作簿不保存为业务附件。 */
    public Map<String,Object> createEmployeeWorkbook(Commands.Context context,Map<String,Object> body){
        requireLocalEmployeeWorkbook(context.actor());
        Inputs.only(body,"workbook","templateVersion","generatedEmployeeEmails");
        if(!ImportWorkbook.VERSION.equals(body.get("templateVersion")))throw ApiFault.conflict("导入模板已失效，请下载当前版本");
        byte[] workbook;
        try{workbook=Base64.getDecoder().decode(Inputs.text(body,"workbook",5592408));}
        catch(IllegalArgumentException error){throw ApiFault.invalid("员工 Excel 文件编码无效");}
        List<UUID> scope=List.of(employeeImportScopeId());
        List<ImportWorkbook.Row> parsed;
        String parseError=null;
        try{parsed=ImportWorkbook.readEmployeeWorkbook(workbook);}
        catch(ApiFault error){if(error.status!=400)throw error;parsed=List.of();parseError=error.getMessage();}
        final var rows=fillEmployeeEmails(parsed,body.get("generatedEmployeeEmails"));
        final String structuralError=parseError;
        return commands.run(context,body,"IMPORT_BATCH",ApiController.COLLECTION,"CREATE_IMPORT",
            ()->requireLocalEmployeeWorkbook(context.actor()),
            ()->saveBatch(context.actor(),"EMPLOYEE",scope,null,rows,structuralError),
            id->detail(context.actor(),id));
    }

    private void requireLocalEmployeeWorkbook(Actor actor){
        if(!localEmployeeWorkbook)throw ApiFault.denied();
        authorize(actor,"EMPLOYEE",List.of());
    }

    /** 解析前仍验证文件副本摘要。 */
    private List<ImportWorkbook.Row> readWorkbook(Map<String,Object> file,String type){
        return ImportWorkbook.read(files.get().verifiedPrimary((String)file.get("storage_key"),((Number)file.get("byte_size")).longValue(),(String)file.get("sha256")),type);
    }

    /** 只补齐原表空白邮箱，结果随批次保存用于确认时复检。 */
    private List<ImportWorkbook.Row> fillEmployeeEmails(List<ImportWorkbook.Row> rows,Object supplied){
        if(supplied==null)return rows;
        if(!(supplied instanceof Map<?,?> emails)||emails.size()>1000)throw ApiFault.invalid("员工邮箱生成结果无效");
        return rows.stream().map(row->{
            Object email=emails.get(Integer.toString(row.number()));
            if(email==null||row.values().containsKey("companyEmail"))return row;
            var input=new LinkedHashMap<>(row.values());
            input.put("companyEmail",Inputs.email(Map.of("companyEmail",email)));
            return new ImportWorkbook.Row(row.number(),input);
        }).toList();
    }
    /** 从已检测的固定对象解析台账；解析在事务外，预检事务只写批次和错误，不创建业务目录。 */
    public Map<String,Object> create(Commands.Context context,Map<String,Object> body){
        Inputs.only(body,"importType","uploadObjectId","templateVersion","departmentScope","generatedEmployeeEmails");String type=ImportWorkbook.type(body.get("importType"));List<UUID> scope=scope(body.get("departmentScope"));authorize(context.actor(),type,scope);
        if(!type.equals("EMPLOYEE")&&body.containsKey("generatedEmployeeEmails"))throw ApiFault.invalid("只有员工账号导入支持自动生成公司邮箱");
        if(!ImportWorkbook.VERSION.equals(body.get("templateVersion")))throw ApiFault.conflict("导入模板已失效，请下载当前版本");
        UUID object=Inputs.uuid(body.get("uploadObjectId"));var file=source(context.actor(),type,scope,object);
        List<ImportWorkbook.Row> parsed;String parseError=null;
        try{parsed=readWorkbook(file,type);}catch(ApiFault failure){if(failure.status!=400)throw failure;parsed=List.of();parseError=failure.getMessage();}
        final List<ImportWorkbook.Row> rows=fillEmployeeEmails(parsed,body.get("generatedEmployeeEmails"));final String structuralError=parseError;
        return run(context,body,type,ApiController.COLLECTION,"CREATE_IMPORT",false,()->{
            source(context.actor(),type,scope,object);var prior=db.one("SELECT id FROM ops.import_batch WHERE created_by=? AND upload_object_id=? AND import_type=?",context.actor().userId(),object,type);if(prior!=null)return detail(context.actor(),(UUID)prior.get("id"));
            return saveBatch(context.actor(),type,scope,object,rows,structuralError);
        });
    }

    /** 两种来源复用预检与错误记录，只有受保护上传生成附件引用。 */
    private Map<String,Object> saveBatch(Actor actor,String type,List<UUID> scope,UUID object,List<ImportWorkbook.Row> rows,String structuralError){
        var checked=structuralError==null?validate(actor,type,rows,scope):List.of(new ArchiveImport.Prepared(0,Map.of(),Map.of(),List.of(Sql.map("field","工作簿","message",structuralError))));
        long errors=checked.stream().filter(row->!row.errors().isEmpty()).count();
        boolean ready=errors==0||(type.equals("EMPLOYEE")&&errors<rows.size());
        UUID id=UUID.randomUUID();
        String digest=digest(type,scope,checked);
        db.update("INSERT INTO ops.import_batch(id,import_type,upload_object_id,template_version,department_scope,created_by,state,row_count,error_count,validation_digest) VALUES(?,?,?,?,ARRAY(SELECT jsonb_array_elements_text(?::jsonb)::uuid),?,?,?,?,?)",id,type,object,ImportWorkbook.VERSION,Crypto.json(scope),actor.userId(),ready?"READY":"INVALID",rows.size(),errors,digest);
        for(var row:checked)db.update("INSERT INTO ops.import_row(batch_id,row_no,input_data,errors) VALUES(?,?,?::jsonb,?::jsonb)",id,row.number(),Crypto.json(row.input()),Crypto.json(row.errors()));
        if(object!=null)db.update("INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) VALUES(?,?,'IMPORT_BATCH',?,'BUSINESS')",UUID.randomUUID(),object,id);
        return detail(actor,id);
    }
    /** 确认时重验原快照；员工批次仅创建通过行，其他类型沿用整批提交。 */
    @Transactional
    public Map<String,Object> confirm(Commands.Context context,UUID id,Map<String,Object> body){
        Inputs.only(body,"validationDigest");db.rows("SELECT pg_advisory_xact_lock(61427002)");var batch=visible(context.actor(),id);String type=(String)batch.get("import_type");
        boolean local=batch.get("upload_object_id")==null;
        Supplier<Map<String,Object>> apply=()->{
            var locked=db.required("SELECT * FROM ops.import_batch WHERE id=? FOR UPDATE",id);Commands.version(context,locked);if(!"READY".equals(locked.get("state"))||!Crypto.equal((String)locked.get("validation_digest"),Inputs.text(body,"validationDigest",64)))throw ApiFault.conflict("本批尚未通过预检或预检摘要已变化");
            List<UUID> scope=scope(locked.get("department_scope"));
            if(!local)source(context.actor(),type,scope,(UUID)locked.get("upload_object_id"));
            var rows=db.rows("SELECT row_no,input_data FROM ops.import_row WHERE batch_id=? ORDER BY row_no",id).stream().map(row->new ImportWorkbook.Row(((Number)row.get("row_no")).intValue(),json(row.get("input_data")))).toList();
            var checked=validate(context.actor(),type,rows,scope);
            var valid=checked.stream().filter(row->row.errors().isEmpty()).toList();
            if(!Crypto.equal(digest(type,scope,checked),(String)locked.get("validation_digest"))
                || (type.equals("EMPLOYEE")?valid.isEmpty():valid.size()!=checked.size()))
                throw ApiFault.conflict("人员、部门、模板或目录已变化，请重新上传预检；本批未写入任何业务记录");
            if(type.equals("ARCHIVE"))archive.apply(context.actor(),id,valid);else applyDirectory(id,type,valid,context.actor().userId());
            db.update("UPDATE ops.import_batch SET state='CONFIRMED',confirmed_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=?",id);return detail(context.actor(),id);
        };
        if(local)return commands.run(context,body,"IMPORT_BATCH",id,"CONFIRM_IMPORT",
            ()->requireLocalEmployeeWorkbook(context.actor()),apply,prior->detail(context.actor(),prior));
        return run(context,body,type,id,"CONFIRM_IMPORT",true,apply);
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
    public Map<String,Object> detail(Actor actor,UUID id){
        var row=visible(actor,id);
        var result=Sql.view(row,"id,import_type,template_version,department_scope,state,row_count,error_count,validation_digest,row_version,created_at,confirmed_at,withdrawn_at,reason");
        Object outcome=row.get("state");
        if(outcome.equals("CONFIRMED")){
            if(row.get("import_type").equals("ARCHIVE"))outcome="ALL_CREATED_AWAITING_REVIEW";
            else outcome=((Number)row.get("error_count")).intValue()>0?"PARTIALLY_CREATED":"ALL_CREATED";
        }
        result.put("outcome",outcome);
        return result;
    }
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
        boolean employee=type.equals("EMPLOYEE");
        boolean allDepartments=employee&&scope.size()==1&&scope.contains(employeeImportScopeId());
        Set<String> keys=new HashSet<>(),emails=new HashSet<>();Map<String,String> parents=new HashMap<>();if(type.equals("DEPARTMENT"))for(var row:rows)parents.put(Objects.toString(row.values().get("code")),Objects.toString(row.values().get("parentCode"),""));
        Map<String,List<String>> batchNames=new HashMap<>();
        Map<String,List<Integer>> batchNumbers=new HashMap<>();
        if(employee)for(var row:rows){
            if(row.values().get("employeeNo") instanceof String number){
                batchNumbers.computeIfAbsent(number,ignored->new ArrayList<>()).add(row.number());
                if(row.values().get("name") instanceof String name)
                    batchNames.computeIfAbsent(name,ignored->new ArrayList<>()).add(number);
            }
        }
        List<ArchiveImport.Prepared> result=new ArrayList<>();
        for(var row:rows){var input=row.values();Map<String,Object> body=new LinkedHashMap<>();String field=type.equals("EMPLOYEE")?"employeeNo":"code";
            try{
                String key=Inputs.text(input,field,64);
                if(employee)validateEmployeeNumber(key,batchNumbers.get(key));
                else if(!keys.add(key)||db.count("SELECT count(*) FROM iam.department WHERE code=?",key)>0)
                    throw ApiFault.invalid("编号已存在或本批重复");
                body.put(field,key);
                field="name";body.put(field,Inputs.text(input,field,128));
                if(employee){
                    field="departmentCode";
                    String departmentText=Inputs.text(input,field,128);
                    var department=db.one("SELECT id FROM iam.department WHERE status='ACTIVE' AND code=?",departmentText);
                    if(department==null){
                        var names=db.rows("SELECT id FROM iam.department WHERE status='ACTIVE' AND name=? LIMIT 2",departmentText);
                        if(names.size()>1)throw ApiFault.invalid("部门名称有重复，请填部门编码");
                        if(!names.isEmpty())department=names.getFirst();
                    }
                    if(department==null||(!allDepartments&&!scope.contains(department.get("id"))))throw ApiFault.invalid("部门不存在或不在本批范围");
                    body.put("departmentId",department.get("id").toString());
                    field="companyEmail";
                    if(!input.containsKey(field))throw ApiFault.invalid("无法按姓名生成公司邮箱，请在 Excel 中填写有效的公司邮箱");
                    String email=Inputs.email(input);
                    if(!emails.add(email)||db.count("SELECT count(*) FROM iam.user_account WHERE company_email=?",email)>0)throw ApiFault.invalid("公司邮箱已存在或本批重复");
                    body.put(field,email);
                    field="jobTitle";
                    if(input.containsKey(field))body.put(field,Inputs.text(input,field,128));
                    field="supervisorEmployeeNo";
                    if(input.containsKey(field))
                        resolveSupervisor(body,key,Inputs.text(input,field,128),batchNumbers.keySet(),batchNames);
                    field="mobilePhone";
                    if(input.containsKey(field)){
                        String mobile=Inputs.text(input,field,32);
                        DirectoryService.validateEmployeeMobile(mobile);
                        body.put(field,mobile);
                    }
                    field="contactEmail";
                    if(input.containsKey(field)){
                        String contact=Inputs.text(input,field,254);
                        DirectoryService.validateEmployeeContactEmail(contact);
                        body.put(field,contact);
                    }
                }
                else{if(!key.matches("[A-Z0-9]{1,12}"))throw ApiFault.invalid("部门编码请使用 1 至 12 位大写字母或数字，以兼容档案编号");field="sortOrder";body.put(field,Inputs.integer(input.get(field),0,0,100000));field="parentCode";String parent=Objects.toString(input.get(field),"");Set<String> chain=new HashSet<>();String cursor=key;while(parents.containsKey(cursor)){if(!chain.add(cursor))throw ApiFault.invalid("本批部门存在循环层级");cursor=parents.get(cursor);}if(!parent.isEmpty()&&!parents.containsKey(parent)){var target=db.one("SELECT id FROM iam.department WHERE code=? AND status='ACTIVE'",parent);if(target==null)throw ApiFault.invalid("上级部门不存在");body.put("parentId",target.get("id").toString());}}
                result.add(new ArchiveImport.Prepared(row.number(),input,body,List.of()));
            }catch(ApiFault failure){result.add(new ArchiveImport.Prepared(row.number(),input,body,List.of(Sql.map("field",field,"message",failure.getMessage()))));}
        }
        if(employee){
            checkSupervisorChains(result);
            checkSkippedSupervisors(result);
        }
        return result;
    }

    /** 工号冲突报告来源，表内重复的每一行均列出相关原表行号。 */
    private void validateEmployeeNumber(String number,List<Integer> rows){
        boolean exists=db.count("SELECT count(*) FROM iam.user_account WHERE employee_no=?",number)>0;
        boolean repeated=rows.size()>1;
        if(!exists&&!repeated)return;
        String reason=exists?"工号 "+number+" 已在系统中存在":"工号 "+number;
        if(repeated)reason+=(exists?"，且在":" 在")+"本表第 "+rows.stream().map(String::valueOf)
            .collect(java.util.stream.Collectors.joining("、"))+" 行重复";
        throw ApiFault.invalid(reason);
    }

    /** 工号优先；同一工号在系统和表内出现时只算一位主管。 */
    private void resolveSupervisor(Map<String,Object> body,String employee,String supervisor,Set<String> batchNumbers,Map<String,List<String>> batchNames){
        if(Set.of("无","无上级","无直属主管").contains(supervisor))return;
        var existing=db.one("SELECT id,status FROM iam.user_account WHERE employee_no=?",supervisor);
        if(existing!=null){
            if(!"ACTIVE".equals(existing.get("status")))throw ApiFault.invalid("直属主管工号对应的账号不在职");
            body.put("supervisorId",existing.get("id").toString());
        }else if(batchNumbers.contains(supervisor)){
            body.put("batchSupervisorNo",supervisor);
        }else{
            var existingNames=db.rows("SELECT id,employee_no FROM iam.user_account WHERE name=? AND status='ACTIVE' LIMIT 2",supervisor);
            var candidates=new LinkedHashSet<>(batchNames.getOrDefault(supervisor,List.of()));
            for(var match:existingNames)candidates.add((String)match.get("employee_no"));
            if(candidates.isEmpty())throw ApiFault.invalid("找不到直属主管，请填写在职或同批员工的工号");
            if(candidates.size()>1)throw ApiFault.invalid("有多个不同工号的员工同名，请填写直属主管工号");
            if(!existingNames.isEmpty())body.put("supervisorId",existingNames.getFirst().get("id").toString());
            else{
                String number=candidates.getFirst();
                if(number.equals(supervisor))body.put("batchSupervisorNo",number);
                else resolveSupervisor(body,employee,number,batchNumbers,batchNames);
            }
        }
        if(employee.equals(body.get("batchSupervisorNo")))throw ApiFault.invalid("直属主管不能填写本人；没有上级请留空");
    }

    /** 预检时提示同批主管关系中的循环。 */
    private void checkSupervisorChains(List<ArchiveImport.Prepared> rows){
        Map<String,String> supervisors=new HashMap<>();
        for(var row:rows)if(row.errors().isEmpty()&&row.body().get("batchSupervisorNo") instanceof String supervisor)
            supervisors.put((String)row.body().get("employeeNo"),supervisor);
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);
            if(!row.errors().isEmpty())continue;
            String number=(String)row.body().get("employeeNo");
            Set<String> chain=new HashSet<>();
            while(supervisors.containsKey(number)){
                if(!chain.add(number)){
                    rows.set(i,new ArchiveImport.Prepared(row.number(),row.input(),Map.of(),
                        List.of(Sql.map("field","supervisorEmployeeNo","message","本批直属主管关系存在循环"))));
                    break;
                }
                number=supervisors.get(number);
            }
        }
    }

    /** 同批主管失败时，下属保留明确原因；不在缺少主管的情况下创建关系。 */
    private void checkSkippedSupervisors(List<ArchiveImport.Prepared> rows){
        Set<String> available=new HashSet<>();
        for(var row:rows)if(row.errors().isEmpty())available.add((String)row.body().get("employeeNo"));
        boolean changed;
        do{
            changed=false;
            for(int i=0;i<rows.size();i++){
                var row=rows.get(i);
                if(row.errors().isEmpty()&&row.body().get("batchSupervisorNo") instanceof String supervisor&&!available.contains(supervisor)){
                    available.remove((String)row.body().get("employeeNo"));
                    rows.set(i,new ArchiveImport.Prepared(row.number(),row.input(),Map.of(),
                        List.of(Sql.map("field","supervisorEmployeeNo","message","同批直属主管未通过预检"))));
                    changed=true;
                }
            }
        }while(changed);
    }

    /** 先创建同批主管或上级部门，再创建下属，整批仍在同一事务提交。 */
    private void applyDirectory(UUID batch,String type,List<ArchiveImport.Prepared> rows,UUID operatorId){
        boolean employee=type.equals("EMPLOYEE");
        Map<String,UUID> created=new HashMap<>();
        List<ArchiveImport.Prepared> remaining=new ArrayList<>(rows);
        while(!remaining.isEmpty()){
            boolean progressed=false;
            for(var iterator=remaining.iterator();iterator.hasNext();){
                var row=iterator.next();
                var body=new LinkedHashMap<>(row.body());
                if(employee){
                    String supervisor=(String)body.remove("batchSupervisorNo");
                    if(supervisor!=null){
                        if(!created.containsKey(supervisor))continue;
                        body.put("supervisorId",created.get(supervisor).toString());
                    }
                }else if(!body.containsKey("parentId")&&row.input().get("parentCode") instanceof String parent){
                    if(!created.containsKey(parent))continue;
                    body.put("parentId",created.get(parent).toString());
                }
                var object=employee?directory.createImportedUser(body,operatorId):directory.createDepartment(body);
                UUID id=Inputs.uuid(object.get("id"));
                created.put((String)body.get(employee?"employeeNo":"code"),id);
                db.update("UPDATE ops.import_row SET object_type=?,object_id=?,result='CREATED' WHERE batch_id=? AND row_no=?",type,id,batch,row.number());
                iterator.remove();
                progressed=true;
            }
            if(!progressed)throw ApiFault.conflict("主管或部门关系已变化，未提交本批");
        }
    }
}
