package com.company.ims.shared;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 仅公开最少的就绪状态，真实检查数据库连接，不暴露配置或账号。 */
@RestController
public class HealthController {
    /** 用于确认数据库可连接的只读入口。 */
    private final Db db;
    /** 注入服务实际使用的数据库访问器。 */
    public HealthController(Db db) { this.db = db; }
    /** 数据库不可用时返回503，供部署脚本和反向代理判断就绪状态。 */
    @GetMapping({"/health", "/actuator/health"})
    public ResponseEntity<Map<String,String>> health() {
        try {
            db.scalarLong("select 1");
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (RuntimeException error) {
            // 诊断细节不在匿名接口中公开。
            return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
        }
    }
}
