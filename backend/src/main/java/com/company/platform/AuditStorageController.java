package com.company.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** IT日志存储管理接口：禁止客户端提交本地路径，归档要求本人再验证和稳定操作号。 */
@RestController
@RequestMapping("/api/v1/admin/audit-storage")
public class AuditStorageController {
    private final AuditStorage service;private final Commands commands;
    public AuditStorageController(AuditStorage service,Commands commands){this.service=service;this.commands=commands;}
    @GetMapping public Object overview(HttpServletRequest r){return ok(service.overview(actor(r)),r);}
    /** 仅采样系统目录与已配置数据库卷，失败不影响原日志的追加和读取。 */
    @PostMapping("/sample") public Object sample(HttpServletRequest r){return ok(service.sample(actor(r)),r);}
    /** 只允许已结束的冷月份；重复操作号复用原任务，副本失败保留在线原记录。 */
    @PostMapping("/archives") public Object archive(@RequestBody Map<String,Object> body,HttpServletRequest r){
        var ctx=new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),null);
        return ok(commands.run(ctx,body,"AUDIT_ARCHIVE",ApiController.COLLECTION,"ARCHIVE_LOGS",()->ctx.actor().require("PLATFORM.SECURITY_MANAGE"),()->service.queue(ctx,body),id->service.run(ctx.actor(),id)),r);
    }
    private Actor actor(HttpServletRequest r){return (Actor)r.getAttribute(HttpBoundary.ACTOR);}
    private Object ok(Object value,HttpServletRequest r){return ApiController.envelope("OK","操作成功",value,ApiController.requestId(r));}
}
