package com.company.platform;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** 真实PostgreSQL、正式服务和文件；游标、全局去重、归档失败与独立回读均不使用模拟仓储。 */
@EnabledIfEnvironmentVariable(named="IMS_AUDIT_SCALE_INTEGRATION",matches="yes")
@SpringBootTest(classes=PlatformApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.config.name=platform","server.ssl.enabled=false","platform.messaging.schedule-enabled=false","platform.notifications.schedule-enabled=false","platform.file-worker.enabled=false","platform.scan-intake.worker-enabled=false","platform.borrow-lifecycle.enabled=false","platform.audit-storage.initial-delay-ms=86400000","platform.audit-storage.worker-delay-ms=3600000"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditStorageIntegrationTest {
    @DynamicPropertySource static void guard(DynamicPropertyRegistry registry){String url=System.getenv("IMS_PLATFORM_DB_URL");if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:55476/ims_platform_dev_[0-9]+"))throw new IllegalStateException("日志规模验收只允许55476隔离库");}
    @Autowired Sql db;@Autowired AuditSecurity security;@Autowired AuditStorage storage;@Autowired IdentityService identity;@Autowired Commands commands;@Autowired DataSource source;@Autowired PlatformTransactionManager manager;
    private Actor admin;
    @BeforeEach void login(){admin=authenticate(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));}
    private Actor authenticate(String no,String password){return identity.authenticate(identity.login(Sql.map("employeeNo",no,"password",password),UUID.randomUUID(),"audit-scale-"+UUID.randomUUID()).get("token").toString());}
    private Actor person(boolean it){UUID user=UUID.randomUUID();String no="SCALE-"+user;db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) SELECT ?,?,'日志隔离人员',id,?,?,false FROM iam.department WHERE code='ROOT'",user,no,user+"@biolink.com",Crypto.password("AuditTest123"));if(it)db.update("INSERT INTO iam.user_role(id,user_id,role_id,valid_from,assigned_by,module,role_code) SELECT ?,?,id,CURRENT_TIMESTAMP,?,'PLATFORM','IT_ADMIN' FROM iam.role WHERE module='PLATFORM' AND code='IT_ADMIN'",UUID.randomUUID(),user,admin.userId());return authenticate(no,"AuditTest123");}
    private Map<String,String> filters(String type){return new HashMap<>(Map.of("module","PLATFORM","from",Instant.now().minusSeconds(3600).toString(),"to",Instant.now().plusSeconds(3600).toString(),"resourceType",type,"pageSize","3"));}
    private void insert(UUID id,UUID event,Instant time,UUID actor,String type,String kind,String key){db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,action,result,request_id,record_kind,operation_key) VALUES(?,?,?::timestamptz,?,'PLATFORM',?,'TEST','SUCCEEDED',?,?,?)",id,event,time.toString(),actor,type,UUID.randomUUID(),kind,key);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> result){return (List<Map<String,Object>>)result.get("items");}
    private void status(int status,org.junit.jupiter.api.function.Executable action){assertEquals(status,assertThrows(ApiFault.class,action).status);}
    private Commands.Context context(){var proof=identity.reauthenticate(admin,Sql.map("password",System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"),"purpose","MANAGE","objectIds",List.of(ApiController.COLLECTION.toString()),"scopeItems",List.of(Sql.map("objectType","AUDIT_ARCHIVE","objectId",ApiController.COLLECTION.toString(),"actions",List.of("ARCHIVE_LOGS")))),UUID.randomUUID());return new Commands.Context(admin,UUID.randomUUID(),UUID.randomUUID(),"TEST ARCHIVE_LOGS",proof.get("verificationToken").toString(),null);}
    private Map<String,Object> queue(String month,Commands.Context ctx){var body=Sql.map("month",month);return commands.run(ctx,body,"AUDIT_ARCHIVE",ApiController.COLLECTION,"ARCHIVE_LOGS",()->ctx.actor().require("PLATFORM.SECURITY_MANAGE"),()->storage.queue(ctx,body),id->storage.run(ctx.actor(),id));}

    @Test @Order(1) void cursorTiesNoDuplicatesStableCutoffAndExplicitCount(){
        String type="PAGE-"+UUID.randomUUID();Instant time=Instant.now().minusSeconds(30);List<String> expected=new ArrayList<>();
        for(int n=0;n<8;n++){UUID id=UUID.randomUUID();expected.add(id.toString());insert(id,UUID.randomUUID(),time,admin.userId(),type,"BUSINESS_EVENT",null);}expected.sort(Comparator.reverseOrder());
        var first=security.query(admin,filters(type),UUID.randomUUID());assertNull(first.get("total"));UUID query=(UUID)first.get("queryId");assertEquals(8L,security.count(admin,query).get("total"));
        insert(UUID.randomUUID(),UUID.randomUUID(),time,admin.userId(),type,"BUSINESS_EVENT",null);
        List<String> actual=new ArrayList<>();Map<String,Object> current=first;
        while(true){rows(current).forEach(row->actual.add(row.get("id").toString()));if(!Boolean.TRUE.equals(current.get("hasNext")))break;current=security.query(admin,Map.of("cursor",current.get("nextCursor").toString()),UUID.randomUUID());assertEquals(query,current.get("queryId"));}
        assertEquals(expected,actual);assertEquals(8L,security.count(admin,query).get("total"));
        assertEquals(rows(first),rows(security.query(admin,Map.of("cursor",first.get("cursor").toString()),UUID.randomUUID())));
        status(400,()->security.query(admin,Map.of("cursor",first.get("cursor").toString(),"module","ARCHIVE"),UUID.randomUUID()));
        var deep=filters(type);deep.put("page","2000");status(400,()->security.query(admin,deep,UUID.randomUUID()));
    }
    @Test @Order(2) void cursorOwnerExpiryAndRevokedRole(){
        Actor other=person(true),staff=person(false);var page=security.query(other,filters("NONE"),UUID.randomUUID());String cursor=page.get("cursor").toString();UUID id=(UUID)page.get("queryId");
        status(404,()->security.query(admin,Map.of("cursor",cursor),UUID.randomUUID()));status(403,()->storage.overview(staff));
        db.update("UPDATE iam.user_role SET state='ENDED' WHERE user_id=? AND module='PLATFORM'",other.userId());
        status(403,()->security.query(other,Map.of("cursor",cursor),UUID.randomUUID()));status(403,()->security.count(other,id));
        var own=security.query(admin,filters("NONE"),UUID.randomUUID());db.update("UPDATE ops.audit_export SET expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",own.get("queryId"));
        status(404,()->security.query(admin,Map.of("cursor",own.get("cursor").toString()),UUID.randomUUID()));status(404,()->security.count(admin,(UUID)own.get("queryId")));
    }
    @Test @Order(3) void crossMonthEventAndOperationUniquenessAndIntentReplay(){
        UUID id=UUID.randomUUID(),event=UUID.randomUUID();Instant jan=Instant.parse("2025-01-17T00:00:00Z"),feb=Instant.parse("2025-02-17T00:00:00Z");
        insert(id,event,jan,admin.userId(),"UNIQUE_TEST","BUSINESS_EVENT",null);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->insert(UUID.randomUUID(),event,feb,admin.userId(),"UNIQUE_TEST","BUSINESS_EVENT",null));
        String key="scale-operation-"+UUID.randomUUID();insert(UUID.randomUUID(),UUID.randomUUID(),jan,admin.userId(),"UNIQUE_TEST","IDEMPOTENCY_RECEIPT",key);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->insert(UUID.randomUUID(),UUID.randomUUID(),feb,admin.userId(),"UNIQUE_TEST","IDEMPOTENCY_RECEIPT",key));
        UUID intent=UUID.randomUUID();var body=Sql.map("id",intent.toString(),"requestId",UUID.randomUUID().toString(),"page","audit","action","SUBMIT","control","FORM");security.interaction(admin,body);security.interaction(admin,body);
        assertEquals(1L,db.count("SELECT count(*) FROM ops.ui_interaction WHERE id=?",intent));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("TRUNCATE ops.audit_event_202501"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("DELETE FROM ops.audit_identity WHERE id=?",id));
    }
    @Test @Order(4) void capacityThresholdsDeduplicateAndNoExactAuditScan(){
        var env=new MockEnvironment().withProperty("platform.audit-storage.database-volume",System.getenv("IMS_PLATFORM_AUDIT_DATABASE_VOLUME")).withProperty("platform.audit-storage.minimum-free-bytes","0");
        AuditStorage critical=new AuditStorage(db,identity,new Audit(db),source,manager,env.withProperty("platform.audit-storage.budget-bytes","1"));var sample=critical.sample(admin);assertEquals("CRITICAL",sample.get("state"));
        long bytes=((Number)sample.get("total_bytes")).longValue();AuditStorage warning=new AuditStorage(db,identity,new Audit(db),source,manager,env.withProperty("platform.audit-storage.budget-bytes",Long.toString((long)(bytes/.75))));assertEquals("WARNING",warning.sample(admin).get("state"));
        AuditStorage normal=new AuditStorage(db,identity,new Audit(db),source,manager,env.withProperty("platform.audit-storage.budget-bytes",Long.toString(Long.MAX_VALUE)));assertEquals("NORMAL",normal.sample(admin).get("state"));
        critical.sample(admin);assertEquals(1L,db.count("SELECT count(*) FROM ops.security_alert WHERE user_id=? AND kind='AUDIT_CAPACITY' AND created_at>CURRENT_TIMESTAMP-interval '1 day'",admin.userId()));
        assertEquals(1L,db.count("SELECT count(*) FROM ops.account_message WHERE user_id=? AND kind='AUDIT_CAPACITY' AND created_at>CURRENT_TIMESTAMP-interval '1 day'",admin.userId()));
    }
    @Test @Order(5) void archiveReplayBothCopiesAndIndependentRestore()throws Exception{
        var ctx=context();var queued=queue("2025-01-01",ctx);UUID id=(UUID)queued.get("id");assertEquals(id,queue("2025-01-01",ctx).get("id"));
        long before=db.count("SELECT count(*) FROM ops.audit_event WHERE occurred_at>='2025-01-01' AND occurred_at<'2025-02-01'");storage.archiveNext();assertEquals("ARCHIVED",storage.run(admin,id).get("state"));
        assertEquals(before,db.count("SELECT count(*) FROM ops.audit_event WHERE occurred_at>='2025-01-01' AND occurred_at<'2025-02-01'"));
        Path first=Path.of(System.getenv("IMS_PLATFORM_AUDIT_ARCHIVE_PRIMARY"),id.toString(),"records.jsonl.gz"),second=Path.of(System.getenv("IMS_PLATFORM_AUDIT_ARCHIVE_SECONDARY"),id.toString(),"records.jsonl.gz");assertArrayEquals(Files.readAllBytes(first),Files.readAllBytes(second));
        var run=db.required("SELECT sha256,row_counts::text FROM ops.audit_archive_run WHERE id=?",id);Map<String,Long> counts=new LinkedHashMap<>();Crypto.JSON.readValue(run.get("row_counts").toString(),Map.class).forEach((key,value)->counts.put(key.toString(),((Number)value).longValue()));AuditStorage.verify(first,run.get("sha256").toString(),counts);
        String schema="audit_restore_"+UUID.randomUUID().toString().replace("-","");db.update("CREATE SCHEMA "+schema);for(String table:counts.keySet())db.update("CREATE TABLE "+schema+"."+table+" (LIKE ops."+table+" INCLUDING ALL)");
        try(var input=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(first)),StandardCharsets.UTF_8))){String line;while((line=input.readLine())!=null){var row=Crypto.JSON.readTree(line);String table=row.path("table").asString();assertTrue(counts.containsKey(table));db.update("INSERT INTO "+schema+"."+table+" SELECT * FROM json_populate_record(NULL::"+schema+"."+table+",?::json)",row.path("row").toString());}}
        for(String table:counts.keySet()){assertEquals(counts.get(table),db.count("SELECT count(*) FROM "+schema+"."+table));assertEquals(0L,db.count("SELECT count(*) FROM (SELECT * FROM ops."+table+" WHERE occurred_at>='2025-01-01' AND occurred_at<'2025-02-01' EXCEPT SELECT * FROM "+schema+"."+table+") differences"));}
        Path bad=Files.createTempFile(Path.of(System.getenv("IMS_PLATFORM_AUDIT_DATABASE_VOLUME")).getParent(),"corrupt-audit-",".gz");Files.copy(second,bad,StandardCopyOption.REPLACE_EXISTING);Files.write(bad,new byte[]{1},StandardOpenOption.APPEND);assertThrows(IOException.class,()->AuditStorage.verify(bad,run.get("sha256").toString(),counts));
        assertThrows(org.springframework.dao.DataAccessException.class,()->insert(UUID.randomUUID(),UUID.randomUUID(),Instant.parse("2025-01-18T00:00:00Z"),admin.userId(),"LATE","BUSINESS_EVENT",null));
    }
    @Test @Order(6) void failedReplicaKeepsOnlineAndCanRetry(){
        var queued=queue("2025-02-01",context());UUID id=(UUID)queued.get("id");long before=db.count("SELECT count(*) FROM ops.audit_event WHERE occurred_at>='2025-02-01' AND occurred_at<'2025-03-01'");
        Path blocker=Path.of(System.getenv("IMS_PLATFORM_AUDIT_ARCHIVE_SECONDARY"),id.toString());assertDoesNotThrow(()->Files.writeString(blocker,"隔离副本故障夹具",StandardOpenOption.CREATE_NEW));storage.archiveNext();
        assertEquals("FAILED",storage.run(admin,id).get("state"));assertEquals("SEALED",db.required("SELECT state FROM ops.audit_month WHERE month_start='2025-02-01'").get("state"));assertEquals(before,db.count("SELECT count(*) FROM ops.audit_event WHERE occurred_at>='2025-02-01' AND occurred_at<'2025-03-01'"));
        var retry=queue("2025-02-01",context());storage.archiveNext();assertEquals("ARCHIVED",storage.run(admin,(UUID)retry.get("id")).get("state"));
        status(400,()->queue(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).toString(),context()));
    }
    @Test @Order(7) void queuedArchiveRevocationFailsBeforeSealing(){
        Actor other=person(true);var ctx=new Commands.Context(other,UUID.randomUUID(),UUID.randomUUID(),"TEST",null,null);var queued=storage.queue(ctx,Sql.map("month","2025-03-01"));db.update("UPDATE iam.user_account SET auth_version=auth_version+1 WHERE id=?",other.userId());storage.archiveNext();
        assertEquals("FAILED",storage.run(admin,(UUID)queued.get("id")).get("state"));assertEquals("OPEN",db.required("SELECT state FROM ops.audit_month WHERE month_start='2025-03-01'").get("state"));
    }
    @Test @Order(8) void queryAuditEventsDoNotMoveOldPages(){
        for(int n=0;n<30;n++)new Audit(db).event(admin.userId(),"PLATFORM","CURSOR_SNAPSHOT",UUID.randomUUID(),"TEST","SUCCEEDED",UUID.randomUUID());
        var filter=filters("IGNORED");filter.remove("resourceType");filter.put("pageSize","10");
        var first=security.query(admin,filter,UUID.randomUUID());
        var next=security.query(admin,Map.of("cursor",first.get("nextCursor").toString()),UUID.randomUUID());assertNotNull(next.get("items"));
        var previous=security.query(admin,Map.of("cursor",first.get("cursor").toString()),UUID.randomUUID());assertEquals(rows(first),rows(previous));
        assertTrue(db.count("SELECT count(*) FROM ops.audit_event WHERE resource_id=? AND action='AUDIT_QUERY' AND ingested_at>?::timestamptz",first.get("queryId"),first.get("cutoff"))>=1);
    }
    @Test @Order(9) void globalReceiptLookupUsesOriginalMonth(){
        String key="global-receipt-"+UUID.randomUUID();UUID id=UUID.randomUUID();
        db.update("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,action,result,request_id,record_kind,operation_key,request_digest,operation_result) VALUES(?,?,'2025-06-18',?,'DRIVE','FILE','TEST','SUCCEEDED',?,'IDEMPOTENCY_RECEIPT',?,?,'{\"state\":\"SUCCEEDED\"}')",id,UUID.randomUUID(),admin.userId(),UUID.randomUUID(),key,Crypto.hash(key));
        var receipt=new Audit(db).receipt(admin.userId(),key);assertEquals(Crypto.hash(key),receipt.get("request_digest"));assertTrue(receipt.get("operation_result").toString().contains("SUCCEEDED"));assertNull(new Audit(db).receipt(UUID.randomUUID(),key));
    }
    @Test @Order(10) void pageMembershipSurvivesLateCommit()throws Exception{
        String type="LATE-COMMIT-"+UUID.randomUUID();
        for(int n=0;n<30;n++)new Audit(db).event(admin.userId(),"PLATFORM",type,UUID.randomUUID(),"TEST","SUCCEEDED",UUID.randomUUID());
        UUID late=UUID.randomUUID();
        try(var connection=source.getConnection()){
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("INSERT INTO ops.audit_event(id,event_id,occurred_at,actor_id,module,resource_type,action,result,request_id,record_kind) VALUES(?,?,CURRENT_TIMESTAMP,?,'PLATFORM',?,'LATE','SUCCEEDED',?,'BUSINESS_EVENT')")){
                statement.setObject(1,late);statement.setObject(2,UUID.randomUUID());statement.setObject(3,admin.userId());statement.setString(4,type);statement.setObject(5,UUID.randomUUID());statement.executeUpdate();
            }
            var first=security.query(admin,filters(type),UUID.randomUUID());connection.commit();
            var previous=security.query(admin,Map.of("cursor",first.get("cursor").toString()),UUID.randomUUID());
            assertEquals(rows(first),rows(previous));assertEquals(first.get("nextCursor"),previous.get("nextCursor"));assertFalse(rows(previous).stream().anyMatch(row->late.equals(row.get("id"))));
        }
    }
}
