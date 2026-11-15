package com.company.ims.archive;

import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import com.company.ims.shared.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 通过真实Spring过滤器、控制器、事务和迁移验证HTTP契约；可选择独立PostgreSQL测试库。 */
@SpringBootTest(properties={"IMS_BOOTSTRAP_ADMIN_PASSWORD=Integration_Only_123!","spring.datasource.hikari.minimum-idle=1","spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
class ArchiveHttpIntegrationTest {
    /** 仅测试账号使用的口令，不作为生产默认值。 */
    private static final String PASSWORD="Integration_Only_123!";
    /** 每次测试运行使用新的附件目录，避免读写真实附件。 */
    private static final Path STORAGE=temporaryDirectory();
    /** 备份与附件使用不同根目录，遵守生产目录隔离规则。 */
    private static final Path BACKUPS=temporaryDirectory();
    /** 随机内存库地址在一次上下文中保持稳定。 */
    private static final String H2_URL="jdbc:h2:mem:http_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    /** 可选独立PostgreSQL库，缺省使用随机H2内存库。 */
    private static final String PG_URL=System.getenv("IMS_TEST_DB_URL");
    /** Spring真实请求调用入口。 */
    @Autowired private MockMvc http;
    /** 用于创建互相隔离测试主体及验证业务落库结果。 */
    @Autowired private Db db;
    /** 当前测试所属部门。 */
    private long ownDepartment;
    /** 另一部门，验证对象级权限。 */
    private long otherDepartment;
    /** 另一部门用户标识，接收显式共享。 */
    private long otherUser;
    /** 本部门档案管理员令牌。 */
    private String ownToken;
    /** 另一部门档案管理员令牌。 */
    private String otherToken;
    /** 本测试档案标识。 */
    private long archiveId;

    /** 限制外置测试URL只能指向显式命名的回环测试库，防止误用业务数据库。 */
    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        if(PG_URL!=null&&!PG_URL.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/ims_test_[a-z0-9_]+")) throw new IllegalArgumentException("仅允许回环地址下ims_test_前缀的隔离数据库");
        registry.add("spring.datasource.url",()->PG_URL==null?H2_URL:PG_URL);
        registry.add("spring.datasource.username",()->PG_URL==null?"sa":System.getenv("IMS_TEST_DB_USER"));
        registry.add("spring.datasource.password",()->PG_URL==null?"":Objects.toString(System.getenv("IMS_TEST_DB_PASSWORD"),""));
        registry.add("ims.storage-root",()->STORAGE.toString());
        registry.add("ims.backup-root",()->BACKUPS.toString());
    }
    /** 为测试建立临时目录，失败时中止而不是退回真实目录。 */
    private static Path temporaryDirectory() {
        try {return Files.createTempDirectory("ims-http-test-");}
        catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    /** 每个用例创建独立部门、用户和档案，不清空任何已有数据库。 */
    @BeforeEach
    void prepare() throws Exception {
        // 随机编码使PostgreSQL重复执行时仍互不影响。
        String suffix=UUID.randomUUID().toString().substring(0,8);
        ownDepartment=db.insert("ims_department",Map.of("dept_code","A"+suffix,"dept_name","测试甲"+suffix));
        otherDepartment=db.insert("ims_department",Map.of("dept_code","B"+suffix,"dept_name","测试乙"+suffix));
        createUser("owner"+suffix,ownDepartment);
        otherUser=createUser("other"+suffix,otherDepartment);
        ownToken=login("owner"+suffix);
        otherToken=login("other"+suffix);
        // 真正调用新增接口，覆盖编号、日期、审计和序列事务。
        archiveId=number(data(call(post("/api/archives"),ownToken,Map.of("archive_name","<script>档案</script>","category_id",1,"dept_id",ownDepartment,"archive_date","2026-01-31","validity_months",1),200)).get("id"));
    }
    /** 真实HTTP查询、统计、详情和网页都不能读取其他部门档案。 */
    @Test
    void allEntrancesApplyDepartmentScope() throws Exception {
        call(get("/api/archives/"+archiveId),otherToken,null,404);
        assertEquals(0,number(data(call(get("/api/archives"),otherToken,null,200)).get("total")));
        assertEquals(0,number(data(call(get("/api/archives/stats"),otherToken,null,200)).get("total")));
        call(get("/api/files").param("archive_id",Long.toString(archiveId)),otherToken,null,404);
        perform(get("/web/archives/"+archiveId).cookie(new jakarta.servlet.http.Cookie("ims_session",otherToken))).andExpect(status().isNotFound());
        perform(get("/web/archives").cookie(new jakarta.servlet.http.Cookie("ims_session",otherToken))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("&lt;script&gt;档案"))));
    }
    /** 中文日期与历史快照正确落库，用户编辑不得强行跳过状态流程。 */
    @Test
    void lifecycleAndAuditAreTransactional() throws Exception {
        assertEquals("2026-02-28",data(call(get("/api/archives/"+archiveId),ownToken,null,200)).get("expiry_date"));
        call(put("/api/archives/"+archiveId),ownToken,Map.of("status","destroyed"),409);
        call(post("/api/archives/"+archiveId+"/archive"),ownToken,Map.of(),200);
        call(post("/api/archives/"+archiveId+"/archive"),ownToken,Map.of(),409);
        assertEquals("archive",db.required("select status from archive where id=?",archiveId).get("status"));
        assertEquals(2,db.scalarLong("select count(*) from archive_update_log where archive_id=?",archiveId));
    }
    /** 查看与下载共享相互独立，撤销后下一次请求立即拒绝。 */
    @Test
    void grantsAreActionSpecificAndRevocable() throws Exception {
        // 使用上传接口建立真实磁盘附件。
        long file=upload("说明.txt","正文");
        long grant=number(data(call(post("/api/archives/"+archiveId+"/grants"),ownToken,Map.of("grantee_type","user","grantee_id",otherUser,"permission_code","archive.read"),200)).get("id"));
        call(get("/api/archives/"+archiveId),otherToken,null,200);
        call(get("/api/files/"+file+"/download"),otherToken,null,404);
        call(post("/api/archives/"+archiveId+"/grants"),ownToken,Map.of("grantee_type","user","grantee_id",otherUser,"permission_code","file.download"),200);
        perform(get("/api/files/"+file+"/download").header("Authorization","Bearer "+otherToken)).andExpect(status().isOk()).andExpect(content().bytes("正文".getBytes(StandardCharsets.UTF_8)));
        call(delete("/api/archives/"+archiveId+"/grants/"+grant),ownToken,null,200);
        call(get("/api/archives/"+archiveId),otherToken,null,404);
    }
    /** 同名文件递增版本，只替换同名当前版本；其他附件不受影响。 */
    @Test
    void attachmentVersionsAndSafePreview() throws Exception {
        upload("说明.txt","版本一"); upload("其他.txt","其他文件");
        long newest=upload("说明.txt","版本二");
        assertEquals(2,db.scalarLong("select count(*) from archive_file where archive_id=? and is_current=1",archiveId));
        assertEquals(2,db.scalarLong("select version_no from archive_file where id=?",newest));
        // HTML即使请求预览也必须下载，禁止同源脚本执行。
        MockMultipartFile htmlFile=new MockMultipartFile("file","危险.html","text/html","<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        long htmlId=number(data(json(perform(multipart("/api/files/upload").file(htmlFile).param("archive_id",Long.toString(archiveId)).header("Authorization","Bearer "+ownToken)).andExpect(status().isOk()).andReturn())).get("id"));
        perform(get("/api/files/"+htmlId+"/preview").header("Authorization","Bearer "+ownToken)).andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment"))).andExpect(header().string("X-Content-Type-Options","nosniff"));
    }
    /** 停用与改密都实时阻断已有凭证，REST不接受浏览器Cookie代替身份头。 */
    @Test
    void revocationAndCookieBoundary() throws Exception {
        perform(get("/api/archives").cookie(new jakarta.servlet.http.Cookie("ims_session",ownToken))).andExpect(status().isUnauthorized());
        db.execute("update ims_user set account_status='disabled' where id=?",otherUser);
        call(get("/api/archives"),otherToken,null,401);
        call(post("/api/auth/change-password"),ownToken,Map.of("old_password",PASSWORD,"new_password","Changed_Only_123!"),200);
        call(get("/api/archives"),ownToken,null,401);
    }
    /** 网页展示转义正文；登录来源校验和退出撤销保持独立可测。 */
    @Test
    void webEscapesDataAndProtectsSessionActions() throws Exception {
        perform(get("/web/archives/"+archiveId).cookie(new jakarta.servlet.http.Cookie("ims_session",ownToken))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;档案&lt;/script&gt;")));
        perform(post("/web/login").param("username","admin").param("password",PASSWORD).header("Origin","https://other.example")).andExpect(status().isForbidden());
        // 同源登录后的Cookie必须HttpOnly且采用SameSite限制。
        perform(post("/web/login").param("username","admin").param("password",PASSWORD).header("Origin","http://localhost")).andExpect(status().isSeeOther()).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("HttpOnly")));
        perform(post("/web/logout").cookie(new jakarta.servlet.http.Cookie("ims_session",ownToken)).header("Origin","http://localhost")).andExpect(status().isSeeOther());
        call(get("/api/archives"),ownToken,null,401);
    }
    /** 录入超长/非法数据失败后不留下档案或已消耗的流水号。 */
    @Test
    void invalidWriteDoesNotConsumeSequenceOrArchive() throws Exception {
        // 用同部门序列快照观测失败事务，不依赖测试执行顺序。
        long before=db.scalarLong("select coalesce(sum(current_value),0) from numbering_sequence");
        call(post("/api/archives"),ownToken,Map.of("archive_name","非法","category_id",1,"dept_id",ownDepartment,"copy_count",0),400);
        assertEquals(1,db.scalarLong("select count(*) from archive where dept_id=?",ownDepartment));
        assertEquals(before,db.scalarLong("select coalesce(sum(current_value),0) from numbering_sequence"));
        call(post("/api/archives"),ownToken,Map.of("archive_name","越界","category_id",1,"dept_id",otherDepartment),403);
    }
    /** 登录页和健康检查可匿名，业务接口与未知管理路径不能匿名访问。 */
    @Test
    void publicSurfaceIsMinimal() throws Exception {
        perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        perform(get("/api/archives")).andExpect(status().isUnauthorized());
        perform(get("/web/archives")).andExpect(status().is3xxRedirection());
        call(post("/api/security/db-backup"),ownToken,Map.of(),403);
    }
    /** 创建具有真实默认档案管理员角色的测试账号。 */
    private long createUser(String username,long department) {
        // 带随机盐的哈希与生产校验流程相同。
        long id=db.insert("ims_user",Map.of("username",username,"real_name",username,"dept_id",department,"password_hash",Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD)));
        db.insert("ims_user_role",Map.of("user_id",id,"role_id",db.scalarLong("select id from ims_role where role_code='archive_admin'")));
        return id;
    }
    /** 从真实登录接口取得会话，避免测试绕过认证过滤器。 */
    private String login(String username) throws Exception {return data(call(post("/api/auth/login"),null,Map.of("username",username,"password",PASSWORD),200)).get("access_token").toString();}
    /** 上传文件并返回已持久化版本标识。 */
    private long upload(String name,String content) throws Exception {
        // UTF-8中文正文用于同时验证文件响应字节保真。
        MockMultipartFile file=new MockMultipartFile("file",name,"text/plain",content.getBytes(StandardCharsets.UTF_8));
        return number(data(json(perform(multipart("/api/files/upload").file(file).param("archive_id",Long.toString(archiveId)).header("Authorization","Bearer "+ownToken)).andExpect(status().isOk()).andReturn())).get("id"));
    }
    /** 统一构建请求并断言HTTP状态，不以JSON的成功字段掩盖错误状态。 */
    private Map<String,Object> call(MockHttpServletRequestBuilder request,String token,Map<String,Object> body,int status) throws Exception {
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
        return json(perform(request).andExpect(status().is(status)).andReturn());
    }
    /** MockMvc默认不填servletPath；补齐真实Servlet容器解析结果以验证完整过滤器链。 */
    private ResultActions perform(org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return http.perform(request.with(servletRequest->{servletRequest.setServletPath(servletRequest.getRequestURI());return servletRequest;}));
    }
    /** 将响应信封转换为Map，保留中文内容用于断言。 */
    @SuppressWarnings("unchecked") private Map<String,Object> json(MvcResult result) throws Exception {return Json.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),Map.class);}
    /** 获取成功响应的data字段。 */
    @SuppressWarnings("unchecked") private Map<String,Object> data(Map<String,Object> response) {return (Map<String,Object>)response.get("data");}
    /** 数据库与JSON可能使用不同数值宽度，统一为long断言。 */
    private long number(Object value) {return ((Number)value).longValue();}
}
