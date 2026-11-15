package com.company.ims.workflow;

import com.company.ims.archive.ArchiveAccess;
import com.company.ims.identity.Actor;
import com.company.ims.shared.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import static com.company.ims.workflow.WorkflowInput.*;

/** 可配置审批引擎：模板快照、实际审批人、会签和业务单状态在同一事务内维护。 */
@Service
public class ApprovalService {
    /** 模板条件只允许读取这些档案描述字段，禁止任意SQL字段。 */
    private static final Set<String> CONDITION_FIELDS = Set.of("archive_no","archive_name","keyword","category_name","dept_name");
    /** 明确支持的匹配运算符。 */
    private static final Set<String> CONDITION_OPS = Set.of("startswith","contains","endswith","eq");
    /** 审批人的来源与旧客户端模板编辑器保持一致。 */
    private static final Set<String> APPROVER_TYPES = Set.of("user","role","permission","permission_level","dept_leader","creator_leader");
    /** 数据库访问和事务共享同一连接。 */
    private final Db db;
    /** 档案范围策略用于查询和每次审批的重新授权。 */
    private final ArchiveAccess access;
    /** 审批操作与业务状态一并审计。 */
    private final AuditService audit;

    /** 注入数据库、档案范围检查器和审计服务。 */
    public ApprovalService(Db db, ArchiveAccess access, AuditService audit) {
        this.db = db; this.access = access; this.audit = audit;
    }

    /** 一个已解析节点的不可变快照；批量申请必须解析出完全一致的计划。 */
    record NodePlan(int order, String name, String type, String value, int countersign, List<Long> assignees) { }
    /** 模板编号与实际审批节点；空模板编号表示基于业务权限的默认审批。 */
    record Plan(Long templateId, List<NodePlan> nodes) { }

    /** 返回所有模板和按顺序排列的节点，仅由有模板管理权限的接口调用。 */
    public List<Map<String,Object>> templates() {
        List<Map<String,Object>> result = db.list("select * from approval_template order by biz_type, priority desc, id"); // 模板排序保证同优先级结果稳定。
        result.forEach(row -> row.put("nodes",templateNodes(number(row.get("id"),"id"))));
        return result;
    }

    /** 查询指定模板，未找到时返回404业务异常。 */
    public Map<String,Object> template(long id) {
        Map<String,Object> result = db.required("select * from approval_template where id=?",id); // 仅模板定义，无历史流程引用。
        result.put("nodes",templateNodes(id));
        return result;
    }

    /** 新增或部分更新模板；每次替换节点前完成全部校验，历史流程快照不受影响。 */
    @Transactional
    public Map<String,Object> saveTemplate(Long id, Map<String,Object> body, Actor actor) {
        if (id != null) db.required("select id from approval_template where id=? for update",id);
        Map<String,Object> merged = id == null ? new LinkedHashMap<>() : template(id); // 先加锁再读取，避免并发部分更新相互覆盖。
        merged.putAll(body);
        Map<String,Object> values = fields("name",text(merged,"name",100,true),
                "biz_type",choice(merged,"biz_type","",Set.of("borrow","destroy")),
                "description",text(merged,"description",10000,false),
                "priority",integer(merged,"priority",0,-100000,100000),"enabled",integer(merged,"enabled",1,0,1)); // 仅写入白名单列。
        values.putAll(condition(merged,"trigger"));
        if (!(merged.get("nodes") instanceof List<?> nodes) || nodes.isEmpty() || nodes.size() > 30)
            throw bad("审批模板必须包含1至30个节点");
        List<Map<String,Object>> checked = new ArrayList<>(); // 验证后才对数据库执行替换。
        Set<Integer> orders = new HashSet<>(); // 同模板不允许节点顺序重复。
        for (Object raw : nodes) {
            if (!(raw instanceof Map<?,?>)) throw bad("审批节点格式不正确");
            @SuppressWarnings("unchecked") Map<String,Object> node = (Map<String,Object>)raw; // 请求解析后的单节点。
            int order = integer(node,"node_order",0,1,1000); // 显式节点顺序。
            if (!orders.add(order)) throw bad("审批节点顺序不能重复");
            String type = choice(node,"approver_type","",APPROVER_TYPES); // 审批人来源。
            String value = text(node,"approver_value",200,!Set.of("dept_leader","creator_leader").contains(type)); // 部门负责人来源无需额外值。
            if ("user".equals(type) && number(value,"审批人") < 1) throw bad("审批人ID必须大于零");
            if ("permission_level".equals(type) && (number(value,"权限等级") < 0 || number(value,"权限等级") > 100)) throw bad("权限等级必须在0至100之间");
            Map<String,Object> item = fields("node_order",order,"node_name",text(node,"node_name",100,true),
                    "approver_type",type,"approver_value",value,"is_countersign",integer(node,"is_countersign",0,0,1)); // 节点白名单。
            item.putAll(condition(node,"cond")); checked.add(item);
        }
        long savedId; // 新模板或更新目标的数据库编号。
        if (id == null) savedId = db.insert("approval_template",values);
        else { savedId = id; values.put("updated_at",LocalDateTime.now()); db.update("approval_template",id,values); }
        db.execute("delete from approval_template_node where template_id=?",savedId);
        for (Map<String,Object> node : checked) { node.put("template_id",savedId); db.insert("approval_template_node",node); }
        audit.record(actor.id(),"approval","save_template","approval_template",savedId,"保存审批模板与节点");
        return fields("id",savedId);
    }

