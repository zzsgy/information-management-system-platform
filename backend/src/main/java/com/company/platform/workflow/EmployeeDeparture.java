package com.company.platform.workflow;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 具名员工离职、身份撤销和独立历史归档；只有平台 IT 人员管理员有权访问。 */
@Service
public class EmployeeDeparture {
    /** 主档对外白名单，保留离职当时的工号、姓名和部门。 */
    private static final String FIELDS = "id,user_id,employee_no,employee_name,department_id,department_name,previous_status,operator_id,request_id,reason,departed_at";
    /** 首次实物取件或旧版直接交付后，实收确认前均属于未归还。 */
    private static final String OUTSTANDING_PAPER = "l.state='IN_USE' AND (l.pickup_at IS NOT NULL OR l.actual_delivery_at IS NOT NULL)";
    private final Sql db;
    private final IdentityService identity;
    private final Commands commands;
    private final WorkflowEngine workflow;

    public EmployeeDeparture(Sql db, IdentityService identity, Commands commands, WorkflowEngine workflow) {
        this.db = db;
        this.identity = identity;
        this.commands = commands;
        this.workflow = workflow;
    }

    /** 可办理人员包括临时停用者；共享 IT 账号、本人和既有离职账号不进入候选。 */
    public Map<String,Object> candidates(Actor actor, Map<String,String> query) {
        requireOperator(actor);
        Inputs.only(query, "keyword");
        String keyword = keyword(query);
        return Sql.map("items", db.rows("""
                SELECT u.id,u.employee_no,u.name,d.name AS department_name,u.status
                FROM iam.user_account u JOIN iam.department d ON d.id=u.department_id
                WHERE u.status IN ('ACTIVE','DISABLED') AND NOT u.it_shared_account AND u.id<>?
                  AND position(lower(?) in lower(concat_ws(' ',u.name,u.employee_no)))>0
                ORDER BY u.employee_no,u.id LIMIT 50
                """, actor.userId(), keyword).stream().map(row -> Sql.view(row,"id,employee_no,name,department_name,status")).toList());
    }

    /** 返回最小人员资料和办理影响，用账号行版本保护二次确认。 */
    public Map<String,Object> preview(Actor actor, UUID user) {
        requireOperator(actor);
        var person = person(user, false);
        requireEmployee(actor, person);
        return Sql.map("user", Sql.view(person,"id,employee_no,name,department_name,status,row_version"), "impact", impact(user));
    }

    /** 归档及撤权在同一事务完成，失败时不留下可查询的半份档案。 */
    @Transactional
    public Map<String,Object> depart(Commands.Context ctx, UUID user, Map<String,Object> body) {
        Inputs.only(body, "reason");
        String reason = Inputs.text(body, "reason", 1000);
        db.rows("SELECT pg_advisory_xact_lock(61427002)");
        db.rows("SELECT id FROM iam.user_account WHERE id IN (?,?) ORDER BY id FOR UPDATE", ctx.actor().userId(), user);
        return commands.run(ctx, body, "USER", user, "EMPLOYEE_DEPARTURE", () -> requireOperator(ctx.actor()), () -> {
            var person = person(user, true);
            requireEmployee(ctx.actor(), person);
            Commands.version(ctx, person);
            UUID departure = UUID.randomUUID();
            db.update("""
                    INSERT INTO iam.employee_departure(id,user_id,employee_no,employee_name,department_id,department_name,
                        previous_status,operator_id,request_id,reason,impact)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb)
                    """, departure,user,person.get("employee_no"),person.get("name"),person.get("department_id"),
                    person.get("department_name"),person.get("status"),ctx.actor().userId(),ctx.request(),reason,Crypto.json(impact(user)));
            db.rows("SELECT iam.capture_departure_records(?,?)", departure, user);

            // 与转岗及档案审批保持一致的盒号锁顺序，继续由原经办人处理已借出的实物。
            for (var box : db.rows("""
                    SELECT DISTINCT p.box_id FROM archive.paper_request_item p
                    JOIN workflow.instance w ON w.business_id=p.request_id
                    WHERE w.applicant_id=? AND w.state='PENDING' ORDER BY p.box_id
                    """,user)) db.required("SELECT id FROM archive.box WHERE id=? FOR UPDATE",box.get("box_id"));
            workflow.cancelForDeparture(user, ctx.actor().userId());
            notifyPaperApprovers(departure, user);
            revoke(user, ctx.actor().userId());
            var result = archived(departure);
            // 最后推进独立身份账本；旧备份或旧编辑会话不能恢复原授权。
            identity.invalidate(user, "EMPLOYEE_DEPARTURE", ctx.operation());
            return result;
        }, this::archived);
    }

