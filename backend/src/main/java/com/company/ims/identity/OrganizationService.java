package com.company.ims.identity;

import com.company.ims.shared.AuditService;
import com.company.ims.shared.BusinessException;
import com.company.ims.shared.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 本地组织管理适配器；使用明确授权范围，禁止委派调用者不具备的权限。 */
@Service
public class OrganizationService {
    /** 用户列表允许输出的字段，不包含密码哈希。 */
    private static final String USER_COLUMNS = "id,username,real_name,employee_no,phone,email,dept_id,account_status,created_at";
    /** 身份数据查询与变更入口。 */
    private final Db db;
    /** 授权变更后用于撤销旧会话。 */
    private final AuthenticationService authentication;
    /** 用户角色实时权限解析器。 */
    private final LocalIdentityProvider identities;
    /** 组织管理审计服务。 */
    private final AuditService audit;

    /** 注入组织管理所需的本地基础设施。 */
    public OrganizationService(Db db, AuthenticationService authentication, LocalIdentityProvider identities, AuditService audit) {
        this.db = db;
        this.authentication = authentication;
        this.identities = identities;
        this.audit = audit;
    }

    /** 查询最小部门目录；跨部门档案或账号管理员可选全公司，其他用户仅本部门。 */
    public List<Map<String, Object>> departments(Actor actor) {
        // 普通目录不含负责人；具备用户维护权限时返回负责人编号供Web编辑保留原关联。
        String columns = "id,dept_code,dept_name,parent_id,sort_order,status" + (actor.has("user.write") ? ",leader_user_id" : "");
        return global(actor) || actor.has("archive.manage.all") ? db.list("SELECT " + columns + " FROM ims_department WHERE deleted_at IS NULL ORDER BY sort_order,id")
                : db.list("SELECT " + columns + " FROM ims_department WHERE id=? AND deleted_at IS NULL", actor.deptId());
    }

    /** 创建平台部门；组织树变更须具有明确跨部门管理能力。 */
    @Transactional
    public Map<String, Object> createDepartment(Actor actor, Map<String, Object> body) {
        requireGlobal(actor);
        // 即将写入数据库的白名单字段，不直接透传请求体。
        Map<String, Object> values = departmentValues(body, true);
        // 本次创建记录的数据库主键，供关联写入及审计引用。
        long id = db.insert("ims_department", values);
        audit.record(actor.id(), "user", "create_department", "department", id, "新增部门");
        return db.required("SELECT * FROM ims_department WHERE id=?", id);
    }

