package com.company.platform.operations;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 电脑端业务通知与投递运维API；来源引用不能代替业务授权，消息不含档案正文或题名。 */
@RestController @RequestMapping("/api/v1")
public class NotificationController {
    /** 只注入真实事务服务，控制器不维护第二份状态。 */
    private final BusinessNotifications notifications;
    public NotificationController(BusinessNotifications notifications){this.notifications=notifications;}
    /** 档案员工分页查询本人当前有效通知，最多100条，不允许指定其他接收人。 */
    @GetMapping("/portal/business-notifications") public Map<String,Object> list(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(notifications.list(actor(request),query),request);}
    /** 本人重复确认已读保留首次时间，不推进流程或延长借阅；越权返回404。 */
    @PostMapping("/portal/business-notifications/{id}/read") public Map<String,Object> read(@PathVariable String id,HttpServletRequest request){return ok(notifications.read(actor(request),Inputs.uuid(id)),request);}
    /** 仅有配置权限的档案超级管理员查看投递队列，口令、消息正文和本机路径均不返回。 */
    @GetMapping("/admin/archive/notification-deliveries") public Map<String,Object> queue(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(notifications.queue(actor(request),query),request);}
    /** 人工重试必须携带原版本、稳定操作号、实际原因和绑定本动作的本人再验证凭据。 */
    @PostMapping("/admin/archive/notification-deliveries/{id}/retry") public Map<String,Object> retry(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request){String version=request.getHeader("X-Expected-Version");var context=new Commands.Context(actor(request),ApiController.requestId(request),Inputs.uuid(request.getHeader("X-Operation-Id")),request.getMethod()+" "+request.getRequestURI(),request.getHeader("X-Reauth-Token"),version==null?null:Inputs.integer(version,0,1,Integer.MAX_VALUE));return ok(notifications.retry(context,Inputs.uuid(id),body),request);}
    /** 主体必须来自公共HTTPS会话边界。 */
    private Actor actor(HttpServletRequest request){if(request.getAttribute(HttpBoundary.ACTOR) instanceof Actor actor)return actor;throw ApiFault.session();}
    /** 统一返回请求标识以核对敏感重试记录。 */
    private Map<String,Object> ok(Object value,HttpServletRequest request){return ApiController.envelope("OK","操作成功",value,ApiController.requestId(request));}
}
