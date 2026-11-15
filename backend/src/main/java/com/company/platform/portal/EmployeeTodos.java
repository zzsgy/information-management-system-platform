package com.company.platform.portal;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 个人待办及员工分派。业务系统审批继续由原模块处理。 */
@Service
public class EmployeeTodos {
    private final Sql db;
    private final IdentityService identity;
    private final Audit audit;

    public EmployeeTodos(Sql db,IdentityService identity,Audit audit) {
        this.db=db;this.identity=identity;this.audit=audit;
    }

    /** 任职决定可分派的部门，IT 和公盘角色不推导组织审批职责。 */
    private record Scope(boolean company,List<UUID> departments) {}
    private Scope scope(Actor actor) {
        boolean company=db.count("""
                SELECT count(*) FROM iam.company_duty
                WHERE user_id=? AND duty_code='GENERAL_MANAGER' AND valid_from<=CURRENT_TIMESTAMP
                  AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP)
                """,actor.userId())>0;
        var departments=db.rows("""
                SELECT m.department_id FROM iam.department_manager m
                JOIN iam.user_account u ON u.id=m.user_id AND u.department_id=m.department_id
                JOIN iam.department d ON d.id=m.department_id AND d.status='ACTIVE'
                WHERE m.user_id=? AND m.duty_type='DEPARTMENT_MANAGER' AND m.valid_from<=CURRENT_TIMESTAMP
                  AND (m.valid_until IS NULL OR m.valid_until>CURRENT_TIMESTAMP)
                """,actor.userId()).stream().map(row->(UUID)row.get("department_id")).toList();
        return new Scope(company,departments);
    }

    /** 部门目录及员工候选共用分派范围，员工按姓名、工号或部门筛选并分页。 */
    public Map<String,Object> candidates(Actor actor,Map<String,String> query) {
        Inputs.only(query,"page","keyword","departmentId","view");
        var scope=scope(actor);
        boolean canAssign=scope.company()||!scope.departments().isEmpty();
        String departmentScope=uuidArray(scope.departments());
        if(query.containsKey("view")) {
            Inputs.choice(query.get("view"),"departments");
            var departments=db.rows("""
                    SELECT id,name FROM iam.department
                    WHERE (? OR id=ANY(?::uuid[]))
                    ORDER BY name,id
                    """,scope.company(),departmentScope);
            return Sql.map("canAssign",canAssign,"items",departments);
        }
        int page=Inputs.integer(query.get("page"),1,1,100000);
        String keyword=optionalText(query,"keyword",100);
        UUID department=Inputs.nullableUuid(query.get("departmentId"));
        var rows=db.rows("""
                SELECT id,name,employee_no,department_id FROM iam.user_account
                WHERE status='ACTIVE' AND NOT it_shared_account AND id<>?
                  AND (? OR department_id=ANY(?::uuid[]))
                  AND (?::uuid IS NULL OR department_id=?)
                  AND position(lower(?) in lower(name||' '||employee_no))>0
                ORDER BY name,id LIMIT 21 OFFSET ?
                """,actor.userId(),scope.company(),departmentScope,department,department,keyword,(page-1)*20);
        return Sql.map("canAssign",canAssign,"hasNext",rows.size()>20,
                "items",rows.stream().limit(20).map(row->Sql.view(row,"id,name,employee_no,department_id")).toList());
    }

