package com.company.platform;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** 组织、员工与分模块角色领域服务；跨对象管理写入由 Commands 保证事务和锁顺序。 */
@Service
public class DirectoryService {
    /** 对外部门和权限字段白名单。 */
    public static final String DEPARTMENT_FIELDS = "id,code,name,parent_id,status,sort_order,row_version";
    /** 名片资料仅通过人员管理投影返回。 */
    private static final String DIRECTORY_USER_FIELDS = IdentityService.USER_FIELDS
            + ",organization_name,job_title,supervisor_id,mobile_phone,contact_email";
    /** 人员新建或导入时未指定组织的默认名片名称。 */
    private static final String DEFAULT_EMPLOYEE_ORGANIZATION = "百林科（兰州）新材料有限公司";
    public static final String PERMISSION_FIELDS = "id,module,code,name,resource_type,action,sensitive_flag,assignable_scope_type";
    /** 数据、身份失效和受控临时凭据投递接口。 */
    private final Sql db;
    private final IdentityService identity;
    private final CredentialDelivery delivery;
    /** 角色变更按实际业务模块留痕，与业务在同一事务提交。 */
    private final Audit audit;
    /** 人员编辑按需调用现有交接服务，避免其人员查询依赖形成构造环。 */
    private final org.springframework.beans.factory.ObjectProvider<com.company.platform.workflow.PersonnelContinuity> continuity;

    /** 注入组织写入依赖；凭据投递和身份失效均由独立服务负责。 */
    public DirectoryService(Sql db, IdentityService identity, CredentialDelivery delivery, Audit audit, org.springframework.beans.factory.ObjectProvider<com.company.platform.workflow.PersonnelContinuity> continuity) {
        this.db = db;
        this.identity = identity;
        this.delivery = delivery;
        this.audit = audit;
        this.continuity = continuity;
    }

    /** 员工列表只允许 IT 完整视图；模块管理员仅获取对应授权用途的最小选择对象。 */
    public Map<String, Object> users(Actor actor, Map<String, String> query) {
        Inputs.only(query, "keyword", "departmentId", "status", "purpose", "projection", "page", "pageSize", "sort","email");
        boolean full = !"SELECTOR".equals(query.get("projection"));
        if (full)
            actor.require("PLATFORM.USERS_MANAGE");
        else
            selector(actor, query.get("purpose"));
        StringBuilder where = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        keyword(query, where, args, "name", "employee_no");
        if (query.containsKey("departmentId")) {
            where.append(" AND department_id=?");
            args.add(Inputs.uuid(query.get("departmentId")));
        }
        if (query.containsKey("status")) {
            where.append(" AND status=?");
            args.add(Inputs.choice(query.get("status"), "ACTIVE", "DISABLED"));
        }
        if (full) where.append(new ListConditions(query,args).contains("email","company_email").sql());
        if (!full)
            where.append(" AND status='ACTIVE'");
        return page("iam.user_account", where.toString(), args, query, "id",
                row -> full ? Sql.map("representation", "FULL", "user", Sql.view(row, DIRECTORY_USER_FIELDS))
                        : Sql.map("representation", "SELECTOR", "selector",
                                Sql.view(row, "id,employee_no,name,department_id")));
    }

    /** 部门树由有界分页结果构建；父级关系与详情不额外泄露人员信息。 */
    public Map<String, Object> departments(Actor actor, Map<String, String> query) {
        Inputs.only(query, "parentId", "keyword", "purpose", "projection", "page", "pageSize", "sort","status");
        boolean full = !"SELECTOR".equals(query.get("projection"));
        if (full)
            actor.require("PLATFORM.DEPARTMENTS_MANAGE");
        else if ("DRIVE_SHARED_FILTER".equals(query.get("purpose")))
            actor.require("DRIVE.ACCESS");
        else
            selector(actor, query.get("purpose"));
        StringBuilder where = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        keyword(query, where, args, "name", "code");
        if (query.containsKey("parentId")) {
            where.append(" AND parent_id=?");
            args.add(Inputs.uuid(query.get("parentId")));
        }
        where.append(new ListConditions(query,args).choice("status","status","ACTIVE","DISABLED").sql());
        if (!full)
            where.append(" AND status='ACTIVE'");
        var result = page("iam.department", where.toString(), args, query, "id",
                row -> full ? Sql.map("representation", "FULL", "department", Sql.view(row, DEPARTMENT_FIELDS))
                        : Sql.map("representation", "SELECTOR", "selector", Sql.view(row, "id,code,name,parent_id")));
        result.put("companyCode", CompanyIdentity.CODE);
        return result;
    }

