package com.company.platform.workflow;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** 必要审批职责解析；保存具体任职记录，角色变更和任职过期后不能沿用旧节点处理权。 */
@Service
public class WorkflowDuties {
    /** 当前账号、组织与任职均来自参数化SQL，不借用公盘角色批准档案。 */
    private final Sql db;
    public WorkflowDuties(Sql db) {this.db=db;}
    /** 合法处理人的任职来源，数据库时间负责生效和到期判断。 */
    public record Assignment(/** 实际审批员工。 */ UUID user,/** 任职来源表种类。 */ String type,/** 固定具体任职记录。 */ UUID reference) { }
    /** 没有唯一合法职责时拒绝送审；超级管理员多人时按稳定任职标识分配，不自动批准。 */
    public Assignment resolve(FixedBorrowRoute.Duty duty) {
        return resolve(duty, null);
    }

    /** IT 通用借阅只有一个审批节点，解析档案超管时直接排除申请人本人。 */
    Assignment resolve(FixedBorrowRoute.Duty duty, UUID excludedApplicant) {
        List<Map<String,Object>> rows;String type;
        if(Set.of("ARCHIVIST","ARCHIVE_ADMIN").contains(duty.code())) {
            type="ROLE";rows=db.rows("SELECT a.id,a.user_id FROM iam.user_role a JOIN iam.role r ON r.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.module='ARCHIVE' AND a.role_code=? AND (?::uuid IS NULL OR a.department_id=?) AND a.user_id IS DISTINCT FROM ?::uuid AND a.state='ACTIVE' AND r.status='ACTIVE' AND u.status='ACTIVE' AND iam.archive_access_enabled(u.id) AND NOT iam.it_shared_borrower(u.id) AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP) ORDER BY a.id FOR SHARE OF a,r",duty.code().equals("ARCHIVIST")?"DEPARTMENT_ARCHIVIST":"SUPER_ADMIN",duty.department(),duty.department(),excludedApplicant);
        } else if(Set.of("DEPARTMENT_MANAGER","SUPERVISING_LEADER").contains(duty.code())) {
            type=duty.code().equals("SUPERVISING_LEADER")?"SUPERVISING":"DEPARTMENT";rows=db.rows("SELECT a.id,a.user_id FROM iam.department_manager a JOIN iam.user_account u ON u.id=a.user_id JOIN iam.department d ON d.id=a.department_id WHERE a.department_id=? AND a.duty_type=? AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP) AND u.status='ACTIVE' AND iam.archive_access_enabled(u.id) AND NOT iam.it_shared_borrower(u.id) AND d.status='ACTIVE' ORDER BY a.id FOR SHARE OF a,d",duty.department(),duty.code());
        } else if(duty.code().equals("GENERAL_MANAGER")) {
            type="COMPANY";rows=db.rows("SELECT a.id,a.user_id FROM iam.company_duty a JOIN iam.user_account u ON u.id=a.user_id WHERE a.duty_code='GENERAL_MANAGER' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP) AND u.status='ACTIVE' AND iam.archive_access_enabled(u.id) AND NOT iam.it_shared_borrower(u.id) ORDER BY a.id FOR SHARE OF a");
        } else throw ApiFault.invalid("审批职责不受支持");
        if (rows.isEmpty()) throw dutyError(duty,
            excludedApplicant == null ? "尚未配置有效审批人" : "请配置申请人之外的有效审批人", type);
        if (!duty.code().equals("ARCHIVE_ADMIN") && rows.size() != 1)
            throw dutyError(duty, "存在多位有效审批人，请核对任职", type);
        var chosen = rows.getFirst();
        if (db.count("SELECT CASE WHEN workflow.assignment_valid(?,?,?) THEN 1 ELSE 0 END", type, chosen.get("id"), chosen.get("user_id")) != 1)
            throw dutyError(duty, "审批人的档案权限或部门归属已失效", type);
        return new Assignment((UUID) chosen.get("user_id"), type, (UUID) chosen.get("id"));
    }

    /** 只在配置错误时读取部门名称，给出可定位的中文职责和配置入口。 */
    private ApiFault dutyError(FixedBorrowRoute.Duty duty, String reason, String type) {
        String name = switch (duty.code()) {
            case "ARCHIVIST" -> "部门档案员";
            case "ARCHIVE_ADMIN" -> "档案超级管理员";
            case "DEPARTMENT_MANAGER" -> "部门负责人";
            case "SUPERVISING_LEADER" -> "分管领导";
            case "GENERAL_MANAGER" -> "公司总经理";
            default -> throw ApiFault.invalid("审批职责不受支持");
        };
        String scope = duty.department() == null ? ""
            : "「" + db.required("SELECT name FROM iam.department WHERE id=?", duty.department()).get("name") + "」的";
        String location = "ROLE".equals(type) ? "角色与权限" : "管理配置 → 组织审批职责";
        return ApiFault.conflict(scope + name + "：" + reason + "。请联系管理员到「" + location + "」处理。");
    }
    /** 决定提交时共享锁住原任职行，再验证该任职仍属于当前人且实际有效。 */
    public void requireCurrent(Map<String,Object> step,UUID person) {
        if(!person.equals(step.get("assignee_id"))) throw ApiFault.denied();
        String table=switch((String)step.get("assignment_type")) {case "ROLE"->"iam.user_role";case "DEPARTMENT","SUPERVISING"->"iam.department_manager";case "COMPANY"->"iam.company_duty";default->throw ApiFault.protection();};
        db.required("SELECT id FROM "+table+" WHERE id=? FOR SHARE",step.get("assignment_ref"));
        if(db.count("SELECT CASE WHEN workflow.assignment_valid(?,?,?) THEN 1 ELSE 0 END",step.get("assignment_type"),step.get("assignment_ref"),person)!=1) throw ApiFault.denied();
    }
}
