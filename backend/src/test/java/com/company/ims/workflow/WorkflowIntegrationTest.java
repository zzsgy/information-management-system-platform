package com.company.ims.workflow;

import com.company.ims.archive.ArchiveAccess;
import com.company.ims.identity.*;
import com.company.ims.shared.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.request.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.company.ims.workflow.WorkflowInput.fields;

/** 使用独立H2 PostgreSQL兼容库验证真实SQL、Spring事务及访问范围，绝不连接现有业务数据库。 */
class WorkflowIntegrationTest {
    /** 每个测试拥有独立Spring容器和随机内存数据库。 */
    private AnnotationConfigApplicationContext context;
    /** 测试查询及造数入口。 */
    private Db db;
    /** 经Spring事务代理的业务服务。 */
    private BusinessService business;
    /** 经Spring事务代理的审批服务。 */
    private ApprovalService approval;
    /** 本部门档案管理员。 */
    private Actor manager;
    /** 另一部门档案管理员。 */
    private Actor outsider;
    /** 系统管理员，用于配置跨部门测试规则。 */
    private Actor admin;
    /** 本部门普通申请人。 */
    private Actor employee;
    /** 测试档案主键。 */
    private long archiveA;
    /** 第二份测试档案主键。 */
    private long archiveB;
    /** 外部门测试档案主键。 */
    private long foreignArchive;