    /** 人员选择器限对应的管理动作；公盘资料筛选只使用部门选择器。 */
    private void selector(Actor actor, String purpose) {
        switch (Objects.toString(purpose, "")) {
            case "ACCOUNT_ADMIN" -> actor.require("PLATFORM.USERS_MANAGE");
            case "DRIVE_GRANT" -> actor.require("DRIVE.ROLES_MANAGE");
            case "ARCHIVE_GRANT" -> actor.require("ARCHIVE.ROLES_MANAGE");
            default -> throw ApiFault.denied();
        }
    }

    /** 管理中心的角色配置由平台 IT 维护，模块内的授权继续使用各自的业务权限。 */
    private boolean canManageRoles(Actor actor) {
        return actor.systemAdministrator() && actor.actions().contains("PLATFORM.ROLES_MANAGE");
    }

    /** 只返回平台 IT 获准配置角色的模块，保留档案初始化例外。 */
    public List<String> managedModules(Actor actor) {
        if (!canManageRoles(actor))
            return List.of();
        List<String> modules = new ArrayList<>();
        for (String module : List.of("PLATFORM", "DRIVE", "ARCHIVE"))
            if (actor.actions().contains(module + ".ROLES_MANAGE"))
                modules.add(module);
        if (!modules.contains("ARCHIVE") && firstArchiveAdmin())
            modules.add("ARCHIVE");
        return List.copyOf(modules);
    }

    /** 角色查询仍限制模块；未授权模块不返回角色配置。 */
    public Map<String, Object> roles(Actor actor, Map<String, String> query) {
        Inputs.only(query, "module", "page", "pageSize", "sort");
        String module = Inputs.choice(query.get("module"), "PLATFORM", "DRIVE", "ARCHIVE");
        checkModule(actor, module, true);
        return page("iam.role", " WHERE module=?", List.of(module), query, "id",
                row -> Sql.view(row, IdentityService.ROLE_FIELDS));
    }

    /** 查询角色详情时单独核验模块，防止通过 ID 跨模块读取配置。 */
    public Map<String, Object> role(Actor actor, UUID id) {
        var row = db.required("SELECT * FROM iam.role WHERE id=?", id);
        checkModule(actor, (String) row.get("module"), true);
        var permissions = "PLATFORM".equals(row.get("module")) && "IT_ADMIN".equals(row.get("code"))
                ? db.rows("SELECT p.*,'COMPANY' AS scope_policy,'ALLOW' AS effect FROM iam.permission p WHERE iam.it_management_action(p.code) ORDER BY p.code")
                : db.rows(
                "SELECT p.*,rp.scope_policy,rp.effect FROM iam.permission p JOIN iam.role_permission rp ON rp.permission_id=p.id WHERE rp.role_id=? ORDER BY p.code",
                id);
        // V1.1 的 permissions/scopePolicies 没有 effect 关联，附加 assignments
        // 明确表达允许/拒绝，避免前端误把拒绝当允许。
        return Sql.map("role", Sql.view(row, IdentityService.ROLE_FIELDS), "permissions",
                permissions.stream().map(p -> Sql.view(p, PERMISSION_FIELDS)).toList(), "scopePolicies",
                permissions.stream().map(p -> Sql.map("code", p.get("scope_policy"))).toList(),
                "assignments", permissions.stream().map(p -> Sql.map("permissionId", p.get("id"), "scopePolicy",
                        Sql.map("code", p.get("scope_policy")), "effect", p.get("effect"))).toList());
    }

    /** 权限字典只返回获准模块，不能动态创建星号管理权限。 */
    public Map<String, Object> permissions(Actor actor, Map<String, String> query) {
        Inputs.only(query, "module", "page", "pageSize", "sort");
        String module = Inputs.choice(query.get("module"), "PLATFORM", "DRIVE", "ARCHIVE");
        checkModule(actor, module, false);
        return page("iam.permission", " WHERE module=?", List.of(module), query, "name,id",
                row -> Sql.view(row, PERMISSION_FIELDS));
    }

