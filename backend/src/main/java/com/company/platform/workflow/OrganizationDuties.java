package com.company.platform.workflow;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** 统一组织审批职责配置；不能从公盘总经理角色推导档案总经理审批权。 */
@Service
public class OrganizationDuties {
    /** 低频组织变更复用管理命令及库外身份收紧，不自动赋予正文权限。 */
    private final Sql db;private final Commands commands;private final IdentityService identity;
    public OrganizationDuties(Sql db,Commands commands,IdentityService identity) {this.db=db;this.commands=commands;this.identity=identity;}
    /** 管理中心的组织审批配置须有平台 IT 任职。 */
    private void requireOrganizationAdministrator(Actor actor) {
        if (!actor.systemAdministrator()) throw ApiFault.denied();
        actor.require("PLATFORM.DEPARTMENTS_MANAGE");
    }
    /** 平台 IT 读取当前审批职责及历史依据。 */
    public Map<String,Object> read(Actor actor) {
        requireOrganizationAdministrator(actor);
        return Sql.map("departments",db.rows("SELECT id,code,name,row_version FROM iam.department WHERE status='ACTIVE' ORDER BY code LIMIT 200").stream().map(row->Sql.view(row,"id,code,name,row_version")).toList(),"duties",db.rows("SELECT d.id,d.department_id,d.user_id,d.duty_type,d.valid_from,d.valid_until,d.change_reason,d.approval_reference,u.name,u.employee_no FROM iam.department_manager d JOIN iam.user_account u ON u.id=d.user_id WHERE d.valid_until IS NULL ORDER BY d.department_id,d.duty_type").stream().map(row->Sql.view(row,"id,department_id,user_id,duty_type,valid_from,valid_until,change_reason,approval_reference,name,employee_no")).toList(),"generalManager",db.rows("SELECT d.id,d.user_id,d.valid_from,d.change_reason,d.approval_reference,u.name,u.employee_no FROM iam.company_duty d JOIN iam.user_account u ON u.id=d.user_id WHERE d.duty_code='GENERAL_MANAGER' AND d.valid_until IS NULL").stream().map(row->Sql.view(row,"id,user_id,valid_from,change_reason,approval_reference,name,employee_no")).toList());
    }
    /** 当前合法候选仅返回工号姓名；负责人限本部门，总经理和分管领导不假定属于该部门。 */
    public Map<String,Object> candidates(Actor actor,Map<String,String> query) {
        requireOrganizationAdministrator(actor);Inputs.only(query,"departmentId","dutyType","keyword");String type=Inputs.choice(query.get("dutyType"),"DEPARTMENT_MANAGER","SUPERVISING_LEADER","GENERAL_MANAGER");
        String keyword=query.getOrDefault("keyword","");if(keyword.length()>100) throw ApiFault.invalid("人员检索条件过长");
        String where=" WHERE u.status='ACTIVE' AND iam.archive_access_enabled(u.id) AND NOT iam.it_shared_borrower(u.id) AND (u.name ILIKE ? OR u.employee_no ILIKE ?)";List<Object> args=new ArrayList<>(List.of("%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%","%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"));
        if(type.equals("DEPARTMENT_MANAGER")){where+=" AND u.department_id=?";args.add(Inputs.uuid(query.get("departmentId")));}
        return Sql.map("items",db.rows("SELECT u.id,u.name,u.employee_no FROM iam.user_account u"+where+" ORDER BY u.employee_no LIMIT 100",args.toArray()).stream().map(row->Sql.view(row,"id,name,employee_no")).toList());
    }
    /** 部门负责人和分管领导分别维护；本轮只接受立即生效，保留全部旧任职。 */
    public Map<String,Object> department(Commands.Context context,UUID department,Map<String,Object> body) {
        Inputs.only(body,"duties","changeReason","approvalRef");Inputs.text(body,"changeReason",1000);Inputs.text(body,"approvalRef",256);var duties=Inputs.objects(body.get("duties"),2);if(duties.isEmpty()) throw ApiFault.invalid("请指定需要调整的组织职责");Set<String> seen=new HashSet<>();
        for(var duty:duties) {Inputs.only(duty,"dutyType","userId","validFrom");String type=Inputs.choice(duty.get("dutyType"),"DEPARTMENT_MANAGER","SUPERVISING_LEADER");if(!seen.add(type)) throw ApiFault.invalid("职责不能重复");Inputs.uuid(duty.get("userId"));if(Math.abs(java.time.Duration.between(Instant.parse(Inputs.text(duty,"validFrom",64)),Instant.now()).toSeconds())>300) throw ApiFault.invalid("本轮职责变更仅支持立即生效");}
        return commands.run(context,body,"DEPARTMENT",department,"MANAGEMENT_DUTIES",()->requireOrganizationAdministrator(context.actor()),()->{
            Set<UUID> affected=new TreeSet<>();for(var duty:duties) {affected.add(Inputs.uuid(duty.get("userId")));for(var old:db.rows("SELECT user_id FROM iam.department_manager WHERE department_id=? AND duty_type=? AND valid_until IS NULL",department,duty.get("dutyType"))) affected.add((UUID)old.get("user_id"));}
            for(UUID person:affected) db.required("SELECT id FROM iam.user_account WHERE id=? FOR UPDATE",person);
            var row=db.required("SELECT * FROM iam.department WHERE id=? AND status='ACTIVE' FOR UPDATE",department);Commands.version(context,row);
            for(var duty:duties) {UUID user=Inputs.uuid(duty.get("userId"));var person=db.required("SELECT * FROM iam.user_account WHERE id=? AND status='ACTIVE'",user);if(duty.get("dutyType").equals("DEPARTMENT_MANAGER")&&!department.equals(person.get("department_id"))) throw ApiFault.invalid("部门负责人须属于该部门");
                db.update("UPDATE iam.department_manager SET valid_until=CURRENT_TIMESTAMP WHERE department_id=? AND duty_type=? AND valid_until IS NULL",department,duty.get("dutyType"));
                if(db.count("SELECT CASE WHEN iam.archive_access_enabled(id) AND NOT iam.it_shared_borrower(id) THEN 1 ELSE 0 END FROM iam.user_account WHERE id=?",user)!=1) throw ApiFault.invalid("审批人员须先取得有效档案模块入口权限");
                db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by,approval_reference) VALUES(?,?,?,?,CURRENT_TIMESTAMP,?,?,?)",UUID.randomUUID(),department,user,duty.get("dutyType"),body.get("changeReason"),context.actor().userId(),body.get("approvalRef"));
            }
            for(UUID person:affected) identity.invalidate(person,"ORGANIZATION_DUTY_CHANGE",UUID.randomUUID());db.update("UPDATE iam.department SET row_version=row_version+1 WHERE id=?",department);return Commands.result(context,department,((Number)row.get("row_version")).intValue()+1);
        },id->Commands.result(context,id,((Number)db.required("SELECT row_version FROM iam.department WHERE id=?",id).get("row_version")).intValue()));
    }
    /** 公司总经理是独立组织职责，配置变更终止原任职，不修改历史审批。 */
    public Map<String,Object> generalManager(Commands.Context context,Map<String,Object> body) {
        Inputs.only(body,"userId","changeReason","approvalRef","expectedAssignmentId");UUID user=Inputs.uuid(body.get("userId"));Inputs.text(body,"changeReason",1000);Inputs.text(body,"approvalRef",256);
        return commands.run(context,body,"COMPANY_DUTY",ApiController.COLLECTION,"GENERAL_MANAGER_DUTY",()->requireOrganizationAdministrator(context.actor()),()->{
            var previous=db.one("SELECT * FROM iam.company_duty WHERE duty_code='GENERAL_MANAGER' AND valid_until IS NULL");UUID expected=Inputs.nullableUuid(body.get("expectedAssignmentId"));if(!Objects.equals(expected,previous==null?null:previous.get("id"))) throw ApiFault.conflict("公司职责配置已变化");Set<UUID> people=new TreeSet<>();people.add(user);if(previous!=null) people.add((UUID)previous.get("user_id"));for(UUID person:people) db.required("SELECT id FROM iam.user_account WHERE id=? FOR UPDATE",person);
            if(db.count("SELECT CASE WHEN iam.archive_access_enabled(id) AND NOT iam.it_shared_borrower(id) THEN 1 ELSE 0 END FROM iam.user_account WHERE id=?",user)!=1) throw ApiFault.invalid("总经理审批人须先取得有效档案模块入口权限");
            db.update("UPDATE iam.company_duty SET valid_until=CURRENT_TIMESTAMP WHERE duty_code='GENERAL_MANAGER' AND valid_until IS NULL");UUID id=UUID.randomUUID();db.update("INSERT INTO iam.company_duty(id,duty_code,user_id,valid_from,changed_by,change_reason,approval_reference) VALUES(?,'GENERAL_MANAGER',?,CURRENT_TIMESTAMP,?,?,?)",id,user,context.actor().userId(),body.get("changeReason"),body.get("approvalRef"));for(UUID person:people) identity.invalidate(person,"GENERAL_MANAGER_DUTY_CHANGE",UUID.randomUUID());return Sql.map("id",id,"state","ACTIVE");
        },id->Sql.map("id",id,"state",db.required("SELECT CASE WHEN valid_until IS NULL THEN 'ACTIVE' ELSE 'ENDED' END AS state FROM iam.company_duty WHERE id=?",id).get("state")));
    }
}