    /** 删除模板定义，保留已发起流程的独立节点、审批人及记录快照。 */
    @Transactional
    public void deleteTemplate(long id, Actor actor) {
        db.required("select id from approval_template where id=? for update",id);
        db.execute("delete from approval_template where id=?",id);
        audit.record(actor.id(),"approval","delete_template","approval_template",id,"删除模板，历史流程快照保留");
    }

    /** 解析每份档案的模板、条件节点及具体审批人；不允许首份档案代表整批规则。 */
    Plan batchPlan(String bizType, List<Map<String,Object>> archives) {
        Plan first = null; // 首份完整审批计划，作为批量一致性基准。
        for (Map<String,Object> archive : archives) {
            Plan current = plan(bizType,archive); // 当前档案独立解析的审批计划。
            if (first == null) first = current;
            else if (!first.equals(current)) throw bad("所选档案的审批规则或审批人不同，请按部门和审批规则分别申请");
        }
        if (first == null) throw bad("请选择至少一份档案");
        return first;
    }

    /** 将已验证计划固化成独立流程快照；调用方必须处于创建业务单的事务内。 */
    long createFlow(String bizType, long bizId, Actor actor, String title, Plan plan, List<Map<String,Object>> archives) {
        List<Map<String,Object>> summary = archives.stream().map(a -> fields("archive_no",a.get("archive_no"),"archive_name",a.get("archive_name"))).toList(); // 完整申请范围摘要。
        long flowId = db.insert("approval_flow",fields("biz_type",bizType,"biz_id",bizId,"title",title,
                "applicant_user_id",actor.id(),"template_id",plan.templateId(),"current_node",plan.nodes().getFirst().order(),
                "status","pending","archive_summary",Json.write(summary))); // 流程主记录。
        for (Map<String,Object> archive : archives) db.execute("insert into approval_flow_archive(flow_id,archive_id) values (?,?)",flowId,archive.get("id"));
        for (NodePlan node : plan.nodes()) {
            long nodeId = db.insert("approval_flow_node",fields("flow_id",flowId,"node_order",node.order(),"node_name",node.name(),
                    "approver_type",node.type(),"approver_value",node.value(),"node_status","pending","is_countersign",node.countersign())); // 固化节点。
            for (Long userId : node.assignees()) db.execute("insert into approval_node_assignee(node_id,user_id,acted) values (?,?,0)",nodeId,userId);
        }
        return flowId;
    }