    /** 创建个人事项或按实时组织范围分派。相同提交号可以安全重试。 */
    @Transactional
    public Map<String,Object> create(Actor actor,UUID operation,UUID request,Map<String,Object> body) {
        Inputs.only(body,"title","description","assigneeId","remindAt");
        String title=Inputs.text(body,"title",160),description=optionalText(body,"description",2000);
        Instant remindAt=reminderTime(body);
        UUID assignee=Inputs.nullableUuid(body.get("assigneeId"));
        if(assignee==null) assignee=actor.userId();
        identity.lockActor(actor);
        if(!assignee.equals(actor.userId())) {
            var scope=scope(actor);
            if(db.count("SELECT count(*) FROM iam.user_account WHERE id=? AND status='ACTIVE' AND NOT it_shared_account AND (? OR department_id=ANY(?::uuid[]))",
                    assignee,scope.company(),uuidArray(scope.departments()))!=1) throw ApiFault.denied();
        }
        var payload=Sql.map("title",title,"description",description,"assigneeId",assignee);
        if(remindAt!=null) payload.put("remindAt",remindAt.toString());
        String digest=Crypto.hash(Crypto.json(payload));
        var prior=db.one("SELECT id,request_digest FROM portal.employee_todo WHERE creator_id=? AND operation_id=?",actor.userId(),operation);
        if(prior!=null) {
            if(!Crypto.equal(digest,(String)prior.get("request_digest"))) throw ApiFault.conflict("同一操作号不能用于不同待办");
            return detail(actor,(UUID)prior.get("id"));
        }
        requireFutureReminder(remindAt);
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO portal.employee_todo(id,creator_id,assignee_id,title,description,operation_id,request_digest,remind_at) VALUES(?,?,?,?,?,?,?,?)",
                id,actor.userId(),assignee,title,description,operation,digest,remindAt==null?null:Timestamp.from(remindAt));
        audit.event(actor.userId(),"PLATFORM","EMPLOYEE_TODO",id,"CREATE_TODO","SUCCEEDED",request);
        return detail(actor,id);
    }

    /** 仅分派人和接收人可见详情。个人待办仅本人可见。 */
    public Map<String,Object> detail(Actor actor,UUID id) {
        var row=db.required("""
                SELECT t.*,c.name AS creator_name,a.name AS assignee_name
                FROM portal.employee_todo t JOIN iam.user_account c ON c.id=t.creator_id
                JOIN iam.user_account a ON a.id=t.assignee_id
                WHERE t.id=? AND (t.creator_id=? OR t.assignee_id=?) AND t.state<>'DELETED'
                """,id,actor.userId(),actor.userId());
        return Sql.view(row,"id,creator_id,assignee_id,creator_name,assignee_name,title,description,state,completion_note,created_at,submitted_at,completed_at,remind_at,row_version");
    }

    /** 仅本人可修改个人事项，分派任务的内容保持原样。 */
    @Transactional
    public Map<String,Object> edit(Commands.Context context,UUID id,Map<String,Object> body) {
        Inputs.only(body,"title","description","remindAt");
        String title=Inputs.text(body,"title",160),description=optionalText(body,"description",2000);
        Instant remindAt=reminderTime(body);
        var row=lock(context,id);
        if(!personal(row)) throw ApiFault.denied();
        db.update("UPDATE portal.employee_todo SET title=?,description=?,row_version=row_version+1 WHERE id=?",title,description,id);
        if(body.containsKey("remindAt")&&!Objects.equals(remindAt==null?null:remindAt.toString(),row.get("remind_at"))) {
            requireFutureReminder(remindAt);
            db.update("UPDATE portal.employee_todo SET remind_at=?,reminder_seen_at=NULL WHERE id=?",remindAt==null?null:Timestamp.from(remindAt),id);
        }
        audit.event(context.actor().userId(),"PLATFORM","EMPLOYEE_TODO",id,"EDIT_TODO","SUCCEEDED",context.request());
        return detail(context.actor(),id);
    }

    /** 个人事项可完成、重开或删除；分派事项须经接收人提交和原分派人决定。 */
    @Transactional
    public Map<String,Object> transition(Commands.Context context,UUID id,Map<String,Object> body) {
        Inputs.only(body,"action","completionNote");
        String action=Inputs.choice(body.get("action"),"COMPLETE","REOPEN","DELETE","SUBMIT","CONFIRM","RETURN");
        String note=optionalText(body,"completionNote",2000);
        var row=lock(context,id);
        var actor=context.actor();
        boolean own=actor.userId().equals(row.get("creator_id"));
        boolean personal=personal(row);
        if(!(personal ? Set.of("COMPLETE","REOPEN","DELETE").contains(action)
                : own ? Set.of("CONFIRM","RETURN").contains(action) : action.equals("SUBMIT"))) throw ApiFault.denied();
        String state=(String)row.get("state");
        String next=switch(action) {
            case "DELETE" -> "DELETED";
            case "COMPLETE" -> transitionFrom(state,"OPEN","COMPLETED");
            case "REOPEN" -> transitionFrom(state,"COMPLETED","OPEN");
            case "SUBMIT" -> transitionFrom(state,"OPEN","SUBMITTED");
            case "CONFIRM" -> transitionFrom(state,"SUBMITTED","COMPLETED");
            case "RETURN" -> transitionFrom(state,"SUBMITTED","OPEN");
            default -> throw ApiFault.invalid("未知待办动作");
        };
        db.update("""
                UPDATE portal.employee_todo SET state=?,row_version=row_version+1,
                  completion_note=CASE WHEN ? THEN ? ELSE completion_note END,
                  submitted_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE submitted_at END,
                  completed_at=CASE WHEN ?='COMPLETED' THEN CURRENT_TIMESTAMP ELSE NULL END WHERE id=?
                """,next,action.equals("SUBMIT"),note,action.equals("SUBMIT"),next,id);
        audit.event(actor.userId(),"PLATFORM","EMPLOYEE_TODO",id,action+"_TODO","SUCCEEDED",context.request());
        return next.equals("DELETED")?Sql.map("id",id,"state",next):detail(actor,id);
    }

    /** 同一事项的修改和审批按行锁串行，旧页面不能覆盖新决定。 */
    private Map<String,Object> lock(Commands.Context context,UUID id) {
        var actor=context.actor();identity.lockActor(actor);
        var row=db.required("SELECT * FROM portal.employee_todo WHERE id=? AND (creator_id=? OR assignee_id=?) AND state<>'DELETED' FOR UPDATE",id,actor.userId(),actor.userId());
        Commands.version(context,row);
        return row;
    }

    private String optionalText(Map<String,?> body,String key,int limit) {
        Object value=body.get(key);
        return value==null || value instanceof String text && text.isBlank() ? "" : Inputs.text(body,key,limit);
    }
    /** 已到时的原提醒可保留；新设置的提醒必须在将来。 */
    private void requireFutureReminder(Instant time) {
        if(time!=null&&!time.isAfter(Instant.now())) throw ApiFault.invalid("提醒时间必须晚于现在");
    }
    /** 接收统一的带时区时间，空值表示不提醒。 */
    private Instant reminderTime(Map<String,Object> body) {
        if(body.get("remindAt")==null) return null;
        try {
            return Instant.parse(Inputs.text(body,"remindAt",40)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        } catch(DateTimeParseException failure) {
            throw ApiFault.invalid("提醒时间格式不正确");
        }
    }
    private boolean personal(Map<String,Object> row) {return row.get("creator_id").equals(row.get("assignee_id"));}
    private String transitionFrom(String actual,String expected,String next) {
        if(!expected.equals(actual)) throw ApiFault.conflict("当前待办状态已变化，请刷新后重试");
        return next;
    }
    private String uuidArray(List<UUID> values) {return "{"+String.join(",",values.stream().map(UUID::toString).toList())+"}";}
}
