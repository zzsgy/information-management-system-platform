package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.intake.ImportWorkbook;
import com.company.platform.intake.ArchiveBoxWorkbook;
import org.springframework.stereotype.Service;
import java.util.*;

/** 档案台账领域适配：只新增盒件，整批提交和撤回不允许修改批次外既有目录。 */
@Service
public class ArchiveImport {
    /** 与普通目录共用身份范围、目录规则和领域写入。 */
    private final Sql db;private final ArchiveAuthority authority;private final ArchiveCatalogService catalog;
    /** 撤回未发布导入时，复用原流程终止与候选恢复规则。 */
    private final com.company.platform.workflow.WorkflowEngine workflows;
    public ArchiveImport(Sql db,ArchiveAuthority authority,ArchiveCatalogService catalog,com.company.platform.workflow.WorkflowEngine workflows){this.db=db;this.authority=authority;this.catalog=catalog;this.workflows=workflows;}
    /** 规范化后的单行数据；错误始终附带原表行号和相关字段。 */
    public record Prepared(/** 原行号。 */ int number,/** 原始只读行。 */ Map<String,Object> input,/** 规范领域正文。 */ Map<String,Object> body,/** 首个明确错误。 */ List<Map<String,Object>> errors) { }
    /** 当前授权按每个部门重新确认，不以导入者过去的角色代替权限。 */
    public void authorize(Actor actor,List<UUID> departments){if(departments.isEmpty())throw ApiFault.invalid("请指定部门范围");for(UUID department:departments)authority.requireManage(actor,department);}
    /** 整批只读预检；先解析盒，再校验其件，可引用本批或已有可管理盒。 */
    public List<Prepared> validate(Actor actor,List<ImportWorkbook.Row> rows,List<UUID> scope){
        authorize(actor,scope);Map<Integer,Prepared> prepared=new HashMap<>();Map<String,Map<String,Object>> boxes=new HashMap<>();Set<String> keys=new HashSet<>(),numbers=new HashSet<>(),items=new HashSet<>();
        for(String pass:List.of("BOX","RECORD"))for(var row:rows){
            var source=row.values();var input=source;String type=Objects.toString(input.get("objectType"),"");if(!type.equals(pass)&&!(pass.equals("BOX")&&!type.equals("RECORD")))continue;
            Map<String,Object> body=new LinkedHashMap<>();String field="objectType";
            try{
                if(ArchiveBoxWorkbook.FORMAT.equals(input.get("sourceFormat"))){field="档案盒台账";input=boxLedger(input,scope);}
                Inputs.choice(type,"BOX","RECORD");field="rowKey";String key=Inputs.text(input,"rowKey",128);if(!keys.add(key))throw ApiFault.invalid("本批行号重复");
                field="departmentCode";var department=db.one("SELECT id,code FROM iam.department WHERE code=? AND status='ACTIVE'",Inputs.text(input,field,64));if(department==null||!scope.contains(department.get("id")))throw ApiFault.invalid("部门不存在或不在本批批准范围");UUID departmentId=(UUID)department.get("id");
                if(!((String)department.get("code")).matches("[A-Z0-9]{1,12}(?:/[A-Z0-9]{1,12})?"))throw ApiFault.invalid("部门编码不符合档案编号规则");
                for(String name:List.of("title","safeTitle","classification","retentionCode","archiveDate","numberMonth","categoryCode","carrierType","templateVersionId","location","abstract","formedDate","notes"))if(input.containsKey(name))body.put(name,input.get(name));
                field="ownerEmployeeNo";var owner=db.one("SELECT id FROM iam.user_account WHERE employee_no=? AND department_id=? AND status='ACTIVE'",Inputs.text(input,field,64),departmentId);if(owner==null)throw ApiFault.invalid("责任人必须为当前部门有效员工");body.put("ownerId",owner.get("id").toString());
                field="keywords";body.put(field,Arrays.asList(Inputs.text(input,field,2000).split("[;；]")));
                field="extensionValues";body.put(field,input.containsKey(field)?Crypto.JSON.readValue(Inputs.text(input,field,20000),Map.class):Map.of());
                Map<String,Object> parent=null;field="boxKey/boxNo";
                if(type.equals("BOX")){
                    if(input.containsKey("boxKey")||input.containsKey("boxNo")||input.containsKey("itemNo"))throw ApiFault.invalid("盒行不能填写所属盒或件号");body.put("departmentId",departmentId.toString());
                }else{
                    if(input.containsKey("boxKey")==input.containsKey("boxNo"))throw ApiFault.invalid("本批盒行号与已有盒号必须且只能填写一项");
                    if(input.containsKey("boxKey")){parent=boxes.get(input.get("boxKey"));if(parent==null)throw ApiFault.invalid("本批盒行不存在或未通过校验");}
                    else{var box=db.one("SELECT * FROM archive.box WHERE box_no=? AND department_id=?",Inputs.text(input,"boxNo",128),departmentId);if(box==null)throw ApiFault.invalid("已有盒不在当前部门范围");
                        if(!Set.of("DRAFT","PUBLISHED").contains(box.get("state"))||db.count("SELECT count(*) FROM archive.physical_loan WHERE box_id=? AND state='IN_USE'",box.get("id"))>0)throw ApiFault.conflict("盒正在复核、借出或已撤下");
                        parent=Sql.view(box,"id,department_id,carrier_type,classification,retention_code,row_version");body.put("boxId",box.get("id").toString());}
                    if(!departmentId.toString().equals(parent.get("departmentId").toString()))throw ApiFault.invalid("盒和件必须属于同一部门");
                    field="classification";if(input.containsKey(field)&&!input.get(field).equals(parent.get(field)))throw ApiFault.invalid("件密级必须与盒一致");body.remove("classification");body.remove("numberMonth");
                    body.putIfAbsent("retentionCode",parent.get("retentionCode"));field="itemNo";int item=Inputs.integer(Inputs.text(input,field,10),0,1,1000000);body.put(field,item);
                    String parentKey=Objects.toString(body.get("boxId"),Objects.toString(input.get("boxKey")));if(!items.add(parentKey+":"+item))throw ApiFault.invalid("本批同盒件号重复");
                    if(body.containsKey("boxId")&&db.count("SELECT count(*) FROM archive.record WHERE box_id=? AND item_no=?",Inputs.uuid(body.get("boxId")),item)>0)throw ApiFault.invalid("该盒件号已被使用，导入只允许新增");
                }
                field="originalNumber";if(input.containsKey(field)){String original=Inputs.text(input,field,256);if(!numbers.add(departmentId+":"+type+":"+original)||db.count("SELECT count(*) FROM archive.legacy_number_map WHERE department_id=? AND object_type=? AND legacy_number=?",departmentId,type,original)>0)throw ApiFault.invalid("原有编号重复");}
                field="目录字段";catalog.validateNew(type,body,parent);if(type.equals("BOX"))boxes.put(key,body);
                prepared.put(row.number(),new Prepared(row.number(),source,body,List.of()));
            }catch(Exception error){String message=error instanceof ApiFault?error.getMessage():"日期或扩展字段格式不正确";prepared.put(row.number(),new Prepared(row.number(),source,body,List.of(Sql.map("field",field,"message",message))));}
        }
        return rows.stream().map(row->prepared.get(row.number())).toList();
    }
    /** 中文台账按实际部门和同部门责任人定位，模板全公司通用；不从参考表创建身份或推断角色。 */
    private Map<String,Object> boxLedger(Map<String,Object> input,List<UUID> scope){
        String departmentName=Inputs.text(input,"departmentName",128),ownerName=Inputs.text(input,"ownerName",128);
        var units=db.rows("SELECT id,code FROM iam.department WHERE name=? AND status='ACTIVE'",departmentName);
        if(units.size()!=1||!scope.contains(units.getFirst().get("id")))throw ApiFault.invalid("所属部门：名称不存在、重名或超出当前可登记范围，请核对系统部门名称");
        var department=units.getFirst();var people=db.rows("SELECT employee_no FROM iam.user_account WHERE name=? AND department_id=? AND status='ACTIVE'",ownerName,department.get("id"));
        if(people.size()!=1)throw ApiFault.invalid("档案负责人：须为所属部门的有效人员；不存在或同部门重名时请先核对人员信息");
        Map<String,Object> body=new LinkedHashMap<>(input);body.put("departmentCode",department.get("code"));body.put("ownerEmployeeNo",people.getFirst().get("employee_no"));body.put("categoryCode","COMMON");
        String fileType=Inputs.text(input,"fileType",100);body.put("keywords",fileType);
        body.put("classification",switch(Inputs.text(input,"classification",32)){case "一般","GENERAL"->"GENERAL";case "重要","IMPORTANT"->"IMPORTANT";case "核心","CORE"->"CORE";default->throw ApiFault.invalid("保密级别：请选择一般、重要或核心");});
        String preservation=Inputs.text(input,"preservationType",100);body.put("carrierType",switch(preservation){case "纸质原件","纸质复印件","纸质","PAPER"->"PAPER";case "电子","电子文件","电子原件","电子版","ELECTRONIC"->"ELECTRONIC";case "混合","纸质及电子","纸质+电子","MIXED"->"MIXED";default->throw ApiFault.invalid("保存类型：请选择纸质、电子或纸质+电子");});
        if(!body.get("carrierType").equals("ELECTRONIC"))body.put("location","柜 "+Inputs.text(input,"cabinetNo",100)+" / 列 "+Inputs.text(input,"columnNo",100)+" / 层 "+Inputs.text(input,"layerNo",100));
        String retention=Inputs.text(input,"retentionCode",32);body.put("retentionCode",switch(retention){case "5","05","5年"->"05";case "10","10年"->"10";case "30","30年"->"30";case "永久","YJ"->"YJ";default->throw ApiFault.invalid("有效期限 / 年：当前支持 5、10、30 或永久");});
        java.time.LocalDate archived=ledgerDate(Inputs.text(input,"archiveDate",32),"存档日期");body.put("archiveDate",archived.toString());body.put("numberMonth",archived.withDayOfMonth(1).toString());
        var template=db.one("SELECT id FROM archive.directory_template WHERE template_key='COMMON_BOX_LEDGER' AND state='ACTIVE' ORDER BY version_no DESC LIMIT 1");if(template==null)throw ApiFault.invalid("档案盒通用台账模板尚未启用，请联系管理员");body.put("templateVersionId",template.get("id").toString());
        Map<String,Object> extensions=new LinkedHashMap<>();
        for(String key:List.of("originalNumber","fileType","cabinetNo","columnNo","layerNo","inventory","preservationType","sourceStatus","sourceUpdatedAt"))if(input.containsKey(key))extensions.put(key,Inputs.text(input,key,key.equals("inventory")?2000:256));
        // 旧台账明确写出原件/复印件时沿用原文；仅写“纸质”不猜测标签。
        if(input.containsKey("paperTags"))extensions.put("paperTags",Arrays.asList(Inputs.text(input,"paperTags",100).split("[、,，;；]")));
        else if(List.of("纸质原件","纸质复印件").contains(preservation))extensions.put("paperTags",List.of(preservation));
        if(input.containsKey("sourceExpiryDate"))extensions.put("sourceExpiryDate",ledgerDate(Inputs.text(input,"sourceExpiryDate",32),"到期日期").toString());
        if(input.containsKey("copyCount"))extensions.put("copyCount",Inputs.integer(input.get("copyCount"),0,1,1000000));
        body.put("extensionValues",Crypto.json(extensions));return body;
    }
    /** Excel 文本日期允许不补零的月日，输出固定 ISO 日历日期。 */
    private java.time.LocalDate ledgerDate(String text,String label){try{return java.time.LocalDate.parse(text,java.time.format.DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(java.time.format.ResolverStyle.STRICT));}catch(RuntimeException error){throw ApiFault.invalid(label+"：请填写有效年月日");}}
    /** 在调用方同一事务中全部新增；任意异常回滚编号、实物、旧号及批次结果。 */
    public void apply(Actor actor,UUID batch,List<Prepared> rows){
        Map<String,UUID> boxes=new HashMap<>();
        for(String type:List.of("BOX","RECORD"))for(var row:rows)if(type.equals(row.input().get("objectType"))){
            var body=new LinkedHashMap<>(row.body());if(type.equals("RECORD")&&!body.containsKey("boxId"))body.put("boxId",boxes.get(row.input().get("boxKey")).toString());
            var object=type.equals("BOX")?catalog.insertBox(actor,body):catalog.insertRecord(actor,body);UUID id=Inputs.uuid(object.get("id"));if(type.equals("BOX"))boxes.put((String)row.input().get("rowKey"),id);
            db.update("UPDATE ops.import_row SET object_type=?,object_id=?,result='AWAITING_REVIEW' WHERE batch_id=? AND row_no=?",type,id,batch,row.number());
            if(row.input().containsKey("originalNumber"))db.update("INSERT INTO archive.legacy_number_map(id,department_id,object_type,legacy_number,object_id,batch_id) VALUES(?,?,?,?,?,?)",UUID.randomUUID(),Inputs.uuid(object.get("departmentId")),type,row.input().get("originalNumber"),id,batch);
        }
    }
    /** 只撤回未发布的整批新增对象，已有盒及后续其他批次成员不被连带撤回。 */
    public void withdraw(Commands.Context context,UUID batch){
        Actor actor=context.actor();
        var rows=db.rows("SELECT object_type,object_id FROM ops.import_row WHERE batch_id=? ORDER BY object_type,object_id",batch);
        // 与审批事务保持流程在前、目录在后的锁顺序，避免相互等待或在复核提交后盲撤回。
        var pending=db.rows("SELECT w.* FROM workflow.instance w JOIN ops.import_row i ON i.object_id=w.business_id WHERE i.batch_id=? AND w.business_type='REGISTRATION' AND w.state='PENDING' ORDER BY w.id FOR UPDATE OF w",batch);
        for(var flow:pending)if(!actor.userId().equals(flow.get("applicant_id")))throw ApiFault.conflict("本批含其他人员发起的在审流程，请先由原申请人处理");
        for(var row:rows){String type=(String)row.get("object_type"),table=type.equals("BOX")?"archive.box":"archive.record";var object=db.required("SELECT * FROM "+table+" WHERE id=? FOR UPDATE",row.get("object_id"));authority.requireManage(actor,(UUID)object.get("department_id"));
            if(!Set.of("DRAFT","PENDING_REVIEW").contains(object.get("state"))||object.get("current_revision_id")!=null)throw ApiFault.conflict("本批含已发布或已撤回资料，不能整批撤回");
            if("PENDING_REVIEW".equals(object.get("state"))&&pending.stream().noneMatch(flow->flow.get("business_id").equals(object.get("id"))))throw ApiFault.conflict("在审流程已变化，请重新核对本批");
            if(type.equals("BOX")&&db.count("SELECT count(*) FROM archive.record r WHERE r.box_id=? AND NOT EXISTS(SELECT 1 FROM ops.import_row i WHERE i.batch_id=? AND i.object_id=r.id)",object.get("id"),batch)>0)throw ApiFault.conflict("本批新增盒已关联其他来源的文件");
            if(type.equals("RECORD")&&db.count("SELECT count(*) FROM archive.scan_item WHERE record_id=?",object.get("id"))>0)throw ApiFault.conflict("本批资料已有扫描交接，须先处理对应批次");
        }
        for(var flow:pending){UUID id=(UUID)flow.get("id");var nested=new Commands.Context(actor,context.request(),context.operation(),context.route()+"/workflow/"+id,null,((Number)flow.get("row_version")).intValue());workflows.withdraw(nested,id,Sql.map("reason","原导入批次整体撤回"));}
        for(var row:rows){String table=row.get("object_type").equals("BOX")?"archive.box":"archive.record";db.update("UPDATE "+table+" SET state='WITHDRAWN',row_version=row_version+1 WHERE id=?",row.get("object_id"));}
        db.update("UPDATE ops.import_row SET result='WITHDRAWN' WHERE batch_id=?",batch);
    }
}
