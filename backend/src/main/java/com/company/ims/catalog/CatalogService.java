package com.company.ims.catalog;

import com.company.ims.archive.NumberingService;
import com.company.ims.identity.Actor;
import com.company.ims.shared.AuditService;
import com.company.ims.shared.BusinessException;
import com.company.ims.shared.Db;
import com.company.ims.shared.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 档案基础目录服务；只允许固定表和字段，配置调整不能绕过精确权限。 */
@Service
public class CatalogService {
    /** 固定资源配置，路径参数只能在这份白名单中选择。 */
    private static final Map<String, Kind> KINDS = Map.of(
            "dict-items", new Kind("dict_item", "dict.manage", "archive.read", "sort_order,id", Set.of("type_code", "item_code", "item_name", "sort_order", "enabled")),
            "security-levels", new Kind("security_level", "dict.manage", "dict.manage", "level,id", Set.of("code", "name", "level", "description")),
            "retention-periods", new Kind("retention_period", "dict.manage", "dict.manage", "years,id", Set.of("code", "name", "years")),
            "numbering-rules", new Kind("numbering_rule", "archive.admin", "archive.read", "priority DESC,id", Set.of("rule_code", "rule_name", "pattern", "segments", "scope_type", "reset_cycle", "sequence_length", "priority", "enabled")),
            "categories", new Kind("archive_category", "archive.admin", "archive.read", "sort_order,id", Set.of("category_code", "category_name", "parent_id", "archive_level", "default_security_level_id", "default_retention_id", "numbering_rule_id", "sort_order", "status")));
    /** 公开展示参数白名单，禁止把密钥或内部连接配置当作首页参数存储。 */
    private static final Set<String> PARAMETER_KEYS = Set.of("enterprise_logo", "dashboard_cards", "archive_no_mode");
    /** 基础目录数据访问器。 */
    private final Db db;
    /** 与档案创建共用的真实编号生成器，预览不消耗序列。 */
    private final NumberingService numbering;
    /** 配置变更审计，不记录首页图片原始内容。 */
    private final AuditService audit;

    /** 注入目录存储、编号和审计依赖。 */
    public CatalogService(Db db, NumberingService numbering, AuditService audit) {
        this.db = db;
        this.numbering = numbering;
        this.audit = audit;
    }

    /** 列出对应资源；字典项只返回启用项，其他目录保持管理界面所需的状态字段。 */
    public List<Map<String, Object>> list(Actor actor, String kindName, String typeCode) {
        return list(actor, kindName, typeCode, false);
    }

    /** 管理页可显式包含停用字典；普通业务下拉仍仅返回启用项。 */
    public List<Map<String, Object>> list(Actor actor, String kindName, String typeCode, boolean includeDisabled) {
        // 白名单元数据决定表名、排序和权限，客户端不能直接提供SQL标识符。
        Kind kind = kind(kindName);
        require(actor, kind.readPermission());
        // 查询参数始终绑定；字典类型可由调用方筛选。
        List<Map<String, Object>> rows;
        if ("dict-items".equals(kindName)) {
            if (typeCode == null || typeCode.isBlank() || typeCode.length() > 80) throw bad("需要有效字典类型");
            if (includeDisabled) require(actor, "dict.manage");
            rows = db.list("SELECT * FROM dict_item WHERE type_code=?" + (includeDisabled ? "" : " AND enabled=1") + " ORDER BY sort_order,id", typeCode);
        } else rows = db.list("SELECT * FROM " + kind.table() + " ORDER BY " + kind.order());
        return rows.stream().map(row -> output(kindName, row)).toList();
    }

    /** 下拉框只读取通用密级和保管期限，不要求具备维护字典权限。 */
    public Map<String, Object> dictionaries(Actor actor) {
        require(actor, "archive.read");
        return Map.of("security_levels", db.list("SELECT id,code,name FROM security_level ORDER BY level,id"),
                "retention_periods", db.list("SELECT id,code,name FROM retention_period ORDER BY years,id"));
    }

