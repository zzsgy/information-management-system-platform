package com.company.platform;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** 身份服务：单会话、授权代次、首次设密、锁定及一次性再验证均由数据库协调。 */
@Service
public class IdentityService {
    /** 本人及管理员可读取的账号白名单，不含任何认证材料。 */
    public static final String USER_FIELDS="id,employee_no,name,department_id,company_email,status,password_setup_required,row_version";
    /** 对外角色投影；任职历史只在对应管理服务内使用。 */
    public static final String ROLE_FIELDS="id,module,code,name,status,permission_version,row_version";
    /** 查询、审计和独立保护边界。 */
    private final Sql db;
    private final Audit audit;
    private final SecurityLedger ledger;
    private final com.company.platform.operations.AccountMessages messages;
    /** 不存在账号也执行同级密码计算，缩小明显的账号枚举时间差。 */
    private final String dummyHash=Crypto.password(Crypto.token());
    /** 数据库协调会话，审计记录事实，库外账本防止恢复后权限回升。 */
    public IdentityService(Sql db, Audit audit, SecurityLedger ledger,com.company.platform.operations.AccountMessages messages) { this.db=db; this.audit=audit; this.ledger=ledger;this.messages=messages; }

    /** 登录失败的计数和审计必须提交；账号密码不通过异常消息返回。 */
    @Transactional(noRollbackFor=CredentialFault.class)
    public Map<String,Object> login(Map<String,Object> body, UUID request, String source) {
        Inputs.only(body,"employeeNo","password");
        String employeeNo=Inputs.text(body,"employeeNo",64), password=Inputs.password(body,"password");
        // 来源计数和账号计数共用数据库，跨进程启动不重置保护。
        checkThrottle("source:"+source,100);
        var user=db.one("SELECT * FROM iam.user_account WHERE employee_no=? FOR UPDATE",employeeNo);
        checkThrottle("account:"+employeeNo,5);
        boolean valid=Crypto.matches(password,user==null?dummyHash:user.get("password_hash"));
        if (user==null || !valid || !user.get("status").equals("ACTIVE")) {
            failThrottle("account:"+employeeNo,5); failThrottle("source:"+source,100);
            audit.event(user==null?null:(UUID)user.get("id"),"PLATFORM","SESSION",null,"LOGIN","DENIED",request);
            throw new CredentialFault(401,"INVALID_CREDENTIALS","工号或密码不正确，或账号暂不可用");
        }
        checkCatalogFence();
        UUID userId=(UUID)user.get("id"); ledger.check(userId,((Number)user.get("auth_version")).intValue());
        if (Boolean.TRUE.equals(user.get("password_setup_required")) && temporaryChallenge(userId,password)==null) throw new CredentialFault(401,"INVALID_CREDENTIALS","初始密码已失效，请联系管理员重置");
        db.update("DELETE FROM ops.auth_throttle WHERE bucket=?",ledger.bucket("account:"+employeeNo));
        db.update("UPDATE iam.session SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=? AND revoked_at IS NULL",userId);
        db.update("UPDATE iam.user_account SET session_version=session_version+1 WHERE id=?",userId);
        String token=Crypto.token(); UUID session=UUID.randomUUID();
        db.update("INSERT INTO iam.session(id,user_id,token_hash,session_version,auth_version,last_activity_at,expires_at,client_summary) SELECT ?,id,?,session_version,auth_version,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '12 hours','{}'::jsonb FROM iam.user_account WHERE id=?",session,Crypto.hash(token),userId);
        audit.event(userId,"PLATFORM","SESSION",session,"LOGIN","SUCCEEDED",request);
        return Sql.map("token",token,"user",Sql.view(user,USER_FIELDS),"passwordSetupRequired",user.get("password_setup_required"),"csrfToken",csrf(token),"sessionExpiresAt",Instant.now().plusSeconds(43200).toString());
    }

    /** 全局目录安全水位仅作恢复防回退核验；正常请求为单行读取，禁止旧备份复活撤下内容。 */
    private void checkCatalogFence() {var row=db.required("SELECT id,catalog_epoch FROM platform_meta.instance");ledger.check((UUID)row.get("id"),((Number)row.get("catalog_epoch")).intValue());}

