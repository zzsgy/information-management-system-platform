package com.company.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 千万级本机专项：真实分区、全局去重和触发器，以固定小批次造数并调用正式查询服务。 */
@EnabledIfEnvironmentVariable(named="IMS_AUDIT_SCALE_LOAD",matches="yes")
@SpringBootTest(classes=PlatformApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.config.name=platform","server.ssl.enabled=false","platform.messaging.schedule-enabled=false","platform.notifications.schedule-enabled=false","platform.file-worker.enabled=false","platform.scan-intake.worker-enabled=false","platform.borrow-lifecycle.enabled=false","platform.audit-storage.enabled=false"})
class AuditScaleAcceptanceTest {
    @DynamicPropertySource static void guard(DynamicPropertyRegistry registry){String url=System.getenv("IMS_PLATFORM_DB_URL");if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:55476/ims_platform_dev_[0-9]+"))throw new IllegalStateException("压测只允许55476隔离库");}
    @Autowired Sql db;@Autowired AuditSecurity queries;@Autowired IdentityService identity;@Autowired DataSource source;@Autowired PlatformTransactionManager manager;@Autowired Environment environment;
    private static final int FACTS=1_000_000,INTENTS=9_000_000,BATCH=100_000;
    private Actor login(){return identity.authenticate(identity.login(Sql.map("employeeNo",System.getenv("IMS_PLATFORM_ADMIN_NO"),"password",System.getenv("IMS_PLATFORM_ADMIN_PASSWORD")),UUID.randomUUID(),"scale-benchmark").get("token").toString());}
    private Map<String,String> filters(String type,boolean intent){return new HashMap<>(intent?Map.of("module","PLATFORM","kind","INTERACTION","from","2025-04-01T00:00:00Z","to","2025-10-01T00:00:00Z","pageSize","20"):Map.of("module","PLATFORM","resourceType",type,"from","2025-04-01T00:00:00Z","to","2025-10-01T00:00:00Z","pageSize","20"));}
    /** 用与线上相同的INSERT和触发器，事务每十万行提交；不通过关闭约束获得漂亮的写入结果。 */
    private void populate(String tag,String hash)throws Exception{
        try(Connection connection=source.getConnection()){
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required,status) SELECT md5(?||g)::uuid,'LOAD-'||?||'-'||g,'日志压力隔离人员',(SELECT id FROM iam.department WHERE code='ROOT'),md5(?||g)||'@biolink.com',?,false,'DISABLED' FROM generate_series(0,199) g")){statement.setString(1,tag);statement.setString(2,tag.substring(0,36));statement.setString(3,tag);statement.setString(4,hash);statement.executeUpdate();}connection.commit();
            for(boolean intent:List.of(false,true))for(int start=0;start<(intent?INTENTS:FACTS);start+=BATCH){
                try(var statement=connection.createStatement()){statement.execute("SET LOCAL statement_timeout='300s'");}
                String time="timestamptz '2025-04-01'+(g%6)*interval '1 month'+(g/6)*interval '1 second'";
                String sql=intent?"INSERT INTO ops.ui_interaction(id,user_id,request_id,page,action,control,occurred_at) SELECT gen_random_uuid(),md5(?||(g%200))::uuid,gen_random_uuid(),'audit','ACTIVATE','BUTTON',"+time+" FROM generate_series(?,?) g":
                    "INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,resource_id,action,result,request_id,record_kind,severity) SELECT gen_random_uuid(),gen_random_uuid(),"+time+",md5(?||(g%200))::uuid,'PLATFORM',?,gen_random_uuid(),'LOAD_TEST','SUCCEEDED',gen_random_uuid(),'BUSINESS_EVENT','INFO' FROM generate_series(?,?) g";
                try(var statement=connection.prepareStatement(sql)){statement.setQueryTimeout(300);statement.setString(1,tag);int index=2;if(!intent)statement.setString(index++,tag);statement.setInt(index++,start+1);statement.setInt(index,start+BATCH);assertEquals(BATCH,statement.executeUpdate());}
                connection.commit();System.out.println("[audit-scale] "+(intent?"界面意图":"审计事实")+" "+(start+BATCH));
            }
        }
        for(String table:List.of("audit_identity","interaction_identity","audit_event","audit_scope","ui_interaction"))try(Connection connection=source.getConnection();var statement=connection.createStatement()){statement.setQueryTimeout(300);statement.execute("SET statement_timeout='300s'");statement.execute("ANALYZE ops."+table);}
    }
    private long elapsed(Runnable action){long start=System.nanoTime();action.run();return TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-start);}
    @Test void tenMillionMixedReadsAndLargeMonthArchive()throws Exception{
        Path report=Path.of(System.getenv("IMS_AUDIT_SCALE_REPORT"));assertFalse(Files.exists(report),"每次压测须使用新的证据文件，避免覆盖前次结果");
        String tag="LOAD-"+UUID.randomUUID();long started=System.nanoTime();populate(tag,Crypto.password("AuditLoad123"));long writeMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);Actor admin=login();
        assertEquals(FACTS,db.count("SELECT count(*) FROM ops.audit_event WHERE resource_type=?",tag));assertEquals(INTENTS,db.count("SELECT count(*) FROM ops.ui_interaction WHERE occurred_at>='2025-04-01' AND occurred_at<'2025-10-01'"));
        Map<String,Object> results=new LinkedHashMap<>();results.put("scope","LOCAL_ISOLATED_DATABASE_SERVICE_NOT_PRODUCTION_HTTP_OR_HA");results.put("factRows",FACTS);results.put("interactionRows",INTENTS);results.put("syntheticActors",200);results.put("batchRows",BATCH);results.put("loadMillis",writeMillis);results.put("jvmMaxHeapBytes",Runtime.getRuntime().maxMemory());
        var first=queries.query(admin,filters(tag,false),UUID.randomUUID());assertNull(first.get("total"));
        UUID ticket=(UUID)first.get("queryId"),cursor=UUID.randomUUID();var anchor=db.required("SELECT occurred_at,id FROM ops.audit_event WHERE resource_type=? ORDER BY occurred_at DESC,id DESC OFFSET 800000 LIMIT 1",tag);
        // OFFSET只用于构造深位置夹具；被测服务实际收到的仍是正式游标，不提供跳页后门。
        db.update("INSERT INTO ops.audit_page_cursor(id,query_id,anchor_time,anchor_id) VALUES(?,?,?::timestamptz,?)",cursor,ticket,anchor.get("occurred_at"),anchor.get("id"));
        List<Long> deep=new ArrayList<>();for(int n=0;n<30;n++)deep.add(elapsed(()->queries.query(admin,Map.of("cursor",cursor.toString()),UUID.randomUUID())));Collections.sort(deep);results.put("deepCursorP95Micros",deep.get(28));assertTrue(deep.get(28)<1_000_000,"深游标服务P95须小于1秒");
        results.put("explicitFactCountMicros",elapsed(()->assertEquals((long)FACTS,queries.count(admin,ticket).get("total"))));
        var ui=queries.query(admin,filters(tag,true),UUID.randomUUID());results.put("explicitIntentCountMicros",elapsed(()->assertEquals((long)INTENTS,queries.count(admin,(UUID)ui.get("queryId")).get("total"))));
        List<Long> latency=Collections.synchronizedList(new ArrayList<>());String mixed="MIXED-"+UUID.randomUUID();
        try(var executor=Executors.newFixedThreadPool(10)){
            List<Callable<Void>> work=new ArrayList<>();for(int n=0;n<8;n++)work.add(()->{for(int i=0;i<50;i++){boolean intent=i%2==0;latency.add(elapsed(()->queries.query(admin,filters(tag,intent),UUID.randomUUID())));}return null;});
            for(int n=0;n<2;n++)work.add(()->{for(int i=0;i<100;i++)new Audit(db).event(admin.userId(),"PLATFORM",mixed,UUID.randomUUID(),"WRITE_TEST","SUCCEEDED",UUID.randomUUID());return null;});
            for(var future:executor.invokeAll(work))future.get();
        }
        Collections.sort(latency);results.put("parallelReaders",8);results.put("parallelWriters",2);results.put("readRequests",latency.size());results.put("writeEvents",200);results.put("mixedReadP50Micros",latency.get(199));results.put("mixedReadP95Micros",latency.get(379));results.put("mixedReadP99Micros",latency.get(395));assertTrue(latency.get(379)<1_000_000,"混合查询P95须小于1秒");assertEquals(200L,db.count("SELECT count(*) FROM ops.audit_event WHERE resource_type=?",mixed));
        var plan=db.required("EXPLAIN (ANALYZE,FORMAT JSON) SELECT id FROM ops.ui_interaction WHERE occurred_at>='2025-05-01' AND occurred_at<'2025-06-01' ORDER BY occurred_at DESC,id DESC LIMIT 21").values().iterator().next().toString();assertTrue(plan.contains("202505"));assertFalse(plan.contains("202501"));results.put("singleMonthPartitionPlan",Crypto.JSON.readTree(plan));
        var env=new MockEnvironment().withProperty("platform.audit-storage.database-volume",System.getenv("IMS_PLATFORM_AUDIT_DATABASE_VOLUME")).withProperty("platform.audit-storage.primary",System.getenv("IMS_PLATFORM_AUDIT_ARCHIVE_PRIMARY")).withProperty("platform.audit-storage.secondary",System.getenv("IMS_PLATFORM_AUDIT_ARCHIVE_SECONDARY"));
        AuditStorage archiver=new AuditStorage(db,identity,new Audit(db),source,manager,env);
        var ctx=new Commands.Context(admin,UUID.randomUUID(),UUID.randomUUID(),"LOAD_ARCHIVE",null,null);var run=archiver.queue(ctx,Sql.map("month","2025-04-01"));long archiveMillis=elapsed(archiver::archiveNext)/1000;var archived=archiver.run(admin,(UUID)run.get("id"));assertEquals("ARCHIVED",archived.get("state"));results.put("largeMonthArchiveMillis",archiveMillis);results.put("largeMonthArchive",archived);results.put("capacity",archiver.sample(admin));
        assertEquals(FACTS,db.count("SELECT count(*) FROM ops.audit_event WHERE resource_type=?",tag));assertEquals(INTENTS,db.count("SELECT count(*) FROM ops.ui_interaction WHERE occurred_at>='2025-04-01' AND occurred_at<'2025-10-01'"));results.put("onlineRowsPreservedAfterArchive",true);
        Files.writeString(report,Crypto.json(results),StandardOpenOption.CREATE_NEW);System.out.println("[audit-scale] 千万级读写与整月归档验收通过");
    }
}
