package com.company.platform.portal;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 员工首页Service API；登录员工读取本人范围，管理动作独立授权并复用再验证、版本和幂等。 */
@RestController
@RequestMapping("/api/v1")
public class PortalController {
    /** 内容、办理和统一管理命令入口。 */
    private final PortalContent content;
    private final PortalWork work;
    private final FeedbackImages feedbackImages;
    private final Commands commands;
    private final EmployeeTodos todos;
    private final TodoReminders reminders;
    public PortalController(PortalContent content,PortalWork work,Commands commands,EmployeeTodos todos,TodoReminders reminders,FeedbackImages feedbackImages){this.content=content;this.work=work;this.commands=commands;this.todos=todos;this.reminders=reminders;this.feedbackImages=feedbackImages;}
    /** 可见公告分页，范围、状态及有效期由当前数据库事实决定。 */
    @GetMapping("/portal/announcements") public Map<String,Object> announcements(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(content.announcements(actor(r),query,false),r);}
    /** 查询正文并重验当前可见范围，越权返回404。 */
    @GetMapping("/portal/announcements/{id}") public Map<String,Object> announcement(@PathVariable String id,HttpServletRequest r){return ok(content.announcement(actor(r),Inputs.uuid(id),false),r);}
    /** 登录员工主动打开本人可见公告，首次查看留痕，越权返回404。 */
    @PostMapping("/portal/announcements/{id}/read") public Map<String,Object> readAnnouncement(@PathVariable String id,HttpServletRequest r){return ok(content.readAnnouncement(actor(r),Inputs.uuid(id)),r);}
    /** 全部内容仅公告维护人员可见。 */
    @GetMapping("/admin/portal/announcements") public Map<String,Object> managedAnnouncements(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(content.announcements(actor(r),query,true),r);}
    /** 管理详情包含正文和部门选择，不授予其他管理权限。 */
    @GetMapping("/admin/portal/announcements/{id}") public Map<String,Object> managedAnnouncement(@PathVariable String id,HttpServletRequest r){return ok(content.announcement(actor(r),Inputs.uuid(id),true),r);}
    /** 公告范围选择使用最小部门投影。 */
    @GetMapping("/admin/portal/departments") public Map<String,Object> departments(HttpServletRequest r){return ok(content.departments(actor(r)),r);}
    /** 创建只接收声明字段，稳定操作号避免重复发布。 */
    @PostMapping("/admin/portal/announcements") public Map<String,Object> createAnnouncement(@RequestBody Map<String,Object> body,HttpServletRequest r){return saveAnnouncement(null,body,r);}
    /** 编辑、撤下及重新发布均须带当前版本，冲突返回409。 */
    @PutMapping("/admin/portal/announcements/{id}") public Map<String,Object> updateAnnouncement(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return saveAnnouncement(Inputs.uuid(id),body,r);}
    private Map<String,Object> saveAnnouncement(UUID id,Map<String,Object> body,HttpServletRequest r){var ctx=context(r);return ok(commands.run(ctx,body,"PORTAL_ANNOUNCEMENT",id==null?ApiController.COLLECTION:id,"SAVE_ANNOUNCEMENT",()->ctx.actor().require("PLATFORM.ANNOUNCEMENTS_MANAGE"),()->content.saveAnnouncement(ctx,id,body),key->content.announcement(ctx.actor(),key,true)),r);}
    /** 员工网站只返回启用配置。 */
    @GetMapping("/portal/sites") public Map<String,Object> sites(HttpServletRequest r){return ok(content.sites(actor(r),false),r);}
    /** IT读取启停和顺序配置。 */
    @GetMapping("/admin/portal/sites") public Map<String,Object> managedSites(HttpServletRequest r){return ok(content.sites(actor(r),true),r);}
    /** 新增网站限100个，拒绝脚本地址。 */
    @PostMapping("/admin/portal/sites") public Map<String,Object> createSite(@RequestBody Map<String,Object> body,HttpServletRequest r){return saveSite(null,body,r);}
    /** 网站编辑含停用，保留稳定标识。 */
    @PutMapping("/admin/portal/sites/{id}") public Map<String,Object> updateSite(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return saveSite(Inputs.uuid(id),body,r);}
    private Map<String,Object> saveSite(UUID id,Map<String,Object> body,HttpServletRequest r){var ctx=context(r);return ok(commands.run(ctx,body,"PORTAL_SITE",id==null?ApiController.COLLECTION:id,"SAVE_SITE",()->ctx.actor().require("PLATFORM.SITES_MANAGE"),()->content.saveSite(ctx,id,body),content::site),r);}
    /** IT 经本人密码再验证后删除网站，版本不符返回409。 */
    @DeleteMapping("/admin/portal/sites/{id}") public Map<String,Object> deleteSite(@PathVariable String id,HttpServletRequest r){
        var ctx=context(r);
        UUID target=Inputs.uuid(id);
        return ok(commands.run(ctx,Map.of(),"PORTAL_SITE",target,"DELETE_SITE",()->ctx.actor().require("PLATFORM.SITES_MANAGE"),
            ()->content.deleteSite(ctx,target),key->Sql.map("id",key)),r);
    }
    /** 员工反馈可附带截图，正文和图片一次保存，无需管理再验证。 */
    @PostMapping("/portal/feedback") public Map<String,Object> submit(@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(work.submit(actor(r),Inputs.uuid(r.getHeader("X-Operation-Id")),ApiController.requestId(r),body),r);}
    /** 本人反馈分页。 */
    @GetMapping("/portal/feedback") public Map<String,Object> feedbackList(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(work.feedbackList(actor(r),query,false),r);}
    /** 他人反馈统一404。 */
    @GetMapping("/portal/feedback/{id}") public Map<String,Object> feedback(@PathVariable String id,HttpServletRequest r){return ok(work.feedback(actor(r),Inputs.uuid(id),false),r);}
    /** 截图仅反馈人或反馈办理人员可见，返回经过校验的图片格式。 */
    @GetMapping("/portal/feedback/{id}/images/{message}/{image}")
    public ResponseEntity<byte[]> feedbackImage(@PathVariable String id,@PathVariable String message,@PathVariable String image,HttpServletRequest r){
        var result=feedbackImages.read(actor(r),Inputs.uuid(id),Inputs.uuid(message),Inputs.uuid(image));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.mediaType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(result.bytes());
    }
    /** IT反馈办理分页，不依赖档案身份。 */
    @GetMapping("/admin/portal/feedback") public Map<String,Object> feedbackQueue(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(work.feedbackList(actor(r),query,true),r);}
    /** IT读取员工提交的反馈和答复历史。 */
    @GetMapping("/admin/portal/feedback/{id}") public Map<String,Object> feedbackDetail(@PathVariable String id,HttpServletRequest r){return ok(work.feedback(actor(r),Inputs.uuid(id),true),r);}
    /** 答复与办理状态原子提交，重放不生成第二条消息。 */
    @PostMapping("/admin/portal/feedback/{id}/replies") public Map<String,Object> reply(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){var ctx=context(r);UUID target=Inputs.uuid(id);return ok(commands.run(ctx,body,"PORTAL_FEEDBACK",target,"REPLY_FEEDBACK",()->ctx.actor().require("PLATFORM.FEEDBACK_MANAGE"),()->work.reply(ctx,target,body),key->work.feedback(ctx.actor(),key,true)),r);}
    /** 待审批和待办理有界分页，过期原任职不计入待办。 */
    @GetMapping("/portal/dashboard-tasks") public Map<String,Object> tasks(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(work.tasks(actor(r),query),r);}
    /** 个人与分派待办的创建使用本人会话及稳定提交号。 */
    @PostMapping("/portal/todos") public Map<String,Object> createTodo(@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(todos.create(actor(r),Inputs.uuid(r.getHeader("X-Operation-Id")),ApiController.requestId(r),body),r);}
    /** 人员选项只包含当前组织任职允许的范围。 */
    @GetMapping("/portal/todos/candidates") public Map<String,Object> todoCandidates(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(todos.candidates(actor(r),query),r);}
    @GetMapping("/portal/todos/{id}") public Map<String,Object> todo(@PathVariable String id,HttpServletRequest r){return ok(todos.detail(actor(r),Inputs.uuid(id)),r);}
    @PutMapping("/portal/todos/{id}") public Map<String,Object> editTodo(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(todos.edit(context(r),Inputs.uuid(id),body),r);}
    @PostMapping("/portal/todos/{id}/actions") public Map<String,Object> todoAction(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(todos.transition(context(r),Inputs.uuid(id),body),r);}
    /** 网页轮询使用登录会话，只返回本人到时的提醒。 */
    @GetMapping("/portal/todo-reminders") public Map<String,Object> reminders(HttpServletRequest r){return ok(reminders.web(actor(r)),r);}
    @PostMapping("/portal/todo-reminders/ack") public Map<String,Object> acknowledgeReminder(@RequestBody Map<String,Object> body,HttpServletRequest r){reminders.acknowledge(actor(r).userId(),body);return ok(Map.of(),r);}
    @GetMapping("/portal/todo-reminders/desktop") public Map<String,Object> desktopStatus(HttpServletRequest r){return ok(reminders.status(actor(r)),r);}
    @PostMapping("/portal/todo-reminders/desktop") public Map<String,Object> pairDesktop(HttpServletRequest r){return ok(reminders.pair(actor(r),ApiController.requestId(r)),r);}
    /** 保存本人桌面提醒开关，配对凭据保持有效。 */
    @PutMapping("/portal/todo-reminders/desktop") public Map<String,Object> setDesktopEnabled(@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(reminders.setEnabled(actor(r),ApiController.requestId(r),body),r);}
    @DeleteMapping("/portal/todo-reminders/desktop") public Map<String,Object> unpairDesktop(HttpServletRequest r){reminders.unpair(actor(r),ApiController.requestId(r));return ok(Map.of(),r);}
    /** 单独的桌面身份边界，只允许读取和确认提醒，凭据无效返回401。 */
    @PostMapping("/desktop/todo-reminders") public Map<String,Object> desktopReminders(@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(reminders.desktop(r.getHeader("Authorization"),r.getHeader("X-Todo-Helper-Version"),body),r);}
    /** 员工统一消息列表，业务权限在计数前执行。 */
    @GetMapping("/portal/inbox") public Map<String,Object> inbox(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(work.inbox(actor(r),query),r);}
    /** 标记本人已读并返回受控路由；业务目标打开后继续鉴权。 */
    @PostMapping("/portal/inbox/{id}/read") public Map<String,Object> read(@PathVariable String id,HttpServletRequest r){return ok(work.readMessage(actor(r),Inputs.uuid(id)),r);}
    /** 认证主体仅从HttpBoundary取得，不接受请求体中的身份。 */
    private Actor actor(HttpServletRequest r){Actor actor=(Actor)r.getAttribute(HttpBoundary.ACTOR);if(actor==null)throw ApiFault.session();return actor;}
    /** 版本和稳定操作号遵循既有管理契约。 */
    private Commands.Context context(HttpServletRequest r){return new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),r.getHeader("X-Expected-Version")==null?null:Inputs.integer(r.getHeader("X-Expected-Version"),0,1,Integer.MAX_VALUE));}
    private Map<String,Object> ok(Object data,HttpServletRequest r){return ApiController.envelope("OK","操作成功",data,ApiController.requestId(r));}
}
