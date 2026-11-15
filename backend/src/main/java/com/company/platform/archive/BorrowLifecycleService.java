package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.storage.StorageBoundaries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** 电子借阅到期归档与站内提醒；正文实时鉴权不依赖本工作器是否运行。 */
@Service
public class BorrowLifecycleService {
    /** 有界批次只执行数据库短事务，不在事务中调用邮件或外部服务。 */
    private final Sql db;
    /** 已读操作仍须验证当前登录，不能只信任通知UUID。 */
    private final IdentityService identity;
    /** 到期整理和通知写入也须由当前可写节点执行，隔离旧主不继续产生提醒。 */
    private final StorageBoundaries protection;
    /** 调度和测试共用同一显式事务，避免同类调用绕过事务代理。 */
    private final TransactionTemplate tx;
    /** 默认关闭调度；部署时显式启用，单位和频率见platform.yml。 */
    private final boolean enabled;
    /** 每轮最多处理的到期授权、取消提醒及新提醒数量。 */
    private static final int LIMIT=200;

    /** 注入当前数据库、身份边界及调度开关；不配置通知收件人到代码。 */
    public BorrowLifecycleService(Sql db,IdentityService identity,StorageBoundaries protection,PlatformTransactionManager manager,@Value("${platform.borrow-lifecycle.enabled:false}") boolean enabled) {
        this.db=db;this.identity=identity;this.protection=protection;this.tx=new TransactionTemplate(manager);this.enabled=enabled;
    }
    /** 调度只触发有界扫描，多实例依靠事务咨询锁竞争，进程退出即释放锁。 */
    @Scheduled(fixedDelayString="${platform.borrow-lifecycle.delay-ms:60000}")
    public void scheduled() {if(enabled) {try{tick();}catch(ApiFault fault){if(fault.status!=503) throw fault; /* 保护证据未就绪时本轮不写入，后续按当前主节点事实重试。 */}}}

    /** 按服务端当前时刻处理，重跑不重复授权、决定或同期限提醒。 */
    public void tick() {
        protection.requireWritable("ARCHIVE","BORROW_LIFECYCLE");
        tx.executeWithoutResult(status->{
            if(!Boolean.TRUE.equals(db.required("SELECT pg_try_advisory_xact_lock(61427004) AS acquired").get("acquired"))) return;
            // 物化状态只便于查询；即使扫描尚未处理，grant_valid仍即时拒绝过期授权。
            db.update("WITH due AS (SELECT id FROM archive.access_grant WHERE state='ACTIVE' AND valid_until<=CURRENT_TIMESTAMP ORDER BY valid_until,id LIMIT ? FOR UPDATE SKIP LOCKED) UPDATE archive.access_grant g SET state='EXPIRED' FROM due WHERE g.id=due.id",LIMIT);
            // 续借、撤权或源版本变化使旧提醒取消，保留原记录供追溯。
            db.update("WITH stale AS (SELECT id FROM ops.inbox_notification WHERE state<>'CANCELLED' AND NOT archive.reminder_current(request_id,user_id,deadline) ORDER BY created_at,id LIMIT ? FOR UPDATE SKIP LOCKED) UPDATE ops.inbox_notification n SET state='CANCELLED' FROM stale WHERE n.id=stale.id",LIMIT);
            LocalDate today=LocalDate.now(WorkCalendarService.ZONE);
            var due=db.rows("SELECT DISTINCT g.source_request_id AS request_id,q.batch_id,g.user_id,g.valid_until,(g.valid_until AT TIME ZONE 'Asia/Shanghai')::date AS expiry_day FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE g.source_type='BORROW' AND g.state='ACTIVE' AND g.valid_until>CURRENT_TIMESTAMP AND g.valid_until>=? AND g.valid_until<? AND archive.reminder_current(q.id,g.user_id,g.valid_until) AND NOT EXISTS(SELECT 1 FROM ops.inbox_notification n WHERE n.user_id=g.user_id AND n.request_id=q.id AND n.deadline=g.valid_until AND n.days_before=(g.valid_until AT TIME ZONE 'Asia/Shanghai')::date-?::date AND n.state<>'CANCELLED') ORDER BY g.valid_until,g.source_request_id,g.user_id LIMIT ?",java.sql.Timestamp.from(today.atStartOfDay(WorkCalendarService.ZONE).toInstant()),java.sql.Timestamp.from(today.plusDays(2).atStartOfDay(WorkCalendarService.ZONE).toInstant()),today,LIMIT);
            for(var item:due) {
                int offset=(int)java.time.temporal.ChronoUnit.DAYS.between(today,LocalDate.parse(item.get("expiry_day").toString()));
                // 原截止+接收人+偏移唯一；并发实例和旧任务重放都不能生成重复提醒。
                db.update("INSERT INTO ops.inbox_notification(id,user_id,request_id,batch_id,deadline,reminder_day,days_before) VALUES(?,?,?,?,?,?,?) ON CONFLICT(user_id,request_id,deadline,days_before) DO UPDATE SET state='UNREAD',read_at=NULL WHERE ops.inbox_notification.state='CANCELLED'",UUID.randomUUID(),item.get("user_id"),item.get("request_id"),item.get("batch_id"),java.sql.Timestamp.from(Instant.parse(item.get("valid_until").toString())),today,offset);
            }
            // 事务提交前再次确认主节点事实，过期或隔离变化则整轮回滚。
            protection.requireWritable("ARCHIVE","BORROW_LIFECYCLE");
        });
    }

