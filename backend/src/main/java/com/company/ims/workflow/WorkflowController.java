package com.company.ims.workflow;

import com.company.ims.identity.*;
import com.company.ims.shared.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.company.ims.workflow.WorkflowInput.*;

/** 保持PySide6原有snake_case接口契约，并在每个入口显式检查功能权限。 */
@RestController
@RequestMapping("/api")
public class WorkflowController {
    /** 审批模板、待办和流程动作服务。 */
    private final ApprovalService approval;
    /** 借阅、销毁和损坏状态机服务。 */
    private final BusinessService business;

    /** 注入审批与业务服务，控制器不直接操作数据库。 */
    public WorkflowController(ApprovalService approval, BusinessService business) {
        this.approval=approval; this.business=business;
    }

    /** 查询模板列表；仅具有模板维护权限的账号可见。 */
    @GetMapping("/approval/templates")
    public Object templates() { PermissionGuard.require("approval.template.manage"); return ApiResponse.ok(approval.templates()); }

    /** 查询模板及节点定义。 */
    @GetMapping("/approval/templates/{id}")
    public Object template(@PathVariable long id) { PermissionGuard.require("approval.template.manage"); return ApiResponse.ok(approval.template(id)); }

    /** 创建审批模板，body只允许写入服务定义的字段。 */
    @PostMapping("/approval/templates")
    public Object createTemplate(@RequestBody Map<String,Object> body) {
        Actor actor = PermissionGuard.require("approval.template.manage"); // 当前已授权的模板管理员。
        return ApiResponse.ok(approval.saveTemplate(null,body,actor),"模板创建成功");
    }

    /** 部分更新模板，历史审批流保留原节点与审批人。 */
    @PutMapping("/approval/templates/{id}")
    public Object updateTemplate(@PathVariable long id, @RequestBody Map<String,Object> body) {
        Actor actor = PermissionGuard.require("approval.template.manage"); // 操作审计身份。
        return ApiResponse.ok(approval.saveTemplate(id,body,actor),"模板更新成功");
    }

    /** 删除模板定义，不删除历史流程记录。 */
    @DeleteMapping("/approval/templates/{id}")
    public Object deleteTemplate(@PathVariable long id) {
        Actor actor = PermissionGuard.require("approval.template.manage"); // 有删除模板权限的用户。
        approval.deleteTemplate(id,actor); return ApiResponse.ok(null,"模板已删除");
    }

    /** 返回本人当前尚未签署的待办，支持可选业务类型过滤。 */
    @GetMapping("/approval/my-tasks")
    public Object tasks(@RequestParam(required=false,name="biz_type") String bizType) {
        return ApiResponse.ok(approval.tasks(bizType,PermissionGuard.require("approval.todo.view")));
    }

    /** 返回有权查看的审批流程看板，限制最多200条。 */
    @GetMapping("/approval/flows")
    public Object flows(@RequestParam(required=false,name="biz_type") String bizType,@RequestParam(required=false) String status) {
        return ApiResponse.ok(approval.flows(bizType,status,PermissionGuard.require("approval.todo.view")));
    }

    /** 按业务单查询审批详情，流程不存在时返回空数据。 */
    @GetMapping("/approval/flow-by-biz")
    public Object flowByBusiness(@RequestParam(name="biz_type") String bizType,@RequestParam(name="biz_id") long bizId) {
        return ApiResponse.ok(approval.byBusiness(bizType,bizId,PermissionGuard.require("approval.todo.view")));
    }

    /** 返回流程节点和审批历史，服务层检查本人关系与全部档案范围。 */
    @GetMapping("/approval/flows/{id}")
    public Object flow(@PathVariable long id) {
        return ApiResponse.ok(approval.detail(id,PermissionGuard.require("approval.todo.view")));
    }

    /** 通用待办审批，结束时在同一事务内同步借阅或销毁单状态。 */
    @PostMapping("/approval/flows/{id}/act")
    public Object act(@PathVariable long id,@RequestBody Map<String,Object> body) {
        Actor actor = PermissionGuard.require("approval.todo.view"); // 当前节点参与者仍须由引擎验证。
        return ApiResponse.ok(approval.act(id,text(body,"action",20,true),text(body,"opinion",10000,false),actor),"审批已完成");
    }

    /** 分页查看借阅；功能权限是申请或审批任一，数据范围仍由服务收紧。 */
    @GetMapping("/borrows")
    public Object borrows(@RequestParam(required=false) String status,@RequestParam(required=false,name="applicant_id") Long applicant,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20",name="page_size") int size) {
        pagination(page,size); return ApiResponse.ok(business.borrows(status,applicant,page,size,requireAny("borrow.apply","borrow.approve")));
    }

    /** 读取单张借阅单及档案明细。 */
    @GetMapping("/borrows/{id}")
    public Object borrow(@PathVariable long id) { return ApiResponse.ok(business.borrow(id,requireAny("borrow.apply","borrow.approve"))); }

