package com.company.ims.workflow;

import com.company.ims.archive.ArchiveAccess;
import com.company.ims.identity.Actor;
import com.company.ims.shared.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.company.ims.workflow.WorkflowInput.*;

/** 档案借阅、销毁和损坏处理，所有修改受事务、状态机、档案范围和审计保护。 */
@Service
public class BusinessService {
    /** 业务持久化入口，调用时复用当前Spring事务。 */
    private final Db db;
    /** 平台档案访问范围检查器。 */
    private final ArchiveAccess access;
    /** 统一审批服务保证两个审批入口采用同一状态推进逻辑。 */
    private final ApprovalService approval;
    /** 业务操作审计，与业务数据原子提交。 */
    private final AuditService audit;

    /** 注入数据库、访问策略、审批引擎和审计服务。 */
    public BusinessService(Db db, ArchiveAccess access, ApprovalService approval, AuditService audit) {
        this.db=db; this.access=access; this.approval=approval; this.audit=audit;
    }

    /** 创建借阅申请并保存明细、审批快照；不同审批规则必须分单申请。 */
    @Transactional
    public Map<String,Object> createBorrow(Map<String,Object> body, Actor actor) {
        List<Long> ids = archiveIds(body); // 申请范围排序后锁定，避免批量锁顺序死锁。
        String mode = choice(body,"borrow_mode","electronic",Set.of("physical","electronic","both")); // 纸质/电子/混合借阅模式。
        LocalDate expected = expectedDate(body); // 可空预计归还日期。
        for (Long archiveId : ids) access.requireRead(archiveId); // 能看见档案不等于已获准发起借阅。
        List<Map<String,Object>> archives = lockArchives(ids,"borrow.apply"); // 借阅动作需独立的功能和对象范围授权。
        for (Map<String,Object> archive : archives) {
            requireArchived(archive);
            if (db.scalarLong("select count(*) from destroy_request_detail d join destroy_request r on r.id=d.request_id where d.archive_id=? and r.status in ('pending','approved')",archive.get("id")) > 0)
                throw new BusinessException(409,"档案已有待处理销毁申请，不能新增借阅");
        }
        ApprovalService.Plan plan = approval.batchPlan("borrow",archives); // 所有档案分别解析后才允许创建。
        String borrowNo = "BY-" + UUID.randomUUID(); // UUID保证并发单号唯一且无需依赖时间分辨率。
        long id = db.insert("borrow_record",fields("borrow_no",borrowNo,"applicant_user_id",actor.id(),"applicant_dept_id",actor.deptId() == 0 ? null : actor.deptId(),
                "purpose",text(body,"purpose",500,false),"expected_return_date",expected,"status","pending")); // 创建申请主记录。
        for (Long archiveId : ids) db.insert("borrow_detail",fields("borrow_id",id,"archive_id",archiveId,"borrow_mode",mode));
        approval.createFlow("borrow",id,actor,"借阅申请 " + borrowNo,plan,archives);
        audit.record(actor.id(),"borrow","apply","borrow",id,"档案IDs=" + ids);
        return borrow(id,actor);
    }

    /** 返回借阅详情，包括准确档案范围和模式；本人或流程参与人之外不可查看。 */
    public Map<String,Object> borrow(long id, Actor actor) {
        Map<String,Object> result = db.required("select r.*,u.real_name as applicant_name from borrow_record r join ims_user u on u.id=r.applicant_user_id join approval_flow f on f.biz_type='borrow' and f.biz_id=r.id where r.id=? and "
                + businessVisible("borrow",actor),id); // 同时验证每份档案的范围。
        List<Map<String,Object>> details = db.list("select d.*,a.archive_no,a.archive_name from borrow_detail d join archive a on a.id=d.archive_id where d.borrow_id=? order by d.archive_id",id); // 不过滤已销毁元数据，历史单据仍保留。
        result.put("archive_ids",details.stream().map(d -> d.get("archive_id")).toList());
        result.put("archive_names",details.stream().map(d -> d.get("archive_name")).toList());
        result.put("borrow_mode",details.isEmpty() ? "electronic" : details.getFirst().get("borrow_mode"));
        result.put("details",details);
        return result;
    }