    /** 审批业务入口与通用待办入口共同调用本方法，最终状态和业务单同步提交或全部回滚。 */
    @Transactional
    public Map<String,Object> act(long flowId, String action, String opinion, Actor actor) {
        if (!Set.of("agree","reject").contains(action)) throw bad("审批动作必须为agree或reject");
        if (opinion != null && opinion.length() > 10000) throw bad("审批意见过长");
        Map<String,Object> flow = db.required("select * from approval_flow where id=? for update",flowId); // 行锁串行化同流程多次审批。
        String actionPermission = flow.get("biz_type") + ".approve"; // 通用待办不能绕过业务审批权限撤销。
        if (!actor.has(actionPermission)) throw new BusinessException(403,"没有业务审批权限：" + actionPermission);
        if (!"pending".equals(flow.get("status"))) throw new BusinessException(409,"流程已结束，不能重复审批");
        requireScope(flowId,actor,actionPermission);
        Map<String,Object> node = db.required("select * from approval_flow_node where flow_id=? and node_order=?",flowId,flow.get("current_node")); // 当前待审节点。
        long nodeId = number(node.get("id"),"node_id"); // 节点外键。
        Map<String,Object> assignment = db.one("select acted from approval_node_assignee where node_id=? and user_id=?",nodeId,actor.id()); // 必须属于显式指定的审批人，管理员也不绕过会签。
        if (assignment == null) throw new BusinessException(403,"您不是当前节点的审批人");
        if (number(assignment.get("acted"),"acted") == 1) throw new BusinessException(409,"您已完成当前节点审批");
        db.insert("approval_flow_record",fields("flow_id",flowId,"node_id",nodeId,"action",action,"opinion",opinion,"operator_user_id",actor.id()));
        db.execute("update approval_node_assignee set acted=1 where node_id=? and user_id=?",nodeId,actor.id());
        String status = "pending"; // 最终流程状态，未完成会签或还有后续节点时保持pending。
        int next = ((Number)flow.get("current_node")).intValue(); // 下一个待审节点序号。
        if ("reject".equals(action)) {
            status = "rejected"; next = 0;
            db.update("approval_flow_node",nodeId,fields("node_status","rejected"));
        } else if (number(node.get("is_countersign"),"is_countersign") == 0
                || db.scalarLong("select count(*) from approval_node_assignee where node_id=? and acted=0",nodeId) == 0) {
            db.update("approval_flow_node",nodeId,fields("node_status","approved"));
            Map<String,Object> following = db.one("select node_order from approval_flow_node where flow_id=? and node_status='pending' order by node_order limit 1",flowId); // 下一未决节点。
            next = following == null ? 0 : ((Number)following.get("node_order")).intValue();
            if (following == null) status = "approved";
        }
        db.update("approval_flow",flowId,fields("status",status,"current_node",next,"updated_at",LocalDateTime.now()));
        if (!"pending".equals(status)) {
            // 表名仅由受约束业务类型决定，禁止使用任何客户端提供的任意表名。
            String table = "borrow".equals(flow.get("biz_type")) ? "borrow_record" : "destroy_request";
            int changed = db.execute("update " + table + " set status=?,approved_by=?,approved_at=?,updated_at=? where id=? and status='pending'",
                    status,actor.id(),LocalDateTime.now(),LocalDateTime.now(),flow.get("biz_id")); // 条件更新避免覆盖已执行业务。
            if (changed != 1) throw new BusinessException(409,"审批流与业务单状态不一致，请联系管理员");
        }
        audit.record(actor.id(),"approval",action,"approval_flow",flowId,"流程状态=" + status);
        return fields("flow_status",status);
    }

    /** 查询业务对应流程编号，只供服务层路由；查看内容仍须检查数据范围。 */
    long flowId(String bizType, long bizId) {
        return number(db.required("select id from approval_flow where biz_type=? and biz_id=?",bizType,bizId).get("id"),"flow_id");
    }

    /** 返回当前用户真正尚未办理的节点；已会签的用户不会再次出现在待办中。 */
    public List<Map<String,Object>> tasks(String bizType, Actor actor) {
        List<Object> args = new ArrayList<>(List.of(actor.id())); // 可选业务筛选的参数。
        String filter = bizType == null ? "" : " and f.biz_type=?"; // SQL结构由服务固定。
        if (bizType != null) args.add(bizType);
        return db.list("select f.id as flow_id,f.title,f.biz_type,f.biz_id,f.applicant_user_id,f.archive_summary,f.created_at,n.node_name as current_node_name,u.real_name as applicant_name "
                + "from approval_flow f join approval_flow_node n on n.flow_id=f.id and n.node_order=f.current_node "
                + "join approval_node_assignee x on x.node_id=n.id join ims_user u on u.id=f.applicant_user_id "
                + "where f.status='pending' and x.acted=0 and x.user_id=?" + filter + " and " + scopePredicate(actor,"f") + " order by f.id desc",args.toArray());
    }

