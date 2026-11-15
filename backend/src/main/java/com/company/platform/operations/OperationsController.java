package com.company.platform.operations;
import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 运维API禁止提交本地路径、命令及恢复库名；高风险动作均需本人再验证。 */
@RestController @RequestMapping("/api/v1/admin/operations")
public class OperationsController {
 private final OperationsConsole service;private final Commands commands;private final ServiceHealth health;private final IdentityService identity;
 public OperationsController(OperationsConsole service,Commands commands,ServiceHealth health,IdentityService identity){this.service=service;this.commands=commands;this.health=health;this.identity=identity;}
 @GetMapping("/service-health") public Object health(HttpServletRequest r){identity.background(actor(r).sessionId()).require("PLATFORM.OPERATIONS_MANAGE");return ok(health.status(),r);}
 @GetMapping public Object overview(HttpServletRequest r){return ok(service.overview(actor(r)),r);}
 @GetMapping("/lists/{section}") public Object list(@PathVariable String section,@RequestParam Map<String,String> query,HttpServletRequest r){return ok(service.list(actor(r),section,query),r);}
 @GetMapping("/runs/{id}") public Object run(@PathVariable UUID id,HttpServletRequest r){return ok(service.run(actor(r),id),r);}
 /** 排队不等于完成；备份和恢复返回WAITING_WINDOW等待源服务停止。 */
 @PostMapping("/runs") public Object create(@RequestBody Map<String,Object> body,HttpServletRequest r){var ctx=context(r);return ok(commands.run(ctx,body,"MAINTENANCE_RUN",ApiController.COLLECTION,"CREATE_MAINTENANCE",()->ctx.actor().require("PLATFORM.OPERATIONS_MANAGE"),()->service.create(ctx,body,null),id->service.run(ctx.actor(),id)),r);}
 @PostMapping("/runs/{id}/retry") public Object retry(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest r){var ctx=context(r);return ok(commands.run(ctx,body,"MAINTENANCE_RUN",id,"RETRY_MAINTENANCE",()->ctx.actor().require("PLATFORM.OPERATIONS_MANAGE"),()->service.retry(ctx,id,body),key->service.run(ctx.actor(),key)),r);}
 @PostMapping("/runs/{id}/cancel") public Object cancel(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest r){Inputs.only(body,"reason");Inputs.text(body,"reason",1000);var ctx=context(r);return ok(commands.run(ctx,body,"MAINTENANCE_RUN",id,"CANCEL_MAINTENANCE",()->ctx.actor().require("PLATFORM.OPERATIONS_MANAGE"),()->service.cancel(ctx,id),key->service.run(ctx.actor(),key)),r);}
 @PutMapping("/backup-plan") public Object plan(@RequestBody Map<String,Object> body,HttpServletRequest r){var ctx=context(r);return ok(commands.run(ctx,body,"BACKUP_PLAN",ApiController.COLLECTION,"SAVE_BACKUP_PLAN",()->ctx.actor().require("PLATFORM.BACKUP_MANAGE"),()->service.plan(ctx,body),id->Sql.map("id",id)),r);}
 private Actor actor(HttpServletRequest r){return (Actor)r.getAttribute(HttpBoundary.ACTOR);}
 private Commands.Context context(HttpServletRequest r){return new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),r.getHeader("X-Expected-Version")==null?null:Inputs.integer(r.getHeader("X-Expected-Version"),0,1,Integer.MAX_VALUE));}
 private Object ok(Object result,HttpServletRequest r){return ApiController.envelope("OK","操作成功",result,ApiController.requestId(r));}
}
