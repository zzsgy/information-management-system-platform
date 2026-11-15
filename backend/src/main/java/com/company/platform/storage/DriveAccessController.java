package com.company.platform.storage;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 公盘基础管理和个人授权接口；所有写操作需要绑定对象的本人再验证。 */
@RestController @RequestMapping("/api/v1")
public class DriveAccessController {
    /** 授权服务不接受HTTP传入的操作者身份或角色。 */
    private final DriveAccessService access;
    public DriveAccessController(DriveAccessService access) {this.access=access;}
    /** API-DRV-003：IT建立顶层，负责人只能建立委托范围内子目录。 */
    @PostMapping("/drive/folders") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> folder(@RequestBody Map<String,Object> body,HttpServletRequest request) {return ok(access.folder(context(request),body),request);}
    /** API-DRV-025：仅IT建立部门负责人角色委托，不能直接取得内容写权。 */
    @PostMapping("/admin/drive/delegations") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> delegation(@RequestBody Map<String,Object> body,HttpServletRequest request) {return ok(access.delegate(context(request),body),request);}
    /** API-DRV-027：撤销委托及其派生会话，保留授权历史。 */
    @DeleteMapping("/admin/drive/delegations/{id}")
    public Map<String,Object> revokeDelegation(@PathVariable String id,HttpServletRequest request) {return ok(access.revokeDelegation(context(request),Inputs.uuid(id)),request);}
    /** API-DRV-029：负责人按委托上界新增具体人员动作或明确拒绝。 */
    @PostMapping("/drive/resource-grants") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> grant(@RequestBody Map<String,Object> body,HttpServletRequest request) {return ok(access.grant(context(request),body),request);}
    /** API-DRV-031：预期版本和再验证均匹配才撤销。 */
    @DeleteMapping("/drive/resource-grants/{id}")
    public Map<String,Object> revokeGrant(@PathVariable String id,HttpServletRequest request) {return ok(access.revokeGrant(context(request),Inputs.uuid(id)),request);}
    /** 当前目录负责人可读取本层个人配置，最多100项；其他查询能力继续按设计扩展。 */
    @GetMapping("/drive/resource-grants")
    public Map<String,Object> grants(@RequestParam String resourceId,@RequestParam(defaultValue="FOLDER") String resourceType,HttpServletRequest request) {return ok(access.grants(actor(request),Inputs.uuid(resourceId),Inputs.choice(resourceType,"FOLDER","DOCUMENT")),request);}
    @GetMapping("/drive/access-options") public Map<String,Object> options(@RequestParam(required=false) UUID folderId,@RequestParam(defaultValue="") String keyword,HttpServletRequest request){return ok(access.options(actor(request),folderId,keyword),request);}
    @GetMapping("/admin/drive/delegations") public Map<String,Object> delegations(HttpServletRequest request){return ok(access.delegations(actor(request)),request);}
    /** 使用公共边界的可信主体和请求号，操作号只负责幂等。 */
    private Commands.Context context(HttpServletRequest request) {String version=request.getHeader("X-Expected-Version");return new Commands.Context(actor(request),ApiController.requestId(request),Inputs.uuid(request.getHeader("X-Operation-Id")),request.getMethod()+" "+request.getRequestURI(),request.getHeader("X-Reauth-Token"),version==null?null:Inputs.integer(version,0,1,Integer.MAX_VALUE));}
    /** 身份读取失败统一终止，禁止从业务JSON伪造身份。 */
    private Actor actor(HttpServletRequest request) {if(request.getAttribute(HttpBoundary.ACTOR) instanceof Actor actor) return actor;throw ApiFault.session();}
    /** 成功结果仍使用统一追踪包装。 */
    private Map<String,Object> ok(Object data,HttpServletRequest request) {return ApiController.envelope("OK","操作成功",data,ApiController.requestId(request));}
}