    /** 分页查询借阅，范围过滤在统计和分页之前应用，避免跨部门计数泄露。 */
    public Map<String,Object> borrows(String status, Long applicantId, int page, int size, Actor actor) {
        List<Object> args = new ArrayList<>(); // 分页前状态和申请人筛选参数。
        String filter = businessFilter("borrow",status,applicantId,actor,args); // 包含所有档案范围和参与关系。
        long count = db.scalarLong("select count(*) from borrow_record r join approval_flow f on f.biz_type='borrow' and f.biz_id=r.id where " + filter,args.toArray()); // 授权内总数。
        args.add(size); args.add((page-1)*size);
        List<Map<String,Object>> rows = db.list("select r.id from borrow_record r join approval_flow f on f.biz_type='borrow' and f.biz_id=r.id where " + filter + " order by r.id desc limit ? offset ?",args.toArray()); // 先分页，再加载明细。
        return fields("total",count,"items",rows.stream().map(r -> borrow(number(r.get("id"),"id"),actor)).toList());
    }

    /** 旧业务审批接口委托同一个通用审批事务，不再自行修改状态。 */
    @Transactional
    public Map<String,Object> approveBorrow(long id, boolean agree, String opinion, Actor actor) {
        approval.act(approval.flowId("borrow",id),agree ? "agree" : "reject",opinion,actor);
        return borrow(id,actor);
    }

    /** 显式借出：仅已批准单据可执行，纸质档案同时锁定并改变实物状态。 */
    @Transactional
    public Map<String,Object> checkout(long id, Actor actor) {
        Map<String,Object> record = db.required("select * from borrow_record where id=? for update",id); // 业务单行锁阻止重复借出/归还。
        requireBorrowScope(id,actor);
        if (!"approved".equals(record.get("status"))) throw new BusinessException(409,"仅已批准的借阅单可以办理借出");
        List<Map<String,Object>> details = db.list("select * from borrow_detail where borrow_id=? order by archive_id",id); // 按档案ID锁定。
        for (Map<String,Object> detail : details) {
            long archiveId = number(detail.get("archive_id"),"archive_id"); // 明细对应的已批准档案。
            Map<String,Object> archive = db.required("select * from archive where id=? for update",archiveId); // 防止并发双重借出或销毁。
            requireArchived(archive);
            if (!"electronic".equals(detail.get("borrow_mode"))) db.update("archive",archiveId,fields("status","borrowed","updated_at",LocalDateTime.now()));
            db.update("borrow_detail",number(detail.get("id"),"id"),fields("checkout_time",LocalDateTime.now()));
        }
        db.update("borrow_record",id,fields("status","borrowed","updated_at",LocalDateTime.now()));
        audit.record(actor.id(),"borrow","checkout","borrow",id,"已办理借出");
        return borrow(id,actor);
    }

    /** 归还需遵守状态机：电子批准后可关闭，纸质/混合必须先办理借出。 */
    @Transactional
    public Map<String,Object> returnBorrow(long id, Map<String,Object> body, Actor actor) {
        Map<String,Object> record = db.required("select * from borrow_record where id=? for update",id); // 串行化归还与借出。
        requireBorrowScope(id,actor);
        List<Map<String,Object>> details = db.list("select * from borrow_detail where borrow_id=? order by archive_id",id); // 精确申请范围。
        boolean electronic = details.stream().allMatch(d -> "electronic".equals(d.get("borrow_mode"))); // 电子借阅无需实物交接。
        if (!"borrowed".equals(record.get("status")) && !(electronic && "approved".equals(record.get("status"))))
            throw new BusinessException(409,"当前状态不允许归还；纸质或混合借阅请先办理借出");
        String condition = text(body,"return_condition",500,false); // 归还情况留痕。
        for (Map<String,Object> detail : details) {
            long archiveId = number(detail.get("archive_id"),"archive_id"); // 锁定当前明细对应档案。
            Map<String,Object> archive = db.required("select * from archive where id=? for update",archiveId); // 防止与处置操作交叉。
            if (!"electronic".equals(detail.get("borrow_mode"))) {
                if (!"borrowed".equals(archive.get("status"))) throw new BusinessException(409,"档案实物状态异常，不能直接归还");
                db.update("archive",archiveId,fields("status","archive","updated_at",LocalDateTime.now()));
            }
            db.update("borrow_detail",number(detail.get("id"),"id"),fields("return_condition",condition));
        }
        db.update("borrow_record",id,fields("status","returned","actual_return_date",LocalDate.now(),"updated_at",LocalDateTime.now()));
        audit.record(actor.id(),"borrow","return","borrow",id,condition);
        return fields("id",id,"status","returned");
    }

