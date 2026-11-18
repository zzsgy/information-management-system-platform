package com.company.platform.portal;

import com.company.platform.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 已到时且未确认的提醒只交付给待办接收人。 */
@Service
public class TodoReminders {
    private static final String DUE=" assignee_id=? AND state='OPEN' AND remind_at<=CURRENT_TIMESTAMP AND reminder_seen_at IS NULL";
    private final Sql db;
    private final IdentityService identity;
    private final Audit audit;

    public TodoReminders(Sql db,IdentityService identity,Audit audit) {
        this.db=db;
        this.identity=identity;
        this.audit=audit;
    }

    /** 配对替换旧设备，令牌仅返回一次，授权代次变化即失效。 */
    @Transactional
    public Map<String,Object> pair(Actor actor,UUID request) {
        identity.lockActor(actor);
        String token=Crypto.token();
        db.update("""
                INSERT INTO portal.todo_desktop(user_id,token_hash,auth_version)
                VALUES(?,?,?) ON CONFLICT(user_id) DO UPDATE
                SET token_hash=EXCLUDED.token_hash,auth_version=EXCLUDED.auth_version,last_poll_at=NULL,enabled=NULL,client_version=NULL
                """,actor.userId(),Crypto.hash(token),actor.authVersion());
        audit.event(actor.userId(),"PLATFORM","USER",actor.userId(),"PAIR_TODO_DESKTOP","SUCCEEDED",request);
        return Sql.map("token",token);
    }

    /** 解除后旧助手立即失去读取权限，网页继续处理提醒。 */
    @Transactional
    public void unpair(Actor actor,UUID request) {
        identity.lockActor(actor);
        db.update("DELETE FROM portal.todo_desktop WHERE user_id=?",actor.userId());
        audit.event(actor.userId(),"PLATFORM","USER",actor.userId(),"UNPAIR_TODO_DESKTOP","SUCCEEDED",request);
    }

    /** 开关是账号偏好，在线是设备当前状态，两者独立。 */
    public Map<String,Object> status(Actor actor) {
        var row=db.one("""
                SELECT enabled,client_version,last_poll_at IS NOT NULL AS connected,
                  COALESCE(last_poll_at>CURRENT_TIMESTAMP-interval '35 seconds',false) AS online
                FROM portal.todo_desktop WHERE user_id=? AND auth_version=?
                """,actor.userId(),actor.authVersion());
        return Sql.map("paired",row!=null,"connected",row!=null&&Boolean.TRUE.equals(row.get("connected")),
                "enabled",row!=null&&Boolean.TRUE.equals(row.get("enabled")),
                "online",row!=null&&Boolean.TRUE.equals(row.get("online")),
                "clientVersion",row==null?null:row.get("client_version"));
    }

    /** 关闭桌面提醒保留原配对，重新开启不必换码。 */
    @Transactional
    public Map<String,Object> setEnabled(Actor actor,UUID request,Map<String,Object> body) {
        Inputs.only(body,"enabled");
        if(!(body.get("enabled") instanceof Boolean enabled)) throw ApiFault.invalid("请选择开启或关闭桌面提醒");
        identity.lockActor(actor);
        if(db.update("UPDATE portal.todo_desktop SET enabled=? WHERE user_id=? AND auth_version=? AND last_poll_at IS NOT NULL",
                enabled,actor.userId(),actor.authVersion())!=1) throw ApiFault.conflict("请先在 Windows 助手中完成配对");
        audit.event(actor.userId(),"PLATFORM","USER",actor.userId(),enabled?"ENABLE_TODO_DESKTOP":"DISABLE_TODO_DESKTOP","SUCCEEDED",request);
        return status(actor);
    }

    /** 网页只取本人最早一条待确认提醒。 */
    public Map<String,Object> web(Actor actor) {
        var desktop=status(actor);
        return Boolean.TRUE.equals(desktop.get("enabled"))&&Boolean.TRUE.equals(desktop.get("online"))
                ?Sql.map("item",null):due(actor.userId());
    }

    /** 仅此接口接受桌面令牌，不能用它调用系统业务接口。 */
    @Transactional
    public Map<String,Object> desktop(String authorization,String clientVersion,Map<String,Object> body) {
        if(authorization==null||!authorization.matches("Bearer [A-Za-z0-9_-]{43}")) throw ApiFault.session();
        if(clientVersion!=null&&!clientVersion.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}"))
            throw ApiFault.invalid("助手版本格式不正确");
        var paired=db.one("""
                UPDATE portal.todo_desktop d SET last_poll_at=CURRENT_TIMESTAMP,enabled=COALESCE(d.enabled,true),client_version=?
                FROM iam.user_account u WHERE d.token_hash=? AND u.id=d.user_id AND u.status='ACTIVE'
                  AND NOT u.password_setup_required AND u.auth_version=d.auth_version
                RETURNING d.user_id,d.enabled
                """,clientVersion,Crypto.hash(authorization.substring(7)));
        if(paired==null) throw ApiFault.session();
        if(Boolean.FALSE.equals(paired.get("enabled"))) return Sql.map("item",null);
        UUID user=(UUID)paired.get("user_id");
        if(!body.isEmpty()) acknowledge(user,body);
        return due(user);
    }

    /** 确认仅影响同一条提醒，已改期或已办结的旧弹窗不影响新安排。 */
    public void acknowledge(UUID user,Map<String,Object> body) {
        Inputs.only(body,"id","remindAt");
        UUID id=Inputs.uuid(body.get("id"));
        Instant time;
        try {
            time=Instant.parse(Inputs.text(body,"remindAt",40));
        } catch(DateTimeParseException failure) {
            throw ApiFault.invalid("提醒时间格式不正确");
        }
        db.update("UPDATE portal.employee_todo SET reminder_seen_at=CURRENT_TIMESTAMP WHERE id=? AND remind_at=? AND"+DUE,
                id,Timestamp.from(time),user);
    }

    private Map<String,Object> due(UUID user) {
        var row=db.one("SELECT id,title,description,remind_at FROM portal.employee_todo WHERE"+DUE+" ORDER BY remind_at,id LIMIT 1",user);
        if(row!=null)row.put("description",FormattedText.preview((String)row.get("description")));
        return Sql.map("item",row==null?null:Sql.view(row,"id,title,description,remind_at"));
    }
}
