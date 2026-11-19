package com.company.platform;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 使用真实会话、过滤器、控制器及PostgreSQL，禁止连接部署库或日常预览库。 */
@EnabledIfEnvironmentVariable(named="IMS_RESPONSE_CONTRACT_INTEGRATION",matches="yes")
@SpringBootTest(classes=PlatformApplication.class,properties={"spring.config.name=platform","server.ssl.enabled=false",
 "platform.file-worker.enabled=false","platform.scan-intake.worker-enabled=false","platform.operations.schedule-enabled=false",
 "platform.notifications.schedule-enabled=false","platform.messaging.schedule-enabled=false","platform.borrow-lifecycle.enabled=false"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogResponseHttpIntegrationTest {
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry ignored){
        if(!Objects.toString(System.getenv("IMS_PLATFORM_DB_URL"),"").matches("jdbc:postgresql://127\\.0\\.0\\.1:55514/ims_platform_dev_[0-9]+"))throw new IllegalStateException("仅允许55514响应契约专用隔离库");
    }
    @Autowired MockMvc http;
    @Autowired Sql db;
    @Autowired IdentityService identity;
    private String token,staffToken;
    private UUID department;
    @BeforeAll void fixtures(){
        var admin=identity.authenticate(identity.login(Sql.map("employeeNo",System.getenv("IMS_PLATFORM_ADMIN_NO"),"password",System.getenv("IMS_PLATFORM_ADMIN_PASSWORD")),UUID.randomUUID(),"response-http").get("token").toString());
        department=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",department,"HTTP-"+department,"接口响应隔离部门");
        String password=Crypto.token();UUID user=person(password,"目录管理员");
        UUID role=(UUID)db.required("SELECT id FROM iam.role WHERE module='ARCHIVE' AND code='DEPARTMENT_ARCHIVIST'").get("id");
        db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,?,CURRENT_TIMESTAMP,?,'ARCHIVE','DEPARTMENT_ARCHIVIST')",UUID.randomUUID(),user,role,department,admin.userId());
        token=login(user,password);staffToken=login(person(password,"普通员工"),password);
    }
    private UUID person(String password,String name){
        UUID id=UUID.randomUUID();String no="HTTP-"+id;
        db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,no,name,department,no+"@biolink.com",Crypto.password(password));return id;
    }
    private String login(UUID user,String password){return identity.login(Sql.map("employeeNo","HTTP-"+user,"password",password),UUID.randomUUID(),"response-http").get("token").toString();}
    @Test void catalogReadEndpointsReturnCompleteEnvelopeThroughHttp()throws Exception{
        for(String path:List.of("/archive/catalog/RECORD","/archive/catalog/BOX","/archive/retention","/admin/archive/directory-template-versions","/archive/catalog-options","/archive/owners?departmentId="+department)){
            var result=http.perform(get("/api/v1"+path).cookie(new Cookie("IMS_SESSION",token)).header("X-Request-Id",UUID.randomUUID())).andReturn();
            var body=contract(result.getResponse().getContentAsString(),result.getResponse().getHeader("X-Request-Id"));
            assertEquals(200,result.getResponse().getStatus(),path+" "+body);assertEquals("OK",body.get("code"));assertInstanceOf(Map.class,body.get("data"));
        }
    }
    @Test void unauthorizedInvalidAndMissingResponsesKeepSameContract()throws Exception{
        record Case(String path,String session,int status){}
        for(var test:List.of(new Case("/archive/catalog/RECORD",null,401),new Case("/archive/catalog/RECORD",staffToken,403),new Case("/archive/catalog/RECORD?page=0",token,400),new Case("/archive/catalog/RECORD/"+UUID.randomUUID(),token,404))){
            var request=get("/api/v1"+test.path()).header("X-Request-Id",UUID.randomUUID());if(test.session()!=null)request.cookie(new Cookie("IMS_SESSION",test.session()));
            var result=http.perform(request).andReturn();var body=contract(result.getResponse().getContentAsString(),result.getResponse().getHeader("X-Request-Id"));
            assertEquals(test.status(),result.getResponse().getStatus(),body.toString());assertNotEquals("OK",body.get("code"));assertNull(body.get("data"));
        }
    }
    private Map<?,?> contract(String content,String header){
        var body=Crypto.JSON.readValue(content,Map.class);assertInstanceOf(String.class,body.get("code"));assertFalse(body.get("code").toString().isBlank());
        assertInstanceOf(String.class,body.get("message"));assertFalse(body.get("message").toString().isBlank());assertTrue(body.containsKey("data"));
        assertEquals(header,body.get("requestId"));assertDoesNotThrow(()->UUID.fromString(body.get("requestId").toString()));
        assertDoesNotThrow(()->java.time.Instant.parse(body.get("timestamp").toString()));return body;
    }
}