    /** 创建销毁申请时固定档案ID及名称快照；活动借阅或重复销毁申请会阻止申请。 */
    @Transactional
    public Map<String,Object> createDestroy(Map<String,Object> body, Actor actor) {
        List<Long> ids = archiveIds(body); // 精确拟销毁范围。
        List<Map<String,Object>> archives = lockArchives(ids,"destroy.apply"); // 销毁按该动作的数据范围授权。
        for (Map<String,Object> archive : archives) {
            requireArchived(archive);
            requireNoActiveBorrow(number(archive.get("id"),"archive_id"));
            if (db.scalarLong("select count(*) from destroy_request_detail d join destroy_request r on r.id=d.request_id where d.archive_id=? and r.status in ('pending','approved')",archive.get("id")) > 0)
                throw new BusinessException(409,"档案已有待处理销毁申请，请勿重复申请");
        }
        ApprovalService.Plan plan = approval.batchPlan("destroy",archives); // 全批次审批规则一致性核验。
        long id = db.insert("destroy_request",fields("applicant_user_id",actor.id(),"reason",text(body,"reason",10000,true),
                "scope_desc",text(body,"scope_desc",10000,false),"status","pending")); // 申请主记录。
        for (Map<String,Object> archive : archives) db.execute("insert into destroy_request_detail(request_id,archive_id,archive_no,archive_name) values (?,?,?,?)",id,archive.get("id"),archive.get("archive_no"),archive.get("archive_name"));
        approval.createFlow("destroy",id,actor,"销毁申请 #" + id,plan,archives);
        audit.record(actor.id(),"destroy","apply","destroy",id,"申请档案IDs=" + ids);
        return fields("id",id,"status","pending");
    }

    /** 查询销毁申请与不可变申请明细，供执行弹窗只显示本次获批范围。 */
    public Map<String,Object> destroy(long id, Actor actor) {
        Map<String,Object> result = db.required("select r.*,u.real_name as applicant_name from destroy_request r join ims_user u on u.id=r.applicant_user_id join approval_flow f on f.biz_type='destroy' and f.biz_id=r.id where r.id=? and "
                + businessVisible("destroy",actor),id); // 限制参与关系与全部档案可见性。
        List<Map<String,Object>> details = db.list("select d.archive_id as id,d.archive_id,d.archive_no,d.archive_name,a.status from destroy_request_detail d join archive a on a.id=d.archive_id where d.request_id=? order by d.archive_id",id); // 名称来自申请时快照。
        result.put("archive_ids",details.stream().map(d -> d.get("archive_id")).toList());
        result.put("archive_names",details.stream().map(d -> d.get("archive_name")).toList());
        result.put("archives",details);
        return result;
    }

    /** 分页查询销毁申请；批次详情不会从全库重新选择档案。 */
    public Map<String,Object> destroys(String status, Long applicantId, int page, int size, Actor actor) {
        List<Object> args = new ArrayList<>(); // 可选筛选参数。
        String filter = businessFilter("destroy",status,applicantId,actor,args); // 已含范围表达式。
        long count = db.scalarLong("select count(*) from destroy_request r join approval_flow f on f.biz_type='destroy' and f.biz_id=r.id where " + filter,args.toArray()); // 可见总数。
        args.add(size); args.add((page-1)*size);
        List<Map<String,Object>> rows = db.list("select r.id from destroy_request r join approval_flow f on f.biz_type='destroy' and f.biz_id=r.id where " + filter + " order by r.id desc limit ? offset ?",args.toArray()); // 分页主键。
        return fields("total",count,"items",rows.stream().map(r -> destroy(number(r.get("id"),"id"),actor)).toList());
    }

    /** 旧销毁审批接口复用通用待办审批事务。 */
    @Transactional
    public Map<String,Object> approveDestroy(long id, boolean agree, String opinion, Actor actor) {
        approval.act(approval.flowId("destroy",id),agree ? "agree" : "reject",opinion,actor);
        return fields("id",id,"status",db.required("select status from destroy_request where id=?",id).get("status"));
    }

