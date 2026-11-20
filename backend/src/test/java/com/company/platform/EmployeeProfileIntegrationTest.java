package com.company.platform;

import com.company.platform.intake.ImportService;
import com.company.platform.intake.ImportWorkbook;
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
    @Autowired ImportService imports;
    private Actor admin;
    private UUID employee;
    private static final String PASSWORD="isolated-profile-password";
    @BeforeEach void setup(){admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));employee=person();}
    /** 不让本用例的合成管理员参与后续流程用例的真实审批人选择。 */
    @AfterEach void endFixtureRoles(){db.update("UPDATE iam.user_role SET state='ENDED',valid_until=CURRENT_TIMESTAMP WHERE user_id=? AND state='ACTIVE'",employee);}
    private UUID person(){UUID id=UUID.randomUUID();db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,id.toString(),"隔离编辑员工",admin.user().get("departmentId"),id+"@biolink.com",Crypto.password(PASSWORD));return id;}
    private Actor login(String number,String password){return identity.authenticate((String)identity.login(Sql.map("employeeNo",number,"password",password),UUID.randomUUID(),"profile-"+UUID.randomUUID()).get("token"));}
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
        var result=createWithEmployeePassword();UUID user=Inputs.uuid(result.get("id"));String no=result.get("employeeNo").toString();Actor initial=login(no,no);
        assertTrue(initial.setupRequired());assertTrue(initial.roles().isEmpty());assertThrows(ApiFault.class,()->initial.require("PLATFORM.USERS_MANAGE"));
        for(String weak:List.of("12345678","abcdefgh","Abc1234",no))assertThrows(ApiFault.class,()->identity.password(initial,Sql.map("temporaryPassword",no,"newPassword",weak),true,UUID.randomUUID()));
        identity.password(initial,Sql.map("temporaryPassword",no,"newPassword","Welcome8"),true,UUID.randomUUID());
        assertThrows(ApiFault.class,()->identity.background(initial.sessionId()));assertThrows(CredentialFault.class,()->login(no,no));
        Actor active=login(no,"Welcome8");
        assertFalse(active.setupRequired());
        assertEquals(List.of("ARCHIVE.STAFF","DRIVE.STAFF","PLATFORM.MEMBER"),
                active.roles().stream().map(role->role.get("module")+"."+role.get("code")).toList());
        assertEquals(0,db.count("SELECT count(*) FROM iam.credential_challenge WHERE user_id=? AND purpose='TEMP_PASSWORD' AND consumed_at IS NULL",user));
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
