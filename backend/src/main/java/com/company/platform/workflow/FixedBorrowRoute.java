package com.company.platform.workflow;

import com.company.platform.ApiFault;
import java.util.*;

/** 送审固定路线的纯规则；不会接收浏览器指定的审批人或允许省略必要节点。 */
public final class FixedBorrowRoute {
    private FixedBorrowRoute() { }
    /** 必要职责和所属部门；同一人兼任两个职责不会自动合并。 */
    public record Duty(/** 固定职责代码。 */ String code,/** 职责所属部门，总经理和超级管理员为空。 */ UUID department) { }
    /** 按原父清单最高密级选择；仅去重同部门档案员，申请人是档案员时跳过本人档案员节点。 */
    public static List<Duty> plan(String classification,UUID applicantDepartment,UUID ownerDepartment,boolean applicantArchivist) {
        List<Duty> nodes=new ArrayList<>();boolean cross=!applicantDepartment.equals(ownerDepartment);
        if(classification.equals("CORE")) {
            nodes.add(new Duty("ARCHIVIST",applicantDepartment));nodes.add(new Duty("ARCHIVIST",ownerDepartment));nodes.add(new Duty("DEPARTMENT_MANAGER",ownerDepartment));nodes.add(new Duty("GENERAL_MANAGER",null));
        } else if(Set.of("GENERAL","IMPORTANT").contains(classification)) {
            nodes.add(new Duty("ARCHIVIST",applicantDepartment));nodes.add(new Duty("DEPARTMENT_MANAGER",applicantDepartment));
            if(cross) {nodes.add(new Duty("DEPARTMENT_MANAGER",ownerDepartment));nodes.add(new Duty("ARCHIVIST",ownerDepartment));}
            if(classification.equals("IMPORTANT")) nodes.add(new Duty("GENERAL_MANAGER",null));
        } else throw ApiFault.invalid("保密等级不受支持");
        nodes.add(new Duty("ARCHIVE_ADMIN",null));Set<UUID> seenArchivists=new HashSet<>();
        return nodes.stream().filter(node->!node.code().equals("ARCHIVIST") || (seenArchivists.add(node.department()) && !(applicantArchivist&&node.department().equals(applicantDepartment)))).toList();
    }

    /** 新借阅先经部门负责人，再经档案员；需要总经理时安排在末级前。 */
    public static List<Duty> managerFirst(UUID applicantDepartment, UUID ownerDepartment, boolean generalManager) {
        var route = new ArrayList<Duty>();
        route.add(new Duty("DEPARTMENT_MANAGER", applicantDepartment));
        route.add(new Duty("ARCHIVIST", applicantDepartment));
        if (!applicantDepartment.equals(ownerDepartment)) {
            route.add(new Duty("DEPARTMENT_MANAGER", ownerDepartment));
            route.add(new Duty("ARCHIVIST", ownerDepartment));
        }
        if (generalManager) route.add(new Duty("GENERAL_MANAGER", null));
        route.add(new Duty("ARCHIVE_ADMIN", null));
        return route;
    }
}