    /** 仅执行完整获批集合，锁住申请和档案，保留批次快照并逻辑销毁；不物理删除附件。 */
    @Transactional
    public Map<String,Object> executeDestroy(long id, Map<String,Object> body, Actor actor) {
        List<Long> supplied = archiveIds(body); // 客户端选中范围，仅用于核对，绝不能直接决定执行对象。
        Map<String,Object> request = db.required("select * from destroy_request where id=? for update",id); // 防止重复执行产生多个批次。
        if (!"approved".equals(request.get("status"))) throw new BusinessException(409,"仅已批准且未执行的销毁申请可以执行");
        List<Long> approved = db.list("select archive_id from destroy_request_detail where request_id=? order by archive_id",id)
                .stream().map(r -> number(r.get("archive_id"),"archive_id")).toList(); // 服务端保存的唯一执行依据。
        if (!approved.equals(supplied)) throw bad("执行档案必须与该申请已批准的档案集合完全一致");
        List<Map<String,Object>> archives = lockArchives(approved,"destroy.execute"); // 每份档案重新检查执行范围，且按ID锁定。
        for (Map<String,Object> archive : archives) { requireArchived(archive); requireNoActiveBorrow(number(archive.get("id"),"archive_id")); }
        String batchNo = "DS-" + UUID.randomUUID(); // 唯一批次编号。
        long batchId = db.insert("destroy_batch",fields("destroy_request_id",id,"batch_no",batchNo,"executed_by",actor.id(),
                "witness",text(body,"witness",100,true),"method",text(body,"method",100,true))); // 必须填写见证人及销毁方式。
        for (Map<String,Object> archive : archives) {
            long archiveId = number(archive.get("id"),"archive_id"); // 获批且锁定的档案主键。
            db.insert("destroy_detail",fields("batch_id",batchId,"archive_id",archiveId,"archive_no",archive.get("archive_no"),"archive_name",archive.get("archive_name"),"original_status",archive.get("status")));
            db.update("archive",archiveId,fields("status","destroyed","deleted_at",LocalDateTime.now(),"updated_at",LocalDateTime.now()));
        }
        db.update("destroy_request",id,fields("status","executed","updated_at",LocalDateTime.now()));
        audit.record(actor.id(),"destroy","execute","destroy",id,"批次=" + batchNo + ";档案IDs=" + approved);
        return fields("batch_no",batchNo);
    }

    /** 登记损坏并核验所属档案写入范围；责任人必须是已存在账号。 */
    @Transactional
    public Map<String,Object> createDamage(Map<String,Object> body, Actor actor) {
        Long archiveId = optionalId(body,"archive_id"); // 必填档案外键。
        if (archiveId == null) throw bad("缺少archive_id");
        access.requireWrite(archiveId);
        Long responsible = optionalId(body,"responsible_user_id"); // 可空责任人。
        if (responsible != null) db.required("select id from ims_user where id=?",responsible);
        long id = db.insert("damage_record",fields("archive_id",archiveId,"reported_by",actor.id(),"damage_type",text(body,"damage_type",50,false),
                "damage_desc",text(body,"damage_desc",20000,true),"responsible_user_id",responsible,"status","reported")); // 新登记状态固定。
        audit.record(actor.id(),"damage","report","archive",archiveId,"损坏登记ID=" + id);
        return fields("id",id,"status","reported");
    }

    /** 处理损坏并追加独立处理历史，禁止关闭后修改或倒退状态。 */
    @Transactional
    public Map<String,Object> handleDamage(long id, Map<String,Object> body, Actor actor) {
        Map<String,Object> record = db.required("select * from damage_record where id=? for update",id); // 同一损坏记录处理串行化。
        access.requireWrite(number(record.get("archive_id"),"archive_id"));
        String previous = (String)record.get("status"); // 原状态用于状态机及历史记录。
        String next = choice(body,"status","resolved",Set.of("processing","resolved","closed")); // 目标状态。
        boolean allowed = switch (previous) { // 允许登记直接解决；关闭只能在已解决之后进行。
            case "reported" -> Set.of("processing","resolved").contains(next);
            case "processing" -> Set.of("processing","resolved").contains(next);
            case "resolved" -> "closed".equals(next);
            default -> false;
        };
        if (!allowed) throw new BusinessException(409,"损坏记录不能从" + previous + "变为" + next);
        Map<String,Object> values = fields("handling_method",text(body,"handling_method",100,true),"handling_result",text(body,"handling_result",20000,true),
                "handled_by",actor.id(),"handled_at",LocalDateTime.now(),"status",next); // 本次处理摘要。
        Map<String,Object> history = new LinkedHashMap<>(values); // 独立历史保留前后状态和每次处理内容。
        history.put("damage_id",id); history.put("previous_status",previous);
        db.insert("damage_handling_record",history);
        values.put("updated_at",LocalDateTime.now()); db.update("damage_record",id,values);
        audit.record(actor.id(),"damage","handle","damage",id,previous + " -> " + next);
        return fields("id",id,"status",next);
    }