    /** 显式账号和部门详情投影；调用入口必须已经核验管理权限。 */
    public Map<String, Object> user(UUID id) {
        return Sql.view(db.required("SELECT * FROM iam.user_account WHERE id=?", id), DIRECTORY_USER_FIELDS);
    }

    /** 返回部门白名单资料；不存在时统一返回不可见错误。 */
    public Map<String, Object> department(UUID id) {
        return Sql.view(db.required("SELECT * FROM iam.department WHERE id=?", id), DEPARTMENT_FIELDS);
    }

    /** 返回获准管理模块内的任职，不泄露其他模块配置。 */
    public Map<String, Object> userRoles(Actor actor, UUID id) {
        List<String> modules = managedModules(actor);
        if (modules.isEmpty())
            throw ApiFault.denied();
        var user = db.required("SELECT auth_version FROM iam.user_account WHERE id=?", id);
        var roles = db.rows(
                "SELECT r.* FROM iam.user_role ur JOIN iam.role r ON r.id=ur.role_id WHERE ur.user_id=? AND ur.state='ACTIVE' ORDER BY r.module,r.code",
                id).stream().filter(r -> modules.contains(r.get("module")))
                .map(r -> Sql.view(r, IdentityService.ROLE_FIELDS)).toList();
        return Sql.map("userId", id, "roles", roles, "authVersion", user.get("auth_version"));
    }

    /** 初始密码使用工号，24小时内完成强制改密后才可进入业务。 */
    public Map<String, Object> createUser(Commands.Context ctx, Map<String, Object> body) {
        return createUser(body, true);
    }

    /** 已确认的员工批次允许空白名片字段，沿用账号创建和初始凭据流程。 */
    public Map<String, Object> createImportedUser(Map<String, Object> body) {
        return createUser(body, false);
    }

