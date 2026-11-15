package com.company.platform;

import com.company.platform.operations.AccountMessages;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** 账号找回入口；未知、停用、限流账号均返回同一结构，不能用响应枚举公司邮箱。 */
@Service
public class AccountRecovery {
    private final Sql db;private final IdentityService identity;private final SecurityLedger ledger;private final Audit audit;private final CredentialDelivery delivery;private final AccountMessages messages;
    private final SecureRandom random=new SecureRandom();
    public AccountRecovery(Sql db,IdentityService identity,SecurityLedger ledger,Audit audit,CredentialDelivery delivery,AccountMessages messages){this.db=db;this.identity=identity;this.ledger=ledger;this.audit=audit;this.delivery=delivery;this.messages=messages;}
    /** 匿名发码/重发；数据库串行化同账号限额，累计失败次数保留在同一找回流程。 */
    @Transactional(noRollbackFor=CredentialFault.class)
    public Map<String,Object> request(Map<String,Object> body,String source,UUID request){
        Inputs.only(body,"employeeNo","flowId");String no=Inputs.text(body,"employeeNo",64),bucket=ledger.bucket("recovery-account:"+no);
        UUID requested=body.get("flowId")==null?null:Inputs.uuid(body.get("flowId"));sourceLimit(source);
        db.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,61427035))",bucket);
        var user=db.one("SELECT * FROM iam.user_account WHERE employee_no=? FOR UPDATE",no);
        var flow=requested==null?db.one("SELECT * FROM iam.password_recovery WHERE account_bucket=? AND expires_at>CURRENT_TIMESTAMP ORDER BY created_at DESC LIMIT 1 FOR UPDATE",bucket):db.one("SELECT * FROM iam.password_recovery WHERE id=? AND account_bucket=? FOR UPDATE",requested,bucket);
        if(requested!=null&&flow==null)throw invalid();
        if(flow==null){UUID id=UUID.randomUUID();db.update("INSERT INTO iam.password_recovery(id,user_id,account_bucket,expires_at) VALUES(?,?,?,CURRENT_TIMESTAMP+interval '10 minutes')",id,user==null?null:user.get("id"),bucket);flow=db.required("SELECT * FROM iam.password_recovery WHERE id=?",id);}
        UUID id=(UUID)flow.get("id");var accepted=Sql.map("flowId",id,"state","ACCEPTED","retryAfterSeconds",60,"expiresInSeconds",600);
        if(flow.get("closed_at")!=null||((Number)flow.get("failures")).intValue()>=5)return accepted;
        if(db.count("SELECT count(*) FROM iam.password_recovery WHERE id=? AND (sent_at>CURRENT_TIMESTAMP-interval '60 seconds' OR expires_at<=CURRENT_TIMESTAMP)",id)>0)return accepted;
        if(db.count("SELECT count(*) FROM ops.recovery_send_event WHERE account_bucket=? AND created_at>CURRENT_TIMESTAMP-interval '1 hour'",bucket)>=5||db.count("SELECT count(*) FROM ops.recovery_send_event WHERE account_bucket=? AND created_at>=(date_trunc('day',CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai')",bucket)>=10)return accepted;
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
        db.update("INSERT INTO ops.recovery_send_event(id,account_bucket) VALUES(?,?)",UUID.randomUUID(),bucket);
        db.update("UPDATE iam.password_recovery SET code_hash=?,sent_at=CURRENT_TIMESTAMP,expires_at=CURRENT_TIMESTAMP+interval '10 minutes',auth_version=?,email_snapshot=? WHERE id=?",ledger.bucket("recovery-code:"+id+":"+code),user==null?null:user.get("auth_version"),user==null?null:user.get("company_email"),id);
        if(user!=null&&"ACTIVE".equals(user.get("status")))messages.credential(UUID.randomUUID(),(UUID)user.get("id"),id,"RECOVERY",user.get("company_email").toString(),code,Instant.now().plusSeconds(600));
        audit.event(user==null?null:(UUID)user.get("id"),"PLATFORM","RECOVERY",id,"REQUEST_PASSWORD_RECOVERY","SUCCEEDED",request);return accepted;
    }
    /** 六位码单次消费；失败计数必须提交，成功设密使所有旧会话和旧凭据立即失效。 */
    @Transactional(noRollbackFor=CredentialFault.class)
    public Map<String,Object> complete(Map<String,Object> body,String source,UUID request){
        Inputs.only(body,"flowId","code","newPassword");UUID id=Inputs.uuid(body.get("flowId"));String code=Inputs.text(body,"code",6),password=Inputs.newPassword(body,"newPassword");
        if(!code.matches("[0-9]{6}"))throw ApiFault.invalid("请输入六位数字验证码");sourceLimit(source);
        var reference=db.one("SELECT user_id,account_bucket FROM iam.password_recovery WHERE id=?",id);if(reference==null)throw invalid();
        db.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,61427035))",reference.get("account_bucket"));
        var user=reference.get("user_id")==null?null:db.one("SELECT * FROM iam.user_account WHERE id=? FOR UPDATE",reference.get("user_id"));
        var flow=db.required("SELECT *,expires_at>CURRENT_TIMESTAMP AS current FROM iam.password_recovery WHERE id=? FOR UPDATE",id);
        if(flow.get("closed_at")!=null||!Boolean.TRUE.equals(flow.get("current"))||((Number)flow.get("failures")).intValue()>=5)throw invalid();
        boolean valid=user!=null&&"ACTIVE".equals(user.get("status"))&&Objects.equals(user.get("auth_version"),flow.get("auth_version"))&&Objects.equals(user.get("company_email"),flow.get("email_snapshot"))&&Crypto.equal((String)flow.get("code_hash"),ledger.bucket("recovery-code:"+id+":"+code));
        if(!valid){db.update("UPDATE iam.password_recovery SET failures=failures+1,closed_at=CASE WHEN failures+1>=5 THEN CURRENT_TIMESTAMP ELSE closed_at END WHERE id=?",id);audit.event(reference.get("user_id")==null?null:(UUID)reference.get("user_id"),"PLATFORM","RECOVERY",id,"PASSWORD_RECOVERY","DENIED",request);throw invalid();}
        if(password.equals(user.get("employee_no"))) throw ApiFault.invalid("新密码须与工号不同");
        UUID uid=(UUID)user.get("id");ledger.check(uid,((Number)user.get("auth_version")).intValue());
        db.update("UPDATE iam.user_account SET password_hash=?,password_setup_required=false,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",Crypto.password(password),uid);
        closeCredentials(uid);identity.invalidate(uid,"PASSWORD_RECOVERY",request);
        db.update("DELETE FROM ops.auth_throttle WHERE bucket=?",ledger.bucket("account:"+user.get("employee_no")));
        audit.event(uid,"PLATFORM","USER",uid,"PASSWORD_RECOVERY","SUCCEEDED",request);return Sql.map("state","SUCCEEDED");
    }
    /** 重置密码为当前工号，24小时内须强制改密；立即收回旧会话与旧凭据。 */
    public Map<String,Object> reset(Commands.Context ctx,UUID target,Map<String,Object> body){
        Inputs.only(body,"reason");Inputs.text(body,"reason",1000);if(target.equals(ctx.actor().userId()))throw ApiFault.invalid("本人请使用修改密码或邮箱找回");
        var user=db.required("SELECT * FROM iam.user_account WHERE id=? FOR UPDATE",target);Commands.version(ctx,user);if(!"ACTIVE".equals(user.get("status")))throw ApiFault.conflict("停用账号不能重置密码");
        ledger.check(target,((Number)user.get("auth_version")).intValue());closeCredentials(target);String temporary=user.get("employee_no").toString();
        db.update("UPDATE iam.user_account SET password_hash=?,password_setup_required=true,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",Crypto.password(temporary),target);
        delivery.issue(target,temporary,user.get("company_email").toString());identity.invalidate(target,"PASSWORD_RESET",ctx.operation());
        db.update("DELETE FROM ops.auth_throttle WHERE bucket=?",ledger.bucket("account:"+user.get("employee_no")));return Commands.result(ctx,target,((Number)user.get("row_version")).intValue()+1);
    }
    private void closeCredentials(UUID user){db.update("UPDATE iam.credential_challenge SET consumed_at=CURRENT_TIMESTAMP WHERE user_id=? AND consumed_at IS NULL",user);db.update("UPDATE iam.password_recovery SET closed_at=CURRENT_TIMESTAMP,code_hash=NULL WHERE user_id=? AND closed_at IS NULL",user);}
    /** 来源每小时最多100次请求，既包含发码也包含验证；不以账号存在性决定限流响应。 */
    private void sourceLimit(String source){String bucket=ledger.bucket("recovery-source:"+source);db.update("INSERT INTO ops.auth_throttle(bucket) VALUES(?) ON CONFLICT DO NOTHING",bucket);var row=db.required("SELECT *,window_start>CURRENT_TIMESTAMP-interval '1 hour' AS current FROM ops.auth_throttle WHERE bucket=? FOR UPDATE",bucket);if(Boolean.TRUE.equals(row.get("current"))&&((Number)row.get("attempts")).intValue()>=100)throw new CredentialFault(429,"RECOVERY_RATE_LIMIT","请求过于频繁，请稍后再试");db.update("UPDATE ops.auth_throttle SET attempts=CASE WHEN window_start>CURRENT_TIMESTAMP-interval '1 hour' THEN attempts+1 ELSE 1 END,window_start=CASE WHEN window_start>CURRENT_TIMESTAMP-interval '1 hour' THEN window_start ELSE CURRENT_TIMESTAMP END WHERE bucket=?",bucket);}
    private CredentialFault invalid(){return new CredentialFault(400,"INVALID_RECOVERY","验证码无效或流程已结束，请重新申请找回");}
}