    /** 损坏记录按档案可见范围查询，不返回其他部门的名称、人员或统计总数。 */
    public Map<String,Object> damages(String status, int page, int size, Actor actor) {
        List<Object> args = new ArrayList<>(); // 状态和分页参数。
        String filter = "(" + access.predicate(actor,"a") + ")"; // 显式括号避免共享OR条件绕过状态筛选。
        if (status != null) { filter += " and r.status=?"; args.add(status); }
        long count = db.scalarLong("select count(*) from damage_record r join archive a on a.id=r.archive_id where " + filter,args.toArray()); // 可见记录总数。
        args.add(size); args.add((page-1)*size);
        List<Map<String,Object>> rows = db.list("select r.*,r.reported_at as created_at,a.archive_name,u.real_name as reporter_name from damage_record r join archive a on a.id=r.archive_id join ims_user u on u.id=r.reported_by where "
                + filter + " order by r.id desc limit ? offset ?",args.toArray()); // 兼容客户端字段。
        return fields("total",count,"items",rows);
    }

    /** 返回损坏及处理历史详情，仅授权范围内可见。 */
    public Map<String,Object> damage(long id, Actor actor) {
        Map<String,Object> row = db.required("select r.*,a.archive_name from damage_record r join archive a on a.id=r.archive_id where r.id=? and (" + access.predicate(actor,"a") + ")",id); // 详情授权，ID与数据范围必须同时满足。
        row.put("history",db.list("select h.*,u.real_name as handler_name from damage_handling_record h join ims_user u on u.id=h.handled_by where h.damage_id=? order by h.id",id));
        return row;
    }

    /** 统一加载申请档案并加行锁；调用方负责按用途检查已归档等状态。 */
    private List<Map<String,Object>> lockArchives(List<Long> ids, String permission) {
        List<Map<String,Object>> result = new ArrayList<>(); // 已锁定且已授权档案。
        for (Long id : ids) {
            access.require(id,permission);
            result.add(db.required("select * from archive where id=? and deleted_at is null for update",id));
        }
        return result;
    }

    /** 档案必须是有效已归档状态，借出、草稿和销毁均拒绝。 */
    private void requireArchived(Map<String,Object> archive) {
        if (archive.get("deleted_at") != null || !"archive".equals(archive.get("status")))
            throw new BusinessException(409,"档案「" + archive.get("archive_name") + "」不是可办理的已归档状态");
    }

    /** 有未关闭借阅的档案不得销毁，包括尚未批准和电子借阅。 */
    private void requireNoActiveBorrow(long archiveId) {
        if (db.scalarLong("select count(*) from borrow_detail d join borrow_record r on r.id=d.borrow_id where d.archive_id=? and r.status in ('pending','approved','borrowed')",archiveId) > 0)
            throw new BusinessException(409,"档案存在未结束借阅，不能销毁");
    }

    /** 借出/归还的办理人须具备每份档案的写入范围，审批指派不产生写入授权。 */
    private void requireBorrowScope(long id, Actor actor) {
        for (Map<String,Object> row : db.list("select archive_id from borrow_detail where borrow_id=? order by archive_id",id))
            access.require(number(row.get("archive_id"),"archive_id"),"borrow.approve");
    }

    /** 固定业务类型下构造筛选表达式，用户参数一律通过占位符绑定。 */
    private String businessFilter(String type, String status, Long applicant, Actor actor, List<Object> args) {
        String result = businessVisible(type,actor); // 本人或办理人且全批次范围可见。
        if (status != null) { result += " and r.status=?"; args.add(status); }
        if (applicant != null) { result += " and r.applicant_user_id=?"; args.add(applicant); }
        return result;
    }

    /** 业务办理人可查看范围内待处理单据；一般申请人只能看本人或所参与的流程。 */
    private String businessVisible(String type,Actor actor) {
        boolean operator = actor.has(type + ".approve") || "destroy".equals(type) && actor.has("destroy.execute"); // 功能权限不扩大部门范围。
        return operator ? "(" + approval.scopePredicate(actor,"f") + ")" : approval.visiblePredicate(actor,"f");
    }
}