    private Map<String, Object> createUser(Map<String, Object> body, boolean requireProfile) {
        Inputs.only(body, "employeeNo", "name", "departmentId", "companyEmail", "organizationName", "jobTitle", "supervisorId", "mobilePhone", "contactEmail");
        UUID department = Inputs.uuid(body.get("departmentId"));
        activeDepartment(department);
        String employee = Inputs.text(body, "employeeNo", 64), name = Inputs.text(body, "name", 128),
                email = Inputs.email(body), temporary = employee;
        UUID id = UUID.randomUUID();
        var details = employeeDetails(id, body, Map.of());
        if (requireProfile && details.title() == null) throw ApiFault.invalid("请填写职位");
        if (requireProfile && details.supervisor() == null) throw ApiFault.invalid("请选择直属主管");
        if (requireProfile && details.phone() == null) throw ApiFault.invalid("请填写手机号");
        db.update("""
                INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,
                    organization_name,job_title,supervisor_id,mobile_phone,contact_email)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, id, employee, name, department, email, Crypto.password(temporary),
                Objects.requireNonNullElse(details.organization(), DEFAULT_EMPLOYEE_ORGANIZATION),
                details.title(), details.supervisor(), details.phone(), details.contactEmail());
        delivery.issue(id, employee, email);
        return user(id);
    }

    /** IT 可编辑全部人员资料；工号保持唯一，部门调整复用真实授权回收和待办处理。 */
    public Map<String, Object> updateUser(Commands.Context ctx, UUID id, Map<String, Object> body) {
        Inputs.only(body, "name", "companyEmail", "employeeNo", "departmentId", "changeReason", "organizationName", "jobTitle", "supervisorId", "mobilePhone", "contactEmail");
        ctx.actor().require("PLATFORM.USERS_MANAGE");
        if (body.isEmpty()) throw ApiFault.invalid("请提供要修改的资料");
        if ((body.containsKey("employeeNo") || body.containsKey("departmentId")) && !ctx.actor().systemAdministrator())
            throw ApiFault.denied();
        var row = db.required("SELECT * FROM iam.user_account WHERE id=? FOR UPDATE", id);
        Commands.version(ctx, row);
        String name = body.containsKey("name") ? Inputs.text(body, "name", 128) : (String) row.get("name");
        String email = body.containsKey("companyEmail") ? Inputs.email(body) : (String) row.get("company_email");
        String employee = body.containsKey("employeeNo") ? Inputs.text(body, "employeeNo", 64) : (String) row.get("employee_no");
        UUID department = body.containsKey("departmentId") ? Inputs.uuid(body.get("departmentId")) : (UUID) row.get("department_id");
        var details = employeeDetails(id, body, row);
        boolean moved = !department.equals(row.get("department_id")), renumbered = !employee.equals(row.get("employee_no"));
        if (db.count("SELECT count(*) FROM iam.user_account WHERE employee_no=? AND id<>?",employee,id)>0)
            throw ApiFault.conflict("公司工号已被其他人员使用");
        if (db.count("SELECT count(*) FROM iam.user_account WHERE company_email=? AND id<>?",email,id)>0)
            throw ApiFault.conflict("公司邮箱已被其他人员使用");
        if (moved) continuity.getObject().changeDepartmentForProfile(ctx,row,department,Inputs.text(body,"changeReason",2000));
        // 转部门已递增资料版本；同次保存其他字段不再重复加一，工号变更不重建账号或历史引用。
        db.update("""
                UPDATE iam.user_account SET employee_no=?,name=?,company_email=?,organization_name=?,job_title=?,
                    supervisor_id=?,mobile_phone=?,contact_email=?,row_version=row_version+?,updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, employee, name, email, details.organization(), details.title(), details.supervisor(),
                details.phone(), details.contactEmail(), moved?0:1, id);
        if (renumbered || !email.equals(row.get("company_email")))
            db.update("UPDATE iam.password_recovery SET closed_at=CURRENT_TIMESTAMP,code_hash=NULL WHERE user_id=? AND closed_at IS NULL", id);
        // 尚未完成首次设密时，工号更正同步刷新初始密码，避免新工号仍对应旧工号密码。
        if (renumbered && Boolean.TRUE.equals(row.get("password_setup_required"))) {
            db.update("UPDATE iam.user_account SET password_hash=? WHERE id=?",Crypto.password(employee),id);
            delivery.issue(id,employee,email);
        }
        // 工号用于登录与水印，不能让旧会话继续使用旧标识；部门变化也必须立即停止原授权交付。
        if (renumbered || moved) identity.invalidate(id,"USER_PROFILE_CHANGE",ctx.operation());
        return user(id);
    }

    /** 名片补充资料允许留空；编辑时省略字段保留原值，传 null 清空。 */
    private record EmployeeDetails(String organization, String title, UUID supervisor, String phone, String contactEmail) {}

    private EmployeeDetails employeeDetails(UUID id, Map<String, Object> body, Map<String, Object> current) {
        UUID supervisor = body.containsKey("supervisorId") ? Inputs.nullableUuid(body.get("supervisorId"))
                : (UUID) current.get("supervisor_id");
        if (supervisor != null && !supervisor.equals(current.get("supervisor_id"))) {
            if (id.equals(supervisor) || db.count("SELECT count(*) FROM iam.user_account WHERE id=? AND status='ACTIVE'", supervisor) == 0)
                throw ApiFault.invalid("请选择其他在职员工担任直属主管");
        }
        String mobile = profileText(body, current, "mobilePhone", "mobile_phone", 32);
        if (mobile != null) validateEmployeeMobile(mobile);
        String contact = profileText(body, current, "contactEmail", "contact_email", 254);
        if (contact != null) validateEmployeeContactEmail(contact);
        return new EmployeeDetails(profileText(body, current, "organizationName", "organization_name", 200),
                profileText(body, current, "jobTitle", "job_title", 128), supervisor, mobile, contact);
    }

    /** 人员表单与 Excel 预检共用手机号校验，空值由入口处理。 */
    public static void validateEmployeeMobile(String mobile) {
        if (!mobile.matches("\\+?[0-9][0-9 ()-]{4,30}[0-9]"))
            throw ApiFault.invalid("请填写有效的手机号，可包含国际区号");
    }

