package com.company.platform;

import com.company.platform.operations.AccountMessages;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 账号自助与技术消息Service API；匿名接口只接收工号、流程号和凭据，不接受邮箱或操作者。 */
@RestController @RequestMapping("/api/v1")
public class AccountController {
    private final AccountRecovery recovery;private final AccountMessages messages;private final Commands commands;private final Sql db;
    public AccountController(AccountRecovery recovery,AccountMessages messages,Commands commands,Sql db){this.recovery=recovery;this.messages=messages;this.commands=commands;this.db=db;}
    /** 发码与重发统一返回已受理，不泄露账号状态或完整邮箱。 */
    @PostMapping("/auth/password-recovery") public Map<String,Object> request(@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(recovery.request(body,r.getRemoteAddr(),ApiController.requestId(r)),r);}
    /** 验证成功仅设置密码，不建立匿名浏览器登录；员工必须重新登录。 */
    @PostMapping("/auth/password-recovery/complete") public Map<String,Object> complete(@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(recovery.complete(body,r.getRemoteAddr(),ApiController.requestId(r)),r);}
    /** 管理重置复用既有权限、再验证、版本与幂等事务，不直接返回临时密码。 */
    @PostMapping("/admin/users/{id}/password-reset") public Map<String,Object> reset(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){UUID target=Inputs.uuid(id);var ctx=context(r);return ok(commands.run(ctx,body,"USER",target,"RESET_PASSWORD",()->ctx.actor().require("PLATFORM.USERS_MANAGE"),()->recovery.reset(ctx,target,body),key->Sql.map("objectId",key,"state","SUCCEEDED")),r);}
    /** 本人中心返回真实部门和当前角色，基础身份仍由IT维护。 */
    @GetMapping("/auth/profile") public Map<String,Object> profile(HttpServletRequest r){Actor actor=actor(r);var result=new LinkedHashMap<>(actor.profile());result.put("departmentName",db.required("SELECT name FROM iam.department WHERE id=?",actor.user().get("departmentId")).get("name"));return ok(result,r);}
    /** IT查看受限技术状态，禁止读取凭据明文或邮箱服务秘密。 */
    @GetMapping("/admin/account-messages") public Map<String,Object> queue(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(messages.queue(actor(r),query),r);}
    /** 明确核对原因后重试，稳定操作号避免重复领取；口令到期后不得重新发送。 */
    @PostMapping("/admin/account-messages/{id}/retry") public Map<String,Object> retry(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){UUID target=Inputs.uuid(id);var ctx=context(r);return ok(commands.run(ctx,body,"NOTIFICATION_DELIVERY",target,"RETRY_ACCOUNT_MESSAGE",()->ctx.actor().require("PLATFORM.USERS_MANAGE"),()->messages.retry(ctx,target,body),key->Sql.map("objectId",key,"state",db.required("SELECT state FROM ops.account_message WHERE id=?",key).get("state"))),r);}
    private Actor actor(HttpServletRequest r){return (Actor)r.getAttribute(HttpBoundary.ACTOR);}
    private Commands.Context context(HttpServletRequest r){return new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),r.getHeader("X-Expected-Version")==null?null:Inputs.integer(r.getHeader("X-Expected-Version"),0,1,Integer.MAX_VALUE));}
    private Map<String,Object> ok(Object value,HttpServletRequest r){return ApiController.envelope("OK","操作成功",value,ApiController.requestId(r));}
}
