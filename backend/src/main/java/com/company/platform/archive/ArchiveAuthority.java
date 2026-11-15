package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.storage.StorageDomainPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** 档案对象级授权：目录维护、批准安全题名、正文使用及待审检查分别核验。 */
@Component
public class ArchiveAuthority implements StorageDomainPolicy {
    /** 参数化数据库访问；所有授权只用于本次请求。 */
    private final Sql db;
    /** 更正候选延迟解析，避免目录维护与授权服务循环构造。 */
    private final org.springframework.beans.factory.ObjectProvider<CatalogMaintenance> maintenance;
    public ArchiveAuthority(Sql db,org.springframework.beans.factory.ObjectProvider<CatalogMaintenance> maintenance) {this.db=db;this.maintenance=maintenance;}
    /** 档案原PDF只允许绑定真实文件，公盘及流程凭据不能借用此策略。 */
    @Override public boolean supports(String module,String purpose) {return "ARCHIVE".equals(module)&&"ARCHIVE_PDF".equals(purpose);}
    /** 上传全生命周期必须仍有该文件目录维护权，并且文件尚未进入送审或正式发布。 */
    @Override public void require(Actor actor,String module,String purpose,UUID context,String action) {
        var record=record(context);requireManage(actor,(UUID)record.get("department_id"));
        if(!Set.of("DRAFT","PUBLISHED").contains(record.get("state")) || db.count("SELECT count(*) FROM archive.catalog_change WHERE object_type='RECORD' AND object_id=? AND state IN ('DRAFT','PENDING')",context)>0) throw ApiFault.conflict("当前文件不允许继续接收候选PDF");
    }
    /** IT 具有公司级档案管理权；其他人员仍按独立档案任职判断。 */
    public boolean role(Actor actor,String code,UUID department) {
        return db.count("SELECT count(*) FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.user_id=? AND a.module='ARCHIVE' AND a.role_code=? AND (?::uuid IS NULL OR a.department_id=?) AND a.state='ACTIVE' AND r.status='ACTIVE' AND u.status='ACTIVE' AND (a.role_code<>'DEPARTMENT_ARCHIVIST' OR a.department_id=u.department_id) AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)",actor.userId(),code,department,department)>0;
    }
    /** 档案业务由具有实际任职的档案超级管理员处理。 */
    public void requireSuper(Actor actor,String action) {actor.require(action);if(!role(actor,"SUPER_ADMIN",null)) throw ApiFault.denied();}
    /** 档案员只维护当前所属部门；超级管理员的公司级目录能力由独立档案角色授予。 */
    public void requireManage(Actor actor,UUID department) {actor.require("ARCHIVE.CATALOG_MANAGE");if(!canManage(actor,department)) throw ApiFault.denied();}
    /** 目录管理范围仅由当前档案能力和合法任职决定，不授予正文权。 */
    public boolean canManage(Actor actor,UUID department) {return actor.actions().contains("ARCHIVE.CATALOG_MANAGE")&&(role(actor,"SUPER_ADMIN",null)||role(actor,"DEPARTMENT_ARCHIVIST",department));}
    /** 只读内部目录；提交阶段调用者锁定对象后再次检查版本和状态。 */
    public Map<String,Object> record(UUID id) {return db.required("SELECT r.*,b.box_no,b.state AS box_state FROM archive.record r JOIN archive.box b ON b.id=r.box_id WHERE r.id=?",id);}
    /** 固定当前发布版本，不允许客户端以另一个文件的修订或旧批准指针拼装访问。 */
    public Map<String,Object> published(UUID revision,UUID pdf) {
        return db.required("SELECT r.*,b.box_no,b.state AS box_state,o.id AS object_id,o.storage_key,o.byte_size,o.sha256,p.page_count FROM archive.record r JOIN archive.box b ON b.id=r.box_id JOIN archive.record_revision v ON v.record_id=r.id AND v.id=r.current_revision_id JOIN archive.pdf_version p ON p.record_id=r.id AND p.id=r.current_pdf_version_id AND p.id=v.pdf_version_id JOIN storage.object o ON o.id=p.object_id WHERE v.id=? AND p.id=? AND r.state='PUBLISHED' AND b.state='PUBLISHED' AND p.review_state='APPROVED' AND o.state='READY' AND o.module='ARCHIVE' AND o.media_type='application/pdf'",revision,pdf);
    }
    /** 当前有效的具体来源；页面会话必须固定其中一条，不在撤权后悄悄切换到其他来源。 */
    public Map<String,Object> source(Actor actor,Map<String,Object> record,UUID fixedGrant,UUID fixedPolicy) {
        return source(actor,record,fixedGrant,fixedPolicy,false);
    }
    /** 无水印只接受具体电子借阅批准；基础阅读和候选职责没有该授权。 */
    public Map<String,Object> source(Actor actor,Map<String,Object> record,UUID fixedGrant,UUID fixedPolicy,boolean unwatermarked) {
        actor.require("ARCHIVE.ACCESS");if(record.get("current_pdf_version_id")==null||record.get("current_revision_id")==null)throw ApiFault.denied();UUID department=Inputs.uuid(actor.user().get("departmentId"));
        if(fixedPolicy==null) {
            List<Object> args=new ArrayList<>(List.of(actor.userId(),record.get("id"),record.get("current_revision_id"),record.get("current_pdf_version_id")));
            String fixed=fixedGrant==null?"":" AND g.id=?";if(fixedGrant!=null) args.add(fixedGrant);
            var grant=db.one("SELECT g.* FROM archive.access_grant g WHERE g.user_id=? AND g.record_id=? AND g.record_revision_id=? AND g.pdf_version_id=? AND g.action='VIEW' AND g.state='ACTIVE' AND g.valid_from<=CURRENT_TIMESTAMP AND g.valid_until>CURRENT_TIMESTAMP"+" AND archive.grant_valid(g.id,g.user_id)"+fixed+
                (unwatermarked?" AND archive.unwatermarked_grant(g.id,g.user_id)":"")+" ORDER BY g.valid_until,g.id LIMIT 1"+lock("g"),args.toArray());
            if(grant!=null) return Sql.map("grantId",grant.get("id"),"policyId",null,"sourceVersion",grant.get("policy_version"),"validUntil",grant.get("valid_until"));
            if(fixedGrant!=null) throw ApiFault.denied();
        }
        if(unwatermarked)throw ApiFault.denied();
        // 部门范围只能控制题名检索；本部门基础正文须GENERAL、显式批准和固定版本同时成立。
        List<Object> args=new ArrayList<>(List.of(record.get("id"),record.get("current_revision_id"),record.get("current_pdf_version_id"),department,actor.userId()));
        String fixed=fixedPolicy==null?"":" AND p.id=?";if(fixedPolicy!=null) args.add(fixedPolicy);
        var policy=db.one("SELECT p.* FROM archive.access_policy p JOIN archive.record r ON r.id=p.object_id WHERE p.object_type='RECORD' AND p.object_id=? AND p.record_revision_id=? AND p.pdf_version_id=? AND r.department_id=? AND archive.baseline_valid(p.id,?::uuid)"+fixed+lock("p"),args.toArray());
        if(policy==null) throw ApiFault.denied();
        return Sql.map("grantId",null,"policyId",policy.get("id"),"sourceVersion",policy.get("policy_version"),"validUntil",policy.get("valid_until"));
    }
    /** 返回本人可见的目录表示；跨部门只允许批准安全题名，绝不投影秘密摘要或版本信息。 */
    public Map<String,Object> detail(Actor actor,UUID id) {
        actor.require("ARCHIVE.ACCESS");var row=record(id);boolean manage=canManage(actor,(UUID)row.get("department_id"));
        if(manage) return Sql.map("representation","MANAGE","record",Sql.view(row,"id,file_no,box_id,box_no,item_no,department_id,title,safe_title,classification,category_code,carrier_type,keywords,abstract,owner_id,archive_date,retention_code,expiry_date,state,row_version,current_revision_id,current_pdf_version_id,candidate_revision_id,created_at"));
        if(!"PUBLISHED".equals(row.get("state"))||!"PUBLISHED".equals(row.get("box_state"))) throw ApiFault.hidden();
        if(db.count("SELECT count(*) FROM archive.access_grant g WHERE g.record_id=? AND g.user_id=? AND archive.grant_valid(g.id,?)",id,actor.userId(),actor.userId())>0) return Sql.map("representation","METADATA","record",Sql.view(row,"id,file_no,box_id,box_no,item_no,department_id,title,classification,category_code,carrier_type,state,current_revision_id,current_pdf_version_id"));
        try {source(actor,row,null,null);return Sql.map("representation","METADATA","record",Sql.view(row,"id,file_no,box_id,box_no,item_no,department_id,title,classification,category_code,carrier_type,state,current_revision_id,current_pdf_version_id"));}
        catch(ApiFault denied) {if(denied.status!=403) throw denied;}
        if(db.count("SELECT CASE WHEN archive.visibility(?,?::uuid,false)='TITLE_ONLY' THEN 1 ELSE 0 END",id,actor.userId())!=1) throw ApiFault.hidden();
        return Sql.map("representation","TITLE_ONLY","summary",Sql.map("objectId",row.get("id"),"objectType","RECORD","safeTitle",Objects.toString(row.get("safe_title"),Objects.toString(row.get("file_no"))),"canApply",!"PAPER".equals(row.get("carrier_type"))));
    }
    /** 可申请范围须先经过字段级可见性，知道一个UUID不能直接发起跨部门借阅。 */
    public Map<String,Object> requireRequestable(Actor actor,UUID id) {detail(actor,id);var row=record(id);if(!"PUBLISHED".equals(row.get("state"))||!"PUBLISHED".equals(row.get("box_state"))||row.get("current_pdf_version_id")==null) throw ApiFault.conflict("当前档案没有可申请的已发布PDF");return row;}
    /** 候选只向真实整理者或当前复核人开放，不把目录管理当成正式正文批准。 */
    public Map<String,Object> review(Actor actor,String type,UUID context,UUID pdf) {
        actor.require("ARCHIVE.ACCESS");if(type.equals("CATALOG_CHANGE")) return maintenance.getObject().review(actor,context,pdf);Map<String,Object> row;int version;
        if(type.equals("SCAN_QUALITY")) {
            if(db.count("SELECT count(*) FROM archive.scan_item WHERE record_id=? AND pdf_version_id=? AND state='REWORK'",context,pdf)>0)throw ApiFault.conflict("本轮候选已判定返工，请等待重新接收");
            row=record(context);requireManage(actor,(UUID)row.get("department_id"));if(!"DRAFT".equals(row.get("state"))) throw ApiFault.hidden();
            // 新电子版本尚未准备好时，不能把上次候选冒充本次上传内容交付。
            if(row.get("electronic_version_id")!=null&&db.count("SELECT count(*) FROM archive.electronic_version WHERE id=? AND state='READY' AND pdf_version_id=?",row.get("electronic_version_id"),pdf)!=1)throw ApiFault.conflict("本次电子文件尚未处理完成，请先处理后再阅读");version=((Number)row.get("row_version")).intValue();
        } else if(type.equals("REGISTRATION")) {
            var flow=db.required("SELECT * FROM workflow.instance WHERE id=? AND business_type='REGISTRATION' AND state='PENDING'",context);
            var step=db.required("SELECT *,workflow.assignment_valid(assignment_type,assignment_ref,assignee_id) AS legal FROM workflow.step WHERE id=? AND state='PENDING'",flow.get("current_step_id"));
            if(!(actor.userId().equals(step.get("assignee_id"))&&Boolean.TRUE.equals(step.get("legal"))) && !actor.userId().equals(flow.get("applicant_id"))) throw ApiFault.denied();
            Map<?,?> snapshot=Crypto.JSON.readValue(flow.get("business_snapshot").toString(),Map.class);
            if("BOX".equals(snapshot.get("objectType"))){Object raw=snapshot.get("electronicMembers");if(!(raw instanceof List<?> members))throw ApiFault.hidden();Map<?,?> member=members.stream().map(value->(Map<?,?>)value).filter(value->pdf.toString().equals(value.get("pdfVersionId"))).findFirst().orElseThrow(ApiFault::hidden);row=record(Inputs.uuid(member.get("id")));if(!Objects.equals(row.get("box_id").toString(),snapshot.get("objectId"))||!Objects.equals(row.get("candidate_revision_id").toString(),member.get("revisionId")))throw ApiFault.hidden();}else{if(!"RECORD".equals(snapshot.get("objectType"))) throw ApiFault.hidden();row=record(Inputs.uuid(snapshot.get("objectId")));}
            if(!"PENDING_REVIEW".equals(row.get("state"))) throw ApiFault.hidden();
            if(actor.userId().equals(flow.get("applicant_id"))&&!actor.userId().equals(step.get("assignee_id"))) requireManage(actor,(UUID)row.get("department_id"));
            version=((Number)flow.get("row_version")).intValue();
        } else throw ApiFault.invalid("候选阅读上下文暂不支持");
        var fixed=db.required("SELECT v.id AS revision_id,p.id AS pdf_id,p.object_id,o.storage_key,o.sha256,o.byte_size FROM archive.record_revision v JOIN archive.pdf_version p ON p.record_id=v.record_id AND p.id=v.pdf_version_id JOIN storage.object o ON o.id=p.object_id WHERE v.record_id=? AND v.id=? AND p.id=? AND p.review_state IN ('CHECK_PASSED','PENDING_REVIEW') AND o.state='READY' AND o.module='ARCHIVE'",row.get("id"),row.get("candidate_revision_id"),pdf);
        row.putAll(fixed);row.put("sourceVersion",version);return row;
    }
    /** 对授权来源持共享锁直至短事务提交，与显式撤权互斥。 */
    private String lock(String alias) {return TransactionSynchronizationManager.isActualTransactionActive()?" FOR SHARE OF "+alias:"";}
}
