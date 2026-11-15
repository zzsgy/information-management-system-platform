package com.company.platform.intake;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 电脑端扫描管理 API；设备配置权限与档案作业权限分开判定。 */
@RestController @RequestMapping("/api/v1")
public class ScanController {
    /** 业务状态服务。 */
    private final ScanIntake scan;
    public ScanController(ScanIntake scan){this.scan=scan;}
    /** IT 的部门最小选择列表。 */
    @GetMapping("/admin/archive/scan-options") public Map<String,Object> options(HttpServletRequest r){return ok(scan.options(actor(r)),r);}
    /** IT 可配置设备，档案员只读适用设备。 */
    @GetMapping("/admin/archive/scan-devices") public Map<String,Object> devices(HttpServletRequest r){return ok(scan.devices(actor(r)),r);}
    /** 新建设备时密钥仅写受控部署目录。 */
    @PostMapping("/admin/archive/scan-devices") public Map<String,Object> createDevice(@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(scan.device(context(r),null,b),r);}
    /** 停用立即阻断新分片和回执，不删除既有记录。 */
    @PatchMapping("/admin/archive/scan-devices/{id}") public Map<String,Object> updateDevice(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(scan.device(context(r),Inputs.uuid(id),b),r);}
    /** 分页前按明确部门鉴权。 */
    @GetMapping("/archive/scan-batches") public Map<String,Object> batches(@RequestParam Map<String,String> q,HttpServletRequest r){return ok(scan.list(actor(r),Inputs.uuid(q.get("departmentId")),q),r);}
    /** 创建固定原件清单，最多 50 件。 */
    @PostMapping("/archive/scan-batches") public Map<String,Object> create(@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(scan.create(context(r),b),r);}
    /** 当前业务人员可读交接、返工与接收状态。 */
    @GetMapping("/archive/scan-batches/{id}") public Map<String,Object> detail(@PathVariable String id,HttpServletRequest r){return ok(scan.detail(actor(r),Inputs.uuid(id)),r);}
    /** 事件追加保存，不覆盖先前交接或处理意见。 */
    @PostMapping("/archive/scan-batches/{id}/events") public Map<String,Object> event(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(scan.event(context(r),Inputs.uuid(id),b),r);}
    /** 待接收或返工项可更正页数；已有 PDF 时不能改挂档案，旧上传轮次随更正失效。 */
    @PatchMapping("/archive/scan-items/{id}") public Map<String,Object> correct(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(scan.correct(context(r),Inputs.uuid(id),b),r);}
    /** 人工检查不是发布复核；全部页数和内容确认后才能通过。 */
    @PostMapping("/archive/scan-items/{id}/quality-reviews") public Map<String,Object> quality(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(scan.quality(context(r),Inputs.uuid(id),b),r);}
    /** 会话身份由过滤器确定。 */
    private Actor actor(HttpServletRequest r){var a=(Actor)r.getAttribute(HttpBoundary.ACTOR);if(a==null)throw ApiFault.session();return a;}
    /** 写操作绑定固定动作、对象版本和本人再验证。 */
    private Commands.Context context(HttpServletRequest r){String v=r.getHeader("X-Expected-Version");return new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),v==null?null:Inputs.integer(v,0,1,Integer.MAX_VALUE));}
    /** 统一结果包装。 */
    private Map<String,Object> ok(Object data,HttpServletRequest r){return ApiController.envelope("OK","操作成功",data,ApiController.requestId(r));}
}
