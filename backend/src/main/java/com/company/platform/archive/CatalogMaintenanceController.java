package com.company.platform.archive;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 目录更正与保管接口：服务端重验部门范围、状态版本和一次性本人确认，重复提交复用业务回执。 */
@RestController
@RequestMapping("/api/v1")
public class CatalogMaintenanceController {
    /** 业务状态与模板配置分开服务，路由不直接修改数据库。 */
    private final CatalogMaintenance catalog;private final CatalogTemplates templates;
    public CatalogMaintenanceController(CatalogMaintenance catalog,CatalogTemplates templates){this.catalog=catalog;this.templates=templates;}
    /** 分页维护列表，普通阅档者不能借此搜索未授权扩展字段。 */
    @GetMapping("/archive/catalog/{type}") public Map<String,Object> list(@PathVariable String type,@RequestParam Map<String,String> q,HttpServletRequest r){return ok(catalog.list(actor(r),type,q),r);}
    /** 已发布及候选的不可变目录/PDF版本摘要，不以历史记录授予旧正文访问。 */
    @GetMapping("/archive/records/{id}/revisions") public Map<String,Object> recordRevisions(@PathVariable String id,@RequestParam Map<String,String> q,HttpServletRequest r){return ok(catalog.revisions(actor(r),"RECORD",Inputs.uuid(id),q),r);}
    /** 盒修订版本独立分页，普通编辑另保存在更正历史。 */
    @GetMapping("/archive/boxes/{id}/revisions") public Map<String,Object> boxRevisions(@PathVariable String id,@RequestParam Map<String,String> q,HttpServletRequest r){return ok(catalog.revisions(actor(r),"BOX",Inputs.uuid(id),q),r);}
    /** 获取维护详情；无本部门维护权返回拒绝，不含原件路径。 */
    @GetMapping("/archive/catalog/{type}/{id}") public Map<String,Object> detail(@PathVariable String type,@PathVariable String id,HttpServletRequest r){return ok(catalog.detail(actor(r),type,Inputs.uuid(id)),r);}
    /** 分页查询更正与撤下历史，查询仍以当前范围为准。 */
    @GetMapping("/archive/catalog/{type}/{id}/changes") public Map<String,Object> history(@PathVariable String type,@PathVariable String id,@RequestParam Map<String,String> q,HttpServletRequest r){return ok(catalog.history(actor(r),type,Inputs.uuid(id),q),r);}
    /** 新增候选或普通编辑；必须给出原版本和更正原因，关键字段不直接生效。 */
    @PutMapping("/archive/catalog/{type}/{id}/draft") public Map<String,Object> updateDraft(@PathVariable String type,@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(catalog.updateDraft(context(r),type,Inputs.uuid(id),b),r);}
    /** 撤回草稿盒及盒内草稿；已归档或在途资料仍走原流程，历史与附件原件保留。 */
    @PostMapping("/archive/catalog/{type}/{id}/delete") public Map<String,Object> deleteDraft(@PathVariable String type,@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(catalog.deleteDraft(context(r),type,Inputs.uuid(id),b),r);}
    /** 已发布资料通过固定更正候选办理。 */
    @PostMapping("/archive/catalog/{type}/{id}/changes") public Map<String,Object> create(@PathVariable String type,@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(catalog.create(context(r),type,Inputs.uuid(id),b),r);}
    /** 更正详情不向非管理人员开放，审批者从固定当前节点读取。 */
    @GetMapping("/archive/catalog-changes/{id}") public Map<String,Object> change(@PathVariable String id,HttpServletRequest r){return ok(catalog.change(actor(r),Inputs.uuid(id)),r);}
    /** 只对本人的固定PDF候选登记逐页人工确认。 */
    @PostMapping("/archive/catalog-changes/{id}/quality") public Map<String,Object> quality(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(catalog.quality(context(r),Inputs.uuid(id),b),r);}
    /** 送必要职责复核；候选和原对象失效时返回冲突，不跳过节点。 */
    @PostMapping("/archive/catalog-changes/{id}/submit") public Map<String,Object> submit(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(catalog.submit(context(r),Inputs.uuid(id),b),r);}
    /** 原申请人撤回尚未结束事项，已发生的审批和候选原件保留。 */
    @PostMapping("/archive/catalog-changes/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(catalog.cancel(context(r),Inputs.uuid(id),b),r);}
    /** 只返回有效维护范围的到期清单；接口本身不删除或撤下。 */
    @GetMapping("/archive/retention") public Map<String,Object> retention(@RequestParam Map<String,String> q,HttpServletRequest r){return ok(catalog.retention(actor(r),q),r);}
    /** 模板历史定义可读取，旧档案不因停用失去字段解释。 */
    @GetMapping("/admin/archive/directory-template-versions") public Map<String,Object> versions(HttpServletRequest r){return ok(templates.list(actor(r),true),r);}
    /** 在最新模板后追加不可变版本，禁止改写历史定义。 */
    @PostMapping("/admin/archive/directory-templates/{id}/versions") public Map<String,Object> revise(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(templates.save(context(r),Inputs.uuid(id),b),r);}
    /** 模板启停需要配置权限和并发版本，新登记不能选择停用版本。 */
    @PutMapping("/admin/archive/directory-templates/{id}/state") public Map<String,Object> state(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(templates.state(context(r),Inputs.uuid(id),b),r);}
    /** 请求身份只来自服务端会话过滤器，不接受客户端自报。 */
    private Actor actor(HttpServletRequest r){var a=(Actor)r.getAttribute(HttpBoundary.ACTOR);if(a==null)throw ApiFault.session();return a;}
    /** 管理命令绑定稳定操作号和版本，缺失字段由公共校验拒绝。 */
    private Commands.Context context(HttpServletRequest r){return new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),r.getHeader("X-Expected-Version")==null?null:Inputs.integer(r.getHeader("X-Expected-Version"),0,1,Integer.MAX_VALUE));}
    /** 标准响应保持请求追踪号，内部异常不直接返回。 */
    private Map<String,Object> ok(Object data,HttpServletRequest r){return ApiController.envelope("OK","操作成功",data,ApiController.requestId(r));}
}