    /** 列出本人发起或本人参与的流程；全局权限用户仍受档案范围表达式约束。 */
    public List<Map<String,Object>> flows(String bizType, String status, Actor actor) {
        List<Object> args = new ArrayList<>(); // 可选业务类型和状态参数。
        StringBuilder filter = new StringBuilder(" where " + visiblePredicate(actor,"f")); // 先限制权限，再分页。
        if (bizType != null) { filter.append(" and f.biz_type=?"); args.add(bizType); }
        if (status != null) { filter.append(" and f.status=?"); args.add(status); }
        return db.list("select f.id as flow_id,f.title,f.biz_type,f.biz_id,f.status,f.created_at,u.real_name as applicant_name from approval_flow f join ims_user u on u.id=f.applicant_user_id"
                + filter + " order by f.id desc limit 200",args.toArray());
    }

    /** 按业务单返回流程详情；不存在流程返回null。 */
    public Map<String,Object> byBusiness(String bizType, long bizId, Actor actor) {
        Map<String,Object> row = db.one("select id from approval_flow where biz_type=? and biz_id=?",bizType,bizId); // 不含敏感业务内容。
        return row == null ? null : detail(number(row.get("id"),"id"),actor);
    }

    /** 返回节点快照及完整动作历史；不可借助猜测流程ID读取跨部门信息。 */
    public Map<String,Object> detail(long flowId, Actor actor) {
        Map<String,Object> result = db.required("select f.*,f.id as flow_id,u.real_name as applicant_name from approval_flow f join ims_user u on u.id=f.applicant_user_id where f.id=? and "
                + visiblePredicate(actor,"f"),flowId); // 同时核验参与关系与档案范围。
        List<Map<String,Object>> nodes = db.list("select * from approval_flow_node where flow_id=? order by node_order",flowId); // 流程节点快照。
        for (Map<String,Object> node : nodes) {
            List<Map<String,Object>> users = db.list("select x.user_id,x.acted,u.real_name from approval_node_assignee x join ims_user u on u.id=x.user_id where node_id=? order by x.user_id",node.get("id")); // 还原旧客户端期待的显示字段。
            node.put("approver_user_ids",String.join(",",users.stream().map(u -> u.get("user_id").toString()).toList()));
            node.put("approver_names",users.stream().map(u -> u.get("real_name")).toList());
            node.put("acted_names",users.stream().filter(u -> number(u.get("acted"),"acted") == 1).map(u -> u.get("real_name")).toList());
            // 网页按稳定用户编号判断会签状态，避免同名人员互相隐藏或冒领按钮。
            node.put("acted_user_ids",users.stream().filter(u -> number(u.get("acted"),"acted") == 1).map(u -> u.get("user_id")).toList());
        }
        result.put("nodes",nodes);
        result.put("records",db.list("select r.action,r.opinion,u.real_name as operator,r.acted_at from approval_flow_record r join ims_user u on u.id=r.operator_user_id where r.flow_id=? order by r.id",flowId));
        return result;
    }

    /** 构造业务列表可复用的流程可见条件；所有动态值均来自服务端身份数值。 */
    String visiblePredicate(Actor actor, String alias) {
        String relation = actor.has("*") ? "1=1" : "(" + alias + ".applicant_user_id=" + actor.id()
                + " or exists(select 1 from approval_flow_node pn join approval_node_assignee pa on pa.node_id=pn.id where pn.flow_id=" + alias + ".id and pa.user_id=" + actor.id() + "))"; // 本人发起或参与。
        return "(" + relation + " and " + scopePredicate(actor,alias) + ")";
    }

    /** 查询范围必须覆盖申请内每份档案，不能仅凭第一份档案可见就泄露整批数据。 */
    String scopePredicate(Actor actor, String alias) {
        return scopePredicate(actor,alias,"archive.read");
    }

