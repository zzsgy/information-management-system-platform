package com.company.platform.operations;

import com.company.platform.*;
import com.company.platform.archive.*;
import com.company.platform.storage.StorageBoundaries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** 站内通知与独立投递工作器：短事务领取，事务外送达，租约内确认，失败不改变批准。 */
@Service
public class BusinessNotifications {
    /** 有界查询及事务、身份、范围、实时保护和可替换消息适配器。 */
    private final Sql db;private final IdentityService identity;private final ArchiveAuthority authority;private final ArchiveCommands commands;private final StorageBoundaries gate;private final BusinessMessageTransport transport;private final TransactionTemplate tx;
    /** 默认关闭；单轮20项，租约90秒，初次失败后分别等待60、300、900秒。 */
    private final CatalogMaintenance catalog;
    private final boolean enabled;private final int limit;private final List<Integer> retrySeconds;
    /** 测试和人工演练可停自动轮询，业务通知生成及手动领取保持真实实现。 */
    @Value("${platform.notifications.schedule-enabled:true}") private boolean scheduleEnabled;
    public BusinessNotifications(CatalogMaintenance catalog,Sql db,IdentityService identity,ArchiveAuthority authority,ArchiveCommands commands,StorageBoundaries gate,BusinessMessageTransport transport,PlatformTransactionManager manager,@Value("${platform.notifications.enabled:false}") boolean enabled,@Value("${platform.notifications.batch-size:20}") int limit,@Value("${platform.notifications.retry-seconds:60,300,900}") String retry){
        this.catalog=catalog;this.db=db;this.identity=identity;this.authority=authority;this.commands=commands;this.gate=gate;this.transport=transport;this.tx=new TransactionTemplate(manager);this.enabled=enabled;this.limit=limit;
        retrySeconds=Arrays.stream(retry.split(",")).map(String::trim).map(Integer::parseInt).toList();if(limit<1||limit>100||retrySeconds.size()!=3||retrySeconds.stream().anyMatch(value->value<1||value>86400))throw new IllegalArgumentException("通知批量上限或重试间隔无效");
    }
    /** 无秘密题名，先按本人及当前业务来源筛选，再分页和计数。 */
    public Map<String,Object> list(Actor actor,Map<String,String> query){
        actor.require("ARCHIVE.ACCESS");Inputs.only(query,"page","pageSize","unreadOnly");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);
        String where=" FROM ops.business_notification n WHERE n.user_id=? AND n.state<>'CANCELLED' AND ops.notification_current(n.id,?)";List<Object> parameters=new ArrayList<>(List.of(actor.userId(),actor.userId()));if(query.containsKey("unreadOnly")&&Inputs.choice(query.get("unreadOnly"),"true","false").equals("true"))where+=" AND n.state='UNREAD'";
        long total=db.count("SELECT count(*)"+where,parameters.toArray());parameters.add(size);parameters.add((page-1)*size);
        return Sql.map("items",db.rows("SELECT n.*"+where+" ORDER BY n.created_at DESC,n.id LIMIT ? OFFSET ?",parameters.toArray()).stream().map(row->Sql.view(row,"id,source_kind,source_id,kind,state,created_at,read_at")).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 已读只修改本人的通知，重复操作保留首次时间，不改变源对象。 */
    public Map<String,Object> read(Actor actor,UUID id){return tx.execute(status->{identity.lockActor(actor);identity.background(actor.sessionId()).require("ARCHIVE.ACCESS");db.required("SELECT id FROM ops.business_notification WHERE id=? AND ops.notification_current(id,?) FOR UPDATE",id,actor.userId());db.update("UPDATE ops.business_notification SET state='READ',read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE id=?",id);return Sql.map("id",id,"state","READ");});}
    /** 原任职待办24小时后按上海日期每日提醒；回填仅覆盖当前合法待办，单轮有界。 */
    public void prepare(){tx.executeWithoutResult(status->{gate.requireWritable("ARCHIVE","BUSINESS_NOTIFICATIONS");catalog.reminders();
        for(var step:db.rows("SELECT s.* FROM workflow.step s JOIN workflow.instance w ON w.current_step_id=s.id WHERE s.state='PENDING' AND w.state='PENDING' AND s.assignee_id<>w.applicant_id AND workflow.assignment_valid(s.assignment_type,s.assignment_ref,s.assignee_id) AND (NOT EXISTS(SELECT 1 FROM ops.business_notification n WHERE n.event_key='STEP:'||s.id||':'||s.assignee_id||':'||s.assignment_ref) OR (s.due_at<CURRENT_TIMESTAMP AND NOT EXISTS(SELECT 1 FROM ops.business_notification n WHERE n.event_key='OVERDUE:'||s.id||':'||s.assignee_id||':'||s.assignment_ref||':'||(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date))) ORDER BY s.due_at,s.id LIMIT ?",limit)){
            db.rows("SELECT ops.publish_notification(?,?, 'WORKFLOW_STEP',?,?, 'PENDING','TASK_AVAILABLE')","STEP:"+step.get("id")+":"+step.get("assignee_id")+":"+step.get("assignment_ref"),step.get("assignee_id"),step.get("id"),step.get("assignment_ref").toString());
            if(step.get("due_at")!=null&&Instant.parse(step.get("due_at").toString()).isBefore(Instant.now()))db.rows("SELECT ops.publish_notification(?,?, 'WORKFLOW_STEP',?,?, 'PENDING','TASK_OVERDUE')","OVERDUE:"+step.get("id")+":"+step.get("assignee_id")+":"+step.get("assignment_ref")+":"+LocalDate.now(WorkCalendarService.ZONE),step.get("assignee_id"),step.get("id"),step.get("assignment_ref").toString());
        }
        // 过期租约无法认定是否已发送；来源失效则停止，连续四次中断转人工，历史保留UNKNOWN。
        var retired=db.rows("WITH stale AS(SELECT d.id,ops.notification_current(n.id,n.user_id) AS current FROM ops.notification_delivery d JOIN ops.business_notification n ON n.id=d.notification_id WHERE ((d.state IN ('PENDING','RETRY','MANUAL') AND NOT ops.notification_current(n.id,n.user_id)) OR (d.state='RUNNING' AND d.lease_until<CURRENT_TIMESTAMP AND (d.cycle_attempts>=4 OR NOT ops.notification_current(n.id,n.user_id)))) ORDER BY d.created_at LIMIT ? FOR UPDATE OF d SKIP LOCKED) UPDATE ops.notification_delivery d SET state=CASE WHEN stale.current THEN 'MANUAL' ELSE 'CANCELLED' END,lease_token=NULL,lease_until=NULL,last_error=CASE WHEN stale.current THEN 'LEASE_EXPIRED' ELSE 'SOURCE_UNAVAILABLE' END,row_version=row_version+1 FROM stale WHERE d.id=stale.id RETURNING d.id",limit);
        if(!retired.isEmpty())db.update("UPDATE ops.notification_attempt SET outcome='UNKNOWN',ended_at=CURRENT_TIMESTAMP,error_code='LEASE_EXPIRED' WHERE outcome='RUNNING' AND delivery_id IN(SELECT jsonb_array_elements_text(?::jsonb)::uuid)",Crypto.json(retired.stream().map(row->row.get("id").toString()).toList()));
    });}
    /** 多实例用SKIP LOCKED竞争；外部投递不占用数据库连接和业务事务。 */
    public int tick(){prepare();int processed=0;for(int count=0;count<limit;count++){var work=claim();if(work==null)break;deliver(work);processed++;}return processed;}
    /** 先锁定到期候选，再按通知主键鉴权；失效候选取消后继续，单次最多处理配置上限。 */
    public Map<String,Object> claim(){return tx.execute(status->{
        gate.requireWritable("ARCHIVE","NOTIFICATION_DELIVERY");
        if(!transport.replaySafe()){
            db.update("UPDATE ops.notification_attempt SET outcome='UNKNOWN',ended_at=CURRENT_TIMESTAMP,error_code='LEASE_EXPIRED' WHERE outcome='RUNNING' AND delivery_id IN(SELECT id FROM ops.notification_delivery WHERE state='RUNNING' AND lease_until<CURRENT_TIMESTAMP)");
            db.update("UPDATE ops.notification_delivery SET state='MANUAL',lease_token=NULL,lease_until=NULL,last_error='DELIVERY_UNKNOWN',row_version=row_version+1 WHERE state='RUNNING' AND lease_until<CURRENT_TIMESTAMP");
        }
        // 复杂实时授权函数不能放在全表连接筛选中，否则优化器可能先检查全部历史通知。
        for(int checked=0;checked<limit;checked++){
            var row=db.one("SELECT d.*,n.user_id,n.kind,n.source_kind,n.source_id,u.company_email FROM ops.notification_delivery d JOIN ops.business_notification n ON n.id=d.notification_id JOIN iam.user_account u ON u.id=n.user_id WHERE ((d.state IN ('PENDING','RETRY') AND d.next_run_at<=CURRENT_TIMESTAMP) OR (d.state='RUNNING' AND d.lease_until<CURRENT_TIMESTAMP AND d.cycle_attempts<4)) ORDER BY d.next_run_at,d.id LIMIT 1 FOR UPDATE OF d SKIP LOCKED");
            if(row==null)return null;
            boolean current=db.count("SELECT CASE WHEN ops.notification_current(?,?) THEN 1 ELSE 0 END",row.get("notification_id"),row.get("user_id"))==1;
            // 过期工作器是否已交付仍未知；无论重新领取还是取消，都保存原尝试的 UNKNOWN 事实。
            db.update("UPDATE ops.notification_attempt SET outcome='UNKNOWN',ended_at=CURRENT_TIMESTAMP,error_code='LEASE_EXPIRED' WHERE delivery_id=? AND outcome='RUNNING'",row.get("id"));
            if(!current){
                db.update("UPDATE ops.notification_delivery SET state='CANCELLED',lease_token=NULL,lease_until=NULL,last_error='SOURCE_UNAVAILABLE',row_version=row_version+1 WHERE id=?",row.get("id"));
                continue;
            }
            UUID token=UUID.randomUUID();int attempt=((Number)row.get("attempt_count")).intValue()+1;
            db.update("UPDATE ops.notification_delivery SET state='RUNNING',attempt_count=?,cycle_attempts=cycle_attempts+1,lease_token=?,lease_until=CURRENT_TIMESTAMP+interval '90 seconds',row_version=row_version+1 WHERE id=?",attempt,token,row.get("id"));
            db.update("INSERT INTO ops.notification_attempt(id,delivery_id,attempt_no,lease_hash,outcome) VALUES(?,?,?,?,'RUNNING')",UUID.randomUUID(),row.get("id"),attempt,Crypto.hash(token.toString()));row.put("lease_token",token);return row;
        }
        return null;
    });}
    /** 可测试的单领取者执行入口；只接受仍持有的租约，旧工作器不能确认新任务。 */
    public void deliver(Map<String,Object> work){UUID id=(UUID)work.get("id"),token=(UUID)work.get("lease_token");String receipt=null,error=null;
        boolean current=tx.execute(status->{gate.requireWritable("ARCHIVE","NOTIFICATION_DELIVERY");return db.count("SELECT count(*) FROM ops.notification_delivery d JOIN ops.business_notification n ON n.id=d.notification_id WHERE d.id=? AND d.state='RUNNING' AND d.lease_token=? AND d.lease_until>CURRENT_TIMESTAMP AND ops.notification_current(n.id,n.user_id)",id,token)==1;});if(!current){finish(id,token,null,"SOURCE_UNAVAILABLE");return;}
        try{receipt=transport.send(new BusinessMessageTransport.Message(id,(UUID)work.get("notification_id"),(UUID)work.get("user_id"),work.get("company_email").toString(),work.get("kind").toString(),work.get("source_kind").toString(),(UUID)work.get("source_id")));if(receipt==null||receipt.isBlank()||receipt.length()>128)throw ApiFault.protection();}catch(DeliveryFailure failure){error=failure.getMessage();}catch(Exception failure){error="DELIVERY_UNAVAILABLE";}finish(id,token,receipt,error);
    }
    /** 结果确认与尝试历史同事务；失败初次加三次重试后转人工，通知失败不执行任何业务回调。 */
    private void finish(UUID id,UUID token,String receipt,String error){tx.executeWithoutResult(status->{gate.requireWritable("ARCHIVE","NOTIFICATION_DELIVERY");var row=db.one("SELECT d.*,ops.notification_current(n.id,n.user_id) AS current FROM ops.notification_delivery d JOIN ops.business_notification n ON n.id=d.notification_id WHERE d.id=? AND d.state='RUNNING' AND d.lease_token=? AND d.lease_until>CURRENT_TIMESTAMP FOR UPDATE OF d",id,token);if(row==null)return;
        boolean current=Boolean.TRUE.equals(row.get("current"));int cycle=((Number)row.get("cycle_attempts")).intValue();String state=!current?"CANCELLED":error==null?"SENT":("DELIVERY_UNKNOWN".equals(error)||cycle>retrySeconds.size())?"MANUAL":"RETRY";Instant next=Instant.now().plusSeconds(cycle<=retrySeconds.size()?retrySeconds.get(cycle-1):0);
        db.update("UPDATE ops.notification_delivery SET state=?,next_run_at=?,lease_token=NULL,lease_until=NULL,last_error=?,receipt_id=?,sent_at=CASE WHEN ?='SENT' THEN CURRENT_TIMESTAMP ELSE NULL END,row_version=row_version+1 WHERE id=?",state,java.sql.Timestamp.from(next),current?error:"SOURCE_UNAVAILABLE",receipt,state,id);db.update("UPDATE ops.notification_attempt SET outcome=?,ended_at=CURRENT_TIMESTAMP,error_code=?,receipt_id=? WHERE delivery_id=? AND lease_hash=? AND outcome='RUNNING'",!current?"CANCELLED":error==null?"SENT":"DELIVERY_UNKNOWN".equals(error)?"UNKNOWN":"FAILED",current?error:"SOURCE_UNAVAILABLE",receipt,id,Crypto.hash(token.toString()));
    });}
    /** 管理队列仅允许档案超级管理员；参数化分页不向员工暴露其他收件人。 */
    /** IT 负责技术投递，档案超级管理员保留档案通知处理入口。 */
    private void requireDeliveryManagement(Actor actor) {
        if (actor.systemAdministrator()) actor.require("PLATFORM.OPERATIONS_MANAGE");
        else authority.requireSuper(actor,"ARCHIVE.CONFIG_MANAGE");
    }
    public Map<String,Object> queue(Actor actor,Map<String,String> query){requireDeliveryManagement(actor);Inputs.only(query,"page","pageSize","state","recipient","nextFrom","nextTo");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);String where=" FROM ops.notification_delivery d JOIN ops.business_notification n ON n.id=d.notification_id JOIN iam.user_account u ON u.id=n.user_id WHERE true";List<Object> parameters=new ArrayList<>();if(query.containsKey("state")){where+=" AND d.state=?";parameters.add(Inputs.choice(query.get("state"),"PENDING","RUNNING","RETRY","SENT","MANUAL","CANCELLED"));}where+=new ListConditions(query,parameters)
            .contains("recipient","concat_ws(' ',u.name,u.employee_no)").dates("next","d.next_run_at").sql();
        long total=db.count("SELECT count(*)"+where,parameters.toArray());parameters.add(size);parameters.add((page-1)*size);return Sql.map("enabled",enabled,"transport",transport.channel(),"retrySeconds",retrySeconds,"items",db.rows("SELECT d.*,n.kind,u.employee_no,u.name AS recipient_name"+where+" ORDER BY d.created_at DESC,d.id LIMIT ? OFFSET ?",parameters.toArray()).stream().map(row->Sql.view(row,"id,notification_id,kind,employee_no,recipient_name,state,attempt_count,cycle_attempts,next_run_at,last_error,receipt_id,row_version,sent_at")).toList(),"page",page,"pageSize",size,"total",total);}
    /** 人工重试必须填写原因并重新验证本人；保留累计尝试、原回执与源对象，不重新批准业务。 */
    public Map<String,Object> retry(Commands.Context context,UUID id,Map<String,Object> body){Inputs.only(body,"reason");Inputs.text(body,"reason",1000);return commands.run(context,body,"NOTIFICATION_DELIVERY",id,"RETRY_NOTIFICATION",true,()->requireDeliveryManagement(context.actor()),()->{var row=db.required("SELECT * FROM ops.notification_delivery WHERE id=? FOR UPDATE",id);if(context.version()==null||context.version()!=((Number)row.get("row_version")).intValue()||!"MANUAL".equals(row.get("state")))throw ApiFault.conflict("投递状态已变化或尚未进入人工队列");var notice=db.required("SELECT id,user_id FROM ops.business_notification WHERE id=?",row.get("notification_id"));if(db.count("SELECT CASE WHEN ops.notification_current(?,?) THEN 1 ELSE 0 END",notice.get("id"),notice.get("user_id"))!=1)throw ApiFault.conflict("通知来源或接收人已失效");db.update("UPDATE ops.notification_delivery SET state='RETRY',cycle_attempts=0,next_run_at=CURRENT_TIMESTAMP,last_error=NULL,row_version=row_version+1 WHERE id=?",id);return Sql.map("id",id,"state","RETRY");},key->Sql.view(db.required("SELECT id,state,attempt_count,row_version FROM ops.notification_delivery WHERE id=?",key),"id,state,attempt_count,row_version"));}
    /** 默认不调度；缺保护事实时保持待投递，不记录伪成功。 */
    @Scheduled(fixedDelayString="${platform.notifications.delay-ms:10000}") public void scheduled(){if(enabled&&scheduleEnabled)try{tick();}catch(ApiFault failure){if(failure.status!=503)throw failure;}}
}