    /** 创建或更新目录对象，校验字段类型、引用关系、编号规则及分类树循环。 */
    @Transactional
    public Map<String, Object> save(Actor actor, String kindName, Long id, Map<String, Object> body) {
        // 资源映射和写权限都来自固定服务端定义。
        Kind kind = kind(kindName);
        require(actor, kind.writePermission());
        checkKeys(body, kind.fields());
        if ("categories".equals(kindName)) db.list("SELECT id FROM archive_category ORDER BY id FOR UPDATE");
        // 更新基于已有配置校验合并后的完整状态，避免半更新绕过约束。
        Map<String, Object> existing = id == null ? new LinkedHashMap<>() : db.required("SELECT * FROM " + kind.table() + " WHERE id=? FOR UPDATE", id);
        // 仅白名单字段进入写入集合。
        Map<String, Object> values = new LinkedHashMap<>(body);
        validate(kindName, id, values, existing);
        // 对新建和更新统一产生明确主键，便于审计引用。
        long savedId;
        if (id == null) savedId = db.insert(kind.table(), values);
        else { db.update(kind.table(), id, values); savedId = id; }
        audit.record(actor.id(), "catalog", id == null ? "create" : "update", kind.table(), savedId, "维护档案基础配置");
        return output(kindName, db.required("SELECT * FROM " + kind.table() + " WHERE id=?", savedId));
    }

    /** 删除未引用的配置，已经用于档案或编号历史的记录不允许物理移除。 */
    @Transactional
    public void delete(Actor actor, String kindName, long id) {
        // 固定资源映射确保删除不能指向任意表。
        Kind kind = kind(kindName);
        require(actor, kind.writePermission());
        // 锁定目标，避免与并发更新交错。
        Map<String, Object> existing = db.required("SELECT * FROM " + kind.table() + " WHERE id=? FOR UPDATE", id);
        switch (kindName) {
            case "categories" -> {
                unused("SELECT COUNT(*) FROM archive_category WHERE parent_id=?", id);
                unused("SELECT COUNT(*) FROM archive WHERE category_id=?", id);
            }
            case "security-levels" -> {
                unused("SELECT COUNT(*) FROM archive WHERE security_level_id=?", id);
                unused("SELECT COUNT(*) FROM archive_category WHERE default_security_level_id=?", id);
            }
            case "retention-periods" -> {
                unused("SELECT COUNT(*) FROM archive WHERE retention_id=?", id);
                unused("SELECT COUNT(*) FROM archive_category WHERE default_retention_id=?", id);
            }
            case "numbering-rules" -> {
                unused("SELECT COUNT(*) FROM archive_category WHERE numbering_rule_id=?", id);
                unused("SELECT COUNT(*) FROM numbering_sequence WHERE rule_id=?", id);
            }
            case "dict-items" -> {
                // 已被档案用作编码或展示名称的字典项不可删除，防止旧记录失去含义。
                String field = switch (existing.get("type_code").toString()) {
                    case "file_type" -> "file_type"; case "storage_type" -> "storage_type"; case "storage_location" -> "physical_location"; default -> null;
                };
                if (field != null && db.scalarLong("SELECT COUNT(*) FROM archive WHERE " + field + "=? OR " + field + "=?", existing.get("item_code"), existing.get("item_name")) > 0)
                    throw new BusinessException(409, "字典项已被档案使用，请停用而非删除");
            }
            default -> throw bad("不支持的目录类型");
        }
        db.execute("DELETE FROM " + kind.table() + " WHERE id=?", id);
        audit.record(actor.id(), "catalog", "delete", kind.table(), id, "删除未引用的基础配置");
    }

    /** 读取面向客户端展示的参数；缺少参数时仍保持旧接口value为空的语义。 */
    public Map<String, Object> parameter(Actor actor, String key) {
        require(actor, "archive.read");
        checkParameterKey(key);
        // 仅查找固定公开参数，不开放任意系统配置读取。
        Map<String, Object> row = db.one("SELECT param_value FROM system_parameter WHERE param_key=?", key);
        // 有序映射允许value为空，Map.of不接受空值。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("value", row == null ? null : row.get("param_value"));
        return result;
    }

