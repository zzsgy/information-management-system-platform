package com.company.ims.catalog;

import com.company.ims.identity.Actor;
import com.company.ims.shared.AuditService;
import com.company.ims.shared.BusinessException;
import com.company.ims.shared.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 平台公共公告适配器，限定指定收件人和公告有效期，避免定向公告泄露。 */
@Service
public class NoticeService {
    /** 公告可见范围：全员、指定本人、本人发布；同时必须尚未失效。 */
    private static final String VISIBLE = "n.deleted_at IS NULL AND n.publish_status='published' AND (n.expire_time IS NULL OR n.expire_time>CURRENT_TIMESTAMP) AND (n.global_visible=1 OR n.publisher_id=? OR EXISTS (SELECT 1 FROM notice_receiver r WHERE r.notice_id=n.id AND r.user_id=?))";
    /** 公告与回执数据访问器。 */
    private final Db db;
    /** 发布和阅读操作审计服务。 */
    private final AuditService audit;

    /** 注入公告持久化与审计依赖。 */
    public NoticeService(Db db, AuditService audit) { this.db = db; this.audit = audit; }

    /** 发布全员或指定人员公告，发送列表以明确用户主键保存。 */
    @Transactional
    public Map<String, Object> publish(Actor actor, Map<String, Object> body) {
        require(actor, "notice.publish");
        CatalogService.checkKeys(body, Set.of("title", "content", "notice_type", "receiver_user_ids", "expire_time"));
        // 收件人列表为空是旧客户端的全员发布语义。
        Object rawReceivers = body.getOrDefault("receiver_user_ids", List.of());
        if (!(rawReceivers instanceof List<?> list) || list.size() > 10000) throw CatalogService.bad("接收人须为不超过10000项的数组");
        // 去重后逐个验证账号存在，不能生成悬空授权。
        Set<Long> receivers = new LinkedHashSet<>();
        for (Object value : list) {
            // 本次业务记录或引用对象的主键，供关联和审计使用。
            long id = CatalogService.positiveId(value);
            if (db.one("SELECT id FROM ims_user WHERE id=? AND deleted_at IS NULL AND account_status='active'", id) == null) throw CatalogService.bad("公告接收人不存在或账号未启用");
            receivers.add(id);
        }
        // 写入字段由服务端构造，发布者和发布状态不能由请求伪造。
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", CatalogService.text(body.get("title"), "公告标题", true, 300));
        values.put("content", CatalogService.text(body.get("content"), "公告内容", false, 100000));
        values.put("notice_type", CatalogService.text(body.getOrDefault("notice_type", "system"), "公告类型", true, 30));
        values.put("publisher_id", actor.id());
        values.put("global_visible", receivers.isEmpty() ? 1 : 0);
        if (body.get("expire_time") != null) {
            // ISO日期时间须包含正确时区；兼容旧客户端无时区输入时按公司时区解释。
            OffsetDateTime expiry = timestamp(body.get("expire_time"));
            if (!expiry.isAfter(OffsetDateTime.now())) throw CatalogService.bad("公告失效时间必须晚于当前时间");
            values.put("expire_time", expiry);
        }
        // 本次业务记录或引用对象的主键，供关联和审计使用。
        long id = db.insert("notice", values);
        for (long receiverId : receivers) db.insert("notice_receiver", Map.of("notice_id", id, "user_id", receiverId));
        audit.record(actor.id(), "notice", "publish", "notice", id, receivers.isEmpty() ? "发布全员公告" : "发布定向公告，接收人数=" + receivers.size());
        return db.required("SELECT * FROM notice WHERE id=?", id);
    }

    /** 只返回当前有效且本人可见的公告，total采用相同范围条件。 */
    public Map<String, Object> list(Actor actor, int page, int pageSize) {
        require(actor, "notice.read");
        page(page, pageSize);
        // 总数与列表共用可见谓词，避免通过计数发现其他收件人的公告。
        long total = db.scalarLong("SELECT COUNT(*) FROM notice n WHERE " + VISIBLE, actor.id(), actor.id());
        // 通过权限范围过滤后的当前页结果。
        List<Map<String, Object>> items = db.list("SELECT n.*,(SELECT r.read_at FROM notice_receiver r WHERE r.notice_id=n.id AND r.user_id=?) AS read_at FROM notice n WHERE " + VISIBLE + " ORDER BY n.id DESC LIMIT ? OFFSET ?", actor.id(), actor.id(), actor.id(), pageSize, (page - 1) * pageSize);
        return Map.of("total", total, "items", items);
    }

    /** 单条详情与列表使用同一访问规则，不能靠猜测ID读取定向公告。 */
    public Map<String, Object> detail(Actor actor, long id) {
        require(actor, "notice.read");
        return db.required("SELECT n.* FROM notice n WHERE n.id=? AND " + VISIBLE, id, actor.id(), actor.id());
    }

    /** 为可见公告记录本人阅读回执，不接受替他人标记的user_id。 */
    @Transactional
    public void read(Actor actor, long id) {
        detail(actor, id);
        db.required("SELECT id FROM notice WHERE id=? FOR UPDATE", id);
        // 全员公告首次阅读时创建回执；指定收件人的回执预先存在。
        Map<String, Object> receipt = db.one("SELECT id,read_at FROM notice_receiver WHERE notice_id=? AND user_id=?", id, actor.id());
        if (receipt == null) db.insert("notice_receiver", Map.of("notice_id", id, "user_id", actor.id(), "read_at", OffsetDateTime.now()));
        else if (receipt.get("read_at") == null) db.execute("UPDATE notice_receiver SET read_at=CURRENT_TIMESTAMP WHERE id=?", receipt.get("id"));
    }

    /** 解析公告有效期，无时区的旧客户端值按公司东八区处理。 */
    private OffsetDateTime timestamp(Object raw) {
        // 先校验输入类型和长度，避免把任意对象转换为日期文本。
        String value = CatalogService.text(raw, "公告失效时间", true, 50);
        try { return OffsetDateTime.parse(value); }
        catch (DateTimeParseException error) {
            try { return LocalDateTime.parse(value).atOffset(ZoneOffset.ofHours(8)); }
            catch (DateTimeParseException invalid) { throw CatalogService.bad("公告失效时间须为ISO日期时间"); }
        }
    }

    /** 公告操作权限精确检查，不借用档案管理员身份扩大权限。 */
    private void require(Actor actor, String permission) { if (!actor.has(permission)) throw new BusinessException(403, "没有操作权限：" + permission); }
    /** 分页上限与旧客户端批量刷新兼容，拒绝超大单次读取。 */
    static void page(int page, int pageSize) { if (page < 1 || page > 100000 || pageSize < 1 || pageSize > 200) throw CatalogService.bad("分页参数超出允许范围"); }
}