    /** 更新部门属性；检查组织层级循环和负责人有效性。 */
    @Transactional
    public void updateDepartment(Actor actor, long id, Map<String, Object> body) {
        requireGlobal(actor);
        db.required("SELECT id FROM ims_department WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
        // 即将写入数据库的白名单字段，不直接透传请求体。
        Map<String, Object> values = departmentValues(body, false);
        if (values.get("parent_id") != null) {
            // 沿上级链逐个检查，防止自身或祖先被挂到后代形成循环。
            Long parentId = longValue(values.get("parent_id"), "上级部门");
            // 已经检查过的部门标识，用于发现组织树循环。
            Set<Long> visited = new LinkedHashSet<>();
            visited.add(id);
            while (parentId != null) {
                if (!visited.add(parentId)) throw new BusinessException(400, "部门层级不能形成循环");
                // 当前上级节点的关联信息，用于继续向上检查。
                Map<String, Object> parent = db.required("SELECT parent_id FROM ims_department WHERE id=? AND deleted_at IS NULL", parentId);
                parentId = parent.get("parent_id") == null ? null : ((Number) parent.get("parent_id")).longValue();
            }
        }
        values.put("updated_at", OffsetDateTime.now());
        db.update("ims_department", id, values);
        audit.record(actor.id(), "user", "update_department", "department", id, "更新部门");
    }

    /** 分页查询范围内账号，不返回密码哈希与会话字段。 */
    public Map<String, Object> users(Actor actor, int page, int pageSize) {
        if (page < 1 || page > 100000 || pageSize < 1 || pageSize > 200) throw new BusinessException(400, "分页参数超出允许范围");
        // 根据操作者权限选择的服务端范围条件，不含客户端拼接内容。
        String predicate = global(actor) ? "deleted_at IS NULL" : "deleted_at IS NULL AND dept_id=?";
        // 按SQL占位符顺序组织的参数，避免SQL注入。
        List<Object> args = new ArrayList<>();
        if (!global(actor)) args.add(actor.deptId());
        // 与当前数据范围一致的记录总数，避免跨部门计数泄露。
        long total = db.scalarLong("SELECT COUNT(*) FROM ims_user WHERE " + predicate, args.toArray());
        args.add(pageSize);
        args.add((page - 1) * pageSize);
        // 当前页内通过范围过滤且排除敏感字段的账号列表。
        List<Map<String, Object>> items = db.list("SELECT " + USER_COLUMNS + " FROM ims_user WHERE " + predicate + " ORDER BY id LIMIT ? OFFSET ?", args.toArray());
        return Map.of("total", total, "items", items);
    }

    /** 读取范围内用户详情及当前角色编号；不返回密码哈希或会话令牌。 */
    public Map<String, Object> user(Actor actor, long id) {
        // 详情与列表使用完全相同的部门边界，避免通过猜测主键跨部门读取。
        Map<String, Object> result = global(actor)
                ? db.required("SELECT " + USER_COLUMNS + " FROM ims_user WHERE id=? AND deleted_at IS NULL", id)
                : db.required("SELECT " + USER_COLUMNS + " FROM ims_user WHERE id=? AND dept_id=? AND deleted_at IS NULL", id, actor.deptId());
        result.put("role_ids", db.list("SELECT role_id FROM ims_user_role WHERE user_id=? ORDER BY role_id", id).stream().map(row -> row.get("role_id")).toList());
        return result;
    }

    /** 新建用户时要求显式密码，角色权限必须是操作者现有权限的子集。 */
    @Transactional
    public Map<String, Object> createUser(Actor actor, Map<String, Object> body) {
        // 经过格式和长度检查的登录账号。
        String username = text(body, "username", true, 64);
        if (!username.matches("[A-Za-z0-9_.@-]+")) throw new BusinessException(400, "账号仅允许英文字母、数字、下划线、点、@和短横线");
        // 目标所属部门，必须通过范围与存在性检查。
        long departmentId = longValue(body.get("dept_id"), "所属部门");
        requireDepartment(actor, departmentId);
        // 本次期望授予的去重角色列表，写入前验证权限上限。
        List<Long> roleIds = roleIds(body.getOrDefault("role_ids", List.of()));
        checkRoles(actor, roleIds);
        // 即将写入数据库的白名单字段，不直接透传请求体。
        Map<String, Object> values = profile(body);
        values.put("username", username);
        values.put("real_name", text(body, "real_name", true, 100));
        values.put("dept_id", departmentId);
        values.put("password_hash", IdentityPasswords.encode(text(body, "password", true, 128)));
        values.put("account_status", "active");
        // 本次创建记录的数据库主键，供关联写入及审计引用。
        long id = db.insert("ims_user", values);
        assignRoles(id, roleIds);
        audit.record(actor.id(), "user", "create", "user", id, "新增用户，账号=" + username);
        return db.required("SELECT " + USER_COLUMNS + " FROM ims_user WHERE id=?", id);
    }

    /** 更新用户资料和角色，禁止通过修改高权限账号或部门归属间接提权。 */
    @Transactional
    public void updateUser(Actor actor, long id, Map<String, Object> body) {
        lockAdministratorChanges();
        // 从数据库读取或创建的目标账号记录，使用前必须校验权限。
        Map<String, Object> user = manageableUser(actor, id);
        // 即将写入数据库的白名单字段，不直接透传请求体。
        Map<String, Object> values = profile(body);
        if (body.containsKey("real_name")) values.put("real_name", text(body, "real_name", true, 100));
        if (body.containsKey("dept_id")) {
            // 目标所属部门，必须通过范围与存在性检查。
            long departmentId = longValue(body.get("dept_id"), "所属部门");
            requireDepartment(actor, departmentId);
            values.put("dept_id", departmentId);
        }
        if (body.containsKey("account_status")) {
            // 经过白名单校验的账号或角色启用状态。
            String status = text(body, "account_status", true, 20);
            if (!Set.of("active", "pending", "rejected", "disabled", "locked").contains(status)) throw new BusinessException(400, "不支持的账号状态");
            if (actor.id() == id && !"active".equals(status)) throw new BusinessException(400, "不能停用当前登录账号");
            values.put("account_status", status);
        }
        if (body.containsKey("password")) throw new BusinessException(400, "请通过修改密码接口更改密码");
        if (body.containsKey("role_ids")) {
            // 本次期望授予的去重角色列表，写入前验证权限上限。
            List<Long> roleIds = roleIds(body.get("role_ids"));
            checkRoles(actor, roleIds);
            db.execute("DELETE FROM ims_user_role WHERE user_id=?", id);
            assignRoles(id, roleIds);
        }
        values.put("updated_at", OffsetDateTime.now());
        db.update("ims_user", id, values);
        requireRemainingAdministrator();
        authentication.revokeAll(id);
        audit.record(actor.id(), "user", "update", "user", id, "更新用户资料或授权，原会话已撤销；账号=" + user.get("username"));
    }

    /** 禁用账号并即时撤销全部会话，不能停用自己或最后一个管理员。 */
    @Transactional
    public void disableUser(Actor actor, long id) {
        lockAdministratorChanges();
        manageableUser(actor, id);
        if (actor.id() == id) throw new BusinessException(400, "不能停用当前登录账号");
        db.update("ims_user", id, Map.of("account_status", "disabled", "updated_at", OffsetDateTime.now()));
        requireRemainingAdministrator();
        authentication.revokeAll(id);
        audit.record(actor.id(), "user", "disable", "user", id, "已停用账号并撤销全部会话");
    }

    /** 返回可供角色分配界面显示的角色信息，权限不因查看而授予。 */
    public List<Map<String, Object>> roles() { return db.list("SELECT id,role_code,role_name,role_level,status FROM ims_role ORDER BY role_level,id"); }

    /** 返回带中文作用说明的权限清单，供授权界面展示。 */
    public List<Map<String, Object>> permissions() { return db.list("SELECT id,perm_code,perm_name,module,description FROM ims_permission ORDER BY id"); }

    /** 新建角色只允许精确的已有权限编码，不静默丢弃未知权限。 */
    @Transactional
    public Map<String, Object> createRole(Actor actor, Map<String, Object> body) {
        // 供程序引用的稳定角色编码，与展示名称分离。
        String code = text(body, "role_code", true, 50);
        if (!code.matches("[a-z][a-z0-9_]*") || "super_admin".equals(code)) throw new BusinessException(400, "角色编码不合法或为系统保留编码");
        // 请求中的权限编码集合，先校验类型再逐项授权。
        Object rawCodes = body.getOrDefault("permission_codes", List.of());
        if (!(rawCodes instanceof List<?> codes)) throw new BusinessException(400, "权限列表格式错误");
        // 已确认存在且允许授予的权限主键集合。
        Set<Long> permissionIds = new LinkedHashSet<>();
        for (Object value : codes) {
            if (!(value instanceof String permission)) throw new BusinessException(400, "权限编码必须为字符串");
            if (!actor.has(permission)) throw new BusinessException(403, "不能授予自身没有的权限：" + permission);
            // 精确权限编码对应的数据库记录，不存在时必须拒绝。
            Map<String, Object> row = db.one("SELECT id FROM ims_permission WHERE perm_code=?", permission);
            if (row == null) throw new BusinessException(400, "未知权限：" + permission);
            permissionIds.add(((Number) row.get("id")).longValue());
        }
        // 审批读取的角色级别，不作为自动扩大权限的依据。
        long level = body.containsKey("role_level") ? longValue(body.get("role_level"), "角色级别") : 5;
        if (level > 100) throw new BusinessException(400, "角色级别应在1至100之间");
        // 经过白名单校验的账号或角色启用状态。
        int status = enabled(body.getOrDefault("status", 1));
        // 本次创建记录的数据库主键，供关联写入及审计引用。
        long id = db.insert("ims_role", Map.of("role_code", code, "role_name", text(body, "role_name", true, 100), "role_level", level, "status", status));
        for (long permissionId : permissionIds) db.insert("ims_role_permission", Map.of("role_id", id, "permission_id", permissionId));
        audit.record(actor.id(), "user", "create_role", "role", id, "创建角色，编码=" + code);
        return db.required("SELECT id,role_code,role_name,role_level,status FROM ims_role WHERE id=?", id);
    }

    /** 只有全局通配或显式跨部门用户管理权限能够跨组织操作。 */
    private boolean global(Actor actor) { return actor.has("user.manage.all"); }

    /** 阻止仅有本部门用户权限的操作者调整整棵组织树。 */
    private void requireGlobal(Actor actor) { if (!global(actor)) throw new BusinessException(403, "需要跨部门用户管理权限"); }

    /** 验证目标部门启用且属于当前管理范围。 */
    private void requireDepartment(Actor actor, long departmentId) {
        if (!global(actor) && actor.deptId() != departmentId) throw new BusinessException(403, "不能管理其他部门用户");
        if (db.one("SELECT id FROM ims_department WHERE id=? AND status=1 AND deleted_at IS NULL", departmentId) == null)
            throw new BusinessException(400, "所属部门不存在或未启用");
    }

    /** 锁定并校验被管理账号，禁止修改超过操作者权限上限的账号。 */
    private Map<String, Object> manageableUser(Actor actor, long id) {
        // 从数据库读取或创建的目标账号记录，使用前必须校验权限。
        Map<String, Object> user = db.required("SELECT * FROM ims_user WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
        if (!global(actor) && actor.deptId() != ((Number) user.get("dept_id")).longValue()) throw new BusinessException(403, "不能管理其他部门用户");
        // 目标账号当前有效授权，用于比较操作者权限上限。
        Actor target = identities.actorFor(user);
        for (String permission : target.permissions()) if (!actor.has(permission)) throw new BusinessException(403, "不能修改权限高于自己的账号");
        return user;
    }

    /** 校验所有角色存在且启用，并禁止分配自身不具备的精确权限。 */
    private void checkRoles(Actor actor, List<Long> roleIds) {
        for (long id : roleIds) {
            // 被分配角色的启用记录，停用角色不能继续授予。
            Map<String, Object> role = db.one("SELECT role_code FROM ims_role WHERE id=? AND status=1", id);
            if (role == null) throw new BusinessException(400, "角色不存在或已停用");
            if ("super_admin".equals(role.get("role_code")) && !actor.has("*")) throw new BusinessException(403, "只有系统管理员可以授予系统管理员角色");
            for (Map<String, Object> row : db.list("SELECT p.perm_code FROM ims_permission p JOIN ims_role_permission rp ON rp.permission_id=p.id WHERE rp.role_id=?", id)) {
                // 本次检查的精确权限编码，禁止按名称前缀扩大授权。
                String permission = (String) row.get("perm_code");
                if (!actor.has(permission)) throw new BusinessException(403, "不能授予自身没有的权限：" + permission);
            }
        }
    }

    /** 写入已通过授权检查的用户角色关系，不建立隐含的全公司数据范围。 */
    private void assignRoles(long userId, List<Long> roleIds) {
        for (long roleId : roleIds) db.insert("ims_user_role", Map.of("user_id", userId, "role_id", roleId));
    }

    /** 序列化可能减少管理员数量的操作，避免并发操作分别通过计数检查。 */
    private void lockAdministratorChanges() { db.required("SELECT id FROM ims_role WHERE role_code='super_admin' FOR UPDATE"); }

    /** 保证至少存在一个启用的通配权限账号，否则抛错让事务整体回滚。 */
    private void requireRemainingAdministrator() {
        if (db.scalarLong("""
                SELECT COUNT(DISTINCT u.id) FROM ims_user u JOIN ims_user_role ur ON ur.user_id=u.id
                JOIN ims_role r ON r.id=ur.role_id JOIN ims_role_permission rp ON rp.role_id=r.id
                JOIN ims_permission p ON p.id=rp.permission_id WHERE u.account_status='active'
                AND u.deleted_at IS NULL AND r.status=1 AND p.perm_code='*'
                """) == 0) throw new BusinessException(400, "必须保留至少一个启用的系统管理员");
    }

    /** 仅抽取允许编辑的联系信息；空字符串转为空值以保持可选工号唯一约束正常。 */
    private Map<String, Object> profile(Map<String, Object> body) {
        // 即将写入数据库的白名单字段，不直接透传请求体。
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : List.of("employee_no", "phone", "email")) {
            if (body.containsKey(key)) values.put(key, text(body, key, false, "email".equals(key) ? 120 : "phone".equals(key) ? 30 : 50));
        }
        return values;
    }

    /** 构造部门白名单字段并验证被引用的上级和负责人。 */
    private Map<String, Object> departmentValues(Map<String, Object> body, boolean creating) {
        // 即将写入数据库的白名单字段，不直接透传请求体。
        Map<String, Object> values = new LinkedHashMap<>();
        if (creating) values.put("dept_code", text(body, "dept_code", true, 50));
        if (creating || body.containsKey("dept_name")) values.put("dept_name", text(body, "dept_name", true, 100));
        if (body.containsKey("status")) values.put("status", enabled(body.get("status")));
        if (body.containsKey("sort_order")) {
            if (!(body.get("sort_order") instanceof Number value)) throw new BusinessException(400, "排序值必须是整数");
            if (value.doubleValue() != value.intValue()) throw new BusinessException(400, "排序值必须是整数");
            values.put("sort_order", value.intValue());
        }
        for (String key : List.of("parent_id", "leader_user_id")) if (body.containsKey(key)) {
            // 经过类型检查的字段内容，只有符合约束才进入持久化。
            Long value = body.get(key) == null ? null : longValue(body.get(key), key);
            if (value != null) {
                // 仅由服务端固定分支选择的关联表名，不接受请求表名。
                String table = "parent_id".equals(key) ? "ims_department" : "ims_user";
                if (db.one("SELECT id FROM " + table + " WHERE id=? AND deleted_at IS NULL", value) == null) throw new BusinessException(400, "关联的部门或负责人不存在");
            }
            values.put(key, value);
        }
        return values;
    }

    /** 读取字符串白名单字段；密码原样保留，其他字段清理首尾空格。 */
    private String text(Map<String, Object> body, String key, boolean required, int maxLength) {
        // 请求字段的原始值，必须完成类型验证后再使用。
        Object raw = body.get(key);
        if (raw != null && !(raw instanceof String)) throw new BusinessException(400, key + "必须是字符串");
        // 经过类型检查的字段内容，只有符合约束才进入持久化。
        String value = (String) raw;
        if (value != null && !"password".equals(key)) value = value.trim();
        if (value == null || value.isBlank()) {
            if (required) throw new BusinessException(400, "缺少必填字段：" + key);
            return null;
        }
        if (value.length() > maxLength) throw new BusinessException(400, key + "内容过长");
        return value;
    }

    /** 解析正整数标识；小数、负值和隐式字符串转换均不接受。 */
    private long longValue(Object raw, String field) {
        if (!(raw instanceof Number number) || number.longValue() < 1 || number.doubleValue() != number.longValue()) throw new BusinessException(400, field + "必须是正整数");
        return number.longValue();
    }

    /** 提取并去重角色标识，避免生成重复用户角色记录。 */
    private List<Long> roleIds(Object raw) {
        if (!(raw instanceof List<?> values)) throw new BusinessException(400, "角色列表格式错误");
        // 去重后的有效角色主键，避免重复授权记录。
        Set<Long> ids = new LinkedHashSet<>();
        for (Object value : values) ids.add(longValue(value, "角色"));
        return List.copyOf(ids);
    }

    /** 启用状态只接受0和1，避免任意数值进入组织授权判断。 */
    private int enabled(Object raw) {
        if (!(raw instanceof Number value) || (value.doubleValue() != 0 && value.doubleValue() != 1)) throw new BusinessException(400, "启用状态必须为0或1");
        return value.intValue();
    }
}
