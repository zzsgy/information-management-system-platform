package com.company.platform;

import com.company.platform.operations.*;
import com.company.platform.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实独立库验证 IT 身份配置、密码再验证、即时绑定和拒绝行为，不连接部署环境。 */
@EnabledIfEnvironmentVariable(named="IMS_SERVICE_ADMIN_INTEGRATION",matches="yes")
@SpringBootTest(classes=PlatformApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={"spring.config.name=platform","server.ssl.enabled=false","platform.file-worker.enabled=false",
    "platform.scan-intake.worker-enabled=false","platform.operations.schedule-enabled=false",
    "platform.notifications.schedule-enabled=false","platform.messaging.schedule-enabled=false"})
class ServiceAdministrationIntegrationTest {
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry ignored) {
        if(!Objects.toString(System.getenv("IMS_PLATFORM_DB_URL"),"").matches("jdbc:postgresql://127\\.0\\.0\\.1:55514/ims_platform_dev_[0-9]+"))
            throw new IllegalStateException("只允许55514独立验收库");
    }
    @Autowired Sql db;
    @Autowired IdentityService identity;
    @Autowired ServiceAdministration administration;
    @Autowired ServiceIdentities services;
    @Autowired Commands commands;
    @Autowired ServiceHealth health;
    private Actor admin,staff;
    private UUID department;
    private final UUID target=UUID.fromString("50000000-0000-4000-8000-000000000003");
    @BeforeEach void arrange() {
        admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));
        department=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",department,"SVC-"+department,"服务配置隔离部门");
        UUID user=UUID.randomUUID();String no="SVC-"+user,password=Crypto.token();
        db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",
                user,no,"服务配置验收员工",department,no+"@biolink.com",Crypto.password(password));
        staff=login(no,password);
    }
    private Actor login(String no,String password) {return identity.authenticate(identity.login(Sql.map("employeeNo",no,"password",password),UUID.randomUUID(),"service-admin-test").get("token").toString());}
    private Map<String,Object> body(Map<String,Object> current) {return Sql.map("name","人员台账检测验收","departmentIds",List.of(department.toString()),
            "validUntil",Instant.now().plusSeconds(365L*86400).toString(),"status","ACTIVE","identityVersion",current.get("identityVersion"),"reason","独立库服务配置验收");}
    private Map<String,Object> save(Map<String,Object> current,Map<String,Object> body,boolean verified) {
        String token=null;
        if(verified) token=identity.reauthenticate(admin,Sql.map("password",System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"),"purpose","MANAGE","objectIds",List.of(target.toString()),
                "scopeItems",List.of(Sql.map("objectType","SERVICE_BINDING","objectId",target.toString(),"actions",List.of("CONFIGURE_SERVICE")))) ,UUID.randomUUID()).get("verificationToken").toString();
        var ctx=new Commands.Context(admin,UUID.randomUUID(),UUID.randomUUID(),"PUT /api/v1/admin/operations/service-configurations/"+target,token,((Number)current.get("rowVersion")).intValue());
        return commands.run(ctx,body,"SERVICE_BINDING",target,"CONFIGURE_SERVICE",()->administration.authorize(admin),()->administration.save(ctx,target,body),id->administration.get(admin,id));
    }
    @Test void ordinaryStaffAndUnverifiedWritesCannotChangeService() {
        assertEquals(403,assertThrows(ApiFault.class,()->administration.list(staff)).status);
        assertEquals(403,assertThrows(ApiFault.class,()->administration.check(staff,UUID.randomUUID())).status);
        var current=administration.get(admin,target);
        var forbidden=new Commands.Context(staff,UUID.randomUUID(),UUID.randomUUID(),"PUT service",null,((Number)current.get("rowVersion")).intValue());
        assertEquals(403,assertThrows(ApiFault.class,()->commands.run(forbidden,body(current),"SERVICE_BINDING",target,"CONFIGURE_SERVICE",
                ()->administration.authorize(staff),()->administration.save(forbidden,target,body(current)),id->administration.get(staff,id))).status);
        assertThrows(ApiFault.class,()->save(current,body(current),false));
        assertEquals(current.get("rowVersion"),administration.get(admin,target).get("rowVersion"));
    }
    @Test void savedBindingChangesActualAuthorizationWithoutRestartAndRejectsStaleEdits() {
        var current=administration.get(admin,target);var input=body(current);
        var saved=save(current,input,true);
        UUID service=UUID.fromString(services.configured("platform.scanner.platform-service-id"));
        assertEquals(service,services.require(service,"FILE_CHECK","PLATFORM",department,"FILE_CHECK"));
        assertEquals("SERVICE_IDENTITY_SCOPE_MISMATCH",assertThrows(ApiFault.class,()->services.require(service,"FILE_CHECK","PLATFORM",UUID.randomUUID(),"FILE_CHECK")).code);
        assertEquals(409,assertThrows(ApiFault.class,()->save(current,input,true)).status);
        var disable=body(saved);disable.put("status","DISABLED");
        var disabled=save(saved,disable,true);
        assertEquals("SERVICE_IDENTITY_UNAVAILABLE",assertThrows(ApiFault.class,()->services.require(service,"FILE_CHECK","PLATFORM",department,"FILE_CHECK")).code);
        save(disabled,body(disabled),true);
        assertEquals(service,UUID.fromString(services.configured("platform.scanner.platform-service-id")));
        assertEquals(service,services.require(service,"FILE_CHECK","PLATFORM",department,"FILE_CHECK"));
    }
    @Test void invalidScopeAndExpiryRollBackAndRuntimeFailureDoesNotBecomeHealthy() {
        var current=administration.get(admin,target);var input=body(current);
        input.put("departmentIds",List.of(UUID.randomUUID().toString()));
        var invalidScope=input;
        assertEquals(400,assertThrows(ApiFault.class,()->save(current,invalidScope,true)).status);
        assertEquals(current.get("rowVersion"),administration.get(admin,target).get("rowVersion"));
        input=body(current);input.put("validUntil",Instant.now().minusSeconds(60).toString());
        var expired=input;
        assertEquals(400,assertThrows(ApiFault.class,()->save(current,expired,true)).status);
        UUID request=UUID.randomUUID();var result=administration.check(admin,request);
        assertEquals(request,result.get("requestId"));
        assertEquals(true,health.status().get("warning"));
        assertTrue(result.toString().contains("后台文件处理器"));
    }
}
