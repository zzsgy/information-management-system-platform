package com.company.ims.catalog;

import com.company.ims.identity.Actor;
import com.company.ims.identity.CurrentUser;
import com.company.ims.identity.PermissionGuard;
import com.company.ims.shared.ApiResponse;
import com.company.ims.shared.Db;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 公告与审计查询兼容入口；全局日志只有系统通配权限账号可见。 */
@RestController
@RequestMapping("/api")
public class NoticeLogController {
    /** 公告可见范围与回执业务。 */
    private final NoticeService notices;
    /** 操作日志只读查询入口。 */
    private final Db db;

    /** 注入公告服务和日志数据库访问器。 */
    public NoticeLogController(NoticeService notices, Db db) { this.notices = notices; this.db = db; }

    /** 查询本人可见的当前公告。 */
    @GetMapping("/notices")
    public Object notices(@RequestParam(defaultValue = "1") int page, @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.ok(notices.list(CurrentUser.require(), page, pageSize));
    }

    /** 发布全员或指定用户公告。 */
    @PostMapping("/notices")
    public Object publish(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(notices.publish(CurrentUser.require(), body), "公告已发布");
    }

    /** 读取单条公告，不能用ID绕过收件人范围。 */
    @GetMapping("/notices/{id}")
    public Object detail(@PathVariable long id) { return ApiResponse.ok(notices.detail(CurrentUser.require(), id)); }

    /** 记录本人已读状态。 */
    @PostMapping("/notices/{id}/read")
    public Object read(@PathVariable long id) {
        notices.read(CurrentUser.require(), id);
        return ApiResponse.ok(null, "已标记为已读");
    }

    /** 按模块、动作和用户筛选审计；普通日志查看者只能查看本部门用户事件。 */
    @GetMapping("/logs")
    public Object logs(@RequestParam(required = false) String module, @RequestParam(required = false) String action,
                       @RequestParam(name = "user_id", required = false) Long userId,
                       @RequestParam(defaultValue = "1") int page, @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        // 只有精确log.read或系统通配权限可以进入日志接口。
        Actor actor = PermissionGuard.require("log.read");
        NoticeService.page(page, pageSize);
        // 查询条件由服务端固定片段构成，筛选值使用参数绑定。
        StringBuilder where = new StringBuilder("1=1");
        // 与SQL占位符一一对应的绑定参数。
        List<Object> parameters = new ArrayList<>();
        if (!actor.has("*")) { where.append(" AND l.user_id IN (SELECT u.id FROM ims_user u WHERE u.dept_id=?)"); parameters.add(actor.deptId()); }
        if (module != null && !module.isBlank()) { where.append(" AND l.module=?"); parameters.add(CatalogService.text(module, "日志模块", true, 50)); }
        if (action != null && !action.isBlank()) { where.append(" AND l.action=?"); parameters.add(CatalogService.text(action, "日志动作", true, 50)); }
        if (userId != null) { where.append(" AND l.user_id=?"); parameters.add(userId); }
        // 总数使用相同范围，避免通过统计推断其他部门操作。
        long total = db.scalarLong("SELECT COUNT(*) FROM operation_log l WHERE " + where, parameters.toArray());
        parameters.add(pageSize); parameters.add((page - 1) * pageSize);
        // ip保留兼容字段但不编造来源，现有审计表未持久化IP。
        List<Map<String, Object>> items = db.list("SELECT l.id,l.user_id,l.module,l.action,l.target_type,l.target_id,l.created_at,l.detail_json AS detail,NULL AS ip FROM operation_log l WHERE " + where + " ORDER BY l.id DESC LIMIT ? OFFSET ?", parameters.toArray());
        return ApiResponse.ok(Map.of("total", total, "items", items));
    }
}