    /** 从原借阅和已批准续借的决定取真实审批人，凭据录入人与审批人分开。 */
    private void notifyPaperApprovers(UUID departure, UUID user) {
        db.update("""
                INSERT INTO archive.departure_loan_followup(departure_id,loan_id,assignee_id)
                SELECT DISTINCT ?::uuid,l.id,d.original_approver_id
                FROM archive.physical_loan l
                CROSS JOIN LATERAL (
                    SELECT l.request_id
                    UNION SELECT s.request_id FROM archive.paper_loan_scope s WHERE s.loan_id=l.id
                ) related
                JOIN archive.borrow_request q ON q.id=related.request_id AND q.state='APPROVED'
                JOIN workflow.step s ON s.instance_id=q.workflow_instance_id
                JOIN workflow.decision d ON d.step_id=s.id AND d.result='APPROVE'
                JOIN iam.user_account a ON a.id=d.original_approver_id AND a.status='ACTIVE'
                WHERE l.borrower_id=? AND a.id<>l.borrower_id AND
                """ + OUTSTANDING_PAPER, departure,user);
    }

    /** 撤销通过账号持有的授权和短期凭据；原申请、操作人与实际借阅责任均留在源表。 */
    private void revoke(UUID user, UUID operator) {
        db.update("UPDATE iam.user_role SET state='ENDED',valid_until=GREATEST(valid_from,CURRENT_TIMESTAMP) WHERE user_id=? AND state='ACTIVE'",user);
        db.update("UPDATE iam.permission_assignment SET state='REVOKED' WHERE user_id=? AND state='ACTIVE'",user);
        db.update("UPDATE iam.department_manager SET valid_until=GREATEST(valid_from,CURRENT_TIMESTAMP) WHERE user_id=? AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP)",user);
        db.update("UPDATE iam.company_duty SET valid_until=GREATEST(valid_from,CURRENT_TIMESTAMP) WHERE user_id=? AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP)",user);
        db.update("""
                UPDATE archive.access_grant SET state='REVOKED',revoked_by=?,revoked_at=CURRENT_TIMESTAMP,
                  revocation_reason='员工离职撤权',policy_version=policy_version+1 WHERE user_id=? AND state='ACTIVE'
                """,operator,user);
        db.update("UPDATE drive.resource_grant SET state='REVOKED',revoked_by=?,revoked_at=CURRENT_TIMESTAMP,version=version+1 WHERE subject_id=? AND state='ACTIVE'",operator,user);
        db.update("UPDATE archive.usage_request SET state='INVALIDATED' WHERE user_id=? AND state IN ('PENDING','READY')",user);
        db.update("UPDATE storage.preview_session SET state='INVALIDATED' WHERE user_id=? AND state IN ('PENDING','READY')",user);
        db.update("UPDATE iam.credential_challenge SET consumed_at=CURRENT_TIMESTAMP WHERE user_id=? AND consumed_at IS NULL",user);
        db.update("UPDATE iam.password_recovery SET closed_at=CURRENT_TIMESTAMP,code_hash=NULL WHERE user_id=? AND closed_at IS NULL",user);
        db.update("""
                UPDATE ops.account_message SET state='CANCELLED',payload=NULL,lease_token=NULL,lease_until=NULL,row_version=row_version+1
                WHERE user_id=? AND kind IN ('TEMP_PASSWORD','RECOVERY') AND state IN ('PENDING','RETRY','RUNNING','MANUAL')
                """,user);
        db.update("DELETE FROM portal.todo_desktop WHERE user_id=?",user);
        db.update("UPDATE iam.user_account SET status='DEPARTED',row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",user);
    }

    /** 主档只按已授权的固定条件分页，展示离职时的身份而非当前人员字段。 */
    public Map<String,Object> history(Actor actor, Map<String,String> query) {
        requireOperator(actor);
        Inputs.only(query, "keyword", "page");
        String keyword = keyword(query);
        int page = Inputs.integer(query.get("page"), 1, 1, 100000);
        String match = " WHERE position(lower(?) in lower(concat_ws(' ',employee_name,employee_no)))>0";
        var rows = db.rows("SELECT * FROM iam.employee_departure" + match + " ORDER BY departed_at DESC,id DESC LIMIT 21 OFFSET ?", keyword,(page-1)*20);
        return Sql.map("items",rows.stream().limit(20).map(row -> Sql.view(row,FIELDS)).toList(),"hasNext",rows.size()>20);
    }

