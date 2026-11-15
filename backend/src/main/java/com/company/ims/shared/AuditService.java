package com.company.ims.shared;

import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

/** 审计写入与业务共用调用方事务，失败时一并回滚。 */
@Service
public class AuditService {
    /** 统一数据库访问器。 */
    private final Db db;
    /** 注入数据库访问器。 */
    public AuditService(Db db) { this.db = db; }
    /** 记录操作；detail 只允许业务摘要，禁止口令、令牌和附件原文。 */
    public void record(Long actorId, String module, String action, String targetType, Long targetId, String detail) {
        // 审计字段采用显式白名单，避免混入完整用户请求。
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("user_id", actorId); values.put("module", module); values.put("action", action);
        values.put("target_type", targetType); values.put("target_id", targetId); values.put("detail_json", detail);
        db.insert("operation_log", values);
    }
}
