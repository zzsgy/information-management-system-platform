package com.company.platform.storage;

import com.company.platform.Actor;
import com.company.platform.Inputs;
import java.util.List;
import java.util.Map;

/** 文件和目录使用同一资料空间范围，实际可见性仍由公盘授权决定。 */
enum DriveSpace {
    ALL, OWN_INTERNAL, COMPANY_SHARED, OTHER_DEPARTMENT_SHARED;

    static DriveSpace from(Map<String, String> query) {
        return valueOf(Inputs.choice(query.getOrDefault("space", "ALL"),
                "ALL", "OWN_INTERNAL", "COMPANY_SHARED", "OTHER_DEPARTMENT_SHARED"));
    }

    /** 调用方以 f 表示文件所在目录，部门取当前员工的有效身份。 */
    String condition(Actor actor, List<Object> parameters) {
        return switch (this) {
            case ALL -> "";
            case COMPANY_SHARED -> " AND f.zone='COMPANY_SHARED'";
            case OWN_INTERNAL -> {
                parameters.add(actor.user().get("departmentId"));
                yield " AND f.zone='DEPARTMENT_INTERNAL' AND f.department_id=?";
            }
            case OTHER_DEPARTMENT_SHARED -> {
                parameters.add(actor.user().get("departmentId"));
                yield " AND f.zone='DEPARTMENT_SHARED' AND f.department_id IS DISTINCT FROM ?::uuid";
            }
        };
    }
}
