package com.company.platform;

import com.company.platform.portal.*;
import com.company.platform.workflow.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 本批真实PostgreSQL隔离验收，调用正式事务服务，不复制业务判断或使用模拟仓储。 */
@EnabledIfEnvironmentVariable(named="IMS_PORTAL_INTEGRATION",matches="yes")
@SpringBootTest(classes=PlatformApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.name=platform","server.address=127.0.0.1","server.ssl.enabled=false","platform.messaging.schedule-enabled=false","platform.notifications.schedule-enabled=false","platform.file-worker.enabled=false","platform.scan-intake.worker-enabled=false","platform.borrow-lifecycle.enabled=false"})
class PortalIntegrationTest {
    /** 在创建Spring上下文前限制新库端口，避免误接现有预览或业务库。 */
    @DynamicPropertySource static void guard(DynamicPropertyRegistry registry){String url=System.getenv("IMS_PLATFORM_DB_URL");if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:(55462|55448)/ims_platform_dev_[0-9]+"))throw new IllegalStateException("仅允许首页专项隔离库");}
    @Autowired Sql db;@Autowired IdentityService identity;@Autowired PortalContent content;@Autowired PortalWork work;@Autowired Commands commands;@Autowired WorkflowEngine workflow;@Autowired PlatformTransactionManager manager;
    /** 每例独立员工与部门，测试不覆盖已有记录。 */
    private Actor admin,employee,other,editor;private UUID department,otherDepartment;private final Map<UUID,String> passwords=new HashMap<>();
    @BeforeEach void setup(){admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));passwords.put(admin.userId(),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));department=department();otherDepartment=department();employee=employee(department,null);other=employee(otherDepartment,null);editor=employee(department,"CONTENT_EDITOR");}
    @Autowired EmployeeTodos todos;
    @Autowired TodoReminders reminders;
    @LocalServerPort int port;
    @Test void todoReminderRoutesToPairedDesktopWithoutCompletingTodo() throws Exception {
        var scheduled=Sql.map("title","提醒验收","description","仅接收人可见","remindAt",Instant.now().plusSeconds(120).toString());
        UUID id=(UUID)todos.create(employee,UUID.randomUUID(),UUID.randomUUID(),scheduled).get("id");
        assertNull(reminders.web(employee).get("item"));
        status(400,()->todos.create(employee,UUID.randomUUID(),UUID.randomUUID(),
            Sql.map("title","过去提醒","remindAt",Instant.now().minusSeconds(120).toString())));
        db.update("UPDATE portal.employee_todo SET remind_at=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",id);
        var due=(Map<?,?>)reminders.web(employee).get("item");
        assertEquals(id,due.get("id"));
        assertNull(reminders.web(other).get("item"));
        var receipt=Sql.map("id",id.toString(),"remindAt",due.get("remindAt"));
        reminders.acknowledge(other.userId(),receipt);
        assertNotNull(reminders.web(employee).get("item"));

        String bearer="Bearer "+reminders.pair(employee,UUID.randomUUID()).get("token");
        assertEquals(false,reminders.status(employee).get("online"));
        assertEquals(false,reminders.status(employee).get("enabled"));
        String base="http://127.0.0.1:"+port+"/api/v1";
        var client=HttpClient.newHttpClient();
        var desktopRequest=HttpRequest.newBuilder(URI.create(base+"/desktop/todo-reminders"))
            .header("Authorization",bearer).header("Content-Type","application/json").header("X-Todo-Helper-Version","1.1.0")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var response=client.send(desktopRequest,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        assertEquals(id.toString(),Crypto.JSON.readTree(response.body()).path("data").path("item").path("id").asString());
        var webRequest=HttpRequest.newBuilder(URI.create(base+"/portal/todos/"+id)).header("Authorization",bearer).GET().build();
        assertEquals(401,client.send(webRequest,HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(true,reminders.status(employee).get("online"));
        assertEquals("1.1.0",reminders.status(employee).get("clientVersion"));
        assertEquals(true,reminders.status(employee).get("enabled"));
        assertNull(reminders.web(employee).get("item"));
        db.update("UPDATE portal.todo_desktop SET last_poll_at=CURRENT_TIMESTAMP-interval '1 minute' WHERE user_id=?",employee.userId());
        assertEquals(true,reminders.status(employee).get("enabled"));
        assertEquals(false,reminders.status(employee).get("online"));
        assertNotNull(reminders.web(employee).get("item"));
        reminders.setEnabled(employee,UUID.randomUUID(),Map.of("enabled",false));
        assertNull(reminders.desktop(bearer,null,receipt).get("item"));
        assertNull(reminders.status(employee).get("clientVersion"));
        assertEquals(false,reminders.status(employee).get("enabled"));
        assertNotNull(reminders.web(employee).get("item"));
        reminders.setEnabled(employee,UUID.randomUUID(),Map.of("enabled",true));
        assertEquals(id,((Map<?,?>)reminders.desktop(bearer,"1.1.0",Map.of()).get("item")).get("id"));
        assertNull(reminders.web(employee).get("item"));
        assertNull(reminders.desktop(bearer,"1.1.0",receipt).get("item"));
        assertEquals("OPEN",todos.detail(employee,id).get("state"));

        reminders.unpair(employee,UUID.randomUUID());
        assertEquals(401,client.send(desktopRequest,HttpResponse.BodyHandlers.discarding()).statusCode());
        assertNull(reminders.web(employee).get("item"));
    }
    /** 工作台在分页前排除已办，完成的事项仍可从本人历史查询。 */
    @Test @org.springframework.transaction.annotation.Transactional
    void workspaceRecentTodosExcludeCompletedBeforePagination(){
        var expected=new ArrayList<UUID>();
        UUID completed=null;
        for(int index=0;index<11;index++){
            UUID id=(UUID)todos.create(employee,UUID.randomUUID(),UUID.randomUUID(),Sql.map("title","预览验收 "+index)).get("id");
            db.update("UPDATE portal.employee_todo SET created_at=CURRENT_TIMESTAMP-(? * interval '1 minute') WHERE id=?",11-index,id);
            if(index==0)todos.transition(todoContext(employee,1),id,Sql.map("action","COMPLETE"));
            else if(index>=6)expected.addFirst(id);
            if(index==0)completed=id;
        }
        todos.create(other,UUID.randomUUID(),UUID.randomUUID(),Sql.map("title","其他员工的待办"));
        var preview=work.tasks(employee,Map.of("category","RECENT","pageSize","5"));
        var rows=items(preview);
        assertEquals(expected,rows.stream().map(row->row.get("id")).toList());
        assertEquals(10L,preview.get("total"));
        assertTrue(rows.stream().allMatch(row->"PERSONAL".equals(row.get("category"))));
        assertEquals(completed,items(work.tasks(employee,Map.of("category","COMPLETED"))).getFirst().get("id"));
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void personalTodosArePrivateAndOwnerCanEditCompleteAndDelete() {
        UUID operation=UUID.randomUUID();
        var body=Sql.map("title","个人事项");
        UUID id=(UUID)todos.create(employee,operation,UUID.randomUUID(),body).get("id");
        assertEquals(id,todos.create(employee,operation,UUID.randomUUID(),body).get("id"));
        status(404,()->todos.detail(other,id));
        status(404,()->todos.edit(todoContext(admin,1),id,body));
        assertEquals("改后的标题",todos.edit(todoContext(employee,1),id,Sql.map("title","改后的标题")).get("title"));
        status(409,()->todos.transition(todoContext(employee,1),id,Sql.map("action","COMPLETE")));
        assertEquals("COMPLETED",todos.transition(todoContext(employee,2),id,Sql.map("action","COMPLETE")).get("state"));
        assertEquals(1L,work.tasks(employee,Map.of("category","COMPLETED")).get("total"));
        // 无筛选的全部包含历史已办和未办，翻页总数一致。
        db.update("UPDATE portal.employee_todo SET completed_at=CURRENT_TIMESTAMP-interval '30 days' WHERE id=?",id);
        UUID open=(UUID)todos.create(employee,UUID.randomUUID(),UUID.randomUUID(),Sql.map("title","未办理事项")).get("id");
        var first=work.tasks(employee,Map.of("pageSize","1"));
        assertEquals(2L,first.get("total"));
        assertEquals(open,items(first).getFirst().get("id"));
        assertEquals(id,items(work.tasks(employee,Map.of("page","2","pageSize","1"))).getFirst().get("id"));
        assertEquals(id,items(work.tasks(employee,Map.of("keyword","改后的标题"))).getFirst().get("id"));
        assertEquals(0L,work.tasks(other,Map.of()).get("total"));
        todos.transition(todoContext(employee,3),id,Sql.map("action","DELETE"));
        status(404,()->todos.detail(employee,id));
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void assignedTodosRequireAuthorizedSenderAndOriginalSenderConfirmation() {
        status(403,()->todos.create(employee,UUID.randomUUID(),UUID.randomUUID(),Sql.map("title","越权分派","assigneeId",editor.userId())));
        db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'隔离验收',?)",
                UUID.randomUUID(),department,editor.userId(),admin.userId());
        assertTrue(items(todos.candidates(editor,Map.of())).stream().anyMatch(row->row.get("id").equals(employee.userId())));
        assertFalse(items(todos.candidates(editor,Map.of())).stream().anyMatch(row->row.get("id").equals(other.userId())));
        assertEquals(List.of(department),items(todos.candidates(editor,Map.of("view","departments")))
                .stream().map(row->row.get("id")).toList());
        assertTrue(items(todos.candidates(employee,Map.of("view","departments"))).isEmpty());
        assertTrue(items(todos.candidates(editor,Map.of("departmentId",otherDepartment.toString()))).isEmpty());
        var matches=items(todos.candidates(editor,Map.of("keyword","验","departmentId",department.toString())));
        assertEquals(List.of(employee.userId()),matches.stream().map(row->row.get("id")).toList());
        assertEquals(department,matches.getFirst().get("departmentId"));
        assertEquals(matches,items(todos.candidates(editor,Map.of("keyword",matches.getFirst().get("employeeNo").toString()))));
        status(403,()->todos.create(editor,UUID.randomUUID(),UUID.randomUUID(),Sql.map("title","跨部门分派","assigneeId",other.userId())));
        UUID id=(UUID)todos.create(editor,UUID.randomUUID(),UUID.randomUUID(),Sql.map("title","部门任务","assigneeId",employee.userId())).get("id");
        status(403,()->todos.edit(todoContext(employee,1),id,Sql.map("title","擅自修改")));
        status(403,()->todos.transition(todoContext(employee,1),id,Sql.map("action","COMPLETE")));
        status(403,()->todos.transition(todoContext(employee,1),id,Sql.map("action","DELETE")));
        status(409,()->todos.transition(todoContext(editor,1),id,Sql.map("action","CONFIRM")));
        assertEquals("SUBMITTED",todos.transition(todoContext(employee,1),id,Sql.map("action","SUBMIT","completionNote","已处理")).get("state"));
        assertTrue(items(work.tasks(editor,Map.of("category","APPROVAL"))).stream().anyMatch(row->row.get("id").equals(id)));
        assertEquals(0L,work.tasks(employee,Map.of("category","COMPLETED")).get("total"));
        assertEquals("OPEN",todos.transition(todoContext(editor,2),id,Sql.map("action","RETURN")).get("state"));
        todos.transition(todoContext(employee,3),id,Sql.map("action","SUBMIT"));
        status(404,()->todos.transition(todoContext(other,4),id,Sql.map("action","CONFIRM")));
        assertEquals("COMPLETED",todos.transition(todoContext(editor,4),id,Sql.map("action","CONFIRM")).get("state"));
        assertEquals(1L,work.tasks(employee,Map.of("category","COMPLETED")).get("total"));
        assertEquals(id,items(work.tasks(employee,Map.of())).getFirst().get("id"));
        db.update("UPDATE iam.company_duty SET valid_until=CURRENT_TIMESTAMP WHERE duty_code='GENERAL_MANAGER' AND valid_until IS NULL");
        db.update("INSERT INTO iam.company_duty(id,duty_code,user_id,valid_from,changed_by,change_reason) VALUES(?,'GENERAL_MANAGER',?,CURRENT_TIMESTAMP,?,'隔离验收')",
                UUID.randomUUID(),editor.userId(),admin.userId());
        assertTrue(items(todos.candidates(editor,Map.of("view","departments"))).stream()
                .anyMatch(row->row.get("id").equals(otherDepartment)));
        assertEquals(List.of(other.userId()),items(todos.candidates(editor,Map.of("departmentId",otherDepartment.toString())))
                .stream().map(row->row.get("id")).toList());
        assertEquals(other.userId(),todos.create(editor,UUID.randomUUID(),UUID.randomUUID(),Sql.map("title","公司任务","assigneeId",other.userId())).get("assigneeId"));
    }

    private Commands.Context todoContext(Actor actor,int version) {
        return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TODO TEST",null,version);
    }

    private UUID department(){UUID id=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",id,"PORTAL-"+id,"隔离验收部门");return id;}
    private Actor employee(UUID dept,String role){UUID id=UUID.randomUUID();String no="PORTAL-"+id,password=Crypto.token();passwords.put(id,password);db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,no,"首页验收员工",dept,no+"@biolink.com",Crypto.password(password));if(role!=null)assign(id,"PLATFORM",role,null);return login(no,password);}
    private UUID assign(UUID user,String module,String role,UUID dept){UUID id=UUID.randomUUID();db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,module,role_code) SELECT ?,?,id,?,CURRENT_TIMESTAMP,?,?,? FROM iam.role WHERE module=? AND code=?",id,user,dept,admin.userId(),module,role,module,role);return id;}
    private Actor login(String no,String password){return identity.authenticate(identity.login(Sql.map("employeeNo",no,"password",password),UUID.randomUUID(),"portal-test-"+UUID.randomUUID()).get("token").toString());}
    private Actor refresh(Actor actor){return identity.background(actor.sessionId());}
    /** 使用真实再验证凭据及Commands幂等记录，避免绕过正式写入口。 */
    private Commands.Context context(Actor actor,String type,UUID id,String action,Integer version){var proof=identity.reauthenticate(actor,Sql.map("password",passwords.get(actor.userId()),"purpose","MANAGE","objectIds",List.of(id.toString()),"scopeItems",List.of(Sql.map("objectType",type,"objectId",id.toString(),"actions",List.of(action)))),UUID.randomUUID());return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TEST "+action+" "+id,proof.get("verificationToken").toString(),version);}
    private Map<String,Object> announcementBody(){return Sql.map("title","隔离公告 "+UUID.randomUUID(),"category","通知公告","body","第一段正文。\n第二段正文。","state","PUBLISHED","scope","DEPARTMENTS","departmentIds",List.of(department.toString()),"validFrom",Instant.now().minusSeconds(5).toString(),"validUntil",Instant.now().plusSeconds(3600).toString(),"pinned",false);}
    private Map<String,Object> save(Commands.Context ctx,UUID id,Map<String,Object> body){return commands.run(ctx,body,"PORTAL_ANNOUNCEMENT",id==null?ApiController.COLLECTION:id,"SAVE_ANNOUNCEMENT",()->ctx.actor().require("PLATFORM.ANNOUNCEMENTS_MANAGE"),()->content.saveAnnouncement(ctx,id,body),key->content.announcement(ctx.actor(),key,true));}
    private Map<String,Object> create(Map<String,Object> body){return save(context(editor,"PORTAL_ANNOUNCEMENT",ApiController.COLLECTION,"SAVE_ANNOUNCEMENT",null),null,body);}
    private Map<String,Object> site(Actor actor,Map<String,Object> body){var ctx=context(actor,"PORTAL_SITE",ApiController.COLLECTION,"SAVE_SITE",null);return commands.run(ctx,body,"PORTAL_SITE",ApiController.COLLECTION,"SAVE_SITE",()->actor.require("PLATFORM.SITES_MANAGE"),()->content.saveSite(ctx,null,body),content::site);}
    private Map<String,Object> feedback(){return work.submit(employee,UUID.randomUUID(),UUID.randomUUID(),Sql.map("category","ISSUE","content","隔离反馈内容"));}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}
    private void status(int status,org.junit.jupiter.api.function.Executable call){assertEquals(status,assertThrows(ApiFault.class,call).status);}

    @Test void editorHasOnlyAnnouncementCapability(){assertEquals(Set.of("PLATFORM.ANNOUNCEMENTS_MANAGE"),editor.actions());status(403,()->content.sites(editor,true));status(403,()->work.feedbackList(editor,Map.of(),true));status(403,()->content.announcements(employee,Map.of(),true));}
    @Test void visibleScopeFiltersBeforeCountingAndDoesNotExposeBody(){var saved=create(announcementBody());UUID id=(UUID)saved.get("id");assertEquals(1,items(content.announcements(employee,Map.of(),false)).stream().filter(x->x.get("id").equals(id)).count());assertFalse(items(content.announcements(employee,Map.of(),false)).getFirst().containsKey("body"));status(404,()->content.announcement(other,id,false));assertEquals(0,items(content.announcements(other,Map.of(),false)).stream().filter(x->x.get("id").equals(id)).count());assertEquals("首页验收员工",saved.get("publisherName"));assertEquals("隔离验收部门",saved.get("publisherDepartment"));}
    /** 工作台先按发布范围过滤，再按发布时间取五条，旧置顶不能挤掉近期公告。 */
    @Test void workspaceShowsLatestFiveIncludingExpiredNotOutsideAudience(){
        var expected=new ArrayList<UUID>();
        for(int days=11;days>=1;days--){
            var body=announcementBody();
            body.put("pinned",days==11);
            body.put("validFrom",Instant.now().minus(Duration.ofDays(days)).toString());
            if(days==1)body.put("validUntil",Instant.now().minusSeconds(1).toString());
            UUID id=(UUID)create(body).get("id");
            if(days<=5)expected.addFirst(id);
        }
        var excluded=announcementBody();
        excluded.put("departmentIds",List.of(otherDepartment.toString()));
        create(excluded);
        content.readAnnouncement(employee,expected.getFirst());
        var rows=content.recentAnnouncements(employee);
        assertEquals(expected,rows.stream().map(row->row.get("id")).toList());
        assertEquals(true,rows.getFirst().get("viewed"));
        assertEquals(false,rows.get(1).get("viewed"));
        assertFalse(rows.getFirst().containsKey("body"));
    }

    @Test void readHistoryRetainsExpiredNoticeWithinCurrentAudience() {
        var body=announcementBody();
        body.put("validFrom",Instant.now().minusSeconds(60).toString());
        UUID id=(UUID)create(body).get("id");
        var history=Map.of("view","READ","pageSize","1");
        content.announcement(employee,id,false);
        assertEquals(0L,content.announcements(employee,history,false).get("total"));
        status(404,()->content.readAnnouncement(other,id));
        assertEquals(body.get("body"),content.readAnnouncement(employee,id).get("body"));
        content.readAnnouncement(employee,id);

        db.update("UPDATE portal.announcement SET valid_until=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",id);
        // 有发布范围但从未查看过的员工也可以从历史列表首次打开。
        var expired=Map.of("view","EXPIRED","keyword",body.get("title").toString());
        assertEquals(id,items(content.announcements(editor,expired,false)).getFirst().get("id"));
        assertEquals(0L,content.announcements(other,expired,false).get("total"));
        status(404,()->content.readAnnouncement(other,id));
        assertEquals(0L,content.announcements(employee,Map.of("keyword",body.get("title").toString()),false).get("total"));
        var read=content.announcements(employee,history,false);
        assertEquals(1L,read.get("total"));
        assertEquals(id,items(read).getFirst().get("id"));
        assertFalse(items(read).getFirst().containsKey("body"));
        assertEquals(body.get("body"),content.announcement(employee,id,false).get("body"));
        assertEquals(0L,content.announcements(editor,history,false).get("total"));
        assertEquals(body.get("body"),content.readAnnouncement(editor,id).get("body"));
        assertEquals(id,items(content.announcements(editor,history,false)).getFirst().get("id"));

        db.update("UPDATE iam.user_account SET department_id=? WHERE id=?",otherDepartment,employee.userId());
        assertEquals(0L,content.announcements(employee,history,false).get("total"));
        assertEquals(0L,content.announcements(employee,expired,false).get("total"));
        status(404,()->content.announcement(employee,id,false));
        db.update("UPDATE iam.user_account SET department_id=? WHERE id=?",department,employee.userId());
        db.update("UPDATE portal.announcement SET state='WITHDRAWN' WHERE id=?",id);
        assertEquals(0L,content.announcements(employee,expired,false).get("total"));
        assertEquals(0L,content.announcements(employee,history,false).get("total"));
        status(404,()->content.announcement(employee,id,false));
    }
    @Test void listFiltersApplyBeforePaginationAndPreserveDepartmentVisibility() {
        var body=announcementBody();
        String marker=UUID.randomUUID().toString();
        body.put("title","台账 100% "+marker);
        UUID first=(UUID)create(body).get("id");
        body.put("title","台账 100% "+marker+" 第二条");
        UUID second=(UUID)create(body).get("id");
        body.put("title","台账 100X "+marker);
        create(body);
        String today=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        var filters=new HashMap<>(Map.of("keyword","100% "+marker,"category","通知公告","publisher","首页验收员工",
                "publishedFrom",today,"publishedTo",today,"pageSize","1"));
        var firstPage=content.announcements(employee,filters,false);
        filters.put("page","2");
        var secondPage=content.announcements(employee,filters,false);
        assertEquals(2L,firstPage.get("total"));
        assertEquals(2L,secondPage.get("total"));
        assertEquals(Set.of(first,second),Set.of(items(firstPage).getFirst().get("id"),items(secondPage).getFirst().get("id")));
        assertEquals(0L,content.announcements(other,filters,false).get("total"));
        filters.put("publishedFrom",LocalDate.parse(today).plusDays(1).toString());
        status(400,()->content.announcements(employee,filters,false));
    }
    @Test void companyAnnouncementVisibleAcrossDepartments(){var body=announcementBody();body.put("scope","COMPANY");body.put("departmentIds",List.of());UUID id=(UUID)create(body).get("id");assertEquals(id,content.announcement(other,id,false).get("id"));}
    /** 草稿、未到生效时间和撤下公告不能从任何员工页签或详情打开。 */
    @Test void unpublishedAnnouncementsAreHiddenFromEmployeeHistory() {
        for(String scenario:List.of("DRAFT","SCHEDULED","WITHDRAWN")){
            var body=announcementBody();
            body.put("title","隐藏验收-"+UUID.randomUUID());
            if(scenario.equals("SCHEDULED"))body.put("validFrom",Instant.now().plusSeconds(600).toString());
            else body.put("state",scenario);
            UUID id=(UUID)create(body).get("id");
            for(String view:List.of("CURRENT","READ","EXPIRED"))
                assertEquals(0L,content.announcements(employee,Map.of("view",view,"keyword",body.get("title").toString()),false).get("total"));
            status(404,()->content.announcement(employee,id,false));
            assertEquals(id,content.announcement(editor,id,true).get("id"));
        }
    }
    @Test void departmentChangeImmediatelyRemovesFormerScope(){UUID id=(UUID)create(announcementBody()).get("id");db.update("UPDATE iam.user_account SET department_id=? WHERE id=?",otherDepartment,employee.userId());status(404,()->content.announcement(employee,id,false));}
    @Test void publishReplayIsSingleRevisionAndChangedPayloadConflicts(){var body=announcementBody();var ctx=context(editor,"PORTAL_ANNOUNCEMENT",ApiController.COLLECTION,"SAVE_ANNOUNCEMENT",null);UUID id=(UUID)save(ctx,null,body).get("id");assertEquals(id,save(ctx,null,body).get("id"));assertEquals(1,db.count("SELECT count(*) FROM portal.announcement_revision WHERE announcement_id=?",id));body.put("title","不同内容");status(409,()->save(ctx,null,body));}
    @Test void staleVersionRollsBackAndWithdrawImmediatelyHidesDetail(){var body=announcementBody();UUID id=(UUID)create(body).get("id");body.put("state","WITHDRAWN");status(409,()->save(context(editor,"PORTAL_ANNOUNCEMENT",id,"SAVE_ANNOUNCEMENT",99),id,body));assertEquals("PUBLISHED",content.announcement(employee,id,false).get("state"));save(context(editor,"PORTAL_ANNOUNCEMENT",id,"SAVE_ANNOUNCEMENT",1),id,body);status(404,()->content.announcement(employee,id,false));assertEquals(2,db.count("SELECT count(*) FROM portal.announcement_revision WHERE announcement_id=?",id));}
    @Test void expiredEditorRoleCannotUseSavedProof(){var body=announcementBody();var ctx=context(editor,"PORTAL_ANNOUNCEMENT",ApiController.COLLECTION,"SAVE_ANNOUNCEMENT",null);new TransactionTemplate(manager).executeWithoutResult(tx->identity.invalidate(editor.userId(),"PORTAL_TEST_REVOKE",UUID.randomUUID()));status(401,()->save(ctx,null,body));}
    @Test void invalidAnnouncementInputHasNoRevision(){var body=announcementBody();body.put("validUntil",Instant.now().minusSeconds(600).toString());status(400,()->create(body));var invalid=announcementBody();invalid.put("departmentIds",List.of(UUID.randomUUID().toString()));status(400,()->create(invalid));}
    /** 通过真实 HTTP 路由验证分组、删除及员工首页的同步结果。 */
    @Test void groupedSiteManagementUpdatesEmployeeWorkspace() throws Exception {
        var client=HttpClient.newHttpClient();
        String base="http://127.0.0.1:"+port+"/api/v1";
        var adminSession=portalSession(admin);
        var employeeSession=portalSession(employee);
        admin=identity.authenticate(adminSession);
        employee=identity.authenticate(employeeSession);
        String adminCookie="IMS_SESSION="+adminSession, employeeCookie="IMS_SESSION="+employeeSession;
        assertTrue(items(content.sites(employee,false)).stream().anyMatch(site->
            "ONLINE_TOOL".equals(site.get("category"))&&"腾讯在线文档".equals(site.get("name"))));
        assertTrue(items(content.sites(employee,false)).stream().anyMatch(site->
            "SOFTWARE_DOWNLOAD".equals(site.get("category"))&&"WPS Office".equals(site.get("name"))));
        var created=site(admin,Sql.map("name","隔离下载入口","url","https://example.com/download","category","SOFTWARE_DOWNLOAD","sortOrder",99,"enabled",true));
        UUID id=(UUID)created.get("id");
        assertEquals("SOFTWARE_DOWNLOAD",created.get("category"));
        assertTrue(workspaceSites(client,base,employeeCookie).contains(id.toString()));
        var denied=HttpRequest.newBuilder(URI.create(base+"/admin/portal/sites/"+id))
            .header("Cookie",employeeCookie).header("X-CSRF-Token",employee.csrfToken())
            .header("X-Operation-Id",UUID.randomUUID().toString()).DELETE().build();
        assertEquals(403,client.send(denied,HttpResponse.BodyHandlers.discarding()).statusCode());
        for (int version : List.of(99,1)) {
            var proof=context(admin,"PORTAL_SITE",id,"DELETE_SITE",version);
            var deletion=HttpRequest.newBuilder(URI.create(base+"/admin/portal/sites/"+id))
                .header("Cookie",adminCookie).header("X-CSRF-Token",admin.csrfToken())
                .header("X-Operation-Id",proof.operation().toString()).header("X-Reauth-Token",proof.reauth())
                .header("X-Expected-Version",Integer.toString(version)).DELETE().build();
            assertEquals(version==1?200:409,client.send(deletion,HttpResponse.BodyHandlers.discarding()).statusCode());
            if(version==1)assertEquals(200,client.send(deletion,HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        assertFalse(workspaceSites(client,base,employeeCookie).contains(id.toString()));
        assertFalse(items(content.sites(admin,true)).stream().anyMatch(site->id.equals(site.get("id"))));
    }

    private String portalSession(Actor actor){
        return identity.login(Sql.map("employeeNo",actor.user().get("employeeNo"),"password",passwords.get(actor.userId())),
            UUID.randomUUID(),"portal-test-"+UUID.randomUUID()).get("token").toString();
    }

    private String workspaceSites(HttpClient client,String base,String cookie) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+"/portal/home")).header("Cookie",cookie).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        return Crypto.JSON.readTree(response.body()).path("data").path("sites").toString();
    }

    @Test void defaultSiteAndDisabledSiteVisibility(){assertTrue(items(content.sites(employee,false)).stream().anyMatch(s->s.get("name").equals("百林客")&&s.get("url").equals("https://www.biolink.city/")));var result=site(admin,Sql.map("name","隔离网站","url","https://www.biolink.city/","sortOrder",99,"enabled",false));assertFalse(items(content.sites(employee,false)).stream().anyMatch(s->s.get("id").equals(result.get("id"))));}
    @ParameterizedTest @ValueSource(strings={"javascript:alert(1)","data:text/html,x","//example.test","https://user:pass@example.test/"}) void rejectsUnsafeSite(String url){status(400,()->site(admin,Sql.map("name","错误链接","url",url,"sortOrder",0,"enabled",true)));}
    @Test void feedbackIsPrivateAndReplayDoesNotDuplicate() {UUID op=UUID.randomUUID();var body=Sql.map("category","SUGGESTION","content","完善首页指引");UUID id=(UUID)work.submit(employee,op,UUID.randomUUID(),body).get("id");assertEquals(id,work.submit(employee,op,UUID.randomUUID(),body).get("id"));status(404,()->work.feedback(other,id,false));assertEquals(0L,work.feedbackList(other,Map.of(),false).get("total"));assertEquals(id,work.feedback(admin,id,true).get("id"));body.put("content","替换内容");status(409,()->work.submit(employee,op,UUID.randomUUID(),body));}
    @Test void concurrentFeedbackRetryCreatesOneRecord() throws Exception {UUID op=UUID.randomUUID();var body=Sql.map("category","OTHER","content","并发提交验收");try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->work.submit(employee,op,UUID.randomUUID(),body));var b=pool.submit(()->work.submit(employee,op,UUID.randomUUID(),body));assertEquals(a.get(20,TimeUnit.SECONDS).get("id"),b.get(20,TimeUnit.SECONDS).get("id"));}assertEquals(1,db.count("SELECT count(*) FROM portal.feedback WHERE applicant_id=? AND operation_id=?",employee.userId(),op));}
    @Test void replyCreatesOneMessageReadIsIdempotentAndForeignReadDenied(){UUID id=(UUID)feedback().get("id");var body=Sql.map("body","已处理，请核对","state","RESOLVED");var ctx=context(admin,"PORTAL_FEEDBACK",id,"REPLY_FEEDBACK",1);for(int i=0;i<2;i++)commands.run(ctx,body,"PORTAL_FEEDBACK",id,"REPLY_FEEDBACK",()->admin.require("PLATFORM.FEEDBACK_MANAGE"),()->work.reply(ctx,id,body),key->work.feedback(admin,key,true));var messages=items(work.inbox(employee,Map.of("unreadOnly","true")));assertEquals(1,messages.size());UUID message=(UUID)messages.getFirst().get("id");status(403,()->work.readMessage(other,message));assertEquals("#/feedback?id="+id,work.readMessage(employee,message).get("entry"));var first=db.required("SELECT read_at FROM portal.feedback_reply WHERE id=?",message).get("read_at");work.readMessage(employee,message);assertEquals(first,db.required("SELECT read_at FROM portal.feedback_reply WHERE id=?",message).get("read_at"));assertEquals(0L,work.summary(employee).get("unreadCount"));}
    @Test void handlingQueueAndSummaryUseActualOpenFeedback(){long before=((Number)((Map<?,?>)work.summary(admin).get("todoSummary")).get("handling")).longValue();UUID id=(UUID)feedback().get("id");assertEquals(before+1,((Number)((Map<?,?>)work.summary(admin).get("todoSummary")).get("handling")).longValue());assertTrue(items(work.tasks(admin,Map.of("pageSize","100","category","HANDLING"))).stream().anyMatch(t->t.get("id").equals(id)));assertEquals(0L,work.tasks(employee,Map.of()).get("total"));}
    /** 带档案入口的空工作台也必须执行全部真实SQL分支，不能只覆盖平台管理员。 */
    @Test void archiveEmployeeDashboardHasValidQueries(){assign(employee.userId(),"ARCHIVE","STAFF",null);employee=refresh(employee);assertNotNull(work.summary(employee));assertNotNull(work.tasks(employee,Map.of()));assertNotNull(work.inbox(employee,Map.of()));}
    @Test void archiveAdministratorHandlingQueriesUseActualAuthority(){assign(employee.userId(),"ARCHIVE","SUPER_ADMIN",null);employee=refresh(employee);assertNotNull(work.summary(employee));assertNotNull(work.tasks(employee,Map.of()));}
    /** 通过正式引擎创建真实节点和通知；原任职失效后两个首页入口同时消失。 */
    @Test void approvalAssignmentExpiryRemovesTaskAndNotification(){
        assign(employee.userId(),"ARCHIVE","STAFF",null);assign(other.userId(),"ARCHIVE","STAFF",null);employee=refresh(employee);other=refresh(other);
        UUID assignment=UUID.randomUUID();db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'首页隔离验收',?)",assignment,department,employee.userId(),admin.userId());
        UUID instance=new TransactionTemplate(manager).execute(tx->workflow.create(other,"BORROW",UUID.randomUUID(),UUID.randomUUID(),"GENERAL",0,Sql.map("form","ELECTRONIC","departmentId",department.toString()),List.of(new FixedBorrowRoute.Duty("DEPARTMENT_MANAGER",department))));
        UUID step=(UUID)db.required("SELECT current_step_id FROM workflow.instance WHERE id=?",instance).get("current_step_id");
        assertEquals(step,items(work.tasks(employee,Map.of("category","APPROVAL"))).getFirst().get("id"));
        UUID message=(UUID)items(work.inbox(employee,Map.of())).getFirst().get("id");
        assertEquals("#/workflow?step="+step,work.readMessage(employee,message).get("entry"));
        db.update("UPDATE iam.department_manager SET valid_until=CURRENT_TIMESTAMP WHERE id=?",assignment);
        assertEquals(0L,work.tasks(employee,Map.of("category","APPROVAL")).get("total"));assertEquals(0L,work.inbox(employee,Map.of()).get("total"));status(404,()->work.readMessage(employee,message));
    }
}
