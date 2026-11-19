package com.company.platform;

import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

/** V1.1 第一批 Service API；控制器只处理契约和权限，事务及业务规则由领域服务执行。 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {
    /** 创建操作绑定稳定集合标识，不能把再验证凭据从创建用户换用到创建部门。 */
    public static final UUID COLLECTION=UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** 共享服务通过构造器注入，后续业务模块使用相同身份契约。 */
    private final IdentityService identity;
    private final DirectoryService directory;
    private final Commands commands;
    private final Sql db;
    /** 首页内容与实时事项聚合，权限由各服务独立核验。 */
    private final com.company.platform.portal.PortalContent portalContent;
    private final com.company.platform.portal.PortalWork portalWork;
    /** 借阅到期和站内通知摘要从当前有效来源派生，不依赖静态示例数。 */
    private final com.company.platform.archive.BorrowLifecycleService lifecycle;
    /** 显式注入身份、组织及事务命令入口，便于后续模块复用相同契约。 */
    public ApiController(IdentityService identity,DirectoryService directory,Commands commands,Sql db,com.company.platform.archive.BorrowLifecycleService lifecycle,com.company.platform.portal.PortalContent portalContent,com.company.platform.portal.PortalWork portalWork) { this.portalContent=portalContent;this.portalWork=portalWork; this.identity=identity; this.directory=directory; this.commands=commands; this.db=db;this.lifecycle=lifecycle; }
    /** API-IAM-001：匿名工号登录，统一凭据失败语义；只在 HttpOnly Secure Cookie 交付会话。 */
    @PostMapping("/auth/login")
    public Map<String,Object> login(@RequestBody Map<String,Object> body,HttpServletRequest request,HttpServletResponse response) {
        var data=identity.login(body,requestId(request),request.getRemoteAddr()); String token=(String)data.remove("token");
        response.addHeader("Set-Cookie",cookie(token,43200)); return ok(data,request);
    }
    /** API-IAM-002：幂等退出；清除服务端会话及当前浏览器 Cookie。 */
    @PostMapping("/auth/logout")
    public Map<String,Object> logout(HttpServletRequest request,HttpServletResponse response) {
        identity.logout(HttpBoundary.cookie(request),requestId(request)); response.addHeader("Set-Cookie",cookie("",0));
        return ok(Sql.map("state","SUCCEEDED","operationId",requestId(request)),request);
    }
    /** API-IAM-003：仅返回本人资料，不因为资料轮询延长空闲期限。 */
    @GetMapping("/auth/me") public Map<String,Object> me(HttpServletRequest r) { return ok(actor(r).profile(),r); }
    /** API-IAM-004：临时会话只可设置本人密码，成功必须重新登录。 */
    @PutMapping("/auth/initial-password") public Map<String,Object> initialPassword(@RequestBody Map<String,Object> body,HttpServletRequest r,HttpServletResponse response) { var data=identity.password(actor(r),body,true,requestId(r)); response.addHeader("Set-Cookie",cookie("",0)); return ok(data,r); }
    /** API-IAM-005：验证本人旧密码；新密码至少八位且包含英文字母和数字；修改后立即使旧会话失效。 */
    @PutMapping("/auth/password") public Map<String,Object> password(@RequestBody Map<String,Object> body,HttpServletRequest r,HttpServletResponse response) { var data=identity.password(actor(r),body,false,requestId(r)); response.addHeader("Set-Cookie",cookie("",0)); return ok(data,r); }
    /** API-IAM-008：一次性再验证绑定本次对象和动作，失败采用相同试密保护。 */
    @PostMapping("/auth/reauthentications") public Map<String,Object> reauth(@RequestBody Map<String,Object> body,HttpServletRequest r) { return ok(identity.reauthenticate(actor(r),body,requestId(r)),r); }
    /** API-IAM-009：管理全字段与授权选择视图分离，数据库分页上限 100。 */
    @GetMapping("/admin/users") public Map<String,Object> users(@RequestParam Map<String,String> query,HttpServletRequest r) { return ok(directory.users(actor(r),query),r); }
    /** API-IAM-010：IT 管理详情，不包含密码、验证码或会话令牌。 */
    @GetMapping("/admin/users/{id}") public Map<String,Object> user(@PathVariable String id,HttpServletRequest r) { actor(r).require("PLATFORM.USERS_MANAGE"); return ok(directory.user(Inputs.uuid(id)),r); }
    /** API-IAM-011：创建账号需管理授权、稳定操作号和本人再验证；重复提交不生成第二个员工。 */
    @PostMapping("/admin/users") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createUser(@RequestBody Map<String,Object> body,HttpServletRequest r) {
        var ctx=context(r); return ok(commands.run(ctx,body,"COLLECTION",COLLECTION,"CREATE_USER",()->ctx.actor().require("PLATFORM.USERS_MANAGE"),()->directory.createUser(ctx,body),directory::user),r);
    }
    /** API-IAM-012：IT 可改工号、姓名、部门、邮箱；部门变更须附原因，重复工号、未交接职责或版本冲突返回 409。 */
    @PatchMapping("/admin/users/{id}")
    public Map<String,Object> updateUser(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {
        var ctx=context(r); UUID user=Inputs.uuid(id); return ok(commands.run(ctx,body,"USER",user,"UPDATE_USER",()->ctx.actor().require("PLATFORM.USERS_MANAGE"),()->directory.updateUser(ctx,user,body),directory::user),r);
    }
    /** API-IAM-014：启停不物理删除；停用回收角色并使旧会话立即失效。 */
    @PutMapping("/admin/users/{id}/status")
    public Map<String,Object> status(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {
        var ctx=context(r); UUID user=Inputs.uuid(id); return ok(commands.run(ctx,body,"USER",user,"USER_STATUS",()->ctx.actor().require("PLATFORM.USERS_MANAGE"),()->directory.status(ctx,user,body),key->Commands.result(ctx,key,((Number)directory.user(key).get("rowVersion")).intValue())),r);
    }
    /** API-IAM-015：只终止指定账号会话，不删除历史；关键操作需本人再验证。 */
    @DeleteMapping("/admin/users/{id}/session")
    public Map<String,Object> revoke(@PathVariable String id,HttpServletRequest r) {
        var ctx=context(r); UUID user=Inputs.uuid(id); String reason=Inputs.text(Sql.map("reason",r.getHeader("X-Change-Reason")),"reason",1000);
        return ok(commands.run(ctx,Sql.map("reason",reason),"USER",user,"REVOKE_SESSION",()->ctx.actor().require("PLATFORM.USERS_MANAGE"),()-> {
            var row=db.required("SELECT * FROM iam.user_account WHERE id=? FOR UPDATE",user); Commands.version(ctx,row);
            identity.invalidate(user,"SESSION_REVOKED",ctx.operation()); return Commands.result(ctx,user,((Number)row.get("row_version")).intValue());
        },key->Commands.result(ctx,key,((Number)directory.user(key).get("rowVersion")).intValue())),r);
    }
    /** API-IAM-017：按权限返回部门管理树数据或最小选择对象。 */
    @GetMapping("/admin/departments") public Map<String,Object> departments(@RequestParam Map<String,String> query,HttpServletRequest r) { return ok(directory.departments(actor(r),query),r); }
    /** API-IAM-018：创建部门仅改变组织，不隐含授予员工业务权限。 */
    @PostMapping("/admin/departments") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createDepartment(@RequestBody Map<String,Object> body,HttpServletRequest r) {
        var ctx=context(r); return ok(commands.run(ctx,body,"COLLECTION",COLLECTION,"CREATE_DEPARTMENT",()->ctx.actor().require("PLATFORM.DEPARTMENTS_MANAGE"),()->directory.createDepartment(body),directory::department),r);
    }
    /** API-IAM-019：递归核验层级循环、停用引用和读取版本。 */
    @PatchMapping("/admin/departments/{id}")
    public Map<String,Object> updateDepartment(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {
        var ctx=context(r); UUID department=Inputs.uuid(id); return ok(commands.run(ctx,body,"DEPARTMENT",department,"UPDATE_DEPARTMENT",()->ctx.actor().require("PLATFORM.DEPARTMENTS_MANAGE"),()->directory.updateDepartment(ctx,department,body),directory::department),r);
    }
    /** API-IAM-020：部门详情仅组织管理员可见。 */
    @GetMapping("/admin/departments/{id}") public Map<String,Object> department(@PathVariable String id,HttpServletRequest r) { actor(r).require("PLATFORM.DEPARTMENTS_MANAGE"); return ok(directory.department(Inputs.uuid(id)),r); }
    /** API-IAM-022：角色按模块读取，不能借平台管理员身份读取档案配置。 */
    @GetMapping("/admin/roles") public Map<String,Object> roles(@RequestParam Map<String,String> query,HttpServletRequest r) { return ok(directory.roles(actor(r),query),r); }
    /** API-IAM-023：读取获准模块角色详情。 */
    @GetMapping("/admin/roles/{id}") public Map<String,Object> role(@PathVariable String id,HttpServletRequest r) { return ok(directory.role(actor(r),Inputs.uuid(id)),r); }
    /** API-IAM-024：改角色权限需再验证和配置版本；使全部关联账号原会话失效。 */
    @PutMapping("/admin/roles/{id}/permissions")
    public Map<String,Object> rolePermissions(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {
        var ctx=context(r); UUID role=Inputs.uuid(id);
        commands.run(ctx,body,"ROLE",role,"ROLE_PERMISSIONS",()->directory.checkModule(ctx.actor(),(String)db.required("SELECT module FROM iam.role WHERE id=?",role).get("module"),false),()->directory.rolePermissions(ctx,role,body),key->Sql.map("objectId",key));
        return ok(directory.role(ctx.actor(),role),r);
    }
    /** API-IAM-025：权限代码来自受控字典，并在查询前验证模块范围。 */
    @GetMapping("/admin/permissions") public Map<String,Object> permissions(@RequestParam Map<String,String> query,HttpServletRequest r) { return ok(directory.permissions(actor(r),query),r); }
    /** API-IAM-026：只返回当前操作者可管理模块内的任职。 */
    @GetMapping("/admin/users/{id}/roles") public Map<String,Object> userRoles(@PathVariable String id,HttpServletRequest r) { return ok(directory.userRoles(actor(r),Inputs.uuid(id)),r); }
    /** API-IAM-027：禁止自我增权和跨模块分配；首位档案超级管理员的初始化例外单独核验。 */
    @PutMapping("/admin/users/{id}/roles")
    public Map<String,Object> assignRoles(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {
        var ctx=context(r); UUID user=Inputs.uuid(id);
        return ok(commands.run(ctx,body,"USER",user,"ASSIGN_ROLES",()-> { if(directory.managedModules(ctx.actor()).isEmpty()) throw ApiFault.denied(); },()->directory.assignRoles(ctx,user,body),key->Commands.result(ctx,key,((Number)directory.user(key).get("rowVersion")).intValue())),r);
    }
    /** API-PORTAL-001：工作台仅接入已实现且获权的入口；后续模块不制造模拟待办。 */
    @GetMapping("/portal/home")
    public Map<String,Object> home(HttpServletRequest r) {
        Actor actor=actor(r); List<Map<String,Object>> apps=new ArrayList<>();
        if(actor.actions().contains("DRIVE.ACCESS")) apps.add(Sql.map("code","DRIVE","name","企业公盘","entry","#/drive","canManage",actor.actions().contains("DRIVE.ROLES_MANAGE")));
        if(actor.actions().contains("ARCHIVE.ACCESS")) {apps.add(Sql.map("code","ARCHIVE","name","档案中心","entry","#/archive","canManage",actor.actions().contains("ARCHIVE.CATALOG_MANAGE")));apps.add(Sql.map("code","WORKFLOW","name","流程中心","entry","#/workflow","canManage",false));}
        apps.add(Sql.map("code","MEETINGS","name","会议预约","entry","#/meetings","canManage",false));
        if(actor.actions().contains("PLATFORM.USERS_MANAGE")) apps.add(Sql.map("code","PEOPLE","name","组织与人员","entry","#/people","canManage",true));
        if(!directory.managedModules(actor).isEmpty()) apps.add(Sql.map("code","ROLES","name","角色与权限","entry","#/roles","canManage",true));
        if(actor.systemAdministrator()) apps.add(Sql.map("code","AUDIT","name","操作记录","entry","#/audit","canManage",true));
        if(actor.actions().stream().anyMatch(a->Set.of("PLATFORM.ANNOUNCEMENTS_MANAGE","PLATFORM.SITES_MANAGE","PLATFORM.FEEDBACK_MANAGE").contains(a)))apps.add(Sql.map("code","PORTAL","name","首页内容管理","entry","#/portal-admin","canManage",true));
        if(actor.systemAdministrator()&&actor.actions().contains("PLATFORM.DEPARTMENTS_MANAGE"))apps.add(Sql.map("code","SETTINGS","name","管理配置","entry","#/settings","canManage",true));
        var result=portalWork.summary(actor);result.put("profile",actor.profile());result.put("applications",apps);
        result.put("announcements",portalContent.recentAnnouncements(actor));result.put("sites",portalContent.sites(actor,false).get("items"));
        return ok(result,r);
    }
    /** 请求上下文不会从客户端 JSON 接受操作者或权限。 */
    private Commands.Context context(HttpServletRequest r) { return new Commands.Context(actor(r),requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),r.getHeader("X-Expected-Version")==null?null:Inputs.integer(r.getHeader("X-Expected-Version"),0,1,Integer.MAX_VALUE)); }
    /** 只信任本次过滤器确认的主体。 */
    private Actor actor(HttpServletRequest r) { Actor actor=(Actor)r.getAttribute(HttpBoundary.ACTOR); if(actor==null) throw ApiFault.session(); return actor; }
    /** 获取过滤器产生的实际请求号。 */
    public static UUID requestId(HttpServletRequest r) {
        Object id=r.getAttribute(HttpBoundary.REQUEST);
        if(id instanceof UUID uuid) return uuid;
        UUID generated=UUID.randomUUID();r.setAttribute(HttpBoundary.REQUEST,generated);return generated;
    }
    /** 标准成功包装；响应时间使用 UTC ISO 时间。 */
    private Map<String,Object> ok(Object data,HttpServletRequest r) { return envelope("OK","操作成功",data,requestId(r)); }
    /** 所有接口共享 V1.1 包装，前端无需兼容旧 snake_case/code 数值协议。 */
    public static Map<String,Object> envelope(String code,String message,Object data,UUID request) {
        if(data instanceof Map<?,?> task && task.get("errorCode") instanceof String errorCode) {
            var details=new LinkedHashMap<Object,Object>(task);
            details.put("errorMessage",SystemFailure.taskMessage(errorCode));
            details.put("requestId",request);
            data=details;
        }
        return Sql.map("code",code,"message",message,"data",data,"requestId",request,"timestamp",Instant.now().toString());
    }
    /** Cookie 缺省安全标志不可由客户端或普通配置降级。 */
    private String cookie(String token,int maxAge) { return ResponseCookie.from("IMS_SESSION",token).httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(maxAge).build().toString(); }
}