    /** 单人历史按类型读取归档副本，不直接授权访问原业务正文或附件。 */
    public Map<String,Object> records(Actor actor, UUID departure, Map<String,String> query) {
        requireOperator(actor);
        Inputs.only(query,"page","recordType");
        int page = Inputs.integer(query.get("page"),1,1,100000);
        var archive = archived(departure);
        String recordType = query.containsKey("recordType") ? Inputs.text(query,"recordType",32) : null;
        var rows = db.rows("""
                SELECT record_type,source_id,occurred_at,snapshot::text AS snapshot
                FROM iam.employee_departure_record
                WHERE departure_id=? AND (?::varchar IS NULL OR record_type=?)
                ORDER BY occurred_at DESC,record_type,source_id LIMIT 31 OFFSET ?
                """, departure,recordType,recordType,(page-1)*30);
        var items = rows.stream().limit(30).map(row -> {
            var result = Sql.view(row,"record_type,source_id,occurred_at");
            result.put("snapshot",Crypto.JSON.readValue(row.get("snapshot").toString(),Map.class));
            return result;
        }).toList();
        return Sql.map("archive",archive,"items",items,"hasNext",rows.size()>30);
    }

    private Map<String,Object> archived(UUID id) {
        var row = db.required("SELECT * FROM iam.employee_departure WHERE id=?",id);
        var result = Sql.view(row,FIELDS);
        result.put("impact",Crypto.JSON.readValue(row.get("impact").toString(),Map.class));
        result.put("paperFollowupCount",db.count("SELECT count(*) FROM archive.departure_loan_followup WHERE departure_id=?",id));
        result.put("recordCounts",db.rows("""
                SELECT record_type,count(*) AS count FROM iam.employee_departure_record
                WHERE departure_id=? GROUP BY record_type ORDER BY record_type
                """,id).stream().map(record -> Sql.view(record,"record_type,count")).toList());
        return result;
    }

    private Map<String,Object> impact(UUID user) {
        return Sql.map(
                "roles", db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",user),
                "personalPermissions", db.count("SELECT count(*) FROM iam.permission_assignment WHERE user_id=? AND state='ACTIVE'",user),
                "archiveGrants", db.count("SELECT count(*) FROM archive.access_grant WHERE user_id=? AND state='ACTIVE'",user),
                "driveGrants", db.count("SELECT count(*) FROM drive.resource_grant WHERE subject_id=? AND state='ACTIVE'",user),
                "duties", db.count("""
                        SELECT (SELECT count(*) FROM iam.department_manager WHERE user_id=? AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP))
                             + (SELECT count(*) FROM iam.company_duty WHERE user_id=? AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP))
                        """,user,user),
                "pendingApprovals", db.count("""
                        SELECT count(*) FROM workflow.step s JOIN workflow.instance w ON w.id=s.instance_id
                        WHERE s.assignee_id=? AND s.state IN ('PENDING','WAITING') AND w.state='PENDING'
                        """,user),
                "outstandingLoans", db.count("SELECT count(*) FROM archive.physical_loan l WHERE l.borrower_id=? AND " + OUTSTANDING_PAPER,user),
                "openTodos", db.count("SELECT count(*) FROM portal.employee_todo WHERE assignee_id=? AND state IN ('OPEN','SUBMITTED')",user));
    }

    private Map<String,Object> person(UUID user, boolean lock) {
        return db.required("""
                SELECT u.id,u.employee_no,u.name,u.department_id,d.name AS department_name,u.status,u.row_version,u.it_shared_account
                FROM iam.user_account u JOIN iam.department d ON d.id=u.department_id WHERE u.id=?
                """+(lock ? " FOR UPDATE OF u" : ""),user);
    }

    private void requireEmployee(Actor actor, Map<String,Object> person) {
        if (actor.userId().equals(person.get("id")) || Boolean.TRUE.equals(person.get("it_shared_account"))) throw ApiFault.denied();
        if ("DEPARTED".equals(person.get("status"))) throw ApiFault.conflict("该员工已办理离职，请查询离职档案");
    }

    private void requireOperator(Actor actor) {
        Actor live = identity.background(actor.sessionId());
        live.require("PLATFORM.USERS_MANAGE");
        if (!live.systemAdministrator()) throw ApiFault.denied();
    }

    private String keyword(Map<String,String> query) {
        String keyword = query.getOrDefault("keyword","").trim();
        if (keyword.length()>128) throw ApiFault.invalid("姓名或工号搜索内容过长");
        return keyword;
    }
}