    /** 发起借阅，保存全部申请档案而非只采用第一份路由规则。 */
    @PostMapping("/borrows")
    public Object createBorrow(@RequestBody Map<String,Object> body) { return ApiResponse.ok(business.createBorrow(body,PermissionGuard.require("borrow.apply")),"借阅申请已提交"); }

    /** 兼容旧客户端借阅审批JSON请求。 */
    @PostMapping("/borrows/{id}/approve")
    public Object approveBorrow(@PathVariable long id,@RequestBody Map<String,Object> body) {
        return ApiResponse.ok(business.approveBorrow(id,bool(body,"approve"),text(body,"opinion",10000,false),PermissionGuard.require("borrow.approve")),"审批完成");
    }

    /** 新增明确借出动作，避免纸质档案获批后无借出记录直接归还。 */
    @PostMapping("/borrows/{id}/checkout")
    public Object checkout(@PathVariable long id) { return ApiResponse.ok(business.checkout(id,PermissionGuard.require("borrow.approve")),"借出完成"); }

    /** 办理归还并记录实物状态及实际归还日期。 */
    @PostMapping("/borrows/{id}/return")
    public Object returnBorrow(@PathVariable long id,@RequestBody Map<String,Object> body) { return ApiResponse.ok(business.returnBorrow(id,body,PermissionGuard.require("borrow.approve")),"归还完成"); }

    /** 分页查询销毁单，返回原申请明细以便界面精确执行。 */
    @GetMapping("/destroys")
    public Object destroys(@RequestParam(required=false) String status,@RequestParam(required=false,name="applicant_id") Long applicant,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20",name="page_size") int size) {
        pagination(page,size); return ApiResponse.ok(business.destroys(status,applicant,page,size,requireAny("destroy.apply","destroy.approve","destroy.execute")));
    }

    /** 获取单张销毁申请的完整且不可变申请范围。 */
    @GetMapping("/destroys/{id}")
    public Object destroy(@PathVariable long id) { return ApiResponse.ok(business.destroy(id,requireAny("destroy.apply","destroy.approve","destroy.execute"))); }

    /** 发起销毁申请，不会自动销毁或跳过缺失审批人。 */
    @PostMapping("/destroys")
    public Object createDestroy(@RequestBody Map<String,Object> body) { return ApiResponse.ok(business.createDestroy(body,PermissionGuard.require("destroy.apply")),"销毁申请已提交"); }

    /** 保留旧客户端使用查询参数approve/opinion的销毁审批调用形式。 */
    @PostMapping("/destroys/{id}/approve")
    public Object approveDestroy(@PathVariable long id,@RequestParam boolean approve,@RequestParam(required=false) String opinion) {
        return ApiResponse.ok(business.approveDestroy(id,approve,opinion,PermissionGuard.require("destroy.approve")),"审批完成");
    }

    /** 执行销毁时强制核对申请明细并记录见证人、方法和批次。 */
    @PostMapping("/destroys/{id}/execute")
    public Object executeDestroy(@PathVariable long id,@RequestBody Map<String,Object> body) { return ApiResponse.ok(business.executeDestroy(id,body,PermissionGuard.require("destroy.execute")),"销毁执行完成，已留痕"); }

    /** 按可见档案范围分页查询损坏记录。 */
    @GetMapping("/damages")
    public Object damages(@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20",name="page_size") int size) {
        pagination(page,size); return ApiResponse.ok(business.damages(status,page,size,requireAny("archive.read","archive.write")));
    }

    /** 查询损坏详情和每一次处理历史。 */
    @GetMapping("/damages/{id}")
    public Object damage(@PathVariable long id) { return ApiResponse.ok(business.damage(id,requireAny("archive.read","archive.write"))); }

    /** 登记损坏，必须能够写入该档案的数据范围。 */
    @PostMapping("/damages")
    public Object createDamage(@RequestBody Map<String,Object> body) { return ApiResponse.ok(business.createDamage(body,PermissionGuard.require("archive.write")),"损坏已登记"); }

    /** 处理损坏并追加历史，不覆盖此前处理事实。 */
    @PostMapping("/damages/{id}/handle")
    public Object handleDamage(@PathVariable long id,@RequestBody Map<String,Object> body) { return ApiResponse.ok(business.handleDamage(id,body,PermissionGuard.require("archive.write")),"损坏处理完成"); }

    /** 列表只需具有任一相关操作权限；对象数据范围由服务层单独检查。 */
    private Actor requireAny(String... permissions) {
        Actor actor = CurrentUser.require(); // 当前登录主体，权限不可由请求替换。
        for (String permission : permissions) if (actor.has(permission)) return actor;
        throw new com.company.ims.shared.BusinessException(403,"没有查看此业务的权限");
    }

    /** 限制分页参数，避免负偏移、整数溢出和无限量返回。 */
    private void pagination(int page,int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 200) throw bad("页码必须为1至100000，每页1至200条");
    }
}
