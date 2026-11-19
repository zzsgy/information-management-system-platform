package com.company.platform.operations;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** IT 服务配置入口，写操作复用密码再验证、版本、幂等和审计。 */
@RestController
@RequestMapping("/api/v1/admin/operations/service-configurations")
public class ServiceAdministrationController {
    private final ServiceAdministration service;
    private final Commands commands;
    public ServiceAdministrationController(ServiceAdministration service,Commands commands){this.service=service;this.commands=commands;}
    @GetMapping public Object list(HttpServletRequest r){return ok(service.list(actor(r)),r);}
    @PutMapping("/{id}") public Object save(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest r){
        var ctx=new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),r.getHeader("X-Expected-Version")==null?null:Inputs.integer(r.getHeader("X-Expected-Version"),0,1,Integer.MAX_VALUE));
        return ok(commands.run(ctx,body,"SERVICE_BINDING",id,"CONFIGURE_SERVICE",()->service.authorize(ctx.actor()),()->service.save(ctx,id,body),key->service.get(ctx.actor(),key)),r);
    }
    @PostMapping("/check") public Object check(HttpServletRequest r){return ok(service.check(actor(r),ApiController.requestId(r)),r);}
    private Actor actor(HttpServletRequest r){return (Actor)r.getAttribute(HttpBoundary.ACTOR);}
    private Object ok(Object data,HttpServletRequest r){return ApiController.envelope("OK","操作成功",data,ApiController.requestId(r));}
}