    /** 更新公开参数；编号方式须额外拥有档案高级管理权限。 */
    @Transactional
    public Map<String, Object> setParameter(Actor actor, String key, Map<String, Object> body) {
        require(actor, "archive_no_mode".equals(key) ? "archive.admin" : "dict.manage");
        checkParameterKey(key);
        checkKeys(body, Set.of("value", "description"));
        // 值沿用旧客户端传入文本的协议；禁止把任意对象隐式转成字符串。
        String value = text(body.get("value"), "参数值", false, 100000);
        if ("archive_no_mode".equals(key) && !Set.of("auto", "manual").contains(value == null ? "" : value)) throw bad("编号方式必须为auto或manual");
        if ("dashboard_cards".equals(key) && value != null) {
            try { Json.read(value, List.class); } catch (RuntimeException error) { throw bad("首页卡片配置必须为JSON数组"); }
        }
        // 不允许日志记录完整参数内容，只保留变更键。
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("param_value", value);
        if (body.containsKey("description")) values.put("description", text(body.get("description"), "参数说明", false, 2000));
        // 对已有参数加锁；白名单参数均在迁移脚本中预置。
        Map<String, Object> row = db.one("SELECT id FROM system_parameter WHERE param_key=? FOR UPDATE", key);
        if (row == null) { values.put("param_key", key); db.insert("system_parameter", values); }
        else db.update("system_parameter", ((Number) row.get("id")).longValue(), values);
        audit.record(actor.id(), "catalog", "set_parameter", "system_parameter", null, "更新参数：" + key);
        // 不要求写权限持有人同时具备archive.read，响应由已校验内容构造。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key); result.put("value", value);
        return result;
    }

    /** 预览编号时遵守部门隔离，结果只是预览值而非已预留编号。 */
    public Map<String, Object> preview(Actor actor, long departmentId, long categoryId, Long retentionId) {
        require(actor, "archive.read");
        if (actor.deptId() != departmentId && !actor.has("archive.manage.all")) throw new BusinessException(403, "不能预览其他部门档案编号");
        return Map.of("archive_no", numbering.generate(departmentId, categoryId, retentionId, true));
    }

    /** 校验目录字段，所有未开放的内部列均已在调用前拒绝。 */
    private void validate(String kindName, Long id, Map<String, Object> values, Map<String, Object> existing) {
        // 合并状态用于验证部分更新后的必填条件。
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        merged.putAll(values);
        switch (kindName) {
            case "dict-items" -> {
                required(values, merged, "type_code", 80); required(values, merged, "item_code", 80); required(values, merged, "item_name", 150);
                integerField(values, "sort_order", -100000, 100000); integerField(values, "enabled", 0, 1);
            }
            case "security-levels" -> {
                required(values, merged, "code", 50); required(values, merged, "name", 100);
                integerField(values, "level", 1, 10000); optionalText(values, "description", 10000);
            }
            case "retention-periods" -> {
                required(values, merged, "code", 50); required(values, merged, "name", 100); integerField(values, "years", 0, 1000);
            }
            case "categories" -> {
                required(values, merged, "category_code", 50); required(values, merged, "category_name", 100);
                integerField(values, "archive_level", 1, 100); integerField(values, "sort_order", -100000, 100000); integerField(values, "status", 0, 1);
                reference(values, "parent_id", "archive_category"); reference(values, "default_security_level_id", "security_level");
                reference(values, "default_retention_id", "retention_period"); reference(values, "numbering_rule_id", "numbering_rule");
                // 检查最终父链，阻止自身与祖先形成循环。
                Object rawParent = merged.get("parent_id");
                // 正在追溯的上级分类标识，逐级检查直至根节点。
                Long parentId = rawParent == null ? null : positiveId(rawParent);
                // 已检查的分类标识，用于发现祖先循环。
                Set<Long> visited = new LinkedHashSet<>();
                if (id != null) visited.add(id);
                while (parentId != null) {
                    if (!visited.add(parentId)) throw bad("分类层级不能形成循环");
                    // 当前上级节点的信息，继续沿父链验证层级关系。
                    Map<String, Object> parent = db.required("SELECT parent_id FROM archive_category WHERE id=?", parentId);
                    parentId = parent.get("parent_id") == null ? null : ((Number) parent.get("parent_id")).longValue();
                }
            }
            case "numbering-rules" -> validateNumbering(values, merged);
            default -> throw bad("不支持的目录类型");
        }
    }

