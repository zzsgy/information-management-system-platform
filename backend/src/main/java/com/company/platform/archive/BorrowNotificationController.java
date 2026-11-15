package com.company.platform.archive;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 站内提醒Service API，只允许当前员工读取本人最小必要通知，通知链接不授予业务权限。 */
@RestController @RequestMapping("/api/v1/portal/notifications")
public class BorrowNotificationController {
    /** 当前有效提醒与本人已读事务由生命周期服务承接。 */
    private final BorrowLifecycleService lifecycle;
    /** 注入实际到期服务，不在控制器维护通知或权限缓存。 */
    public BorrowNotificationController(BorrowLifecycleService lifecycle) {this.lifecycle=lifecycle;}
    /** 已登录档案员工分页读取；秘密题名不进入通知内容，旧期限提醒实时隐藏。 */
    @GetMapping public Map<String,Object> list(@RequestParam Map<String,String> query,HttpServletRequest r) {return ApiController.envelope("OK","查询成功",lifecycle.list(actor(r),query),ApiController.requestId(r));}
    /** 本人确认已读，重复提交不增加通知、不改变截止时间；他人标识返回404。 */
    @PostMapping("/{id}/read") public Map<String,Object> read(@PathVariable String id,HttpServletRequest r) {return ApiController.envelope("OK","操作成功",lifecycle.read(actor(r),Inputs.uuid(id)),ApiController.requestId(r));}
    /** 主体只来自公共HTTP边界，禁止以查询参数或请求正文指定其他接收人。 */
    private Actor actor(HttpServletRequest r) {if(r.getAttribute(HttpBoundary.ACTOR) instanceof Actor actor) return actor;throw ApiFault.session();}
}