    /** 用真实迁移脚本初始化空内存库，构造两个互相隔离的部门。 */
    @BeforeEach
    void prepare() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        db = context.getBean(Db.class); business=context.getBean(BusinessService.class); approval=context.getBean(ApprovalService.class);
        db.insert("ims_department",fields("dept_code","OTHER","dept_name","第二部门"));
        long adminId = user("admin",1,"super_admin"); // 可配置全局模板的系统身份。
        long managerId = user("manager",1,"archive_admin"); // 部门一办理人。
        long outsiderId = user("outsider",2,"archive_admin"); // 部门二办理人。
        long employeeId = user("employee",1,"employee"); // 部门一申请人。
        admin=actor(adminId,1,Set.of("*"));
        manager=actor(managerId,1,Set.of("archive.read","archive.write","borrow.apply","borrow.approve","destroy.apply","destroy.approve","destroy.execute","approval.todo.view","approval.template.manage"));
        outsider=actor(outsiderId,2,manager.permissions());
        employee=actor(employeeId,1,Set.of("archive.read","borrow.apply","approval.todo.view"));
        db.execute("update ims_department set leader_user_id=? where id=1",managerId);
        db.execute("update ims_department set leader_user_id=? where id=2",outsiderId);
        archiveA=archive("A",1); archiveB=archive("B",1); foreignArchive=archive("FOREIGN",2);
        bind(admin);
    }

    /** 清理请求身份和内存容器，避免测试间身份或连接泄露。 */
    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        if (context != null) context.close();
    }

    /** 从通用待办审批销毁后业务单必须同步，申请A执行B必须拒绝且不产生批次。 */
    @Test
    void genericApprovalSynchronizesBusinessAndRejectsSubstitutedArchive() {
        explicitTemplate("destroy",manager.id(),0);
        bind(manager);
        long request = id(business.createDestroy(destroyBody(archiveA),manager)); // 销毁A申请。
        long flow = approval.flowId("destroy",request); // 与业务单唯一对应的流程。
        WorkflowController controller = new WorkflowController(approval,business); // 实际通用待办入口。
        controller.act(flow,fields("action","agree"));
        assertEquals("approved",value("destroy_request",request,"status"));
        assertEquals("approved",value("approval_flow",flow,"status"));
        assertThrows(BusinessException.class,() -> business.executeDestroy(request,executeBody(archiveB),manager));
        assertEquals(0,db.scalarLong("select count(*) from destroy_batch"));
        assertEquals("archive",value("archive",archiveA,"status"));
        assertEquals("archive",value("archive",archiveB,"status"));
    }

    /** 正确执行一次后不得重复执行；申请明细、快照、软删除和审计全部保留。 */
    @Test
    void approvedDestroyExecutesExactlyOnceAndPreservesEvidence() {
        explicitTemplate("destroy",manager.id(),0); bind(manager);
        long request = id(business.createDestroy(destroyBody(archiveA),manager)); // 本次唯一销毁申请。
        business.approveDestroy(request,true,"批准",manager);
        business.executeDestroy(request,executeBody(archiveA),manager);
        assertEquals("executed",value("destroy_request",request,"status"));
        assertEquals("destroyed",value("archive",archiveA,"status"));
        assertNotNull(value("archive",archiveA,"deleted_at"));
        assertEquals(1,db.scalarLong("select count(*) from destroy_request_detail where request_id=?",request));
        assertEquals(1,db.scalarLong("select count(*) from destroy_detail"));
        assertThrows(BusinessException.class,() -> business.executeDestroy(request,executeBody(archiveA),manager));
        assertEquals(1,db.scalarLong("select count(*) from destroy_batch"));
        assertEquals(List.of(archiveA),business.destroy(request,manager).get("archive_ids"));
    }

    /** 缺失或已停用审批人不会被跳过并自动通过，整笔申请回滚。 */
    @Test
    void missingApproversFailClosedAndRollbackApplication() {
        explicitTemplate("borrow",manager.id(),0);
        db.execute("update ims_user set account_status='disabled' where id=?",manager.id());
        bind(employee);
        assertThrows(BusinessException.class,() -> business.createBorrow(borrowBody("electronic",archiveA),employee));
        assertEquals(0,db.scalarLong("select count(*) from borrow_record"));
        assertEquals(0,db.scalarLong("select count(*) from approval_flow"));
    }

    /** 所有节点条件均不适用时不能自动通过；未知运算符必须在模板保存时拒绝。 */
    @Test
    void emptyEffectivePlanAndUnknownConditionCannotAutoApprove() {
        Map<String,Object> node = fields("node_order",1,"node_name","条件节点","approver_type","user","approver_value",Long.toString(manager.id()),"cond_field","archive_no","cond_op","eq","cond_value","OTHER"); // 条件不匹配测试档案。
        approval.saveTemplate(null,fields("name","不适用模板","biz_type","borrow","nodes",List.of(node)),admin);
        bind(employee);
        assertThrows(BusinessException.class,() -> business.createBorrow(borrowBody("electronic",archiveA),employee));
        assertEquals(0,db.scalarLong("select count(*) from borrow_record"));
        node.put("cond_op","execute_sql"); // 未知运算不能被当作无条件通过。
        assertThrows(BusinessException.class,() -> approval.saveTemplate(null,fields("name","错误模板","biz_type","borrow","nodes",List.of(node)),admin));
        assertEquals(1,db.scalarLong("select count(*) from approval_template"));
    }

    /** 中途驳回立即同步业务拒绝状态，后续节点和未知动作都不能再推进流程。 */
    @Test
    void rejectionTerminatesFlowAndBusinessInOneTransaction() {
        explicitTemplate("borrow",manager.id(),0); bind(employee);
        long request = id(business.createBorrow(borrowBody("electronic",archiveA),employee)); // 待审借阅申请。
        long flow = approval.flowId("borrow",request); // 审批流程。
        bind(manager);
        assertThrows(BusinessException.class,() -> approval.act(flow,"anything",null,manager));
        assertEquals(0,db.scalarLong("select count(*) from approval_flow_record"));
        approval.act(flow,"reject","用途不明确",manager);
        assertEquals("rejected",value("approval_flow",flow,"status"));
        assertEquals("rejected",value("borrow_record",request,"status"));
        assertThrows(BusinessException.class,() -> approval.act(flow,"agree",null,manager));
        assertThrows(BusinessException.class,() -> business.checkout(request,manager));
    }

    /** 同一模板在不同档案上解析出不同部门负责人时，批量申请必须明确拒绝。 */
    @Test
    void mixedBatchRequiresIdenticalActualApprovers() {
        approval.saveTemplate(null,fields("name","部门审批","biz_type","borrow","nodes",List.of(fields("node_order",1,"node_name","部门领导","approver_type","dept_leader"))),admin);
        assertThrows(BusinessException.class,() -> business.createBorrow(borrowBody("electronic",archiveA,foreignArchive),admin));
        assertEquals(0,db.scalarLong("select count(*) from borrow_record"));
    }

    /** 会签要求所有明确指派的人员完成；同人重复操作和非指派人员越权均被拒绝。 */
    @Test
    void countersignRequiresEveryAssigneeAndRejectsDuplicates() {
        long secondId = user("second",1,"archive_admin"); // 第二名同部门会签人。
        Actor second = actor(secondId,1,manager.permissions()); // 同部门数据范围。
        approval.saveTemplate(null,fields("name","会签","biz_type","borrow","nodes",List.of(fields("node_order",1,"node_name","会签","approver_type","role","approver_value","archive_admin","is_countersign",1))),admin);
        bind(employee);
        long request = id(business.createBorrow(borrowBody("electronic",archiveA),employee)); // 对应两名本部门审批人的申请。
        long flow = approval.flowId("borrow",request); // 当前待办流程。
        bind(manager); approval.act(flow,"agree",null,manager);
        assertEquals("pending",value("borrow_record",request,"status"));
        assertEquals(0,approval.tasks("borrow",manager).size());
        assertThrows(BusinessException.class,() -> approval.act(flow,"agree",null,manager));
        bind(admin); assertThrows(BusinessException.class,() -> approval.act(flow,"agree",null,admin));
        bind(second); approval.act(flow,"agree",null,second);
        assertEquals("approved",value("borrow_record",request,"status"));
        assertEquals(2,db.scalarLong("select count(*) from approval_flow_record where flow_id=?",flow));
    }

    /** 跨部门无法通过列表、流程详情、猜测ID或申请接口绕过范围限制。 */
    @Test
    void departmentScopeAppliesToListsDetailsAndWrites() {
        explicitTemplate("borrow",manager.id(),0); bind(employee);
        long request = id(business.createBorrow(borrowBody("electronic",archiveA),employee)); // 部门一申请。
        long flow = approval.flowId("borrow",request); // 部门一流程。
        bind(outsider);
        assertEquals(0L,business.borrows(null,null,1,20,outsider).get("total"));
        assertThrows(BusinessException.class,() -> business.borrow(request,outsider));
        assertThrows(BusinessException.class,() -> approval.detail(flow,outsider));
        assertThrows(BusinessException.class,() -> business.createBorrow(borrowBody("electronic",archiveA),outsider));
        assertThrows(BusinessException.class,() -> business.createDamage(fields("archive_id",archiveA,"damage_desc","无权登记"),outsider));
    }

    /** 撤销审批权限后即使还保留待办查看权限与旧节点指派，通用入口也不得批准。 */
    @Test
    void genericApprovalCannotBypassRevokedBusinessPermission() {
        explicitTemplate("borrow",manager.id(),0); bind(employee);
        long request = id(business.createBorrow(borrowBody("electronic",archiveA),employee)); // 撤权前创建的有效申请。
        long flow = approval.flowId("borrow",request); // 固化了旧审批人身份的流程。
        Actor revoked = actor(manager.id(),1,Set.of("archive.read","approval.todo.view")); // 只保留查看，不保留borrow.approve。
        bind(revoked);
        WorkflowController controller = new WorkflowController(approval,business); // 与桌面待办使用同一入口。
        assertEquals(403,assertThrows(BusinessException.class,() -> controller.act(flow,fields("action","agree"))).status());
        assertEquals("pending",value("borrow_record",request,"status"));
        assertEquals(0,db.scalarLong("select count(*) from approval_flow_record where flow_id=?",flow));
    }

    /** 跨部门只读共享不附带审批权，指定外部门人员必须另外授予该档案的审批动作。 */
    @Test
    void readSharingDoesNotAuthorizeCrossDepartmentApproval() {
        grant(archiveA,outsider.id(),"archive.read");
        explicitTemplate("destroy",outsider.id(),0); bind(manager);
        assertThrows(BusinessException.class,() -> business.createDestroy(destroyBody(archiveA),manager));
        grant(archiveA,outsider.id(),"destroy.approve");
        long request = id(business.createDestroy(destroyBody(archiveA),manager)); // 同时具有read和审批共享后才可实例化。
        db.execute("delete from archive_grant where archive_id=? and grantee_id=? and permission_code='destroy.approve'",archiveA,outsider.id()); // 流程创建后撤销对象审批权。
        bind(outsider);
        assertEquals(403,assertThrows(BusinessException.class,() -> approval.act(approval.flowId("destroy",request),"agree",null,outsider)).status());
        assertEquals("pending",value("destroy_request",request,"status"));
    }

    /** 只读共享允许查看，但借阅申请必须另有borrow.apply对象授权。 */
    @Test
    void readSharingDoesNotImplicitlyAuthorizeBorrowApplication() {
        explicitTemplate("borrow",manager.id(),0);
        grant(archiveA,outsider.id(),"archive.read"); bind(outsider);
        assertThrows(BusinessException.class,() -> business.createBorrow(borrowBody("electronic",archiveA),outsider));
        grant(archiveA,outsider.id(),"borrow.apply");
        assertNotNull(business.createBorrow(borrowBody("electronic",archiveA),outsider).get("id"));
    }

    /** 最后节点同步业务状态失败时，审批记录、节点和流程的变动全部回滚。 */
    @Test
    void failedBusinessTransitionRollsBackEntireApproval() {
        explicitTemplate("borrow",manager.id(),0); bind(employee);
        long request = id(business.createBorrow(borrowBody("electronic",archiveA),employee)); // 待审申请。
        long flow = approval.flowId("borrow",request); // 待审流程。
        db.execute("update borrow_record set status='rejected' where id=?",request); // 故意构造损坏状态验证事务原子性。
        bind(manager);
        assertThrows(BusinessException.class,() -> approval.act(flow,"agree","不应提交",manager));
        assertEquals("pending",value("approval_flow",flow,"status"));
        assertEquals(0,db.scalarLong("select count(*) from approval_flow_record where flow_id=?",flow));
        assertEquals(0,db.scalarLong("select sum(x.acted) from approval_node_assignee x join approval_flow_node n on n.id=x.node_id where n.flow_id=?",flow));
    }

    /** 纸质借阅必须先批准再借出，归还恢复档案状态，重复归还不能覆盖事实。 */
    @Test
    void physicalBorrowFollowsExplicitCheckoutAndReturnStateMachine() {
        explicitTemplate("borrow",manager.id(),0); bind(manager);
        long request = id(business.createBorrow(borrowBody("physical",archiveA),manager)); // 纸质借阅单。
        assertThrows(BusinessException.class,() -> business.returnBorrow(request,fields(),manager));
        business.approveBorrow(request,true,null,manager);
        assertThrows(BusinessException.class,() -> business.returnBorrow(request,fields(),manager));
        business.checkout(request,manager);
        assertEquals("borrowed",value("archive",archiveA,"status"));
        business.returnBorrow(request,fields("return_condition","原件完整"),manager);
        assertEquals("archive",value("archive",archiveA,"status"));
        assertEquals("returned",value("borrow_record",request,"status"));
        assertThrows(BusinessException.class,() -> business.returnBorrow(request,fields(),manager));
    }

    /** 两张批准单不能同时借出同一纸质原件；第二单保持批准状态而非部分提交。 */
    @Test
    void physicalArchiveCannotBeCheckedOutTwice() {
        explicitTemplate("borrow",manager.id(),0); bind(manager);
        long first = id(business.createBorrow(borrowBody("physical",archiveA),manager)); // 第一张借阅单。
        long second = id(business.createBorrow(borrowBody("physical",archiveA),manager)); // 尚未借出的第二张申请。
        business.approveBorrow(first,true,null,manager); business.approveBorrow(second,true,null,manager);
        business.checkout(first,manager);
        assertThrows(BusinessException.class,() -> business.checkout(second,manager));
        assertEquals("approved",value("borrow_record",second,"status"));
        assertNull(db.required("select checkout_time from borrow_detail where borrow_id=?",second).get("checkout_time"));
    }

    /** 活动电子借阅同样阻止销毁，关闭后才能提出销毁申请。 */
    @Test
    void activeElectronicBorrowPreventsDestruction() {
        explicitTemplate("borrow",manager.id(),0); explicitTemplate("destroy",manager.id(),0); bind(manager);
        long request = id(business.createBorrow(borrowBody("electronic",archiveA),manager)); // 活动电子借阅。
        business.approveBorrow(request,true,null,manager);
        assertThrows(BusinessException.class,() -> business.createDestroy(destroyBody(archiveA),manager));
        business.returnBorrow(request,fields(),manager);
        assertNotNull(business.createDestroy(destroyBody(archiveA),manager).get("id"));
    }

    /** 损坏处理逐次留痕，关闭后禁止再次改写处理结果。 */
    @Test
    void damageTransitionsAppendHistoryAndStopAtClosed() {
        bind(manager);
        long damage = id(business.createDamage(fields("archive_id",archiveA,"damage_type","破损","damage_desc","封面磨损"),manager)); // 新损坏记录。
        business.handleDamage(damage,fields("status","processing","handling_method","修复","handling_result","安排修复"),manager);
        business.handleDamage(damage,fields("status","resolved","handling_method","修复","handling_result","修复完成"),manager);
        business.handleDamage(damage,fields("status","closed","handling_method","复核","handling_result","复核通过"),manager);
        assertEquals(3,db.scalarLong("select count(*) from damage_handling_record where damage_id=?",damage));
        assertThrows(BusinessException.class,() -> business.handleDamage(damage,fields("status","processing","handling_method","覆盖","handling_result","改写"),manager));
        bind(outsider); assertThrows(BusinessException.class,() -> business.damage(damage,outsider));
    }

    /** 两个并发执行请求由数据库行锁与唯一约束保证仅一个成功。 */
    @Test
    void concurrentDestroyRequestsProduceOneBatch() throws Exception {
        explicitTemplate("destroy",manager.id(),0); bind(manager);
        long request = id(business.createDestroy(destroyBody(archiveA),manager)); // 两个线程争用同一申请。
        business.approveDestroy(request,true,null,manager);
        CountDownLatch start = new CountDownLatch(1); // 同步两个任务开始争用。
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = new ArrayList<>(); // 每个结果为是否成功执行。
            for (int attempt = 0; attempt < 2; attempt++) {
                results.add(executor.submit(() -> {
                    bind(manager);
                    try { start.await(); business.executeDestroy(request,executeBody(archiveA),manager); return true; }
                    catch (BusinessException rejected) { return false; }
                    finally { RequestContextHolder.resetRequestAttributes(); }
                }));
            }
            start.countDown();
            int successes = 0; // 必须恰好一个成功结果。
            for (Future<Boolean> result : results) if (result.get(15,TimeUnit.SECONDS)) successes++;
            assertEquals(1,successes);
        }
        assertEquals(1,db.scalarLong("select count(*) from destroy_batch"));
        assertEquals(1,db.scalarLong("select count(*) from destroy_detail"));
    }

    /** 测试创建指定账号并分配已有种子角色。 */
    private long user(String name,long dept,String role) {
        long id = db.insert("ims_user",fields("username",name,"password_hash","not-a-real-password","real_name",name,"dept_id",dept)); // 仅测试身份，无可用密码。
        db.execute("insert into ims_user_role(user_id,role_id) select ?,id from ims_role where role_code=?",id,role);
        return id;
    }

    /** 构造测试Actor，权限由测试显式指定以覆盖授权边界。 */
    private Actor actor(long id,long dept,Set<String> permissions) { return new Actor(id,dept,"test-" + id,"测试用户" + id,Set.of(),permissions); }

    /** 创建最小有效档案记录，使用迁移脚本的默认分类。 */
    private long archive(String number,long dept) { return db.insert("archive",fields("archive_no",number,"archive_name","测试档案" + number,"category_id",1L,"dept_id",dept,"status","archive")); }

    /** 显式授予单份档案某个动作，测试查询与办理权限是否保持独立。 */
    private void grant(long archiveId,long userId,String permission) {
        db.insert("archive_grant",fields("archive_id",archiveId,"grantee_type","user","grantee_id",userId,"permission_code",permission,"granted_by",admin.id()));
    }

    /** 绑定请求身份，使ArchiveAccess走与HTTP相同的授权路径。 */
    private void bind(Actor actor) {
        MockHttpServletRequest request = new MockHttpServletRequest(); // 独立请求上下文。
        request.setAttribute(CurrentUser.ATTRIBUTE,actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 安装单人审批模板，避免测试依赖默认模板中系统管理员候选人。 */
    private void explicitTemplate(String type,long approver,int countersign) {
        approval.saveTemplate(null,fields("name","测试" + type,"biz_type",type,"nodes",List.of(fields("node_order",1,"node_name","业务审批","approver_type","user","approver_value",Long.toString(approver),"is_countersign",countersign))),admin);
    }

    /** 构造旧客户端形式的借阅请求。 */
    private Map<String,Object> borrowBody(String mode,Long... ids) { return fields("archive_ids",List.of(ids),"borrow_mode",mode,"purpose","测试借阅"); }
    /** 构造带具体原因的销毁申请。 */
    private Map<String,Object> destroyBody(Long... ids) { return fields("archive_ids",List.of(ids),"reason","测试档案已完成鉴定"); }
    /** 构造带见证人与方式的执行请求。 */
    private Map<String,Object> executeBody(Long... ids) { return fields("archive_ids",List.of(ids),"witness","测试见证人","method","测试逻辑处置"); }
    /** 读取响应中的业务主键。 */
    private long id(Map<String,Object> result) { return ((Number)result.get("id")).longValue(); }
    /** 读取受控测试表的一列状态用于断言。 */
    private Object value(String table,long id,String column) { return db.required("select " + column + " from " + table + " where id=?",id).get(column); }

    /** 只注册工作流所需依赖，防止测试启动正式服务器或初始化真实管理员。 */
    @Configuration
    @EnableTransactionManagement
    static class TestConfiguration {
        /** 创建随机命名的内存数据库并执行正式DDL，验证迁移脚本可用性。 */
        @Bean(destroyMethod="close") HikariDataSource dataSource() {
            HikariDataSource source = new HikariDataSource(); // 连接池保持建表连接，避免H2常量集合CHECK仍引用已关闭的建表会话。
            source.setJdbcUrl("jdbc:h2:mem:workflow_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
            source.setUsername("sa"); source.setPassword(""); source.setMaximumPoolSize(4); source.setMinimumIdle(1);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__identity.sql"),new ClassPathResource("db/migration/V2__archive.sql"),new ClassPathResource("db/migration/V3__workflow.sql")).execute(source);
            return source;
        }
        /** JDBC实例共享当前测试数据源。 */
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        /** 业务数据库工具。 */
        @Bean Db db(JdbcTemplate jdbc) { return new Db(jdbc); }
        /** 真实事务管理器用于验证跨表回滚和并发锁。 */
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        /** 实际档案授权策略，避免使用全放行mock。 */
        @Bean ArchiveAccess access(Db db) { return new ArchiveAccess(db); }
        /** 实际审计写入，检查与业务共用事务。 */
        @Bean AuditService audit(Db db) { return new AuditService(db); }
        /** 审批引擎通过Spring创建事务代理。 */
        @Bean ApprovalService approval(Db db,ArchiveAccess access,AuditService audit) { return new ApprovalService(db,access,audit); }
        /** 业务服务通过Spring创建事务代理。 */
        @Bean BusinessService business(Db db,ArchiveAccess access,ApprovalService approval,AuditService audit) { return new BusinessService(db,access,approval,audit); }
    }
}
