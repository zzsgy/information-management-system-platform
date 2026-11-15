package com.company.platform.operations;

import com.company.platform.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** 账号凭据事务发件箱；短事务领取、事务外网络投递，与业务审批及档案保护状态解耦。 */
@Service
public class AccountMessages {
    private final Sql db;private final SecurityLedger ledger;private final MailGateway mail;private final DingTalkGateway alerts;private final TransactionTemplate tx;
    /** 自动调度可在隔离验收中关闭；单轮最多20项，避免占满连接和HTTP线程。 */
    private final boolean enabled;
    public AccountMessages(Sql db,SecurityLedger ledger,MailGateway mail,DingTalkGateway alerts,PlatformTransactionManager manager,Environment env){this.db=db;this.ledger=ledger;this.mail=mail;this.alerts=alerts;tx=new TransactionTemplate(manager);enabled=env.getProperty("platform.messaging.schedule-enabled",Boolean.class,true);}
    /** 与调用者业务事务一起入队；密钥从库外秘密按用途派生，DB备份不含解密材料。 */
    public void credential(UUID id,UUID user,UUID challenge,String kind,String address,String secret,Instant expires){
        db.update("INSERT INTO ops.account_message(id,user_id,challenge_id,kind,channel,recipient,payload,expires_at) VALUES(?,?,?,?,'MAIL',?,?,?)",id,user,challenge,kind,address,encrypt(id,secret),java.sql.Timestamp.from(expires));
    }
    /** 锁定事件已有账号事务保护；一小时三次锁定后，同一账号最多一小时一条技术告警。 */
    public void locked(String bucket,UUID user){
        db.update("INSERT INTO ops.login_lock_event(id,account_bucket) VALUES(?,?)",UUID.randomUUID(),bucket);
        if(user!=null&&db.count("SELECT count(*) FROM ops.login_lock_event WHERE account_bucket=? AND created_at>CURRENT_TIMESTAMP-interval '1 hour'",bucket)>=3&&db.count("SELECT count(*) FROM ops.account_message WHERE user_id=? AND kind='LOGIN_LOCKS' AND created_at>CURRENT_TIMESTAMP-interval '1 hour'",user)==0)
            db.update("INSERT INTO ops.account_message(id,user_id,kind,channel,expires_at) VALUES(?,?,'LOGIN_LOCKS','DINGTALK',CURRENT_TIMESTAMP+interval '1 day')",UUID.randomUUID(),user);
    }
    /** 任务已过期或凭据已失效时清除密文，不再向员工发送旧码。 */
    private boolean current(Map<String,Object> row){
        if("DINGTALK".equals(row.get("channel")))return true;
        String kind=row.get("kind").toString();
        if(kind.equals("RECOVERY"))return db.count("SELECT count(*) FROM iam.password_recovery r JOIN iam.user_account u ON u.id=r.user_id WHERE r.id=? AND r.closed_at IS NULL AND r.expires_at>CURRENT_TIMESTAMP AND r.auth_version=u.auth_version AND r.email_snapshot=u.company_email AND u.status='ACTIVE' AND u.company_email=? AND r.sent_at<=?::timestamptz",row.get("challenge_id"),row.get("recipient"),row.get("created_at"))==1;
        return db.count("SELECT count(*) FROM iam.credential_challenge c JOIN iam.user_account u ON u.id=c.user_id WHERE c.id=? AND c.consumed_at IS NULL AND c.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE' AND u.company_email=? AND u.password_setup_required",row.get("challenge_id"),row.get("recipient"))==1;
    }
    /** SMTP/机器人没有精确去重承诺；崩溃后的发送结果转人工核对，不自动二次发送。 */
    public Map<String,Object> claim(){return tx.execute(status->{
        db.update("UPDATE ops.account_message SET state='MANUAL',lease_token=NULL,lease_until=NULL,last_error='DELIVERY_UNKNOWN',row_version=row_version+1 WHERE state='RUNNING' AND lease_until<CURRENT_TIMESTAMP");
        db.update("UPDATE ops.account_message SET payload=NULL,state=CASE WHEN state='SENT' THEN state ELSE 'CANCELLED' END,row_version=row_version+1 WHERE expires_at<=CURRENT_TIMESTAMP AND (payload IS NOT NULL OR state IN ('PENDING','RETRY','MANUAL'))");
        for(int i=0;i<20;i++){
            var row=db.one("SELECT * FROM ops.account_message WHERE state IN ('PENDING','RETRY') AND next_run_at<=CURRENT_TIMESTAMP AND expires_at>CURRENT_TIMESTAMP ORDER BY next_run_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");if(row==null)return null;
            if(!current(row)){db.update("UPDATE ops.account_message SET state='CANCELLED',payload=NULL,last_error='CREDENTIAL_EXPIRED',row_version=row_version+1 WHERE id=?",row.get("id"));continue;}
            UUID lease=UUID.randomUUID();db.update("UPDATE ops.account_message SET state='RUNNING',attempts=attempts+1,cycle_attempts=cycle_attempts+1,lease_token=?,lease_until=CURRENT_TIMESTAMP+interval '90 seconds',row_version=row_version+1 WHERE id=?",lease,row.get("id"));row.put("lease_token",lease);return row;
        }return null;
    });}
    /** 发件箱不返回秘密到HTTP；本机收件箱仅供开发验收，正式邮件只确认SMTP接受。 */
    public void deliver(Map<String,Object> row){
        UUID id=(UUID)row.get("id"),lease=(UUID)row.get("lease_token");String error=null;boolean unknown=false;
        if(db.count("SELECT count(*) FROM ops.account_message WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP",id,lease)!=1)return;
        if(!current(row)){finish(id,lease,"CREDENTIAL_EXPIRED",false);return;}
        try{
            if("DINGTALK".equals(row.get("channel")))alerts.send(id,row.get("kind").toString());
            else {String secret=decrypt(id,row.get("payload").toString()),kind=row.get("kind").toString();boolean recovery=kind.equals("RECOVERY");
                mail.send(id,row.get("recipient").toString(),recovery?"信息管理系统密码找回":"信息管理系统临时密码",
                    (recovery?"本次找回密码验证码：":"本次临时密码：")+secret+"\n"+(recovery?"验证码10分钟内有效，重发后旧码失效。":"临时密码24小时内单次有效，登录后请设置本人密码。")+"\n如非本人申请，请联系IT。\n"+mail.portal(),
                    Sql.map(recovery?"code":"temporaryPassword",secret,"kind",kind,"expiresAt",row.get("expires_at")));
            }
        }catch(DeliveryFailure failure){unknown=failure.uncertain;error=failure.getMessage();}catch(Exception failure){error="DELIVERY_UNAVAILABLE";}
        finish(id,lease,error,unknown);
    }
    /** 网络结果未知进入人工队列；明确失败按1/5/15分钟重试，过期凭据不延寿。 */
    private void finish(UUID id,UUID lease,String error,boolean unknown){tx.executeWithoutResult(status->{
        var row=db.one("SELECT * FROM ops.account_message WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP FOR UPDATE",id,lease);if(row==null)return;
        int attempt=((Number)row.get("cycle_attempts")).intValue();boolean invalid="CREDENTIAL_EXPIRED".equals(error);String state=invalid?"CANCELLED":error==null?"SENT":unknown||attempt>=4?"MANUAL":"RETRY";int delay=List.of(60,300,900,900).get(Math.min(attempt,4)-1);
        db.update("UPDATE ops.account_message SET state=?,payload=CASE WHEN ? IN ('SENT','CANCELLED') THEN NULL ELSE payload END,lease_token=NULL,lease_until=NULL,last_error=?,sent_at=CASE WHEN ?='SENT' THEN CURRENT_TIMESTAMP ELSE sent_at END,next_run_at=CURRENT_TIMESTAMP+(? * interval '1 second'),row_version=row_version+1 WHERE id=?",state,state,error,state,delay,id);
    });}
    public int tick(){int count=0;while(count<20){var row=claim();if(row==null)break;deliver(row);count++;}return count;}
    @Scheduled(fixedDelayString="${platform.messaging.delay-ms:10000}") public void scheduled(){if(enabled)tick();}
    /** AES-GCM绑定消息标识；换行、重放到另一消息或密文篡改均无法解密。 */
    private String encrypt(UUID id,String secret){try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=cipher(Cipher.ENCRYPT_MODE,id,iv);return Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(c.doFinal(secret.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw ApiFault.protection();}}
    private String decrypt(UUID id,String payload){try{String[] parts=payload.split(":");return new String(cipher(Cipher.DECRYPT_MODE,id,Base64.getDecoder().decode(parts[0])).doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);}catch(Exception e){throw ApiFault.protection();}}
    private Cipher cipher(int mode,UUID id,byte[] iv)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(HexFormat.of().parseHex(ledger.bucket("account-message-encryption-v1")),"AES"),new GCMParameterSpec(128,iv));c.updateAAD(id.toString().getBytes(StandardCharsets.UTF_8));return c;}
    /** 仅IT查询技术状态，不返回邮箱、验证码、密文或机器人配置。 */
    public Map<String,Object> queue(Actor actor,Map<String,String> query){
        actor.require("PLATFORM.USERS_MANAGE");
        Inputs.only(query,"page","pageSize","recipient","kind","state","createdFrom","createdTo","expiresFrom","expiresTo");
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);
        var args=new ArrayList<Object>();
        String from=" FROM ops.account_message m LEFT JOIN iam.user_account u ON u.id=m.user_id WHERE true"+new ListConditions(query,args)
            .contains("recipient","concat_ws(' ',u.name,u.employee_no)")
            .choice("kind","m.kind","RECOVERY","TEMP_PASSWORD","LOGIN_LOCKS")
            .choice("state","m.state","PENDING","RUNNING","RETRY","SENT","MANUAL","CANCELLED")
            .dates("created","m.created_at").dates("expires","m.expires_at").sql();
        long total=db.count("SELECT count(*)"+from,args.toArray());
        args.add(size);args.add((page-1)*size);
        var rows=db.rows("SELECT m.id,m.kind,m.channel,m.state,m.attempts,m.created_at,m.expires_at,m.next_run_at,m.last_error,m.row_version,u.employee_no,u.name AS recipient_name"+from+" ORDER BY m.created_at DESC,m.id LIMIT ? OFFSET ?",args.toArray());
        return Sql.map("mailMode",mail.mode(),"alertMode",alerts.mode(),"items",rows.stream().map(r->Sql.view(r,"id,kind,channel,state,attempts,created_at,expires_at,next_run_at,last_error,row_version,employee_no,recipient_name")).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 人工核对未知结果后才允许重试；重试旧凭据仍须当前有效且未超过原期限。 */
    public Map<String,Object> retry(Commands.Context ctx,UUID id,Map<String,Object> body){Inputs.only(body,"reason");Inputs.text(body,"reason",1000);var row=db.required("SELECT * FROM ops.account_message WHERE id=? FOR UPDATE",id);Commands.version(ctx,row);if(!"MANUAL".equals(row.get("state"))||!current(row)||Instant.parse(row.get("expires_at").toString()).isBefore(Instant.now()))throw ApiFault.conflict("消息已失效或不在人工队列");db.update("UPDATE ops.account_message SET state='RETRY',cycle_attempts=0,next_run_at=CURRENT_TIMESTAMP,last_error=NULL,row_version=row_version+1 WHERE id=?",id);return Sql.map("objectId",id,"state","RETRY");}
}