    /** 校验传统模板与分段规则，并将旧客户端NEVER归一到内部NONE枚举。 */
    private void validateNumbering(Map<String, Object> values, Map<String, Object> merged) {
        required(values, merged, "rule_code", 50); required(values, merged, "rule_name", 100);
        integerField(values, "priority", -100000, 100000); integerField(values, "enabled", 0, 1); integerField(values, "sequence_length", 1, 18);
        if (values.containsKey("scope_type")) enumField(values, "scope_type", Set.of("global", "dept", "category", "dept_category"));
        if (values.containsKey("reset_cycle")) {
            if ("NEVER".equals(values.get("reset_cycle"))) values.put("reset_cycle", "NONE");
            enumField(values, "reset_cycle", Set.of("YEAR", "MONTH", "NONE"));
        }
        // 请求包含分段时严格检查结构和最大长度，再编码为数据库文本。
        if (values.containsKey("segments")) {
            // 尚未通过结构校验的请求值。
            Object raw = values.get("segments");
            if (raw == null || raw instanceof List<?> list && list.isEmpty()) values.put("segments", null);
            else {
                if (!(raw instanceof List<?> segments) || segments.size() > 30) throw bad("编号分段须为不超过30项的数组");
                // 已检测到的流水号段数量，要求恰好一个。
                int sequences = 0;
                for (Object item : segments) {
                    if (!(item instanceof Map<?, ?> segment)) throw bad("编号分段必须为对象");
                    for (Object key : segment.keySet()) if (!Set.of("type", "value", "ref", "length", "pad").contains(key)) throw bad("编号分段包含不支持字段");
                    // 当前操作或编号分段的受控类型。
                    String type = text(segment.get("type"), "编号分段类型", true, 10);
                    if ("seq".equals(type)) sequences++;
                    else if ("fixed".equals(type)) text(segment.get("value"), "编号固定文本", true, 100);
                    else if ("ref".equals(type)) {
                        if (!Set.of("dept_code", "category_code", "retention_code", "year", "month", "day").contains(text(segment.get("ref"), "编号引用", true, 30))) throw bad("不支持的编号引用字段");
                    } else throw bad("不支持的编号分段类型");
                    if (segment.containsKey("length")) integer(segment.get("length"), 0, 100);
                    if (segment.containsKey("pad") && text(segment.get("pad"), "补位字符", true, 1).length() != 1) throw bad("补位字符必须为一个字符");
                }
                if (sequences != 1) throw bad("编号分段必须且只能包含一个流水号");
                values.put("segments", Json.write(segments));
            }
        }
        merged.putAll(values);
        // 无分段时验证传统模板只能出现生成器支持的占位符。
        if (merged.get("segments") == null) {
            String pattern = text(merged.getOrDefault("pattern", "{DEPT}-{CATEGORY}-{YEAR}-{SEQ4}"), "编号模板", true, 300);
            // 配置的补位宽度，只补齐而不截断真实编号。
            int width = merged.containsKey("sequence_length") ? integer(merged.get("sequence_length"), 1, 18) : 4;
            // 移除合法占位符后的模板，用于检测未知表达式。
            String remainder = pattern.replace("{DEPT}", "").replace("{CATEGORY}", "").replace("{YEAR}", "").replace("{SEQ}", "").replace("{SEQ" + width + "}", "");
            if (remainder.contains("{") || remainder.contains("}") || !(pattern.contains("{SEQ}") || pattern.contains("{SEQ" + width + "}"))) throw bad("编号模板须包含有效流水占位符，且只能使用部门、分类、年度和流水字段");
            if (values.containsKey("pattern")) values.put("pattern", pattern);
        } else if (values.containsKey("pattern")) values.put("pattern", text(values.get("pattern"), "编号模板", false, 300) == null ? "" : values.get("pattern"));
    }

    /** 对外还原分段JSON，并把内部NONE表示为旧客户端认识的NEVER。 */
    private Map<String, Object> output(String kindName, Map<String, Object> row) {
        // 复制数据库行，避免展示转换污染后续内部逻辑。
        Map<String, Object> result = new LinkedHashMap<>(row);
        if ("numbering-rules".equals(kindName)) {
            result.put("segments", row.get("segments") == null ? null : Json.read(row.get("segments").toString(), List.class));
            if ("NONE".equals(row.get("reset_cycle"))) result.put("reset_cycle", "NEVER");
        }
        return result;
    }

