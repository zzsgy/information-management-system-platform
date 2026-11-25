package com.company.platform;

import com.company.platform.intake.ImportService;
import com.company.platform.intake.ImportWorkbook;
import com.company.platform.workflow.EmployeeDeparture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实事务、认证与组织表验证人员编辑；合成账号只存在于本批独立库。 */
@EnabledIfEnvironmentVariable(named="IMS_EMPLOYEE_EDIT_INTEGRATION",matches="yes")
@SpringBootTest(classes=PlatformApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.config.name=platform","server.ssl.enabled=false","platform.messaging.schedule-enabled=false","platform.notifications.schedule-enabled=false","platform.file-worker.enabled=false","platform.scan-intake.worker-enabled=false","platform.operations.schedule-enabled=false","platform.employee-import.local-workbook=true"})
class EmployeeProfileIntegrationTest {
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        if(!System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:55475/ims_platform_dev_[0-9]+"))throw new IllegalStateException("只允许人员编辑专用隔离库");
    }
    @Autowired Sql db;
    @Autowired IdentityService identity;
    @Autowired DirectoryService directory;
    @Autowired Commands commands;
    @Autowired AccountRecovery recovery;
    @Autowired AccountController accounts;
    @Autowired ImportService imports;
    @Autowired EmployeeDeparture departures;
    @Autowired Audit audit;
    private Actor admin;
    private UUID employee;
    private static final String PASSWORD="isolated-profile-password";
    @BeforeEach void setup(){admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));employee=person();}
    /** 不让本用例的合成管理员参与后续流程用例的真实审批人选择。 */
    @AfterEach void endFixtureRoles(){db.update("UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP WHERE user_id=? AND state='ACTIVE'",employee);}
    private UUID person(){UUID id=UUID.randomUUID();db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,id.toString(),"隔离编辑员工",admin.user().get("departmentId"),id+"@biolink.com",Crypto.password(PASSWORD));return id;}
    private Actor login(String number,String password){return identity.authenticate((String)identity.login(Sql.map("employeeNo",number,"password",password),UUID.randomUUID(),"profile-"+UUID.randomUUID()).get("token"));}
    /** 网页登录名同时支持工号和中文全名，旧客户端继续用工号字段。 */
    private Actor loginName(String name,String password){
        return identity.authenticate((String)identity.login(Sql.map("loginName",name,"password",password),UUID.randomUUID(),"profile-"+UUID.randomUUID()).get("token"));
    }
    private UUID department(){return (UUID)db.required("SELECT id FROM iam.department WHERE code='AD'").get("id");}
    private void role(UUID person,String module,String code,UUID department){db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,module,role_code) SELECT ?,?,id,?,CURRENT_TIMESTAMP,?,module,code FROM iam.role WHERE module=? AND code=?",UUID.randomUUID(),person,department,admin.userId(),module,code);}
    private Commands.Context context(Actor operator,String password,UUID target,int version){var proof=identity.reauthenticate(operator,Sql.map("password",password,"purpose","MANAGE","objectIds",List.of(target),"scopeItems",List.of(Sql.map("objectType","USER","objectId",target,"actions",List.of("UPDATE_USER")))),UUID.randomUUID());return new Commands.Context(operator,UUID.randomUUID(),UUID.randomUUID(),"PATCH profile "+target,(String)proof.get("verificationToken"),version);}
    private Commands.Context context(int version){return context(admin,System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"),employee,version);}
    private Map<String,Object> save(Commands.Context ctx,UUID target,Map<String,Object> body){return commands.run(ctx,body,"USER",target,"UPDATE_USER",()->ctx.actor().require("PLATFORM.USERS_MANAGE"),()->directory.updateUser(ctx,target,body),directory::user);}
    /** 新增与编辑共用名片资料，保留账号邮箱规则及历史兼容。 */
    @Test void directoryDetailsRoundTripAndCanBeCleared(){
        UUID target=ApiController.COLLECTION;
        var proof=identity.reauthenticate(admin,Sql.map("password",System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"),
            "purpose","MANAGE","objectIds",List.of(target),"scopeItems",
            List.of(Sql.map("objectType","COLLECTION","objectId",target,"actions",List.of("CREATE_USER")))),UUID.randomUUID());
        var ctx=new Commands.Context(admin,UUID.randomUUID(),UUID.randomUUID(),"CREATE employee details",
            (String)proof.get("verificationToken"),null);
        var body=Sql.map("employeeNo","PROFILE-"+employee,"name","名片隔离员工","departmentId",department(),
            "companyEmail",employee+"-card@biolink.com","organizationName","百林科测试组织","jobTitle","工程师",
            "supervisorId",employee,"mobilePhone","+86-13800000000","contactEmail","contact@example.com");
        var result=commands.run(ctx,body,"COLLECTION",target,"CREATE_USER",()->admin.require("PLATFORM.USERS_MANAGE"),
            ()->directory.createUser(ctx,body),directory::user);
        UUID id=(UUID)result.get("id");
        assertEquals("百林科测试组织",result.get("organizationName"));
        assertEquals("工程师",result.get("jobTitle"));
        assertEquals(employee,result.get("supervisorId"));
        assertEquals("+86-13800000000",result.get("mobilePhone"));
        assertEquals("contact@example.com",result.get("contactEmail"));
        var update=context(admin,System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"),id,1);
        assertEquals(400,assertThrows(ApiFault.class,()->save(update,id,Sql.map("supervisorId",id))).status);
        var edited=save(update,id,Sql.map("jobTitle","高级工程师","contactEmail",null,"supervisorId",null));
        assertEquals("高级工程师",edited.get("jobTitle"));
        assertNull(edited.get("contactEmail"));
        assertNull(edited.get("supervisorId"));
        assertEquals(result.get("mobilePhone"),edited.get("mobilePhone"));
    }

    @Test void allFourFieldsSaveAtomicallyAndReplayKeepsOneHistory(){
        role(employee,"PLATFORM","IT_ADMIN",null);role(employee,"ARCHIVE","SUPER_ADMIN",null);
        role(employee,"DRIVE","DEPARTMENT_MANAGER",(UUID)admin.user().get("departmentId"));
        Actor old=login(employee.toString(),PASSWORD);var ctx=context(1);String number="EDIT-"+employee;
        var body=Sql.map("employeeNo",number,"name","已更正姓名","departmentId",department(),"companyEmail","new-"+employee+"@biolink.com","changeReason","组织资料更正");
        var result=save(ctx,employee,body);save(ctx,employee,body);
        assertEquals(number,result.get("employeeNo"));assertEquals("已更正姓名",result.get("name"));assertEquals(department(),result.get("departmentId"));assertEquals(2,result.get("rowVersion"));
        assertEquals(1,db.count("SELECT count(*) FROM iam.department_change WHERE user_id=?",employee));
        assertEquals(2,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",employee));
        assertEquals(0,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND department_id IS NOT NULL AND state='ACTIVE'",employee));
        assertThrows(ApiFault.class,()->identity.background(old.sessionId()));
        assertThrows(CredentialFault.class,()->login(employee.toString(),PASSWORD));assertTrue(login(number,PASSWORD).systemAdministrator());
    }
    @Test void unchangedIdentityDoesNotRevokeSessionOrNeedTransferReason(){
        Actor old=login(employee.toString(),PASSWORD);save(context(1),employee,Sql.map("employeeNo",employee.toString(),"departmentId",admin.user().get("departmentId"),"name","改姓名"));
        assertNotNull(identity.background(old.sessionId()));assertEquals(0,db.count("SELECT count(*) FROM iam.department_change WHERE user_id=?",employee));
    }
    @Test void duplicateEmployeeNumberRollsBackOtherFields(){
        UUID other=person();ApiFault failure=assertThrows(ApiFault.class,()->save(context(1),employee,Sql.map("employeeNo",other.toString(),"name","不应保存","departmentId",department(),"changeReason","更正")));
        assertEquals(409,failure.status);assertTrue(failure.getMessage().contains("工号"));assertEquals("隔离编辑员工",directory.user(employee).get("name"));assertEquals(0,db.count("SELECT count(*) FROM iam.department_change WHERE user_id=?",employee));
    }
    @Test void duplicateEmailRollsBackDepartmentAndKeepsSession(){
        UUID other=person();Actor old=login(employee.toString(),PASSWORD);
        assertEquals(409,assertThrows(ApiFault.class,()->save(context(1),employee,Sql.map("companyEmail",other+"@biolink.com","departmentId",department(),"changeReason","更正"))).status);
        assertNotNull(identity.background(old.sessionId()));assertEquals(admin.user().get("departmentId"),directory.user(employee).get("departmentId"));
    }
    @Test void staleVersionAndInactiveDepartmentCannotPartiallySave(){
        assertEquals(409,assertThrows(ApiFault.class,()->save(context(99),employee,Sql.map("name","不应保存"))).status);
        UUID inactive=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name,status) VALUES(?,?,?,'DISABLED')",inactive,inactive.toString(),"已停用隔离部门");
        assertThrows(ApiFault.class,()->save(context(1),employee,Sql.map("name","不应保存","departmentId",inactive,"changeReason","更正")));
        assertEquals("隔离编辑员工",directory.user(employee).get("name"));
    }
    @Test void clerkAndApproverDutiesNeedExplicitHandover(){
        UUID unit=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",unit,unit.toString(),"隔离职责部门");db.update("UPDATE iam.user_account SET department_id=? WHERE id=?",unit,employee);role(employee,"ARCHIVE","DEPARTMENT_ARCHIVIST",unit);
        ApiFault failure=assertThrows(ApiFault.class,()->save(context(1),employee,Sql.map("name","不应保存","departmentId",department(),"changeReason","更正")));
        assertEquals(409,failure.status);assertTrue(failure.getMessage().contains("交接"));assertEquals(unit,directory.user(employee).get("departmentId"));
    }
    @Test void delegatedPersonnelManagerCannotChangeProtectedIdentityFields(){
        UUID operator=person(),supportRole=UUID.randomUUID();String code="SUPPORT_"+supportRole.toString().substring(0,8);
        db.update("INSERT INTO iam.role(id,module,code,name) VALUES(?,'PLATFORM',?,'隔离人员维护')",supportRole,code);
        db.update("INSERT INTO iam.role_permission(role_id,permission_id,scope_policy,effect,changed_by) SELECT ?,id,'COMPANY','ALLOW',? FROM iam.permission WHERE code='PLATFORM.USERS_MANAGE'",supportRole,admin.userId());
        role(operator,"PLATFORM",code,null);Actor support=login(operator.toString(),PASSWORD);
        assertEquals(403,assertThrows(ApiFault.class,()->save(context(support,PASSWORD,employee,1),employee,Sql.map("employeeNo","DENIED-"+employee))).status);
        assertEquals(403,assertThrows(ApiFault.class,()->save(context(support,PASSWORD,employee,1),employee,Sql.map("departmentId",department(),"changeReason","越权"))).status);
        assertEquals("可维护姓名",save(context(support,PASSWORD,employee,1),employee,Sql.map("name","可维护姓名")).get("name"));
    }
    @Test void itCanCorrectOwnIdentityAndRetainAdministratorRole(){
        role(employee,"PLATFORM","IT_ADMIN",null);Actor operator=login(employee.toString(),PASSWORD);String number="SELF-"+employee;
        save(context(operator,PASSWORD,employee,1),employee,Sql.map("employeeNo",number,"departmentId",department(),"changeReason","本人资料更正"));
        assertThrows(ApiFault.class,()->identity.background(operator.sessionId()));assertTrue(login(number,PASSWORD).systemAdministrator());
    }
    /** 实际创建、首次改密和重复重置沿用 Commands 事务，不复制生产密码判定。 */
    private Commands.Context credentialContext(String type,UUID id,String action,Integer version){
        var proof=identity.reauthenticate(admin,Sql.map("password",System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"),"purpose","MANAGE","objectIds",List.of(id),"scopeItems",List.of(Sql.map("objectType",type,"objectId",id,"actions",List.of(action)))),UUID.randomUUID());
        return new Commands.Context(admin,UUID.randomUUID(),UUID.randomUUID(),"TEST "+action+" "+id,(String)proof.get("verificationToken"),version);
    }
    /** 办理离职后旧会话作废，源业务记录不改写，独立归档可按来源查询。 */
    @Test void departureRevokesAccessAndPreservesIndependentHistory(){
        role(employee,"PLATFORM","MEMBER",null);
        role(employee,"ARCHIVE","STAFF",null);
        Actor before=login(employee.toString(),PASSWORD);
        UUID rolePermission=UUID.randomUUID(), todo=UUID.randomUUID(), request=UUID.randomUUID();
        db.update("""
                INSERT INTO iam.permission_assignment(id,user_id,permission_id,scope_type,effect,valid_from,granted_by)
                SELECT ?,?,id,'COMPANY','DENY',CURRENT_TIMESTAMP,? FROM iam.permission WHERE code='ARCHIVE.ACCESS'
                """,rolePermission,employee,admin.userId());
        db.update("INSERT INTO portal.todo_desktop(user_id,token_hash,auth_version) VALUES(?,?,?)",
                employee,Crypto.hash("departure-test-"+employee),before.authVersion());
        db.update("""
                INSERT INTO portal.employee_todo(id,creator_id,assignee_id,title,operation_id,request_digest)
                VALUES(?,?,?,'隔离未办待办',?,?)
                """,todo,admin.userId(),employee,UUID.randomUUID(),Crypto.hash(todo.toString()));
        audit.event(employee,"PLATFORM","USER",employee,"DEPARTURE_FIXTURE","SUCCEEDED",request);
        var body=Sql.map("reason","离职隔离验收");
        var bad=credentialContext("USER",employee,"EMPLOYEE_DEPARTURE",99);
        assertEquals(409,assertThrows(ApiFault.class,()->departures.depart(bad,employee,body)).status);
        assertEquals(0,db.count("SELECT count(*) FROM iam.employee_departure WHERE user_id=?",employee));
        assertNotNull(identity.background(before.sessionId()));

        var ctx=credentialContext("USER",employee,"EMPLOYEE_DEPARTURE",1);
        var archived=departures.depart(ctx,employee,body);
        UUID id=(UUID)archived.get("id");
        assertEquals(id,departures.depart(ctx,employee,body).get("id"));
        assertEquals(1,db.count("SELECT count(*) FROM iam.employee_departure WHERE user_id=?",employee));
        assertEquals("DEPARTED",directory.user(employee).get("status"));
        assertEquals(0,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",employee));
        assertEquals("REVOKED",db.required("SELECT state FROM iam.permission_assignment WHERE id=?",rolePermission).get("state"));
        assertEquals(0,db.count("SELECT count(*) FROM portal.todo_desktop WHERE user_id=?",employee));
        assertThrows(ApiFault.class,()->identity.background(before.sessionId()));
        assertThrows(CredentialFault.class,()->login(employee.toString(),PASSWORD));
        assertEquals(409,assertThrows(ApiFault.class,()->directory.status(context(2),employee,Sql.map("status","ACTIVE","reason","误启用"))).status);
        assertEquals(1,db.count("SELECT count(*) FROM ops.audit_event WHERE request_id=? AND actor_id=?",request,employee));
        assertEquals(1,db.count("SELECT count(*) FROM iam.employee_departure_record WHERE departure_id=? AND record_type='AUDIT' AND snapshot->>'requestId'=?",id,request.toString()));
        assertEquals(1,db.count("SELECT count(*) FROM iam.employee_departure_record WHERE departure_id=? AND record_type='TODO' AND source_id=? AND snapshot->>'state'='OPEN'",id,todo));
        assertEquals("OPEN",db.required("SELECT state FROM portal.employee_todo WHERE id=?",todo).get("state"));
        assertEquals(1,((List<?>) departures.records(admin,id,Map.of("recordType","TODO")).get("items")).size());
        assertTrue(departures.history(admin,Map.of("keyword",employee.toString())).get("items").toString().contains(id.toString()));
        Actor outsider=login(person().toString(),PASSWORD);
        assertEquals(403,assertThrows(ApiFault.class,()->departures.records(outsider,id,Map.of())).status);
    }
    private Map<String,Object> createWithEmployeePassword(){
        var body=Sql.map("employeeNo","PWD"+UUID.randomUUID(),"name","隔离密码员工","departmentId",admin.user().get("departmentId"),"companyEmail",UUID.randomUUID()+"@biolink.com",
            "jobTitle","测试员工","supervisorId",admin.userId(),"mobilePhone","+86 13800000000");
        var ctx=credentialContext("COLLECTION",ApiController.COLLECTION,"CREATE_USER",null);
        var result=commands.run(ctx,body,"COLLECTION",ApiController.COLLECTION,"CREATE_USER",()->admin.require("PLATFORM.USERS_MANAGE"),()->directory.createUser(ctx,body),directory::user);
        assertEquals(result,commands.run(ctx,body,"COLLECTION",ApiController.COLLECTION,"CREATE_USER",()->admin.require("PLATFORM.USERS_MANAGE"),()->directory.createUser(ctx,body),directory::user));
        return result;
    }
    private Map<String,Object> resetCredential(Commands.Context ctx,UUID target){var body=Sql.map("reason","隔离初始密码验收");return commands.run(ctx,body,"USER",target,"RESET_PASSWORD",()->admin.require("PLATFORM.USERS_MANAGE"),()->recovery.reset(ctx,target,body),key->Sql.map("objectId",key));}
    @Test void employeeNumberLoginRequiresRealPasswordChange(){
        var result=createWithEmployeePassword();UUID user=Inputs.uuid(result.get("id"));String no=result.get("employeeNo").toString();
        String fullName="黎牧舟";
        db.update("UPDATE iam.user_account SET name=? WHERE id=?",fullName,user);
        assertThrows(CredentialFault.class,()->loginName(fullName,no));
        Actor initial=loginName(no,no);
        assertTrue(initial.setupRequired());assertTrue(initial.roles().isEmpty());assertThrows(ApiFault.class,()->initial.require("PLATFORM.USERS_MANAGE"));
        for(String weak:List.of("12345678","abcdefgh","Abc1234",no))assertThrows(ApiFault.class,()->identity.password(initial,Sql.map("temporaryPassword",no,"newPassword",weak),true,UUID.randomUUID()));
        identity.password(initial,Sql.map("temporaryPassword",no,"newPassword","Welcome8"),true,UUID.randomUUID());
        assertThrows(ApiFault.class,()->identity.background(initial.sessionId()));assertThrows(CredentialFault.class,()->login(no,no));
        Actor active=login(no,"Welcome8");
        assertFalse(active.setupRequired());
        assertEquals(user,loginName(fullName,"Welcome8").userId());
        assertEquals(List.of("ARCHIVE.STAFF","DRIVE.STAFF","PLATFORM.MEMBER"),
                active.roles().stream().map(role->role.get("module")+"."+role.get("code")).toList());
        assertEquals(0,db.count("SELECT count(*) FROM iam.credential_challenge WHERE user_id=? AND purpose='TEMP_PASSWORD' AND consumed_at IS NULL",user));
    }

    /** 完整中文名与工号进入同一账号，简称、非在用人员和重名均不能通过姓名登录。 */
    @Test void normalEmployeeLoginAcceptsOnlyAnUnambiguousFullChineseName() {
        String fullName="程子墨";
        db.update("UPDATE iam.user_account SET name=? WHERE id=?",fullName,employee);
        Actor byName=loginName("  "+fullName+"  ",PASSWORD);
        assertEquals(employee,byName.userId());
        assertEquals(employee,loginName(employee.toString(),PASSWORD).userId());
        assertThrows(ApiFault.class,()->identity.background(byName.sessionId()));
        assertThrows(CredentialFault.class,()->loginName("程子",PASSWORD));
        assertThrows(CredentialFault.class,()->login(fullName,PASSWORD));
        UUID duplicate=person();
        db.update("UPDATE iam.user_account SET name=? WHERE id=?",fullName,duplicate);
        assertThrows(CredentialFault.class,()->loginName(fullName,PASSWORD));
        assertEquals(employee,login(employee.toString(),PASSWORD).userId());
        assertEquals(duplicate,loginName(duplicate.toString(),PASSWORD).userId());
        db.update("UPDATE iam.user_account SET status='DISABLED' WHERE id IN (?,?)",employee,duplicate);
        assertThrows(CredentialFault.class,()->loginName(fullName,PASSWORD));
    }

    /** 切换工号或中文名不会重置同一个账号的错误次数。 */
    @Test void chineseNameAndEmployeeNumberShareLoginLockout() {
        String fullName="陆景清";
        db.update("UPDATE iam.user_account SET name=? WHERE id=?",fullName,employee);
        for(int i=0;i<4;i++) assertEquals(401,assertThrows(CredentialFault.class,()->loginName(fullName,"incorrect")).status);
        assertEquals(401,assertThrows(CredentialFault.class,()->login(employee.toString(),"incorrect")).status);
        assertEquals(429,assertThrows(CredentialFault.class,()->loginName(fullName,PASSWORD)).status);
        assertEquals(429,assertThrows(CredentialFault.class,()->login(employee.toString(),PASSWORD)).status);
    }

    @Test void employeeWorkbookAssignsAllDefaultRoles() {
        String no="IMPORT-"+employee;
        byte[] workbook=ImportWorkbook.workbook("EMPLOYEE",List.of(Sql.map("employeeNo",no,
                "name","隔离导入员工","departmentCode","AD","companyEmail",no+"@biolink.com")));
        var preview=imports.createEmployeeWorkbook(credentialContext("IMPORT_BATCH",ApiController.COLLECTION,"CREATE_IMPORT",null),
                Sql.map("workbook",Base64.getEncoder().encodeToString(workbook),"templateVersion",ImportWorkbook.VERSION));
        assertEquals("READY",preview.get("state"),preview.toString());
        UUID batch=Inputs.uuid(preview.get("id"));
        var confirmed=imports.confirm(credentialContext("IMPORT_BATCH",batch,"CONFIRM_IMPORT",1),batch,
                Sql.map("validationDigest",preview.get("validationDigest")));
        assertEquals("CONFIRMED",confirmed.get("state"));
        var roles=db.rows("""
                SELECT ur.module || '.' || ur.role_code AS role FROM iam.user_role ur
                JOIN iam.user_account u ON u.id=ur.user_id
                WHERE u.employee_no=? AND ur.assigned_by=? AND ur.state='ACTIVE' ORDER BY ur.module
                """,no,admin.userId());
        assertEquals(List.of("ARCHIVE.STAFF","DRIVE.STAFF","PLATFORM.MEMBER"),
                roles.stream().map(role->role.get("role")).toList());
    }
    @Test void repeatedResetOfSameEmployeeNumberIsValidAndIdempotent(){
        var result=createWithEmployeePassword();UUID user=Inputs.uuid(result.get("id"));String no=result.get("employeeNo").toString();Actor old=login(no,no);
        var first=credentialContext("USER",user,"RESET_PASSWORD",1);resetCredential(first,user);resetCredential(first,user);
        assertThrows(ApiFault.class,()->identity.background(old.sessionId()));assertEquals(2,db.count("SELECT count(*) FROM iam.credential_challenge WHERE user_id=? AND purpose='TEMP_PASSWORD'",user));
        var second=credentialContext("USER",user,"RESET_PASSWORD",2);resetCredential(second,user);
        assertEquals(3,db.count("SELECT count(*) FROM iam.credential_challenge WHERE user_id=? AND purpose='TEMP_PASSWORD'",user));assertTrue(login(no,no).setupRequired());
        assertEquals(1,db.count("SELECT count(*) FROM iam.credential_challenge WHERE user_id=? AND purpose='TEMP_PASSWORD' AND consumed_at IS NULL",user));
    }
    /** 真正执行批量入口，覆盖多页、过滤、在用状态、单次凭据和幂等重试。 */
    @Test void batchResetsOnlyPendingAccountsAcrossPages() {
        String keyword = "批量隔离-" + UUID.randomUUID();
        var issued = createWithEmployeePassword();
        UUID pending = Inputs.uuid(issued.get("id"));
        String pendingNo = issued.get("employeeNo").toString();
        Actor oldPending = login(pendingNo,pendingNo);
        Actor normal = login(employee.toString(),PASSWORD);
        UUID disabled = person(), departed = person(), outside = person(), otherEmail = person();
        String emailFilter = keyword.substring(5);
        String matchingEmail = emailFilter + "@biolink.com";
        db.update("UPDATE iam.user_account SET name=?,company_email=? WHERE id=?",keyword,matchingEmail,pending);
        db.update("UPDATE iam.user_account SET name=?,company_email=? WHERE id=?",keyword,"normal-"+matchingEmail,employee);
        db.update("UPDATE iam.user_account SET name=?,company_email=?,password_setup_required=true,status='DISABLED' WHERE id=?",keyword,"disabled-"+matchingEmail,disabled);
        db.update("UPDATE iam.user_account SET name=?,company_email=?,password_setup_required=true,status='DEPARTED' WHERE id=?",keyword,"departed-"+matchingEmail,departed);
        db.update("UPDATE iam.user_account SET name=?,company_email=?,password_setup_required=true WHERE id=?","其他人员","other-"+matchingEmail,outside);
        db.update("UPDATE iam.user_account SET name=?,password_setup_required=true WHERE id=?",keyword,otherEmail);
        String oldHash = Crypto.password(PASSWORD);
        var crossPage = new ArrayList<UUID>();
        for(int i=0;i<20;i++){
            UUID id=UUID.randomUUID();
            db.update("""
                    INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required)
                    VALUES(?,?,?,?,?,?,true)
                    """,id,id.toString(),keyword,admin.user().get("departmentId"),i+"-"+matchingEmail,oldHash);
            crossPage.add(id);
        }
        var filters=Map.of("keyword",keyword,"departmentId",admin.user().get("departmentId").toString(),"email",emailFilter);
        assertEquals(20,((List<?>)directory.users(admin,filters).get("items")).size());
        var incorrect=batchPasswordRequest(credentialContext("COLLECTION",ApiController.COLLECTION,"CREATE_USER",null),admin);
        assertEquals(403,assertThrows(ApiFault.class,()->accounts.resetPending(filters,incorrect)).status);
        var ctx=credentialContext("COLLECTION",ApiController.COLLECTION,AccountRecovery.RESET_PENDING_PASSWORDS,null);
        assertEquals(403,assertThrows(ApiFault.class,()->accounts.resetPending(filters,batchPasswordRequest(ctx,normal))).status);
        var request=batchPasswordRequest(ctx,admin);
        var first=(Map<?,?>)accounts.resetPending(filters,request).get("data");
        var replay=(Map<?,?>)accounts.resetPending(filters,request).get("data");
        assertEquals(21,((Number)first.get("resetCount")).intValue());
        assertEquals(21,((Number)replay.get("resetCount")).intValue());
        assertEquals(first.get("objectId"),replay.get("objectId"));
        assertEquals(409,assertThrows(ApiFault.class,()->accounts.resetPending(Map.of("keyword","改动筛选"),request)).status);
        assertThrows(ApiFault.class,()->identity.background(oldPending.sessionId()));
        assertTrue(login(pendingNo,pendingNo).setupRequired());
        assertTrue(login(crossPage.getLast().toString(),crossPage.getLast().toString()).setupRequired());
        assertEquals(normal.sessionId(),identity.background(normal.sessionId()).sessionId());
        for(UUID id:List.of(employee,disabled,departed,outside,otherEmail)){
            var saved=db.required("SELECT row_version,password_hash FROM iam.user_account WHERE id=?",id);
            assertEquals(1,saved.get("row_version"));
            assertTrue(Crypto.matches(PASSWORD,saved.get("password_hash")));
        }
        assertEquals(1,db.count("SELECT count(*) FROM iam.credential_challenge WHERE user_id=? AND purpose='TEMP_PASSWORD' AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP+interval '23 hours'",pending));
        assertEquals(2,db.count("SELECT count(*) FROM iam.credential_challenge WHERE user_id=? AND purpose='TEMP_PASSWORD'",pending));
        var empty=(Map<?,?>)accounts.resetPending(Map.of("keyword",UUID.randomUUID().toString()),
            batchPasswordRequest(credentialContext("COLLECTION",ApiController.COLLECTION,AccountRecovery.RESET_PENDING_PASSWORDS,null),admin)).get("data");
        assertEquals(0,((Number)empty.get("resetCount")).intValue());
    }

    private org.springframework.mock.web.MockHttpServletRequest batchPasswordRequest(Commands.Context ctx,Actor actor) {
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/admin/users/pending-password-reset");
        request.setAttribute(HttpBoundary.ACTOR,actor);
        request.setAttribute(HttpBoundary.REQUEST,ctx.request());
        request.addHeader("X-Operation-Id",ctx.operation().toString());
        request.addHeader("X-Reauth-Token",ctx.reauth());
        return request;
    }

    @Test void expiredInitialPasswordAndDisabledAccountAreRejected(){
        var result=createWithEmployeePassword();UUID user=Inputs.uuid(result.get("id"));String no=result.get("employeeNo").toString();
        db.update("UPDATE iam.credential_challenge SET issued_at=CURRENT_TIMESTAMP-interval '2 days',expires_at=CURRENT_TIMESTAMP-interval '1 day' WHERE user_id=?",user);
        assertThrows(CredentialFault.class,()->login(no,no));db.update("UPDATE iam.user_account SET status='DISABLED' WHERE id=?",user);
        assertThrows(ApiFault.class,()->resetCredential(credentialContext("USER",user,"RESET_PASSWORD",1),user));
    }
    @Test void personalPasswordChangeEnforcesNewRulesButOldLoginRemainsValid(){
        Actor user=login(employee.toString(),PASSWORD);
        assertThrows(ApiFault.class,()->identity.password(user,Sql.map("currentPassword",PASSWORD,"newPassword","12345678"),false,UUID.randomUUID()));
        identity.password(user,Sql.map("currentPassword",PASSWORD,"newPassword","Changed9"),false,UUID.randomUUID());
        assertFalse(login(employee.toString(),"Changed9").setupRequired());
    }
    @Test void renumberingPendingAccountRefreshesInitialPassword(){
        var result=createWithEmployeePassword();UUID user=Inputs.uuid(result.get("id"));String oldNo=result.get("employeeNo").toString(),nextNo="NEW"+UUID.randomUUID();
        save(context(admin,System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"),user,1),user,Sql.map("employeeNo",nextNo));
        assertThrows(CredentialFault.class,()->login(nextNo,oldNo));assertTrue(login(nextNo,nextNo).setupRequired());
    }
    @Test void ordinaryUserCannotEditOtherPerson(){
        Actor user=login(employee.toString(),PASSWORD);UUID target=person();
        assertEquals(403,assertThrows(ApiFault.class,()->save(context(user,PASSWORD,target,1),target,Sql.map("name","越权修改"))).status);
    }
}
