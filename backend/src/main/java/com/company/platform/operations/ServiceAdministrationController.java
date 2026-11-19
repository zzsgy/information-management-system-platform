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
    private final DeploymentSettings deployment;
    public ServiceAdministrationController(ServiceAdministration service,Commands commands,DeploymentSettings deployment){this.service=service;this.commands=commands;this.deployment=deployment;}
    /** 仅IT可下载公开安装脚本；包内不含当前数据库密码、路径或任何密钥。 */
    @GetMapping("/deployment-agent.zip") public org.springframework.http.ResponseEntity<byte[]> agent(HttpServletRequest r)throws java.io.IOException {
        service.authorize(actor(r));var output=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(output)){
            for(String file:List.of("operations-agent.cjs","operations-runner.cjs","maintenance.cjs","Install-OperationsAgent.ps1","Set-OperationsService.ps1","service-configuration.md","maintenance.template.json")){
                try(var input=getClass().getResourceAsStream("/deployment-agent/"+file)){
                    if(input==null)throw new ApiFault(503,"MAINTENANCE_PACKAGE_UNAVAILABLE","当前应用缺少执行器资源，请使用完整构建包升级");
                    zip.putNextEntry(new java.util.zip.ZipEntry(file));input.transferTo(zip);zip.closeEntry();
                }
            }
        }
        return org.springframework.http.ResponseEntity.ok().header("Content-Type","application/zip").header("Content-Disposition","attachment; filename=IMS-operations-agent.zip").body(output.toByteArray());
    }
    @GetMapping("/deployment") public Object deployment(HttpServletRequest r){return ok(deployment.list(actor(r)),r);}
    @PutMapping("/deployment/{id}") public Object configure(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest r){
        var ctx=new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),Inputs.integer(r.getHeader("X-Expected-Version"),0,1,Integer.MAX_VALUE));
        return ok(commands.run(ctx,body,"DEPLOYMENT_SETTINGS",id,"CONFIGURE_DEPLOYMENT",()->service.authorize(ctx.actor()),()->deployment.save(ctx,id,body),key->deployment.get(ctx.actor(),key)),r);
    }
    @GetMapping public Object list(HttpServletRequest r){return ok(service.list(actor(r)),r);}
    @PutMapping("/{id}") public Object save(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest r){
        var ctx=new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),r.getHeader("X-Expected-Version")==null?null:Inputs.integer(r.getHeader("X-Expected-Version"),0,1,Integer.MAX_VALUE));
        return ok(commands.run(ctx,body,"SERVICE_BINDING",id,"CONFIGURE_SERVICE",()->service.authorize(ctx.actor()),()->service.save(ctx,id,body),key->service.get(ctx.actor(),key)),r);
    }
    @PostMapping("/check") public Object check(HttpServletRequest r){return ok(service.check(actor(r),ApiController.requestId(r)),r);}
    private Actor actor(HttpServletRequest r){return (Actor)r.getAttribute(HttpBoundary.ACTOR);}
    private Object ok(Object data,HttpServletRequest r){return ApiController.envelope("OK","操作成功",data,ApiController.requestId(r));}
}