    /** 联系邮箱可用个人邮箱，与公司邮箱的域名校验独立。 */
    public static void validateEmployeeContactEmail(String contact) {
        if (!contact.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
            throw ApiFault.invalid("请填写有效的联系邮箱");
    }

    /** 可选名片文字沿用入口长度校验。 */
    private String profileText(Map<String, Object> body, Map<String, Object> current, String key, String column, int max) {
        if (!body.containsKey(key)) return (String) current.get(column);
        return body.get(key) == null ? null : Inputs.text(body, key, max);
    }

    /** 账号停用结束所有任职；重新启用不自动恢复旧权限。本人不能停用自己。 */
    public Map<String, Object> status(Commands.Context ctx, UUID id, Map<String, Object> body) {
        Inputs.only(body, "status", "reason", "approvalRef");
        Inputs.text(body, "reason", 1000);
        if (id.equals(ctx.actor().userId()))
            throw ApiFault.denied();
        String state = Inputs.choice(body.get("status"), "ACTIVE", "DISABLED");
        var row = db.required("SELECT * FROM iam.user_account WHERE id=? FOR UPDATE", id);
        Commands.version(ctx, row);
        if (state.equals("DISABLED"))
            db.update(
                    "UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP WHERE user_id=? AND state='ACTIVE'",
                    id);
        db.update("UPDATE iam.permission_assignment SET state='REVOKED' WHERE user_id=? AND state='ACTIVE'", id);
        db.update(
                "UPDATE iam.user_account SET status=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                state, id);
        identity.invalidate(id, "ACCOUNT_" + state, ctx.operation());
        return Commands.result(ctx, id, ((Number) row.get("row_version")).intValue() + 1);
    }

    /** 创建部门，不能创建隐含业务角色或内容权限。 */
    public Map<String, Object> createDepartment(Map<String, Object> body) {
        Inputs.only(body, "code", "name", "parentId", "sortOrder");
        UUID parent = Inputs.nullableUuid(body.get("parentId"));
        if (parent != null)
            activeDepartment(parent);
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO iam.department(id,code,name,parent_id,sort_order) VALUES(?,?,?,?,?)", id,
                Inputs.text(body, "code", 64), Inputs.text(body, "name", 128), parent,
                Inputs.integer(body.get("sortOrder"), 0, 0, 100000));
        return department(id);
    }

    /** 修改部门层级采用递归祖先核验；有效人员或下级存在时拒绝停用。 */
    public Map<String, Object> updateDepartment(Commands.Context ctx, UUID id, Map<String, Object> body) {
        Inputs.only(body, "code", "name", "parentId", "status", "sortOrder");
        if (body.isEmpty())
            throw ApiFault.invalid("请提供要修改的部门资料");
        var row = db.required("SELECT * FROM iam.department WHERE id=? FOR UPDATE", id);
        Commands.version(ctx, row);
        UUID parent = body.containsKey("parentId") ? Inputs.nullableUuid(body.get("parentId"))
                : (UUID) row.get("parent_id");
        if (parent != null) {
            activeDepartment(parent);
            if (db.count(
                    "WITH RECURSIVE ancestors AS (SELECT id,parent_id FROM iam.department WHERE id=? UNION SELECT d.id,d.parent_id FROM iam.department d JOIN ancestors a ON d.id=a.parent_id) SELECT count(*) FROM ancestors WHERE id=?",
                    parent, id) > 0)
                throw ApiFault.conflict("不能将部门放到自己或自己的下级下面");
        }
        String state = body.containsKey("status") ? Inputs.choice(body.get("status"), "ACTIVE", "DISABLED")
                : (String) row.get("status");
        if (state.equals("DISABLED") && (db
                .count("SELECT count(*) FROM iam.user_account WHERE department_id=? AND status='ACTIVE'", id) > 0
                || db.count("SELECT count(*) FROM iam.department WHERE parent_id=? AND status='ACTIVE'", id) > 0))
            throw ApiFault.conflict("部门仍有有效员工或下级部门，不能停用");
        db.update(
                "UPDATE iam.department SET code=?,name=?,parent_id=?,status=?,sort_order=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                body.containsKey("code") ? Inputs.text(body, "code", 64) : row.get("code"),
                body.containsKey("name") ? Inputs.text(body, "name", 128) : row.get("name"), parent, state,
                body.containsKey("sortOrder") ? Inputs.integer(body.get("sortOrder"), 0, 0, 100000)
                        : row.get("sort_order"),
                id);
        return department(id);
    }

    /** 替换操作者有权管理模块的完整角色集合；其他模块保持不变，初始化例外单独处理。 */
    public Map<String, Object> assignRoles(Commands.Context ctx, UUID id, Map<String, Object> body) {
        if (!canManageRoles(ctx.actor()))
            throw ApiFault.denied();
        Inputs.only(body, "roles", "reason", "approvalRef");
        var user = db.required("SELECT * FROM iam.user_account WHERE id=? FOR UPDATE", id);
        // 任职配置使用 UserRoles.authVersion；最小人员选择视图不必泄露 UserView 管理字段。
        if (ctx.version() == null || ctx.version() != ((Number) user.get("auth_version")).intValue())
            throw ApiFault.conflict("人员授权已变化，请重新选择人员后提交");
        if (!user.get("status").equals("ACTIVE"))
            throw ApiFault.conflict("请先启用账号再配置角色");
        List<Map<String, Object>> inputs = Inputs.objects(body.get("roles"), 20);
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        Set<UUID> seen = new HashSet<>();
        // PUT 替换操作者真正获准管理模块的任职；其他模块保留，空集合可撤销本域全部角色。
        for (String module : List.of("PLATFORM", "DRIVE", "ARCHIVE"))
            if (ctx.actor().actions().contains(module + ".ROLES_MANAGE"))
                grouped.put(module, new ArrayList<>());
        for (var input : inputs) {
            Inputs.only(input, "roleId", "departmentId");
            UUID roleId = Inputs.uuid(input.get("roleId"));
            if (!seen.add(roleId))
                throw ApiFault.invalid("角色不能重复");
            var role = db.required("SELECT * FROM iam.role WHERE id=? AND status='ACTIVE'", roleId);
            String module = (String) role.get("module");
            boolean initial = module.equals("ARCHIVE") && !ctx.actor().actions().contains("ARCHIVE.ROLES_MANAGE") && firstArchiveAdmin();
            if (initial) {
                if (!role.get("code").equals("SUPER_ADMIN"))
                    throw ApiFault.denied();
            } else
                checkModule(ctx.actor(), module, false);
            if (Boolean.TRUE.equals(user.get("it_shared_account"))
                    && !(module.equals("PLATFORM") && Set.of("IT_ADMIN", "MEMBER").contains(role.get("code"))
                    || module.equals("DRIVE") && "IT_ADMIN".equals(role.get("code"))))
                throw ApiFault.invalid("请使用具名人员账号承担业务角色");
            UUID department = Inputs.nullableUuid(input.get("departmentId"));
            if (role.get("code").equals("DEPARTMENT_ARCHIVIST") || role.get("code").equals("DEPARTMENT_MANAGER")) {
                if (!Objects.equals(department, user.get("department_id")))
                    throw ApiFault.invalid("部门角色必须绑定员工所属部门");
                activeDepartment(department);
            } else if (department != null)
                throw ApiFault.invalid("此角色不接受部门范围");
            role.put("assignment_department", department);
            grouped.computeIfAbsent(module, k -> new ArrayList<>()).add(role);
        }
        if (grouped.getOrDefault("DRIVE", List.of()).size() > 1)
            throw ApiFault.conflict("每人只能有一个有效公盘角色");
        Map<String, List<Map<String, Object>>> removals = new LinkedHashMap<>(), additions = new LinkedHashMap<>();
        // PUT携带完整选择；只增删真正变化的任职，保留未变身份的编号、任期和审批引用。
        for (var entry : grouped.entrySet()) {
            var current = db.rows("SELECT * FROM iam.user_role WHERE user_id=? AND module=? AND state='ACTIVE'", id, entry.getKey());
            var removed = current.stream().filter(old -> entry.getValue().stream().noneMatch(next -> sameRoleScope(old, next))).toList();
            var added = entry.getValue().stream().filter(next -> current.stream().noneMatch(old -> sameRoleScope(old, next))).toList();
            if (!removed.isEmpty() || !added.isEmpty()) {
                removals.put(entry.getKey(), removed);
                additions.put(entry.getKey(), added);
            }
        }
        optionalRoleText(body, "reason", 1000);
        String approval = optionalRoleText(body, "approvalRef", 256);
        if (removals.isEmpty())
            return Commands.result(ctx, id, ((Number) user.get("row_version")).intValue());
        for (String module : removals.keySet()) {
            // 只有实际撤销或更换档案员身份才交接；保留档案员的其他角色调整不触发。
            if (module.equals("ARCHIVE") && removals.get(module).stream().anyMatch(r -> r.get("role_code").equals("DEPARTMENT_ARCHIVIST")))
                throw ApiFault.conflict("档案员更替须通过专用交接流程");
        }
        for (String module : removals.keySet()) {
            for (var old : removals.get(module))
                db.update("UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP WHERE id=?", old.get("id"));
            for (var role : additions.get(module))
                db.update(
                        "INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,approval_ref,module,role_code) VALUES(?,?,?,?,CURRENT_TIMESTAMP,?,?,?,?)",
                        UUID.randomUUID(), id, role.get("id"), role.get("assignment_department"), ctx.actor().userId(),
                        approval, module, role.get("code"));
            audit.event(ctx.actor().userId(), module, "USER", id, "ASSIGN_ROLES", "SUCCEEDED", ctx.request());
        }
        db.update("UPDATE iam.user_account SET row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        identity.invalidate(id, "ROLE_CHANGE", ctx.operation());
        return Commands.result(ctx, id, ((Number) user.get("row_version")).intValue() + 1);
    }

    /** 同一角色及部门范围代表同一任职，不以表单顺序判断变化。 */
    private boolean sameRoleScope(Map<String, Object> current, Map<String, Object> selected) {
        return current.get("role_id").equals(selected.get("id")) && Objects.equals(current.get("department_id"), selected.get("assignment_department"));
    }

    /** 兼容旧页面提交的选填说明；当前角色管理不再要求手填依据或原因。 */
    private String optionalRoleText(Map<String, Object> body, String field, int maximum) {
        Object value = body.get(field);
        if (value == null || value instanceof String text && text.isBlank()) return null;
        return Inputs.text(body, field, maximum);
    }

    /** 平台 IT 配置各模块的权限，平台 IT 自身的权限由系统固定。 */
    public Map<String, Object> rolePermissions(Commands.Context ctx, UUID id, Map<String, Object> body) {
        Inputs.only(body, "permissions", "changeReason", "approvalRef");
        optionalRoleText(body, "changeReason", 1000);
        optionalRoleText(body, "approvalRef", 256);
        var role = db.required("SELECT * FROM iam.role WHERE id=? FOR UPDATE", id);
        checkModule(ctx.actor(), (String) role.get("module"), false);
        if ("PLATFORM".equals(role.get("module")) && "IT_ADMIN".equals(role.get("code")))
            throw ApiFault.invalid("IT 管理员具有固定的系统管理权限，请通过人员角色分配授予或撤销该资格");
        Commands.version(ctx, role);
        List<Map<String, Object>> inputs = Inputs.objects(body.get("permissions"), 100);
        Set<UUID> seen = new HashSet<>();
        for (var input : inputs) {
            Inputs.only(input, "permissionId", "scopePolicy", "effect");
            UUID permission = Inputs.uuid(input.get("permissionId"));
            if (!seen.add(permission))
                throw ApiFault.invalid("权限不能重复");
            var p = db.required("SELECT * FROM iam.permission WHERE id=?", permission);
            if (!p.get("module").equals(role.get("module")))
                throw ApiFault.denied();
            if (!(input.get("scopePolicy") instanceof Map<?, ?> policy) || !policy.keySet().equals(Set.of("code"))
                    || !"COMPANY".equals(policy.get("code")))
                throw ApiFault.invalid("本批管理权限仅支持固定 COMPANY 范围");
            Inputs.choice(input.get("effect"), "ALLOW", "DENY");
        }
        db.update("DELETE FROM iam.role_permission WHERE role_id=?", id);
        for (var input : inputs)
            db.update(
                    "INSERT INTO iam.role_permission(role_id,permission_id,scope_policy,effect,changed_by) VALUES(?,?,'COMPANY',?,?)",
                    id, Inputs.uuid(input.get("permissionId")), input.get("effect"), ctx.actor().userId());
        db.update("UPDATE iam.role SET permission_version=permission_version+1,row_version=row_version+1 WHERE id=?",
                id);
        for (var user : db.rows("SELECT user_id FROM iam.user_role WHERE role_id=? AND state='ACTIVE' ORDER BY user_id",
                id))
            identity.invalidate((UUID) user.get("user_id"), "ROLE_PERMISSION_CHANGE", UUID.randomUUID());
        audit.event(ctx.actor().userId(), (String) role.get("module"), "ROLE", id, "ROLE_PERMISSIONS", "SUCCEEDED",
                ctx.request());
        return Sql.map("objectId", id, "state", "SUCCEEDED", "operationId", ctx.operation(), "rowVersion",
                ((Number) role.get("row_version")).intValue() + 1);
    }

    /** 在管理命令事务和统一管理写锁内核对有效部门，避免启停与引用并发穿透。 */
    private void activeDepartment(UUID id) {
        if (id == null || db.count("SELECT count(*) FROM iam.department WHERE id=? AND status='ACTIVE'", id) != 1)
            throw ApiFault.invalid("请选择有效部门");
    }

    /** 初始化例外只在档案超级管理员从未分配时存在，不能通过停用重新打开。 */
    private boolean firstArchiveAdmin() {
        return db.count("SELECT count(*) FROM iam.user_role WHERE module='ARCHIVE' AND role_code='SUPER_ADMIN'") == 0;
    }

    /** 平台 IT 按模块管理角色，初始化例外仅允许读取档案角色。 */
    public void checkModule(Actor actor, String module, boolean readInitial) {
        if (!canManageRoles(actor))
            throw ApiFault.denied();
        if (actor.actions().contains(module + ".ROLES_MANAGE"))
            return;
        if (readInitial && module.equals("ARCHIVE") && firstArchiveAdmin())
            return;
        throw ApiFault.denied();
    }

    /** 关键字使用绑定参数并转义 LIKE 通配符；参数长度受控。 */
    private void keyword(Map<String, String> query, StringBuilder where, List<Object> args, String first,
            String second) {
        if (query.containsKey("keyword") && !query.get("keyword").isBlank()) {
            String keyword = Inputs.text(query, "keyword", 200).replace("\\", "\\\\").replace("%", "\\%").replace("_",
                    "\\_");
            where.append(" AND (" + first + " ILIKE ? OR " + second + " ILIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
    }

    /** 统一有界数据库分页；表名、筛选列和排序列只由服务端固定调用提供。 */
    public Map<String, Object> page(String table, String where, List<?> values, Map<String, String> query,
            String sortable, java.util.function.Function<Map<String, Object>, Map<String, Object>> projection) {
        int page = Inputs.integer(query.get("page"), 1, 1, 100000),
                size = Inputs.integer(query.get("pageSize"), 20, 1, 100);
        String sort = query.getOrDefault("sort", "id:desc");
        Set<String> allowed = Set.of(sortable.split(","));
        List<String> order = new ArrayList<>();
        for (String part : sort.split(",")) {
            String[] pair = part.split(":");
            if (pair.length != 2 || !allowed.contains(pair[0]) || !Set.of("asc", "desc").contains(pair[1]))
                throw ApiFault.invalid("排序字段不受支持");
            String column = pair[0].replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
            order.add(column + " " + pair[1]);
        }
        if (order.stream().noneMatch(x -> x.startsWith("id ")))
            order.add("id asc");
        long total = db.count("SELECT count(*) FROM " + table + where, values.toArray());
        List<Object> args = new ArrayList<>(values);
        args.add(size);
        args.add((page - 1) * size);
        var items = db
                .rows("SELECT * FROM " + table + where + " ORDER BY " + String.join(",", order) + " LIMIT ? OFFSET ?",
                        args.toArray())
                .stream().map(projection).toList();
        return Sql.map("items", items, "page", page, "pageSize", size, "total", total, "hasNext",
                (long) page * size < total);
    }
}
