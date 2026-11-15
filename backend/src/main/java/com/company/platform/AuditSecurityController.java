package com.company.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 电脑端审计、安全队列和用户意图入口；所有写操作使用当前会话防伪造保护。 */
@RestController
@RequestMapping("/api/v1")
public class AuditSecurityController {
    private final AuditSecurity service;private final Commands commands;
    public AuditSecurityController(AuditSecurity service,Commands commands){this.service=service;this.commands=commands;}
    @GetMapping("/admin/audit-events") public Object query(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(service.query(actor(r),query,ApiController.requestId(r)),r);}
    /** 仅原查询本人按需统计，重用原条件与当前权限，不接受另传筛选范围。 */
    @GetMapping("/admin/audit-events/{id}/count") public Object count(@PathVariable UUID id,HttpServletRequest r){return ok(service.count(actor(r),id),r);}
    /** 仅本人查询票据，需模块独立AUDIT_EXPORT权限和本人再验证，重复提交不会扩大条件。 */
    @PostMapping("/admin/audit-exports/{id}") public Object prepare(@PathVariable UUID id,HttpServletRequest r){var ctx=context(r);return ok(commands.run(ctx,Map.of(),"AUDIT_EXPORT",id,"EXPORT_AUDIT",()->service.authorizeExport(ctx.actor(),id),()->service.authorizeExport(ctx.actor(),id),key->service.authorizeExport(ctx.actor(),key)),r);}
    /** CSV固定名称，不拼接员工输入；响应准备记录不宣称客户端已收到或已打开文件。 */
    @GetMapping("/admin/audit-exports/{id}/file") public ResponseEntity<byte[]> file(@PathVariable UUID id,HttpServletRequest r){return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"audit-events.csv\"").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(service.export(actor(r),id,ApiController.requestId(r)));}
    @PostMapping("/me/interactions") public Object interaction(@RequestBody Map<String,Object> body,HttpServletRequest r){service.interaction(actor(r),body);return ok(Map.of(),r);}
    @GetMapping("/admin/security") public Object overview(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(service.overview(actor(r),query),r);}
    @GetMapping("/admin/security/{id}/history") public Object history(@PathVariable UUID id,HttpServletRequest r){return ok(service.history(actor(r),id),r);}
    /** 处置状态与解除限流同事务；不自动停用账号。 */
    @PostMapping("/admin/security/{id}/disposition") public Object dispose(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest r){var ctx=context(r);return ok(commands.run(ctx,body,"SECURITY_ALERT",id,"DISPOSE_SECURITY",()->ctx.actor().require("PLATFORM.SECURITY_MANAGE"),()->service.dispose(ctx,id,body),key->Sql.map("id",key)),r);}
    @PutMapping("/admin/audit-retention") public Object retention(@RequestBody Map<String,Object> body,HttpServletRequest r){var ctx=context(r);return ok(commands.run(ctx,body,"AUDIT_RETENTION",ApiController.COLLECTION,"EXTEND_RETENTION",()->ctx.actor().require("PLATFORM.SECURITY_MANAGE"),()->service.retention(ctx,body),key->Sql.map("id",key)),r);}
    private Actor actor(HttpServletRequest r){return (Actor)r.getAttribute(HttpBoundary.ACTOR);}
    private Commands.Context context(HttpServletRequest r){return new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),r.getHeader("X-Expected-Version")==null?null:Inputs.integer(r.getHeader("X-Expected-Version"),0,1,Integer.MAX_VALUE));}
    private Object ok(Object result,HttpServletRequest r){return ApiController.envelope("OK","操作成功",result,ApiController.requestId(r));}
}
