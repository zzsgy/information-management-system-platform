package com.company.platform.archive;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

/** 电脑端实物借还Service API；每次写入重验身份、职责、批准、占用和保护事实。 */
@RestController @RequestMapping("/api/v1/archive")
public class PhysicalController {
    /** 台账、实际借还和纸质审批分别承担领域职责。 */
    private final PhysicalItems items;private final PhysicalLoans loans;private final PaperBorrowing paper;
    public PhysicalController(PhysicalItems items,PhysicalLoans loans,PaperBorrowing paper){this.items=items;this.loans=loans;this.paper=paper;}
    /** 已批准可检索的盒件原件，位置和完整编号仅向实际维护范围投影。 */
    @GetMapping("/physical-items") public Map<String,Object> items(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(items.list(actor(r),query),r);}
    /** 原件借出期间拒绝位置变更；版本号和本人再验证均必须匹配。 */
    @PatchMapping("/physical-items/{id}/location") public Map<String,Object> location(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(items.location(context(r),Inputs.uuid(id),body),r);}
    /** 申请人或所属部门经办人读取固定交付范围，批准不表示已借出。 */
    @GetMapping("/physical-delivery-requests") public Map<String,Object> requests(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(loans.requests(actor(r),query),r);}
    /** 实际交付建立原件占用；返回待补批时可沿原操作号继续，不能伪造完成。 */
    @PostMapping("/borrow-requests/{id}/deliveries") public Map<String,Object> deliver(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(loans.deliver(context(r),Inputs.uuid(id),body),r);}
    /** 个人或职责范围列表，分页上限100，逾期与待复核始终保持占用。 */
    @GetMapping("/physical-loans") public Map<String,Object> list(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(loans.list(actor(r),query),r);}
    /** 仅本人或有范围职责的经办人可查借出与核验历史，其他人知道标识仍被拒绝。 */
    @GetMapping("/physical-loans/{id}") public Map<String,Object> detail(@PathVariable String id,HttpServletRequest r){return ok(loans.detail(actor(r),Inputs.uuid(id)),r);}
    /** 借阅人本人声明归还，不能直接改成已归还。 */
    @PostMapping("/physical-loans/{id}/return-requests") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> returnRequest(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(loans.requestReturn(context(r),Inputs.uuid(id),body),r);}
    /** 非借阅人本人且有复核能力的档案超级管理员确认实收或记录异常。 */
    @PostMapping("/physical-loans/{id}/return-reviews") public Map<String,Object> review(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(loans.review(context(r),Inputs.uuid(id),body),r);}
    /** 原件续借保留原实际借出和占用，未批准或驳回不修改原截止。 */
    @PostMapping("/physical-loans/{id}/renewals") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> renew(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(paper.renew(context(r),Inputs.uuid(id),body),r);}
    /** 站内实物事项，不向其他员工公开原件归还或催还记录。 */
    @GetMapping("/physical-notifications") public Map<String,Object> notices(HttpServletRequest r){return ok(loans.notices(actor(r)),r);}
    /** 只接受HTTPS边界已认证的会话，不信任表单中的操作者或角色。 */
    private Actor actor(HttpServletRequest r){if(r.getAttribute(HttpBoundary.ACTOR) instanceof Actor actor)return actor;throw ApiFault.session();}
    /** 操作号标识业务重试，版本号拒绝覆盖晚到的实际借还变更。 */
    private Commands.Context context(HttpServletRequest r){String version=r.getHeader("X-Expected-Version");return new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),version==null?null:Inputs.integer(version,0,1,Integer.MAX_VALUE));}
    /** 统一携带本次请求标识，便于对照命令回执和审计。 */
    private Map<String,Object> ok(Object value,HttpServletRequest r){return ApiController.envelope("OK","操作成功",value,ApiController.requestId(r));}
}