    /** 按具体业务动作构造全批次范围，审批共享与只读共享不得互相替代。 */
    String scopePredicate(Actor actor,String alias,String permission) {
        return "not exists(select 1 from approval_flow_archive fa join archive a on a.id=fa.archive_id where fa.flow_id=" + alias
                + ".id and not (" + access.predicate(actor,"a",permission) + "))";
    }

    /** 再次检查当前审批人的数据范围；模板指派本身不授予跨部门档案读取权。 */
    private void requireScope(long flowId, Actor actor,String permission) {
        if (db.scalarLong("select count(*) from approval_flow f where f.id=? and " + scopePredicate(actor,"f") + " and " + scopePredicate(actor,"f",permission),flowId) != 1)
            throw new BusinessException(403,"当前账号无权查看或审批此申请中的全部档案");
    }

    /** 读取模板节点，使用确定性排序供计划比对。 */
    private List<Map<String,Object>> templateNodes(long id) {
        return db.list("select * from approval_template_node where template_id=? order by node_order",id);
    }

    /** 验证完整条件三元组；空条件表示总是匹配，残缺条件必须显式报错。 */
    private Map<String,Object> condition(Map<String,Object> body, String prefix) {
        String field = text(body,prefix + "_field",30,false); // 条件字段。
        String op = text(body,prefix + "_op",20,false); // 条件运算。
        String value = text(body,prefix + "_value",200,false); // 条件比较文本。
        if (field == null || field.isBlank()) {
            if (op != null && !op.isBlank() || value != null && !value.isBlank()) throw bad("条件字段、运算符和条件值必须同时填写");
            return fields(prefix + "_field",null,prefix + "_op",null,prefix + "_value",null);
        }
        if (!CONDITION_FIELDS.contains(field) || !CONDITION_OPS.contains(op == null ? "" : op) || value == null || value.isBlank()) throw bad("审批条件字段、运算符或值不合法");
        return fields(prefix + "_field",field,prefix + "_op",op,prefix + "_value",value);
    }

    /** 解析单份档案的有效模板并解析每个有效节点的审批人，未配置有效节点时拒绝申请。 */
    private Plan plan(String bizType, Map<String,Object> archive) {
        Map<String,Object> context = new HashMap<>(archive); // 补充分类和部门名称供旧模板词缀匹配。
        Map<String,Object> category = db.one("select category_name from archive_category where id=?",archive.get("category_id")); // 可空分类。
        Map<String,Object> department = db.one("select dept_name from ims_department where id=?",archive.get("dept_id")); // 可空部门。
        context.put("category_name",category == null ? "" : category.get("category_name"));
        context.put("dept_name",department == null ? "" : department.get("dept_name"));
        Map<String,Object> template = db.list("select * from approval_template where biz_type=? and enabled=1 order by priority desc,id",bizType)
                .stream().filter(t -> matches(t,"trigger",context)).findFirst().orElse(null); // 优先级最高且ID较小的模板。
        List<Map<String,Object>> definitions = template == null
                ? List.of(fields("node_order",1,"node_name","审批","approver_type","permission","approver_value",bizType + ".approve","is_countersign",0))
                : templateNodes(number(template.get("id"),"template_id")); // 无模板采用显式业务审批权限。
        List<NodePlan> nodes = new ArrayList<>(); // 条件过滤后的有效节点。
        for (Map<String,Object> node : definitions) {
            if (!matches(node,"cond",context)) continue;
            List<Long> approvers = resolveApprovers(node,archive,bizType); // 已启用并可访问档案的实际审批人。
            if (approvers.isEmpty()) throw bad("审批节点「" + node.get("node_name") + "」没有有效审批人，请配置人员及档案访问范围后重试");
            nodes.add(new NodePlan(((Number)node.get("node_order")).intValue(),(String)node.get("node_name"),
                    (String)node.get("approver_type"),(String)node.get("approver_value"),((Number)node.get("is_countersign")).intValue(),approvers));
        }
        if (nodes.isEmpty()) throw bad("没有适用的审批节点，请完善审批模板；系统不会自动批准申请");
        return new Plan(template == null ? null : number(template.get("id"),"template_id"),List.copyOf(nodes));
    }

