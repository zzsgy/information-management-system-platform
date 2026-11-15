package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import java.text.Normalizer;
import java.util.*;

/** 当前公盘文件链路的授权配置：IT建立顶层和委托，现任负责人配置具体人员动作。 */
@Service
public class DriveAccessService {
    /** 所有变更复用再验证、事务、稳定操作号及独立撤权账本。 */
    private final Sql db;private final IdentityService identity;private final DriveFilePolicy policy;private final StorageBoundaries protection;private final Commands commands;
    public DriveAccessService(Sql db,IdentityService identity,DriveFilePolicy policy,StorageBoundaries protection,Commands commands) {this.db=db;this.identity=identity;this.policy=policy;this.protection=protection;this.commands=commands;}
    /** 只允许公盘IT基础管理者，不由平台角色推导权限。 */
    private void it(Actor actor) {actor.require("DRIVE.ROLES_MANAGE");if(!actor.systemAdministrator() && actor.roles().stream().noneMatch(r->"DRIVE".equals(r.get("module"))&&"IT_ADMIN".equals(r.get("code")))) throw ApiFault.denied();}
    /** 顶层按部门唯一；子目录继承父区和部门，必须处于负责人有效委托内。 */
    public Map<String,Object> folder(Commands.Context ctx,Map<String,Object> body) {
        Inputs.only(body,"parentId","zone","departmentId","name");String name=Inputs.text(body,"name",128);
        if(name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl)) throw ApiFault.invalid("目录名称不合法");
        UUID parent=body.get("parentId")==null?null:Inputs.uuid(body.get("parentId"));
        return commands.run(ctx,body,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",()->{protection.requireWritable("DRIVE","CREATE_FOLDER");if(parent==null) it(ctx.actor());else manager(ctx.actor(),parent,"FOLDER_MANAGE",null);},()->{
            String zone=Inputs.choice(body.get("zone"),"COMPANY_SHARED","DEPARTMENT_SHARED","DEPARTMENT_INTERNAL");UUID department=body.get("departmentId")==null?null:Inputs.uuid(body.get("departmentId"));
            if(parent!=null) {var row=policy.requireFolder(ctx.actor(),parent,"FOLDER_MANAGE");if(!Objects.equals(department,row.get("department_id"))||!zone.equals(row.get("zone"))) throw ApiFault.invalid("子目录须继承部门与分区");}
            if(department==null && (!zone.equals("COMPANY_SHARED")||parent!=null)) throw ApiFault.invalid("请指定目录所属部门");
            if(department!=null) db.required("SELECT id FROM iam.department WHERE id=? AND status='ACTIVE' FOR SHARE",department);
            UUID id=UUID.randomUUID();db.update("INSERT INTO drive.folder(id,parent_id,zone,department_id,folder_level,name,name_key) VALUES(?,?,?,?,?,?,?)",id,parent,zone,department,parent==null?"TOP":"CHILD",name,Normalizer.normalize(name,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));return folderView(id);
        },this::folderView);
    }
    /** 创建真实部门负责人角色委托；内容动作分别委托，不因可读自动取得写权。 */
    public Map<String,Object> delegate(Commands.Context ctx,Map<String,Object> body) {
        Inputs.only(body,"departmentId","folderId","roleId","allowedActions","reason");Inputs.text(body,"reason",1000);
        UUID folder=Inputs.uuid(body.get("folderId")),department=Inputs.uuid(body.get("departmentId")),role=Inputs.uuid(body.get("roleId"));List<String> actions=actions(body.get("allowedActions"));
        return commands.run(ctx,body,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DELEGATION",()->{it(ctx.actor());protection.requireWritable("DRIVE","CREATE_DELEGATION");},()->{
            var target=db.required("SELECT * FROM drive.folder WHERE id=? AND state='ACTIVE' FOR SHARE",folder);
            if(!department.equals(target.get("department_id"))||!"TOP".equals(target.get("folder_level"))) throw ApiFault.denied();
            db.required("SELECT id FROM iam.role WHERE id=? AND module='DRIVE' AND code='DEPARTMENT_MANAGER' AND status='ACTIVE' FOR SHARE",role);
            UUID id=UUID.randomUUID();db.update("INSERT INTO drive.delegation(id,department_id,folder_id,role_id,allowed_actions,created_by) VALUES(?,?,?,?,?::varchar[],?)",id,department,folder,role,"{"+String.join(",",actions)+"}",ctx.actor().userId());return delegationView(id);
        },this::delegationView);
    }
    /** 负责人在当前委托内新增个人允许或明确拒绝；授权变更立即作废旧登录和页面会话。 */
    public Map<String,Object> grant(Commands.Context ctx,Map<String,Object> body) {
        Inputs.only(body,"subjectId","resourceType","resourceId","action","effect","delegationId","reason");Inputs.text(body,"reason",1000);
        UUID subject=Inputs.uuid(body.get("subjectId")),resource=Inputs.uuid(body.get("resourceId")),delegation=Inputs.uuid(body.get("delegationId"));
        String type=Inputs.choice(body.get("resourceType"),"FOLDER","DOCUMENT"),action=Inputs.choice(body.get("action"),"VIEW","DOWNLOAD","UPLOAD","EDIT","DELETE","MOVE","RESTORE"),effect=Inputs.choice(body.get("effect"),"ALLOW","DENY");
        if(action.equals("UPLOAD")&&!type.equals("FOLDER")) throw ApiFault.invalid("上传权只能授予目录");
        if(action.equals("EDIT")&&!type.equals("DOCUMENT")) throw ApiFault.invalid("协作编辑必须绑定具体文档");
        UUID folder=folderOf(type,resource);
        return commands.run(ctx,body,"FOLDER",folder,"CREATE_GRANT",()->{manager(ctx.actor(),folder,action,delegation);protection.requireWritable("DRIVE","CREATE_GRANT");},()->{
            if(subject.equals(ctx.actor().userId())) throw ApiFault.denied();
            if(action.equals("EDIT") && (!Boolean.TRUE.equals(db.required("SELECT collaboration_enabled FROM drive.document WHERE id=?",resource).get("collaboration_enabled")) || policy.managerDelegation(ctx.actor(),policy.ancestors(folder),"COLLABORATE")==null)) throw ApiFault.denied();
            db.required("SELECT id FROM iam.user_account WHERE id=? AND status='ACTIVE' FOR UPDATE",subject);
            UUID id=UUID.randomUUID();db.update("INSERT INTO drive.resource_grant(id,subject_id,resource_type,resource_id,action,effect,delegation_id,granted_by) VALUES(?,?,?,?,?,?,?,?)",id,subject,type,resource,action,effect,delegation,ctx.actor().userId());
            identity.invalidate(subject,"DRIVE_GRANT_CHANGE",ctx.operation());return grantView(id);
        },this::grantView);
    }
    /** 撤销个人记录保留历史；移除ALLOW不等于新增DENY，默认共享读取规则仍独立生效。 */
    public Map<String,Object> revokeGrant(Commands.Context ctx,UUID id) {
        return commands.run(ctx,Map.of(),"DRIVE_GRANT",id,"REVOKE_GRANT",()->{var row=db.required("SELECT * FROM drive.resource_grant WHERE id=?",id);manager(ctx.actor(),folderOf((String)row.get("resource_type"),(UUID)row.get("resource_id")),(String)row.get("action"),(UUID)row.get("delegation_id"));protection.requireWritable("DRIVE","REVOKE_GRANT");},()->{
            var before=db.required("SELECT * FROM drive.resource_grant WHERE id=?",id);
            // 页面交付也先锁账号再锁授权，撤权保持相同顺序，避免等待环路。
            db.required("SELECT id FROM iam.user_account WHERE id=? FOR UPDATE",before.get("subject_id"));
            var row=db.required("SELECT * FROM drive.resource_grant WHERE id=? FOR UPDATE",id);version(ctx,row);
            if(!"ACTIVE".equals(row.get("state"))) throw ApiFault.conflict("授权已经撤销");
            db.update("UPDATE drive.resource_grant SET state='REVOKED',revoked_by=?,revoked_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=?",ctx.actor().userId(),id);identity.invalidate((UUID)row.get("subject_id"),"DRIVE_GRANT_REVOKED",ctx.operation());return grantView(id);
        },this::grantView);
    }
    /** IT撤销委托即阻断全部依赖动作；相关个人与负责人会话同步失效。 */
    public Map<String,Object> revokeDelegation(Commands.Context ctx,UUID id) {
        return commands.run(ctx,Map.of(),"DRIVE_DELEGATION",id,"REVOKE_DELEGATION",()->{it(ctx.actor());protection.requireWritable("DRIVE","REVOKE_DELEGATION");},()->{
            var before=db.required("SELECT * FROM drive.delegation WHERE id=?",id);
            var affected=db.rows("SELECT subject_id AS id FROM drive.resource_grant WHERE delegation_id=? AND state='ACTIVE' UNION SELECT user_id AS id FROM iam.user_role WHERE role_id=? AND department_id=? AND state='ACTIVE' ORDER BY id",id,before.get("role_id"),before.get("department_id"));
            // 先按稳定顺序锁相关账号，再锁委托；与最终交付/检测提交的账号→授权锁顺序一致。
            for(var user:affected) db.required("SELECT id FROM iam.user_account WHERE id=? FOR UPDATE",user.get("id"));
            var row=db.required("SELECT * FROM drive.delegation WHERE id=? FOR UPDATE",id);version(ctx,row);
            if(!"ACTIVE".equals(row.get("state"))) throw ApiFault.conflict("委托已经撤销");
            db.update("UPDATE drive.delegation SET state='REVOKED',version=version+1,revoked_at=CURRENT_TIMESTAMP WHERE id=?",id);
            for(var user:affected) identity.invalidate((UUID)user.get("id"),"DRIVE_DELEGATION_REVOKED",UUID.randomUUID());return delegationView(id);
        },this::delegationView);
    }
    /** 返回用于操作按钮的当前管理摘要；不可查询其他负责人范围。 */
    public Map<String,Object> grants(Actor actor,UUID resource,String type) {
        UUID folder=folderOf(type,resource);var path=policy.ancestors(folder);
        boolean manages=List.of("VIEW","DOWNLOAD","UPLOAD","EDIT","DELETE","MOVE","RESTORE").stream().anyMatch(action->policy.managerDelegation(actor,path,action)!=null);
        actor.require("DRIVE.ACCESS");
        return Sql.map("items",db.rows("SELECT g.*,u.name AS subject_name FROM drive.resource_grant g JOIN iam.user_account u ON u.id=g.subject_id WHERE g.resource_type=? AND g.resource_id=? AND g.state='ACTIVE' AND (? OR g.subject_id=?) ORDER BY g.created_at DESC LIMIT 100",type,resource,manages,actor.userId()).stream().map(r->Sql.view(r,"id,subject_id,subject_name,resource_type,resource_id,action,effect,delegation_id,granted_by,state,version")).toList());
    }
    /** 人员选择只返回公开工作标识；当前负责人仅取得其实际有效委托，IT另有基础配置入口。 */
    public Map<String,Object> options(Actor actor,UUID folder,String keyword) {
        actor.require("DRIVE.ACCESS");if(keyword.length()>100)throw ApiFault.invalid("人员检索过长");boolean isIt=actor.systemAdministrator() || actor.roles().stream().anyMatch(r->"DRIVE".equals(r.get("module"))&&"IT_ADMIN".equals(r.get("code")));
        List<Map<String,Object>> delegated=new ArrayList<>();
        if(folder!=null){var path=policy.ancestors(folder);for(String action:List.of("VIEW","DOWNLOAD","UPLOAD","EDIT","DELETE","MOVE","RESTORE","COLLABORATE","FOLDER_MANAGE")){var row=policy.managerDelegation(actor,path,action);if(row!=null&&delegated.stream().noneMatch(d->d.get("id").equals(row.get("id"))))delegated.add(row);}}
        if(!isIt&&delegated.isEmpty())throw ApiFault.denied();
        return Sql.map("itAdmin",isIt,"delegations",delegated.stream().map(r->Sql.view(r,"id,department_id,folder_id,allowed_actions,version,state")).toList(),
            "people",db.rows("SELECT id,name,employee_no,department_id FROM iam.user_account WHERE status='ACTIVE' AND id<>? AND (position(lower(?) in lower(name))>0 OR position(lower(?) in lower(employee_no))>0) ORDER BY name,id LIMIT 50",actor.userId(),keyword,keyword).stream().map(r->Sql.view(r,"id,name,employee_no,department_id")).toList(),
            "departments",isIt?db.rows("SELECT id,name FROM iam.department WHERE status='ACTIVE' ORDER BY sort_order,name LIMIT 1000"):List.of(),
            "managerRoleId",isIt?db.required("SELECT id FROM iam.role WHERE module='DRIVE' AND code='DEPARTMENT_MANAGER' AND status='ACTIVE'").get("id"):null);
    }
    public Map<String,Object> delegations(Actor actor){it(actor);return Sql.map("items",db.rows("SELECT d.*,f.name AS folder_name,p.name AS department_name FROM drive.delegation d JOIN drive.folder f ON f.id=d.folder_id JOIN iam.department p ON p.id=d.department_id WHERE d.state='ACTIVE' ORDER BY p.name,f.name LIMIT 200").stream().map(r->Sql.view(r,"id,department_id,department_name,folder_id,folder_name,allowed_actions,version,state")).toList());}
    /** 当前角色与独立任职记录都必须匹配；传入委托还要与实际有效委托一致。 */
    private void manager(Actor actor,UUID folder,String action,UUID delegation) {actor.require("DRIVE.ACCESS");var current=policy.managerDelegation(actor,policy.ancestors(folder),action);if(current==null || (delegation!=null&&!delegation.equals(current.get("id")))) throw ApiFault.denied();}
    /** 文档动作以文档真实所在目录为准，不能由调用方指定一个无关目录。 */
    private UUID folderOf(String type,UUID resource) {if(type.equals("FOLDER")) return resource;return (UUID)db.required("SELECT folder_id FROM drive.document WHERE id=? AND status='ACTIVE'",resource).get("folder_id");}
    /** 委托可包含内容管理动作；个人授权仍不包含目录管理、协作配置或永久清理。 */
    private List<String> actions(Object input) {if(!(input instanceof List<?> values)||values.isEmpty()||values.size()>10) throw ApiFault.invalid("委托动作无效");return values.stream().map(v->Inputs.choice(v,"VIEW","DOWNLOAD","UPLOAD","EDIT","DELETE","MOVE","RESTORE","PURGE","FOLDER_MANAGE","COLLABORATE")).distinct().sorted().toList();}
    /** 授权版本使用显式期望值，防止管理员覆盖并发修改。 */
    private void version(Commands.Context ctx,Map<String,Object> row) {if(ctx.version()==null||ctx.version()!=((Number)row.get("version")).intValue()) throw ApiFault.conflict("授权版本已变化，请刷新后重试");}
    /** 配置回执均只返回业务白名单，不含账号凭据和内部路径。 */
    private Map<String,Object> folderView(UUID id) {return Sql.view(db.required("SELECT * FROM drive.folder WHERE id=?",id),"id,parent_id,zone,department_id,name,row_version");}
    private Map<String,Object> delegationView(UUID id) {return Sql.view(db.required("SELECT * FROM drive.delegation WHERE id=?",id),"id,department_id,folder_id,role_id,allowed_actions,version,state");}
    private Map<String,Object> grantView(UUID id) {return Sql.view(db.required("SELECT * FROM drive.resource_grant WHERE id=?",id),"id,subject_id,resource_type,resource_id,action,effect,delegation_id,granted_by,state,version");}
}