    /** 检查可选外键，空值表示清除默认配置。 */
    private void reference(Map<String, Object> values, String key, String table) {
        if (!values.containsKey(key) || values.get(key) == null) return;
        // 表名由固定调用点给出，引用值始终绑定。
        long id = positiveId(values.get(key));
        if (db.one("SELECT id FROM " + table + " WHERE id=?", id) == null) throw bad("关联配置不存在：" + key);
        values.put(key, id);
    }

    /** 对已被其他业务引用的配置返回冲突，不自动级联删除历史。 */
    private void unused(String sql, long id) { if (db.scalarLong(sql, id) > 0) throw new BusinessException(409, "配置已被引用，不能删除"); }
    /** 查找固定资源定义，不存在时不执行任何SQL。 */
    private Kind kind(String name) { Kind result = KINDS.get(name); if (result == null) throw new BusinessException(404, "目录类型不存在"); return result; }
    /** 精确操作授权，archive.admin不能代替dict.manage。 */
    private void require(Actor actor, String permission) { if (!actor.has(permission)) throw new BusinessException(403, "没有操作权限：" + permission); }
    /** 限制公开参数名，防止泄露部署机密。 */
    private void checkParameterKey(String key) { if (!PARAMETER_KEYS.contains(key)) throw bad("不支持的公开系统参数"); }
    /** 拒绝未开放字段，避免误把拼写错误或内部列当作成功更新。 */
    static void checkKeys(Map<String, Object> body, Set<String> allowed) { for (String key : body.keySet()) if (!allowed.contains(key)) throw bad("不支持的字段：" + key); }
    /** 必填文本在创建与部分更新后都必须存在。 */
    private void required(Map<String, Object> values, Map<String, Object> merged, String key, int max) { String value = text(merged.get(key), key, true, max); if (values.containsKey(key)) values.put(key, value); }
    /** 校验可选文本的类型与长度。 */
    private void optionalText(Map<String, Object> values, String key, int max) { if (values.containsKey(key)) values.put(key, text(values.get(key), key, false, max)); }
    /** 校验可选整数列，不允许浮点数被截断。 */
    private void integerField(Map<String, Object> values, String key, int min, int max) { if (values.containsKey(key)) values.put(key, integer(values.get(key), min, max)); }
    /** 校验受控枚举，未知值不能落库。 */
    private void enumField(Map<String, Object> values, String key, Set<String> allowed) { if (!allowed.contains(text(values.get(key), key, true, 30))) throw bad("不支持的配置值：" + key); }
    /** 严格读取字符串，禁止将列表或对象隐式字符串化。 */
    static String text(Object raw, String field, boolean required, int max) {
        if (raw != null && !(raw instanceof String)) throw bad(field + "必须为字符串");
        // 规范化空白，空值只允许用于明确可选字段。
        String value = raw == null ? null : ((String) raw).trim();
        if (value == null || value.isEmpty()) { if (required) throw bad(field + "不能为空"); return null; }
        if (value.length() > max) throw bad(field + "内容过长");
        return value;
    }
    /** 读取有界整数，拒绝字符串、布尔值和小数。 */
    static int integer(Object raw, int min, int max) {
        if (!(raw instanceof Number value) || value.doubleValue() != value.longValue() || value.longValue() < min || value.longValue() > max) throw bad("整数超出允许范围");
        return ((Number) raw).intValue();
    }
    /** 读取正整数对象标识。 */
    static long positiveId(Object raw) { if (!(raw instanceof Number value) || value.longValue() < 1 || value.doubleValue() != value.longValue()) throw bad("标识必须是正整数"); return ((Number) raw).longValue(); }
    /** 统一构造输入校验错误。 */
    static BusinessException bad(String message) { return new BusinessException(400, message); }
    /**
     * 服务端固定的目录元数据。
     * @param table 固定数据库表名
     * @param writePermission 维护操作所需精确权限
     * @param readPermission 查询所需精确权限
     * @param order 固定排序表达式
     * @param fields 客户端可写字段白名单
     */
    private record Kind(String table, String writePermission, String readPermission, String order, Set<String> fields) { }
}