    /** 使用受限运算符匹配模板条件，不执行表达式或任意SQL。 */
    private boolean matches(Map<String,Object> source, String prefix, Map<String,Object> context) {
        Object field = source.get(prefix + "_field"); // 缺省条件表示全部适用。
        if (field == null || field.toString().isBlank()) return true;
        String actual = Objects.toString(context.get(field.toString()),""); // 空档案字段按空字符串处理。
        String expected = Objects.toString(source.get(prefix + "_value"),""); // 模板保存时已校验。
        return switch (Objects.toString(source.get(prefix + "_op"),"")) {
            case "eq" -> actual.equals(expected);
            case "startswith" -> actual.startsWith(expected);
            case "endswith" -> actual.endsWith(expected);
            case "contains" -> actual.contains(expected);
            default -> throw bad("审批模板包含未知运算符");
        };
    }

    /** 按六类来源解析审批人，仅保留启用账号和启用角色，并过滤档案数据范围。 */
    private List<Long> resolveApprovers(Map<String,Object> node, Map<String,Object> archive, String bizType) {
        String type = (String)node.get("approver_type"); // 受白名单验证的来源类型。
        Object value = node.get("approver_value"); // 角色、用户、权限码或等级。
        List<Map<String,Object>> users; // 数据库解析出的候选用户。
        switch (type) {
            case "user" -> users = db.list("select * from ims_user where id=? and account_status='active' and deleted_at is null",number(value,"审批人"));
            case "role" -> users = db.list("select distinct u.* from ims_user u join ims_user_role ur on ur.user_id=u.id join ims_role r on r.id=ur.role_id where u.account_status='active' and u.deleted_at is null and r.status=1 and r.role_code=?",value);
            case "permission" -> users = db.list("select distinct u.* from ims_user u join ims_user_role ur on ur.user_id=u.id join ims_role r on r.id=ur.role_id join ims_role_permission rp on rp.role_id=r.id join ims_permission p on p.id=rp.permission_id where u.account_status='active' and u.deleted_at is null and r.status=1 and (p.perm_code=? or p.perm_code='*')",value);
            case "permission_level" -> users = db.list("select distinct u.* from ims_user u join ims_user_role ur on ur.user_id=u.id join ims_role r on r.id=ur.role_id where u.account_status='active' and u.deleted_at is null and r.status=1 and r.role_level<=?",number(value,"权限等级"));
            case "dept_leader" -> users = db.list("select u.* from ims_user u join ims_department d on d.leader_user_id=u.id where d.id=? and u.account_status='active' and u.deleted_at is null",archive.get("dept_id"));
            case "creator_leader" -> users = db.list("select u.* from ims_user u join ims_department d on d.leader_user_id=u.id join ims_user c on c.dept_id=d.id where c.id=? and u.account_status='active' and u.deleted_at is null",archive.get("creator_user_id"));
            default -> throw bad("不支持的审批人来源");
        }
        List<Long> result = new ArrayList<>(); // 稳定排序用于批次计划等价判断。
        for (Map<String,Object> user : users) {
            long userId = number(user.get("id"),"user_id"); // 候选审批人的数据库主键。
            Set<String> roles = new HashSet<>(db.list("select r.role_code from ims_role r join ims_user_role ur on ur.role_id=r.id where ur.user_id=? and r.status=1",userId).stream().map(r -> (String)r.get("role_code")).toList()); // 启用角色。
            Set<String> permissions = new HashSet<>(db.list("select distinct p.perm_code from ims_permission p join ims_role_permission rp on rp.permission_id=p.id join ims_role r on r.id=rp.role_id join ims_user_role ur on ur.role_id=r.id where ur.user_id=? and r.status=1",userId).stream().map(p -> (String)p.get("perm_code")).toList()); // 实际启用权限。
            Actor candidate = new Actor(userId,user.get("dept_id") == null ? 0 : number(user.get("dept_id"),"dept_id"),Objects.toString(user.get("username"),""),Objects.toString(user.get("real_name"),""),roles,permissions); // 复用平台统一数据范围策略。
            if (!candidate.has(bizType + ".approve")) continue;
            if (db.scalarLong("select count(*) from archive a where a.id=? and (" + access.predicate(candidate,"a") + ") and ("
                    + access.predicate(candidate,"a",bizType + ".approve") + ")",archive.get("id")) == 1) result.add(userId);
        }
        return result.stream().distinct().sorted().toList();
    }
}
