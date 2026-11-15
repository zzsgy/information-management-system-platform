package com.company.platform.workflow;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 人员交接 Service API：档案超级管理员办理档案员交接，IT 办理批准后的人员转岗。 */
@RestController @RequestMapping("/api/v1/admin")
public class PersonnelController {
    /** 业务协调器保证任职、事项与审计同事务提交。 */
    private final PersonnelContinuity continuity;
    public PersonnelController(PersonnelContinuity continuity){this.continuity=continuity;}
    /** 人员管理可选目标部门，不返回组织管理字段或关系配置。 */
    @GetMapping("/personnel-department-options") public Map<String,Object> departments(HttpServletRequest r){return ok(continuity.departmentOptions(actor(r)),r);}
    /** 最小候选资料；接任人需要在本部门有效任职资格，不能继承原人的个人授权。 */
    @GetMapping("/archive/archivist-handover-options") public Map<String,Object> options(@RequestParam(required=false) String departmentId,HttpServletRequest r){return ok(continuity.options(actor(r),Inputs.nullableUuid(departmentId)),r);}
    /** 只读影响预览，返回人员代次和未完事项摘要以防确认期间变化。 */
    @PostMapping("/archive/archivist-handovers/preview") public Map<String,Object> preview(@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(continuity.preview(actor(r),body),r);}
    /** 再验证绑定 DEPARTMENT / HANDOVER_ARCHIVIST，摘要不一致则整体拒绝。 */
    @PostMapping("/archive/archivist-handovers") public Map<String,Object> handover(@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(continuity.handover(context(r),body),r);}
    /** 交接事件保留原人与实际经办人，历史决定不修改。 */
    @GetMapping("/workflow/assignment-events") public Map<String,Object> events(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(continuity.events(actor(r),query),r);}
    /** IT 仅看转岗影响数量和未交接职责，不获取档案内容。 */
    @GetMapping("/users/{id}/department-change-preview") public Map<String,Object> transferPreview(@PathVariable String id,HttpServletRequest r){return ok(continuity.transferPreview(actor(r),Inputs.uuid(id)),r);}
    /** 再验证绑定 USER / CHANGE_DEPARTMENT，并要求用户版本；旧个人授权和会话原子失效。 */
    @PostMapping("/users/{id}/department-changes") public Map<String,Object> transfer(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(continuity.transfer(context(r),Inputs.uuid(id),body),r);}
    /** 身份只来自 HTTPS 会话边界。 */
    private Actor actor(HttpServletRequest r){if(r.getAttribute(HttpBoundary.ACTOR) instanceof Actor a)return a;throw ApiFault.session();}
    /** 幂等操作号与预期版本阻止重复交接和覆盖他人的最新配置。 */
    private Commands.Context context(HttpServletRequest r){String version=r.getHeader("X-Expected-Version");return new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),version==null?null:Inputs.integer(version,0,1,Integer.MAX_VALUE));}
    /** 包装实际事务结果并保留请求追踪号。 */
    private Map<String,Object> ok(Object value,HttpServletRequest r){return ApiController.envelope("OK","操作成功",value,ApiController.requestId(r));}
}
