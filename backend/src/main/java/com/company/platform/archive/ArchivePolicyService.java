package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** 基础开放先走真实业务审批；不接受一个未经核验的approvedBy或批准文字直接扩权。 */
@Service
public class ArchivePolicyService implements WorkflowBusiness {
    /** 基础批准与临时借阅分开，来源撤销不删除其他有效批准。 */
    private final Sql db;private final ArchiveAuthority authority;private final ArchiveCommands commands;private final WorkflowEngine workflows;private final IdentityService identity;
    public ArchivePolicyService(Sql db,ArchiveAuthority authority,ArchiveCommands commands,WorkflowEngine workflows,IdentityService identity) {this.db=db;this.authority=authority;this.commands=commands;this.workflows=workflows;this.identity=identity;}
    /** 获权目录维护者为具体文件选择人员批准名单，只返回当前有效档案员工的最小摘要。 */
    public Map<String,Object> subjects(Actor actor,UUID record,String keyword) {
        authority.requireManage(actor,(UUID)authority.record(record).get("department_id"));if(keyword.length()>100) throw ApiFault.invalid("人员检索条件过长");
        String match="%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        return Sql.map("items",db.rows("SELECT id,name,employee_no FROM iam.user_account WHERE status='ACTIVE' AND iam.archive_access_enabled(id) AND (name ILIKE ? OR employee_no ILIKE ?) ORDER BY employee_no LIMIT 100",match,match).stream().map(row->Sql.view(row,"id,name,employee_no")).toList());
    }
    /** 基础开放专用业务标识，与临时借阅分别存储批准来源。 */
    @Override public String type() {return "ACCESS_POLICY";}
    /** 提交安全题名、本部门一般文件开放或具体VIEW名单，批准对象和接收范围全部冻结。 */
    public Map<String,Object> propose(Commands.Context context,Map<String,Object> body) {
        Inputs.only(body,"objectType","objectId","departmentScope","titleVisibility","safeSearchFields","allowOwnerDepartmentView","recordRevisionId","pdfVersionId","userIds","validFrom","validUntil","reason");
        if(!"RECORD".equals(body.get("objectType"))) throw ApiFault.invalid("本轮按具体文件批准，不从盒传播正文权");UUID record=Inputs.uuid(body.get("objectId"));Inputs.text(body,"reason",1000);
        String visibility=Inputs.choice(body.get("titleVisibility"),"SAFE_TITLE","OWNER_DEPARTMENT","HIDDEN");List<UUID> departments=ids(body.get("departmentScope")),users=ids(body.getOrDefault("userIds",List.of()));
        if(!(body.get("allowOwnerDepartmentView") instanceof Boolean)) throw ApiFault.invalid("请明确是否开放本部门一般文件阅读");boolean baseline=Boolean.TRUE.equals(body.get("allowOwnerDepartmentView"));
        if(!(body.get("safeSearchFields") instanceof List<?> fields)||!Set.of("safeTitle").containsAll(fields)||fields.size()>1) throw ApiFault.invalid("当前仅允许批准安全题名检索");
        // PostgreSQL时间精度为微秒；送审前统一截断，避免JDBC与SQL解析对半微秒采用不同舍入规则。
        Instant from=Instant.parse(Inputs.text(body,"validFrom",64)).truncatedTo(java.time.temporal.ChronoUnit.MICROS),until=body.get("validUntil")==null?null:Instant.parse(Inputs.text(body,"validUntil",64)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);if(until!=null&&!until.isAfter(from)) throw ApiFault.invalid("批准期限无效");if(!users.isEmpty()&&until==null) throw ApiFault.invalid("具体人员名单必须指定失效时刻");
        return commands.run(context,body,"ARCHIVE_POLICY",record,"REQUEST_ACCESS_POLICY",true,()->authority.requireManage(context.actor(),(UUID)authority.record(record).get("department_id")),()->{
            var row=db.required("SELECT * FROM archive.record WHERE id=? FOR UPDATE",record);if(!"PUBLISHED".equals(row.get("state"))) throw ApiFault.conflict("基础开放只适用于已发布文件");
            if("PAPER".equals(row.get("carrier_type"))&&(baseline||!users.isEmpty()))throw ApiFault.invalid("纯纸质仅批准安全题名，不生成电子阅读授权");
            if(baseline&&!"GENERAL".equals(row.get("classification"))) throw ApiFault.denied();
            if((baseline||!users.isEmpty())&&(!Objects.equals(Inputs.uuid(body.get("recordRevisionId")),row.get("current_revision_id"))||!Objects.equals(Inputs.uuid(body.get("pdfVersionId")),row.get("current_pdf_version_id")))) throw ApiFault.conflict("开放必须绑定当前修订和PDF");
            if(!visibility.equals("HIDDEN")&&(row.get("safe_title")==null||!fields.contains("safeTitle"))) throw ApiFault.invalid("请先登记安全题名并明确批准检索字段");
            for(UUID department:departments) db.required("SELECT id FROM iam.department WHERE id=? AND status='ACTIVE'",department);for(UUID user:users) db.required("SELECT id FROM iam.user_account WHERE id=? AND status='ACTIVE'",user);
            if(db.count("SELECT count(*) FROM workflow.instance WHERE business_type='ACCESS_POLICY' AND business_id=? AND state='PENDING'",record)>0) throw ApiFault.conflict("该文件已有待处理基础授权申请");
            var snapshot=new LinkedHashMap<String,Object>(body);snapshot.put("departmentId",row.get("department_id").toString());snapshot.put("classification",row.get("classification"));snapshot.put("title",row.get("title"));snapshot.put("safeTitle",row.get("safe_title"));snapshot.put("fileNo",row.get("file_no"));snapshot.put("recordRevisionId",row.get("current_revision_id").toString());snapshot.put("pdfVersionId",Objects.toString(row.get("current_pdf_version_id"),null));snapshot.put("basePolicyVersion",db.count("SELECT COALESCE(max(policy_version),0) FROM archive.access_policy WHERE object_type='RECORD' AND object_id=?",record));
            snapshot.put("validFrom",from.toString());snapshot.put("validUntil",until==null?null:until.toString());
            List<FixedBorrowRoute.Duty> route=new ArrayList<>();route.add(new FixedBorrowRoute.Duty("DEPARTMENT_MANAGER",(UUID)row.get("department_id")));if(!"GENERAL".equals(row.get("classification"))) route.add(new FixedBorrowRoute.Duty("GENERAL_MANAGER",null));route.add(new FixedBorrowRoute.Duty("ARCHIVE_ADMIN",null));
            UUID flow=workflows.create(context.actor(),"ACCESS_POLICY",record,null,(String)row.get("classification"),0,snapshot,route);return Sql.map("id",flow,"state","PENDING","workflowInstanceId",flow);
        },flow->Sql.map("id",flow,"state",db.required("SELECT state FROM workflow.instance WHERE id=?",flow).get("state"),"workflowInstanceId",flow));
    }
    /** 原版本、等级和原策略基准都必须未变化，过期批准不执行扩权。 */
    @Override public void verify(Map<String,Object> instance) {
        var snapshot=WorkflowEngine.snapshot(instance);var row=snapshot.get("pdfVersionId")==null?authority.record(Inputs.uuid(snapshot.get("objectId"))):authority.published(Inputs.uuid(snapshot.get("recordRevisionId")),Inputs.uuid(snapshot.get("pdfVersionId")));if(snapshot.get("pdfVersionId")==null&&(!"PAPER".equals(row.get("carrier_type"))||!"PUBLISHED".equals(row.get("state"))||!Inputs.uuid(snapshot.get("recordRevisionId")).equals(row.get("current_revision_id"))))throw ApiFault.conflict("纸质目录修订已变化");
        if(!Inputs.uuid(snapshot.get("objectId")).equals(row.get("id"))||!snapshot.get("classification").equals(row.get("classification"))||((Number)snapshot.get("basePolicyVersion")).longValue()!=db.count("SELECT COALESCE(max(policy_version),0) FROM archive.access_policy WHERE object_type='RECORD' AND object_id=?",row.get("id"))) throw ApiFault.conflict("基础授权送审范围已变化");
        if(snapshot.get("validUntil")!=null&&!Instant.parse(snapshot.get("validUntil").toString()).isAfter(Instant.now())) throw ApiFault.conflict("申请的基础开放期限已失效");
    }
    /** 完整批准自动生成精确策略和已批准人员VIEW名单，禁止客户端手动创建BORROW授权。 */
    @Override public void finish(Map<String,Object> instance,boolean approved,UUID approver) {
        if(!approved) return;verify(instance);var snapshot=WorkflowEngine.snapshot(instance);UUID record=Inputs.uuid(snapshot.get("objectId"));
        // 先锁对象再检查基准，避免两个历史申请同时生效；旧策略保留历史但停止授权。
        db.required("SELECT id FROM archive.record WHERE id=? FOR UPDATE",record);verify(instance);
        db.update("UPDATE archive.access_policy SET state='REVOKED' WHERE object_type='RECORD' AND object_id=? AND state='ACTIVE'",record);
        int version=((Number)snapshot.get("basePolicyVersion")).intValue()+1;UUID policy=UUID.randomUUID();
        db.update("INSERT INTO archive.access_policy(id,object_type,object_id,department_scope,title_visibility,safe_search_fields,policy_version,approval_reference,approved_by,workflow_instance_id,valid_from,valid_until,allow_owner_department_view,record_revision_id,pdf_version_id) VALUES(?,'RECORD',?,ARRAY(SELECT jsonb_array_elements_text(?::jsonb))::uuid[],?,ARRAY(SELECT jsonb_array_elements_text(?::jsonb))::varchar[],?,?,?,?,?,?,?,?,?)",policy,record,Crypto.json(snapshot.get("departmentScope")),snapshot.get("titleVisibility"),Crypto.json(snapshot.get("safeSearchFields")),version,"workflow:"+instance.get("id"),approver,instance.get("id"),timestamp(snapshot.get("validFrom")),timestamp(snapshot.get("validUntil")),snapshot.get("allowOwnerDepartmentView"),Inputs.uuid(snapshot.get("recordRevisionId")),snapshot.get("pdfVersionId")==null?null:Inputs.uuid(snapshot.get("pdfVersionId")));
        for(UUID user:ids(snapshot.getOrDefault("userIds",List.of()))) db.update("INSERT INTO archive.access_grant(id,user_id,record_id,record_revision_id,pdf_version_id,action,source_type,source_policy_id,approval_reference,valid_from,valid_until,policy_version) VALUES(?,?,?,?,?,'VIEW','BASELINE',?,?,?,?,?)",UUID.randomUUID(),user,record,Inputs.uuid(snapshot.get("recordRevisionId")),Inputs.uuid(snapshot.get("pdfVersionId")),policy,"workflow:"+instance.get("id"),timestamp(snapshot.get("validFrom")),timestamp(snapshot.get("validUntil")),version);
        commands.event("ARCHIVE_POLICY",policy,"BASELINE_APPROVED");
    }
    /** 必要审批人查看冻结受众和具体员工姓名，避免只凭UUID作出决定。 */
    @Override public Map<String,Object> view(Actor actor,Map<String,Object> instance) {var snapshot=WorkflowEngine.snapshot(instance);return Sql.map("snapshot",snapshot,"audiences",db.rows("SELECT id,name FROM iam.department WHERE id IN (SELECT jsonb_array_elements_text(?::jsonb)::uuid) ORDER BY name",Crypto.json(snapshot.get("departmentScope"))),"users",db.rows("SELECT id,name FROM iam.user_account WHERE id IN (SELECT jsonb_array_elements_text(?::jsonb)::uuid) ORDER BY name",Crypto.json(snapshot.getOrDefault("userIds",List.of()))));}
    /** 只撤销指定来源并提升该用户授权/登录代次；其他借阅或基础批准继续独立核验。 */
    @Transactional public Map<String,Object> revoke(Commands.Context context,UUID id,Map<String,Object> body) {
        Inputs.only(body,"reason");Inputs.text(body,"reason",1000);authority.requireSuper(context.actor(),"ARCHIVE.GRANTS_MANAGE");var preflight=db.required("SELECT user_id FROM archive.access_grant WHERE id=?",id);db.rows("SELECT id FROM iam.user_account WHERE id IN (?,?) ORDER BY id FOR UPDATE",context.actor().userId(),preflight.get("user_id"));
        return commands.run(context,body,"ARCHIVE_GRANT",id,"REVOKE_ARCHIVE_GRANT",true,()->authority.requireSuper(context.actor(),"ARCHIVE.GRANTS_MANAGE"),()->{
            var row=db.required("SELECT * FROM archive.access_grant WHERE id=? FOR UPDATE",id);if(context.version()==null||context.version()!=((Number)row.get("policy_version")).intValue()||!Set.of("ACTIVE","EXPIRED").contains(row.get("state"))) throw ApiFault.conflict("目标授权已变化");
            db.update("UPDATE archive.access_grant SET state='REVOKED',policy_version=policy_version+1,revoked_by=?,revoked_at=CURRENT_TIMESTAMP,revocation_reason=? WHERE id=?",context.actor().userId(),body.get("reason"),id);identity.invalidate((UUID)row.get("user_id"),"ARCHIVE_GRANT_REVOKED",context.operation());return Sql.map("objectId",id,"state","REVOKED");
        },key->Sql.map("objectId",key,"state",db.required("SELECT state FROM archive.access_grant WHERE id=?",key).get("state")));
    }
    /** 收紧本部门基础开放或安全题名策略；旧阅读来源和依赖名单同时停止，不删除借阅批准。 */
    @Transactional public Map<String,Object> revokePolicy(Commands.Context context,UUID id,Map<String,Object> body) {
        Inputs.only(body,"reason");Inputs.text(body,"reason",1000);authority.requireSuper(context.actor(),"ARCHIVE.GRANTS_MANAGE");TreeSet<UUID> people=new TreeSet<>();
        for(var person:db.rows("SELECT user_id FROM archive.usage_request WHERE source_policy_id=? AND expires_at>CURRENT_TIMESTAMP UNION SELECT user_id FROM archive.access_grant WHERE source_policy_id=? AND state='ACTIVE'",id,id)) people.add((UUID)person.get("user_id"));
        TreeSet<UUID> affected=new TreeSet<>(people);people.add(context.actor().userId());
        for(UUID person:people) db.required("SELECT id FROM iam.user_account WHERE id=? FOR UPDATE",person);
        return commands.run(context,body,"ARCHIVE_POLICY",id,"REVOKE_ARCHIVE_POLICY",true,()->authority.requireSuper(context.actor(),"ARCHIVE.GRANTS_MANAGE"),()->{
            var policy=db.required("SELECT * FROM archive.access_policy WHERE id=? FOR UPDATE",id);if(context.version()==null||context.version()!=((Number)policy.get("policy_version")).intValue()||!"ACTIVE".equals(policy.get("state"))) throw ApiFault.conflict("基础策略已变化");
            db.update("UPDATE archive.access_policy SET state='REVOKED',policy_version=policy_version+1 WHERE id=?",id);
            for(UUID person:affected) identity.invalidate(person,"ARCHIVE_POLICY_REVOKED",UUID.randomUUID());
            return Sql.map("objectId",id,"state","REVOKED");
        },key->Sql.map("objectId",key,"state",db.required("SELECT state FROM archive.access_policy WHERE id=?",key).get("state")));
    }
    /** 管理者查看实际批准状态及版本，用于只撤选中策略，不作为正文访问接口。 */
    public Map<String,Object> policyList(Actor actor,Map<String,String> query) {
        authority.requireSuper(actor,"ARCHIVE.GRANTS_MANAGE");Inputs.only(query,"objectType","objectId","page","pageSize","number","visibility","effective","untilFrom","untilTo");if(query.containsKey("objectType")) Inputs.choice(query.get("objectType"),"RECORD");
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);String where=" FROM archive.access_policy p JOIN archive.record r ON r.id=p.object_id WHERE p.object_type='RECORD'";List<Object> args=new ArrayList<>();if(query.containsKey("objectId")){where+=" AND p.object_id=?";args.add(Inputs.uuid(query.get("objectId")));}String effective="archive.policy_approved(p.id) AND p.record_revision_id=r.current_revision_id AND p.pdf_version_id IS NOT DISTINCT FROM r.current_pdf_version_id AND r.state='PUBLISHED'";
        where+=new ListConditions(query,args).contains("number","r.file_no").choice("visibility","p.title_visibility","HIDDEN","TITLE_ONLY")
            .flag("effective",effective).dates("until","p.valid_until").sql();
        long total=db.count("SELECT count(*)"+where,args.toArray());args.add(size);args.add((page-1)*size);
        return Sql.map("items",db.rows("SELECT p.*,r.file_no,"+effective+" AS effective"+where+" ORDER BY p.valid_from DESC,p.id LIMIT ? OFFSET ?",args.toArray()).stream().map(row->Sql.view(row,"id,object_id,file_no,policy_version,state,title_visibility,allow_owner_department_view,valid_from,valid_until,effective")).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 当前本人授权摘要不含原件键和他人名单，管理人需独立档案撤权能力。 */
    public Map<String,Object> grants(Actor actor,Map<String,String> query) {
        actor.require("ARCHIVE.ACCESS");Inputs.only(query,"userId","recordId","page","pageSize","number","source","effective","untilFrom","untilTo");UUID user=query.containsKey("userId")?Inputs.uuid(query.get("userId")):actor.userId();if(!user.equals(actor.userId())) authority.requireSuper(actor,"ARCHIVE.GRANTS_MANAGE");
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);String where=" FROM archive.access_grant g JOIN archive.record r ON r.id=g.record_id WHERE g.user_id=?";List<Object> args=new ArrayList<>(List.of(user));if(query.containsKey("recordId")){where+=" AND g.record_id=?";args.add(Inputs.uuid(query.get("recordId")));}String effective="archive.grant_valid(g.id,g.user_id)";
        where+=new ListConditions(query,args).contains("number","r.file_no").choice("source","g.source_type","BORROW","BASELINE")
            .flag("effective",effective).dates("until","g.valid_until").sql();
        long total=db.count("SELECT count(*)"+where,args.toArray());args.add(size);args.add((page-1)*size);
        return Sql.map("items",db.rows("SELECT g.*,r.file_no,"+effective+" AS effective"+where+" ORDER BY g.valid_until DESC,g.id LIMIT ? OFFSET ?",args.toArray()).stream().map(row->Sql.view(row,"id,user_id,record_id,file_no,record_revision_id,pdf_version_id,action,source_type,source_request_id,source_policy_id,valid_from,valid_until,state,policy_version,effective")).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 授权清单去重并限制规模；重复人员不会意外获得多份同来源批准。 */
    private List<UUID> ids(Object value) {if(!(value instanceof List<?> values)||values.size()>50) throw ApiFault.invalid("范围清单最多50项");List<UUID> ids=values.stream().map(Inputs::uuid).toList();if(new HashSet<>(ids).size()!=ids.size()) throw ApiFault.invalid("范围不能包含重复标识");return ids;}
    /** 已冻结的UTC时刻转换为数据库时间，不再次改变批准有效期。 */
    private java.sql.Timestamp timestamp(Object value) {return value==null?null:java.sql.Timestamp.from(Instant.parse(value.toString()));}
}
