package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.workflow.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 档案与固定流程Service API；业务身份均来自HTTPS边界，浏览器不能提交审批人或扫描结论。 */
@RestController @RequestMapping("/api/v1")
public class ArchiveController {
    /** 目录、版本授权、借阅、工作流和个人水印阅读分开承担各自职责。 */
    private final ArchiveCatalogService catalog;private final ArchiveAuthority authority;private final BorrowService borrow;private final WorkflowEngine workflow;private final ArchivePolicyService policies;private final ArchiveReadingService reading;private final OrganizationDuties duties;private final Sql db;
    private final ArchiveChecklists checklists;
    private final ArchiveFileTypes fileTypes;
    private final ArchiveBorrowDirectory borrowDirectory;
    public ArchiveController(ArchiveCatalogService catalog,ArchiveAuthority authority,BorrowService borrow,WorkflowEngine workflow,ArchivePolicyService policies,ArchiveReadingService reading,OrganizationDuties duties,Sql db,ArchiveChecklists checklists,ArchiveFileTypes fileTypes,ArchiveBorrowDirectory borrowDirectory) {this.catalog=catalog;this.authority=authority;this.borrow=borrow;this.workflow=workflow;this.policies=policies;this.reading=reading;this.duties=duties;this.db=db;this.checklists=checklists;this.fileTypes=fileTypes;this.borrowDirectory=borrowDirectory;}
    /** 员工查询获准的档案盒题名及可借载体。 */
    @GetMapping("/archive/borrowable-boxes")
    public Map<String,Object> borrowableBoxes(@RequestParam Map<String,String> query,HttpServletRequest r) {
        return ok(borrowDirectory.boxes(actor(r),query),r);
    }
    /** 选择当前可申请的载体，写入时再次按原流程核对权限与版本。 */
    @GetMapping("/archive/borrow-options")
    public Map<String,Object> borrowOptions(@RequestParam String objectType,@RequestParam UUID objectId,HttpServletRequest r) {
        return ok(borrowDirectory.options(actor(r),Inputs.choice(objectType,"BOX","RECORD"),objectId),r);
    }
    /** IT 管理员读取文件类型配置，不需要档案业务角色。 */
    @GetMapping("/admin/archive/file-types")
    public Map<String,Object> fileTypes(HttpServletRequest r) {
        return ok(fileTypes.options(actor(r)),r);
    }
    /** IT 管理员经本人再验证追加唯一的文件类型名称。 */
    @PostMapping("/admin/archive/file-types") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addFileType(@RequestBody Map<String,Object> body,HttpServletRequest r) {
        return ok(fileTypes.add(context(r),body),r);
    }
    /** 档案列表先按字段授权过滤、匹配再分页；无权摘要不参与关键词命中。 */
    @GetMapping("/archive/records") public Map<String,Object> records(@RequestParam Map<String,String> query,HttpServletRequest r) {return ok(catalog.records(actor(r),query),r);}
    /** 目录管理选项独立于账号管理接口，不借业务登记开放人员全字段。 */
    @GetMapping("/archive/catalog-options") public Map<String,Object> options(HttpServletRequest r) {return ok(catalog.options(actor(r)),r);}
    @GetMapping("/archive/owners") public Map<String,Object> owners(@RequestParam String departmentId,@RequestParam(defaultValue="") String keyword,HttpServletRequest r) {return ok(catalog.owners(actor(r),Inputs.uuid(departmentId),keyword),r);}
    /** 仅提供公司部门名称供业务申请选择受众，不提供组织管理字段。 */
    @GetMapping("/archive/department-options") public Map<String,Object> departmentOptions(HttpServletRequest r) {actor(r).require("ARCHIVE.ACCESS");return ok(Sql.map("items",db.rows("SELECT id,name,code FROM iam.department WHERE status='ACTIVE' ORDER BY code LIMIT 200")),r);}
    /** 详情由服务端决定MANAGE/METADATA/TITLE_ONLY表示，不能用投影参数扩大字段。 */
    @GetMapping("/archive/records/{id}") public Map<String,Object> record(@PathVariable String id,HttpServletRequest r) {return ok(authority.detail(actor(r),Inputs.uuid(id)),r);}
    /** 维护范围内按台账字段筛选后分页，每页最多100条，盒内容不继承阅读权。 */
    @GetMapping("/archive/boxes") public Map<String,Object> boxes(@RequestParam Map<String,String> q,HttpServletRequest r) {return ok(catalog.boxes(actor(r),q),r);}
    /** 目录维护人标记原台账补录进度，归档状态与历史修订不随标记修改。 */
    @PutMapping("/archive/boxes/{id}/legacy-ledger-status")
    public Map<String,Object> markLegacyLedger(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest r) {
        return ok(catalog.markLegacyLedger(context(r),id,body),r);
    }
    /** 下拉类型只读取当前人员有权维护的台账，不受当前列表页或其他筛选条件影响。 */
    @GetMapping("/archive/box-filter-options") public Map<String,Object> boxFilterOptions(HttpServletRequest r) {return ok(catalog.boxFilterOptions(actor(r)),r);}
    /** 按部门授权及本人再验证登记盒；若提交清单上传会话，清单与盒同时保存。 */
    @PostMapping("/archive/boxes") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> box(@RequestBody Map<String,Object> body,HttpServletRequest r) {
        var context=context(r);
        return ok(body.containsKey("checklistUploadSessionId")?checklists.registerBox(context,body):catalog.box(context,body),r);
    }
    /** 同盒独立文件草稿，目录维护权不授予正文。 */
    @PostMapping("/archive/records") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createRecord(@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(catalog.record(context(r),body),r);}
    /** 检测通过后绑定完整PDF候选；版本和本人再验证均需匹配。 */
    @PostMapping("/archive/records/{id}/pdf-versions") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> pdf(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(catalog.bindPdf(context(r),Inputs.uuid(id),body),r);}
    /** 获权人员完成逐页人工检查后确认，不把病毒检测当作质量检查。 */
    @PostMapping("/archive/quality-checks") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> quality(@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(catalog.quality(context(r),body),r);}
    /** 提交固定候选及人工证据，超管本人登记直接归档，其余由必要审批节点发布。 */
    @PostMapping("/archive/registration-reviews") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,Object> registration(@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(catalog.submit(context(r),body),r);}
    /** 基础模板仅固定字段，不接受未经定义的扩展值。 */
    @GetMapping("/admin/archive/directory-templates") public Map<String,Object> templates(HttpServletRequest r) {return ok(catalog.templates(actor(r)),r);}
    @PostMapping("/admin/archive/directory-templates") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> template(@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(catalog.template(context(r),body),r);}
    /** 新增基础开放申请，而非未经业务审批的直接增权入口。 */
    @PostMapping("/archive/access-policy-requests") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> policy(@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(policies.propose(context(r),body),r);}
    /** 获权目录维护人按具体文件选择待审批人员名单；普通借阅人和IT不得借道检索。 */
    @GetMapping("/archive/access-policy-subjects") public Map<String,Object> policySubjects(@RequestParam String recordId,@RequestParam(defaultValue="") String keyword,HttpServletRequest r) {return ok(policies.subjects(actor(r),Inputs.uuid(recordId),keyword),r);}
    /** 当前员工本人授权摘要；其他人员需独立档案安全管理能力。 */
    @GetMapping("/archive/access-grants") public Map<String,Object> grants(@RequestParam Map<String,String> query,HttpServletRequest r) {return ok(policies.grants(actor(r),query),r);}
    /** 只撤目标来源，保留其他已批准来源及完整历史。 */
    @PostMapping("/admin/archive/access-grants/{id}/revoke") public Map<String,Object> revoke(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(policies.revoke(context(r),Inputs.uuid(id),body),r);}
    @GetMapping("/admin/archive/access-policies") public Map<String,Object> policies(@RequestParam Map<String,String> query,HttpServletRequest r) {return ok(policies.policyList(actor(r),query),r);}
    @PostMapping("/admin/archive/access-policies/{id}/revoke") public Map<String,Object> revokePolicy(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(policies.revokePolicy(context(r),Inputs.uuid(id),body),r);}
    /** 电子VIEW单份或整批申请，服务端展开盒清单并固定最高密级。 */
    @PostMapping("/archive/borrow-batches") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> borrow(@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(borrow.submit(context(r),body),r);}
    /** 原借阅人续借固定批准范围；同一操作号重放原结果，待审不提前延权。 */
    @PostMapping("/archive/borrow-batches/{id}/renewals") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> renew(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(borrow.renew(context(r),Inputs.uuid(id),body),r);}
    @GetMapping("/archive/borrow-batches/{id}") public Map<String,Object> batch(@PathVariable String id,HttpServletRequest r) {return ok(borrow.batch(actor(r),Inputs.uuid(id)),r);}
    @GetMapping("/archive/borrow-requests/{id}") public Map<String,Object> request(@PathVariable String id,HttpServletRequest r) {return ok(borrow.request(actor(r),Inputs.uuid(id)),r);}
    /** 申请人只能撤回尚未完成的本人子单，不能撤销已批准结果。 */
    @PostMapping("/archive/borrow-requests/{id}/withdraw") public Map<String,Object> withdraw(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {UUID request=Inputs.uuid(id);var row=db.required("SELECT workflow_instance_id FROM archive.borrow_request WHERE id=?",request);return ok(workflow.withdraw(context(r),(UUID)row.get("workflow_instance_id"),body),r);}
    @GetMapping("/portal/requests") public Map<String,Object> requests(@RequestParam Map<String,String> query,HttpServletRequest r) {return ok(borrow.mine(actor(r),query),r);}
    @GetMapping("/portal/tasks") public Map<String,Object> tasks(@RequestParam Map<String,String> query,HttpServletRequest r) {return ok(workflow.tasks(actor(r),query),r);}
    @GetMapping("/workflow/steps/{id}") public Map<String,Object> step(@PathVariable String id,HttpServletRequest r) {return ok(workflow.step(actor(r),Inputs.uuid(id)),r);}
    /** 技术管理视图只返回审批状态、职责和时间。 */
    @GetMapping("/workflow/progress") public Map<String,Object> progress(@RequestParam Map<String,String> query,HttpServletRequest r) {return ok(workflow.progress(actor(r),query),r);}
    @GetMapping("/workflow/attention") public Map<String,Object> attention(HttpServletRequest r) {return ok(workflow.attention(actor(r)),r);}
    @GetMapping("/workflow/steps/{id}/assignment-options") public Map<String,Object> assignmentOptions(@PathVariable String id,HttpServletRequest r) {return ok(workflow.assignmentOptions(actor(r),Inputs.uuid(id)),r);}
    /** 真实当前处理人在线决定；拒绝本人审批、重复决定及失效任职。 */
    @PostMapping("/workflow/steps/{id}/decisions") public Map<String,Object> decide(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(workflow.decide(context(r),Inputs.uuid(id),body),r);}
    /** 只可改派给该职责当前合法非申请人，历史决定不可改写。 */
    @PostMapping("/workflow/steps/{id}/reassign") public Map<String,Object> reassign(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(workflow.reassign(context(r),Inputs.uuid(id),body),r);}
    /** 撤权人员选择只面向档案安全管理者，返回最小必要姓名和工号。 */
    @GetMapping("/admin/archive/grant-subjects") public Map<String,Object> grantSubjects(@RequestParam(defaultValue="") String keyword,HttpServletRequest r) {authority.requireSuper(actor(r),"ARCHIVE.GRANTS_MANAGE");if(keyword.length()>100) throw ApiFault.invalid("检索条件过长");String match="%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";return ok(Sql.map("items",db.rows("SELECT DISTINCT u.id,u.name,u.employee_no FROM iam.user_account u JOIN archive.access_grant g ON g.user_id=u.id WHERE (u.name ILIKE ? OR u.employee_no ILIKE ?) ORDER BY u.employee_no LIMIT 100",match,match).stream().map(row->Sql.view(row,"id,name,employee_no")).toList()),r);}
    /** 普通批准版本或必要候选检查二选一，后台结果不能替代最终读取鉴权。 */
    @PostMapping("/archive/view-sessions") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,Object> createView(@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(reading.create(actor(r),body,Inputs.uuid(r.getHeader("X-Operation-Id")),ApiController.requestId(r)),r);}
    @GetMapping("/archive/view-sessions/{id}") public Map<String,Object> view(@PathVariable String id,HttpServletRequest r) {return ok(reading.manifest(actor(r),Inputs.uuid(id)),r);}
    /** 每页返回已嵌入本人水印的PNG，源PDF路径从不公开，不允许共享缓存。 */
    @GetMapping(value="/archive/view-sessions/{id}/pages/{page}",produces="image/png") public ResponseEntity<byte[]> page(@PathVariable String id,@PathVariable String page,HttpServletRequest r) {return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(reading.page(actor(r),Inputs.uuid(id),Inputs.integer(page,0,1,10000),ApiController.requestId(r)));}
    /** 组织配置查询与人员候选只给平台组织管理者，不复用公盘角色。 */
    @GetMapping("/admin/organization-duties") public Map<String,Object> organization(HttpServletRequest r) {return ok(duties.read(actor(r)),r);}
    /** 返回符合对应任职条件的组织审批候选，最多100人。 */
    @GetMapping("/admin/organization-duties/candidates") public Map<String,Object> candidates(@RequestParam Map<String,String> query,HttpServletRequest r) {return ok(duties.candidates(actor(r),query),r);}
    /** 公司总经理审批职责独立配置，不引用公盘角色作为批准来源。 */
    @PutMapping("/admin/company-duties/general-manager") public Map<String,Object> generalManager(@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(duties.generalManager(context(r),body),r);}
    @PutMapping("/admin/departments/{id}/management-duties") public Map<String,Object> duties(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(duties.department(context(r),Inputs.uuid(id),body),r);}
    /** 仅使用公共HTTPS边界已验证的会话主体。 */
    private Actor actor(HttpServletRequest r) {if(r.getAttribute(HttpBoundary.ACTOR) instanceof Actor actor) return actor;throw ApiFault.session();}
    /** 管理凭据绑定实际路由动作，版本缺失不能降级为盲写。 */
    private Commands.Context context(HttpServletRequest r) {String version=r.getHeader("X-Expected-Version");return new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),version==null?null:Inputs.integer(version,0,1,Integer.MAX_VALUE));}
    /** 包装业务结果并保留当前请求号，禁止直接泄露内部对象。 */
    private Map<String,Object> ok(Object value,HttpServletRequest r) {return ApiController.envelope("OK","操作成功",value,ApiController.requestId(r));}
}