    /** 请求鉴权始终读取当前用户和会话代次；后台轮询只读不续期。 */
    public Actor authenticate(String token) {
        if (token==null || token.length()!=43) throw ApiFault.session();
        var row=db.one("SELECT u.*,s.id AS session_id FROM iam.session s JOIN iam.user_account u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>CURRENT_TIMESTAMP AND s.last_activity_at>CURRENT_TIMESTAMP-interval '30 minutes' AND u.status='ACTIVE' AND s.session_version=u.session_version AND s.auth_version=u.auth_version",Crypto.hash(token));
        return actor(row,csrf(token));
    }
    /** 后台任务只复用其发起时的有效会话，不生成浏览器凭据，也不续期会话。 */
    public Actor background(UUID sessionId) {
        var row=db.one("SELECT u.*,s.id AS session_id FROM iam.session s JOIN iam.user_account u ON u.id=s.user_id WHERE s.id=? AND s.revoked_at IS NULL AND s.expires_at>CURRENT_TIMESTAMP AND s.last_activity_at>CURRENT_TIMESTAMP-interval '30 minutes' AND u.status='ACTIVE' AND s.session_version=u.session_version AND s.auth_version=u.auth_version",sessionId);
        return actor(row,"");
    }
    /** 浏览器和后台任务共享当前角色及拒绝优先逻辑，不能使用过期的角色快照。 */
    private Actor actor(Map<String,Object> row,String csrfValue) {
        if (row==null) throw ApiFault.session();
        checkCatalogFence();
        UUID user=(UUID)row.get("id"); int auth=((Number)row.get("auth_version")).intValue(); ledger.check(user,auth);
        boolean setup=Boolean.TRUE.equals(row.get("password_setup_required"));
        List<Map<String,Object>> roles=setup?List.of():db.rows("SELECT r.* FROM iam.user_role ur JOIN iam.role r ON r.id=ur.role_id WHERE ur.user_id=? AND ur.state='ACTIVE' AND r.status='ACTIVE' AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP) ORDER BY r.module,r.code",user).stream().map(r->Sql.view(r,ROLE_FIELDS)).toList();
        Set<String> allow=new HashSet<>(), deny=new HashSet<>();
        if (!setup) {
            for (var p:db.rows("SELECT p.code,rp.effect FROM iam.user_role ur JOIN iam.role r ON r.id=ur.role_id JOIN iam.role_permission rp ON rp.role_id=r.id JOIN iam.permission p ON p.id=rp.permission_id AND p.module=r.module WHERE ur.user_id=? AND ur.state='ACTIVE' AND r.status='ACTIVE' AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP) AND rp.scope_policy='COMPANY' AND (r.module,r.code)<>('PLATFORM','IT_ADMIN')",user)) (p.get("effect").equals("DENY")?deny:allow).add((String)p.get("code"));
            for (var p:db.rows("SELECT p.code,pa.effect FROM iam.permission_assignment pa JOIN iam.permission p ON p.id=pa.permission_id WHERE pa.user_id=? AND pa.state='ACTIVE' AND pa.valid_from<=CURRENT_TIMESTAMP AND (pa.valid_until IS NULL OR pa.valid_until>CURRENT_TIMESTAMP)",user)) {
                // 本批没有个人增权写入口；已有明确拒绝仍具有最高优先级。
                if (p.get("effect").equals("DENY")) deny.add((String)p.get("code"));
            }
            allow.removeAll(deny);
            // 平台 IT 只展开固定管理动作，通用账号另有申请借阅所需的入口。
            if (roles.stream().anyMatch(r -> "PLATFORM".equals(r.get("module")) && "IT_ADMIN".equals(r.get("code")))) {
                for (var permission : db.rows("SELECT code FROM iam.permission WHERE iam.it_management_action(code)"))
                    allow.add((String) permission.get("code"));
                if (Boolean.TRUE.equals(row.get("it_shared_account")))
                    allow.addAll(Set.of("ARCHIVE.ACCESS", "ARCHIVE.PHYSICAL_BORROW", "ARCHIVE.IT_SHARED_BORROW"));
            }
            // 首位档案管理员的初始化能力单独展示；建立后自动消失，不等同档案角色管理权。
            if (allow.contains("PLATFORM.ROLES_MANAGE") && db.count("SELECT count(*) FROM iam.user_role WHERE module='ARCHIVE' AND role_code='SUPER_ADMIN'")==0) allow.add("ARCHIVE.INITIAL_ADMIN_ASSIGN");
        }
        return new Actor(user,(UUID)row.get("session_id"),auth,setup,Sql.view(row,USER_FIELDS),roles,Set.copyOf(allow),csrfValue);
    }
    /** 前端显式业务操作才续期；每三十秒最多落库一次，避免鼠标事件放大数据库写入。 */
    public void activity(Actor actor) { db.update("UPDATE iam.session SET last_activity_at=CURRENT_TIMESTAMP WHERE id=? AND revoked_at IS NULL AND last_activity_at<CURRENT_TIMESTAMP-interval '30 seconds'",actor.sessionId()); }
    /** 管理命令在事务锁内重新检查会话，封闭读鉴权和写入间的撤权竞态。 */
    public void lockActor(Actor actor) {
        db.required("SELECT id FROM iam.user_account WHERE id=? FOR UPDATE",actor.userId());
        if (db.count("SELECT count(*) FROM iam.session s JOIN iam.user_account u ON u.id=s.user_id WHERE s.id=? AND u.status='ACTIVE' AND u.auth_version=? AND u.auth_version=s.auth_version AND u.session_version=s.session_version AND s.revoked_at IS NULL AND s.expires_at>CURRENT_TIMESTAMP AND s.last_activity_at>CURRENT_TIMESTAMP-interval '30 minutes'",actor.sessionId(),actor.authVersion())!=1) throw ApiFault.session();
        ledger.check(actor.userId(),actor.authVersion());
    }
    /** 会话退出幂等；不撤销其他员工的会话。 */
    @Transactional
    public void logout(String token, UUID request) {
        if (token==null) return;
        var row=db.one("SELECT id,user_id FROM iam.session WHERE token_hash=? AND revoked_at IS NULL",Crypto.hash(token));
        if (row!=null) { db.update("UPDATE iam.session SET revoked_at=CURRENT_TIMESTAMP WHERE id=?",row.get("id")); audit.event((UUID)row.get("user_id"),"PLATFORM","SESSION",(UUID)row.get("id"),"LOGOUT","SUCCEEDED",request); }
    }
    /** 生成一次性 MANAGE 凭据；scopeItems 绑定实际路由动作与对象，不能跨操作复用。 */
    @Transactional(noRollbackFor=CredentialFault.class)
    public Map<String,Object> reauthenticate(Actor actor, Map<String,Object> body, UUID request) {
        Inputs.only(body,"password","purpose","objectIds","scopeItems"); lockActor(actor);
        Inputs.choice(body.get("purpose"),"MANAGE");
        String password=Inputs.password(body,"password");
        verifyPassword(actor,password,request);
        List<Map<String,Object>> items=Inputs.objects(body.get("scopeItems"),50);
        if (items.isEmpty() || !(body.get("objectIds") instanceof List<?> ids) || ids.size()>50) throw ApiFault.invalid("请指定本次管理动作和对象清单");
        Set<UUID> objects=new HashSet<>(); for (Object id:ids) if (!objects.add(Inputs.uuid(id))) throw ApiFault.invalid("对象清单不能重复");
        Set<UUID> described=new HashSet<>();
        for (var item:items) { Inputs.only(item,"objectType","objectId","actions"); Inputs.choice(item.get("objectType"),"USER","DEPARTMENT","ROLE","COLLECTION","DRIVE_COLLECTION","FOLDER","DRIVE_GRANT","DRIVE_DELEGATION","DRIVE_DOCUMENT","EDIT_SESSION","SAVE_RECEIPT","NOTIFICATION_DELIVERY","PHYSICAL_ITEM","PHYSICAL_LOAN","BORROW_REQUEST","ARCHIVE_COLLECTION","ARCHIVE_BOX","ARCHIVE_RECORD","ARCHIVE_REVISION","ARCHIVE_GRANT","ARCHIVE_POLICY","WORKFLOW_STEP","WORKFLOW_INSTANCE","WORK_CALENDAR","COMPANY_DUTY","IMPORT_BATCH","SCAN_BATCH","SCAN_ITEM","SCAN_DEVICE","PORTAL_ANNOUNCEMENT","PORTAL_SITE","PORTAL_FEEDBACK","AUDIT_EXPORT","AUDIT_ARCHIVE","AUDIT_RETENTION","SECURITY_ALERT","MAINTENANCE_RUN","BACKUP_PLAN"); described.add(Inputs.uuid(item.get("objectId"))); if (!(item.get("actions") instanceof List<?> actions) || actions.size()!=1 || !(actions.getFirst() instanceof String action) || !action.matches("[A-Z_]{1,64}")) throw ApiFault.invalid("管理动作格式不正确"); }
        if (!objects.equals(described)) throw ApiFault.invalid("管理对象与范围不一致");
        String digest=scope(actor,items), token=Crypto.token();
        db.update("INSERT INTO iam.credential_challenge(id,user_id,session_id,purpose,credential_hash,expires_at,scope_digest,flow_id) VALUES(?,?,?,'REAUTH',?,CURRENT_TIMESTAMP+interval '5 minutes',?,?)",UUID.randomUUID(),actor.userId(),actor.sessionId(),Crypto.hash(token),digest,UUID.randomUUID());
        audit.event(actor.userId(),"PLATFORM","SESSION",actor.sessionId(),"REAUTH","SUCCEEDED",request);
        return Sql.map("verificationToken",token,"purpose","MANAGE","scopeDigest",digest,"expiresAt",Instant.now().plusSeconds(300).toString());
    }
    /** 事务内原子消费凭据；业务失败时随同回滚，成功不能再次消费。 */
    public void consume(Actor actor, String token, String type, UUID target, String action) {
        consumeMany(actor,token,List.of(Sql.map("objectType",type,"objectId",target.toString(),"actions",List.of(action))));
    }
    /** 敏感或批量导出绑定完整修订清单，一次性凭据不能拆分、换动作或跨会话消费。 */
    public void consumeMany(Actor actor,String token,List<Map<String,Object>> items) {
        String expected=scope(actor,items);
        if(token==null||db.update("UPDATE iam.credential_challenge SET consumed_at=CURRENT_TIMESTAMP WHERE credential_hash=? AND user_id=? AND session_id=? AND purpose='REAUTH' AND scope_digest=? AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP",Crypto.hash(token),actor.userId(),actor.sessionId(),expected)!=1) throw new ApiFault(403,"REAUTH_REQUIRED","请重新验证本人密码后执行此操作");
    }
    /** 本人改密和首次设密；秘密永不进入幂等摘要或审计。 */
    @Transactional(noRollbackFor=CredentialFault.class)
    public Map<String,Object> password(Actor actor, Map<String,Object> body, boolean initial, UUID request) {
        Inputs.only(body,initial?"temporaryPassword":"currentPassword","newPassword"); lockActor(actor);
        if (initial!=actor.setupRequired()) throw ApiFault.denied();
        String current=Inputs.password(body,initial?"temporaryPassword":"currentPassword"), next=Inputs.newPassword(body,"newPassword");
        if(next.equals(actor.user().get("employeeNo")) || next.equals(current)) throw ApiFault.invalid("新密码须与工号及当前密码不同");
        verifyPassword(actor,current,request);
        if (initial) {
            UUID challenge=temporaryChallenge(actor.userId(),current);
            if(challenge==null || db.update("UPDATE iam.credential_challenge SET consumed_at=CURRENT_TIMESTAMP WHERE id=? AND consumed_at IS NULL",challenge)!=1) throw ApiFault.denied();
        }
        db.update("UPDATE iam.user_account SET password_hash=?,password_setup_required=false,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",Crypto.password(next),actor.userId());
        UUID operation=UUID.randomUUID(); invalidate(actor.userId(),"PASSWORD_CHANGE",operation);
        audit.event(actor.userId(),"PLATFORM","USER",actor.userId(),"PASSWORD_CHANGE","SUCCEEDED",request);
        return Sql.map("objectId",actor.userId(),"state","SUCCEEDED","operationId",operation);
    }
    /** 每次签发绑定独立编号以支持工号密码重复重置，同时兼容先前已发出的随机凭据。 */
    private UUID temporaryChallenge(UUID user,String password) {
        for(var row:db.rows("SELECT id,credential_hash FROM iam.credential_challenge WHERE user_id=? AND purpose='TEMP_PASSWORD' AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP",user))
            if(Crypto.equal(row.get("credential_hash").toString(),Crypto.hash(row.get("id")+":"+password)) || Crypto.equal(row.get("credential_hash").toString(),Crypto.hash(password))) return (UUID)row.get("id");
        return null;
    }
    /** 所有安全收紧先落独立账本；业务数据库较旧时不能重新登录恢复旧授权。 */
    public void invalidate(UUID user, String action, UUID operation) {
        var row=db.required("SELECT auth_version FROM iam.user_account WHERE id=? FOR UPDATE",user);
        int version=((Number)row.get("auth_version")).intValue()+1;
        ledger.restrict(user,version,action,operation);
        db.update("UPDATE iam.user_account SET auth_version=?,session_version=session_version+1 WHERE id=?",version,user);
        db.update("UPDATE iam.session SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=? AND revoked_at IS NULL",user);
        db.update("UPDATE iam.credential_challenge SET consumed_at=CURRENT_TIMESTAMP WHERE user_id=? AND purpose='REAUTH' AND consumed_at IS NULL",user);
    }
    /** 本人密码校验采用同一五次/十五分钟保护。 */
    private void verifyPassword(Actor actor,String raw,UUID request) {
        String key="account:"+actor.user().get("employeeNo"); checkThrottle(key,5);
        if (!Crypto.matches(raw,db.required("SELECT password_hash FROM iam.user_account WHERE id=?",actor.userId()).get("password_hash"))) { failThrottle(key,5); audit.event(actor.userId(),"PLATFORM","USER",actor.userId(),"VERIFY_PASSWORD","DENIED",request); throw new CredentialFault(401,"INVALID_CREDENTIALS","本人密码不正确"); }
    }
    /** 使用稳定序列化绑定用户、会话、目的和操作范围。 */
    private String scope(Actor actor,List<Map<String,Object>> items) { return Crypto.hash(Crypto.json(Sql.map("userId",actor.userId().toString(),"sessionId",actor.sessionId().toString(),"purpose","MANAGE","items",items.stream().sorted(Comparator.comparing(Crypto::json)).toList()))); }
    /** 锁住同一计数桶，防止并发试密越过阈值。 */
    private void checkThrottle(String key,int limit) {
        String bucket=ledger.bucket(key);
        db.update("INSERT INTO ops.auth_throttle(bucket) VALUES(?) ON CONFLICT DO NOTHING",bucket);
        var row=db.required("SELECT *,blocked_until>CURRENT_TIMESTAMP AS blocked FROM ops.auth_throttle WHERE bucket=? FOR UPDATE",bucket);
        if (Boolean.TRUE.equals(row.get("blocked"))) throw new CredentialFault(429,"LOGIN_OR_CHALLENGE_LOCKED","尝试次数过多，请十五分钟后重试");
        db.update("UPDATE ops.auth_throttle SET attempts=0,window_start=CURRENT_TIMESTAMP,blocked_until=NULL WHERE bucket=? AND window_start<CURRENT_TIMESTAMP-interval '15 minutes'",bucket);
    }
    /** 累计失败；不把成功检查误计为失败。 */
    private void failThrottle(String key,int limit) {
        String bucket=ledger.bucket(key);db.update("UPDATE ops.auth_throttle SET attempts=attempts+1,blocked_until=CASE WHEN attempts+1>=? THEN CURRENT_TIMESTAMP+interval '15 minutes' ELSE NULL END WHERE bucket=?",limit,bucket);
        if(key.startsWith("account:")&&db.count("SELECT count(*) FROM ops.auth_throttle WHERE bucket=? AND attempts=?",bucket,limit)==1){var user=db.one("SELECT id FROM iam.user_account WHERE employee_no=?",key.substring(8));messages.locked(bucket,user==null?null:(UUID)user.get("id"));}
    }
    /** CSRF 值从高熵会话派生，无法用该值反推 HttpOnly 登录凭据。 */
    public static String csrf(String token) { return Crypto.hash("ims-csrf:"+token); }
}