    /** 当前本人站内提醒，先按接收人和现行来源过滤再分页，不返回秘密档案题名。 */
    public Map<String,Object> list(Actor actor,Map<String,String> query) {
        actor.require("ARCHIVE.ACCESS");Inputs.only(query,"page","pageSize","unreadOnly");
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);
        String where=" FROM ops.inbox_notification n WHERE n.user_id=? AND n.state<>'CANCELLED' AND archive.reminder_current(n.request_id,n.user_id,n.deadline)";
        if(query.containsKey("unreadOnly")){Inputs.choice(query.get("unreadOnly"),"true","false");if(query.get("unreadOnly").equals("true")) where+=" AND n.state='UNREAD'";}
        long total=db.count("SELECT count(*)"+where,actor.userId());
        var rows=db.rows("SELECT n.*"+where+" ORDER BY n.created_at DESC,n.id LIMIT ? OFFSET ?",actor.userId(),size,(page-1)*size);
        return Sql.map("items",rows.stream().map(row->{var item=Sql.view(row,"id,batch_id,request_id,deadline,days_before,state,created_at,read_at");item.put("title",((Number)row.get("days_before")).intValue()==1?"借阅将在明天到期":"借阅今天到期");item.put("message","请按实际需要申请续借；审批和通知均不会自动延长原授权。");return item;}).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 本人确认已读，重复点击保持首次时间；不能通过其他员工通知号改变状态。 */
    public Map<String,Object> read(Actor actor,UUID id) {
        return tx.execute(status->{identity.lockActor(actor);actor.require("ARCHIVE.ACCESS");
            db.required("SELECT id FROM ops.inbox_notification WHERE id=? AND user_id=?",id,actor.userId());
            db.update("UPDATE ops.inbox_notification SET state=CASE WHEN state='UNREAD' THEN 'READ' ELSE state END,read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE id=? AND user_id=?",id,actor.userId());return Sql.map("id",id,"state",db.required("SELECT state FROM ops.inbox_notification WHERE id=? AND user_id=?",id,actor.userId()).get("state"));});
    }
    /** 首页摘要来自现行授权与通知，延迟扫描不会把已过期来源计为有效。 */
    public Map<String,Object> summary(Actor actor) {
        actor.require("ARCHIVE.ACCESS");
        return Sql.map("unread",db.count("SELECT count(*) FROM ops.inbox_notification n WHERE user_id=? AND state='UNREAD' AND archive.reminder_current(n.request_id,n.user_id,n.deadline)",actor.userId()),"expiring",db.count("SELECT count(DISTINCT source_request_id) FROM archive.access_grant g WHERE g.user_id=? AND g.valid_until<? AND archive.reminder_current(g.source_request_id,g.user_id,g.valid_until)",actor.userId(),java.sql.Timestamp.from(LocalDate.now(WorkCalendarService.ZONE).plusDays(2).atStartOfDay(WorkCalendarService.ZONE).toInstant())));
    }
}
