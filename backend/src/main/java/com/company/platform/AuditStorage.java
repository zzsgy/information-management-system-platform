package com.company.platform;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** 审计分区、容量和双副本归档；归档完成也不删除或分离在线证据。 */
@Service
public class AuditStorage {
    private final Sql db;
    private final IdentityService identity;
    private final Audit audit;
    private final DataSource source;
    private final TransactionTemplate tx;
    private final boolean enabled;
    /** 字节预算及磁盘下限均来自部署配置，不接受网页提交本地路径或容量。 */
    private final long budget,minFree,maxArchive;
    private final int hotMonths;
    private final String volume,primary,secondary;
    private final Environment environment;
    /** 领取者固定于当前进程，超时任务转人工核对，不冒充旧进程继续写文件。 */
    private final UUID worker=UUID.randomUUID();
    private static final List<String> ARCHIVE_TABLES=List.of("audit_identity","interaction_identity","audit_event","audit_scope","ui_interaction");
    private static final String RUN_FIELDS="id,month_start,state,created_at,finished_at,sha256,byte_size,row_counts,error_code";

    public AuditStorage(Sql db,IdentityService identity,Audit audit,DataSource source,PlatformTransactionManager manager,Environment env){
        this.db=db;this.identity=identity;this.audit=audit;this.source=source;this.tx=new TransactionTemplate(manager);environment=env;
        enabled=env.getProperty("platform.audit-storage.enabled",Boolean.class,true);
        budget=env.getProperty("platform.audit-storage.budget-bytes",Long.class,1_000_000_000_000L);
        minFree=env.getProperty("platform.audit-storage.minimum-free-bytes",Long.class,5_000_000_000L);
        maxArchive=env.getProperty("platform.audit-storage.max-archive-bytes",Long.class,2_000_000_000L);
        hotMonths=env.getProperty("platform.audit-storage.hot-months",Integer.class,3);
        volume=env.getProperty("platform.audit-storage.database-volume","");primary=env.getProperty("platform.audit-storage.primary","");secondary=env.getProperty("platform.audit-storage.secondary","");
        if(budget<=0||minFree<0||maxArchive<=0||hotMonths<1||hotMonths>120)throw new IllegalStateException("日志容量或保留热区配置无效");
    }
    /** 存储管理是平台安全管理能力，普通日志查询人员不能封存或读取技术容量。 */
    private Actor administrator(Actor subject){Actor actor=identity.background(subject.sessionId());actor.require("PLATFORM.SECURITY_MANAGE");return actor;}
    public Map<String,Object> overview(Actor subject){
        administrator(subject);
        return Sql.map("enabled",enabled,"archiveConfigured",!primary.isBlank()&&!secondary.isBlank(),"budgetBytes",budget,"minimumFreeBytes",minFree,"hotMonths",hotMonths,
            "latest",sampleView(db.one("SELECT sampled_at,total_bytes,available_bytes,filesystem_total_bytes,estimated_rows,state,details::text FROM ops.audit_capacity_sample ORDER BY sampled_at DESC LIMIT 1")),
            "months",db.rows("SELECT month_start,state,sealed_at,archived_at FROM ops.audit_month WHERE month_start<=date_trunc('month',CURRENT_TIMESTAMP) ORDER BY month_start DESC LIMIT 120"),
            "runs",db.rows("SELECT "+RUN_FIELDS+" FROM ops.audit_archive_run ORDER BY created_at DESC,id DESC LIMIT 20").stream().map(row->{decodeCounts(row,"row_counts");return row;}).toList());
    }
    /** 手动采样仍只读取文件系统状态和系统目录，不扫描全部审计记录。 */
    public Map<String,Object> sample(Actor subject){administrator(subject);return sample();}
    /** 按月排队，复用Commands的再验证与幂等；已完成月份返回原结果，失败不会移除在线记录。 */
    public Map<String,Object> queue(Commands.Context ctx,Map<String,Object> body){
        Inputs.only(body,"month");LocalDate month;
        try{month=LocalDate.parse(Inputs.text(body,"month",10));}catch(java.time.format.DateTimeParseException e){throw ApiFault.invalid("月份须使用月初日期");}
        if(month.getDayOfMonth()!=1||!month.isBefore(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(hotMonths-1)))throw ApiFault.invalid("只能归档热区之外的完整月份");
        roots();db.required("SELECT month_start FROM ops.audit_month WHERE month_start=? FOR UPDATE",month);
        var prior=db.one("SELECT id FROM ops.audit_archive_run WHERE month_start=? AND state IN ('QUEUED','RUNNING','ARCHIVED')",month);
        if(prior!=null)return run(ctx.actor(),(UUID)prior.get("id"));
        UUID id=UUID.randomUUID();db.update("INSERT INTO ops.audit_archive_run(id,month_start,requester_id,auth_version) VALUES(?,?,?,?)",id,month,ctx.actor().userId(),ctx.actor().authVersion());return run(ctx.actor(),id);
    }
    public Map<String,Object> run(Actor subject,UUID id){administrator(subject);var row=Sql.view(db.required("SELECT * FROM ops.audit_archive_run WHERE id=?",id),RUN_FIELDS);decodeCounts(row,"rowCounts");return row;}
    /** 归档数量只输出表名和整数，不输出JDBC JSONB包装对象。 */
    private void decodeCounts(Map<String,Object> row,String field){if(row.get(field)!=null)row.put(field,Crypto.JSON.readValue(row.get(field).toString(),Map.class));}

    /** 每小时仅一个实例采样并提前建未来分区；事务锁避免多实例重复告警。 */
    @Scheduled(fixedDelayString="${platform.audit-storage.sample-delay-ms:3600000}",initialDelayString="${platform.audit-storage.initial-delay-ms:15000}")
    public void scheduledSample(){if(!enabled)return;try{tx.executeWithoutResult(status->{
        if(!Boolean.TRUE.equals(db.required("SELECT pg_try_advisory_xact_lock(61427044) AS acquired").get("acquired")))return;
        for(int n=0;n<=12;n++)db.rows("SELECT ops.ensure_audit_month((date_trunc('month',CURRENT_TIMESTAMP)+(?*interval '1 month'))::date)",n);
        if(db.count("SELECT count(*) FROM ops.audit_capacity_sample WHERE sampled_at>CURRENT_TIMESTAMP-interval '1 hour'")==0)sample();
        // 只清除技术采样，三年审计与档案证据不经过此诊断数据清理。
        db.update("DELETE FROM ops.audit_capacity_sample WHERE sampled_at<CURRENT_TIMESTAMP-interval '90 days'");
    });}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("日志容量采样未完成，类型 {}",e.getClass().getSimpleName());}}
    private Map<String,Object> sample(){return tx.execute(status->{
        db.rows("SELECT pg_advisory_xact_lock(61427044)");
        var sizes=db.rows("SELECT family,sum(pg_total_relation_size(relid))::bigint AS bytes,sum(greatest(c.reltuples,0))::bigint AS estimated_rows FROM (SELECT 'audit_event' AS family,relid FROM pg_partition_tree('ops.audit_event') WHERE isleaf UNION ALL SELECT 'audit_scope',relid FROM pg_partition_tree('ops.audit_scope') WHERE isleaf UNION ALL SELECT 'ui_interaction',relid FROM pg_partition_tree('ops.ui_interaction') WHERE isleaf UNION ALL SELECT 'global_identity','ops.audit_identity'::regclass UNION ALL SELECT 'global_identity','ops.interaction_identity'::regclass) t JOIN pg_class c ON c.oid=t.relid GROUP BY family ORDER BY family");
        long bytes=sizes.stream().mapToLong(r->((Number)r.get("bytes")).longValue()).sum();
        long estimated=sizes.stream().filter(r->Set.of("audit_event","ui_interaction").contains(r.get("family"))).mapToLong(r->((Number)r.get("estimated_rows")).longValue()).sum();
        long free=-1,total=-1;try{if(!volume.isBlank()){Path root=Path.of(volume);if(!root.isAbsolute())throw new IOException("absolute required");var store=Files.getFileStore(root);free=store.getUsableSpace();total=store.getTotalSpace();}}catch(IOException e){throw new IllegalStateException("数据库卷采样配置不可用");}
        String state=bytes>=budget*.85||(free>=0&&free<minFree)?"CRITICAL":bytes>=budget*.70||free<0?"WARNING":"NORMAL";
        var old=db.one("SELECT total_bytes,extract(epoch FROM (CURRENT_TIMESTAMP-sampled_at)) AS seconds FROM ops.audit_capacity_sample WHERE sampled_at<CURRENT_TIMESTAMP-interval '1 hour' AND sampled_at>CURRENT_TIMESTAMP-interval '7 days' ORDER BY sampled_at LIMIT 1");
        Long daily=old==null?null:Math.max(0L,Math.round((bytes-((Number)old.get("total_bytes")).longValue())*86400.0/((Number)old.get("seconds")).doubleValue()));
        Map<String,Object> details=Sql.map("families",sizes,"dailyGrowthBytes",daily,"databaseVolumeConfigured",free>=0,"forecastDays",daily==null||daily==0?null:Math.max(0L,(budget-bytes)/daily));
        UUID id=UUID.randomUUID();db.update("INSERT INTO ops.audit_capacity_sample(id,total_bytes,available_bytes,filesystem_total_bytes,estimated_rows,state,details) VALUES(?,?,?,?,?,?,?::jsonb)",id,bytes,free,total,estimated,state,Crypto.json(details));
        if(!state.equals("NORMAL"))capacityNotice();return sampleView(db.required("SELECT sampled_at,total_bytes,available_bytes,filesystem_total_bytes,estimated_rows,state,details::text FROM ops.audit_capacity_sample WHERE id=?",id));
    });}
    /** JSONB显式解码为公开技术指标，不把JDBC驱动包装对象交给网页。 */
    private Map<String,Object> sampleView(Map<String,Object> row){if(row!=null)row.put("details",Crypto.JSON.readValue(row.get("details").toString(),Map.class));return row;}
    /** 沿用正式告警和事务发件箱，技术通知不携带日志内容；每位有效IT一天最多一次。 */
    private void capacityNotice(){
        for(var user:db.rows("SELECT DISTINCT u.id FROM iam.user_account u JOIN iam.user_role r ON r.user_id=u.id WHERE u.status='ACTIVE' AND r.module='PLATFORM' AND r.role_code='IT_ADMIN' AND r.state='ACTIVE' AND r.valid_from<=CURRENT_TIMESTAMP AND (r.valid_until IS NULL OR r.valid_until>CURRENT_TIMESTAMP)")){
            UUID id=(UUID)user.get("id");if(db.count("SELECT count(*) FROM ops.security_alert WHERE user_id=? AND kind='AUDIT_CAPACITY' AND created_at>CURRENT_TIMESTAMP-interval '1 day'",id)>0)continue;
            UUID alert=UUID.randomUUID();db.update("INSERT INTO ops.security_alert(id,user_id,kind,observed_count,threshold_count) VALUES(?,?,'AUDIT_CAPACITY',1,1)",alert,id);
            db.update("INSERT INTO ops.account_message(id,user_id,kind,channel,expires_at) VALUES(?,?,'AUDIT_CAPACITY','DINGTALK',CURRENT_TIMESTAMP+interval '1 day')",alert,id);
        }
    }
    /** 单轮领取一整月流式任务；租约到期转人工核对，副本异常绝不标记归档成功。 */
    @Scheduled(fixedDelayString="${platform.audit-storage.worker-delay-ms:30000}")
    public void archiveNext(){if(!enabled)return;Map<String,Object> run=tx.execute(status->{
        db.update("UPDATE ops.audit_archive_run SET state='MANUAL',error_code='EXECUTION_RESULT_UNKNOWN',finished_at=CURRENT_TIMESTAMP WHERE state='RUNNING' AND lease_until<CURRENT_TIMESTAMP");
        var row=db.one("SELECT * FROM ops.audit_archive_run WHERE state='QUEUED' ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");if(row==null)return null;
        db.update("UPDATE ops.audit_archive_run SET state='RUNNING',lease_token=?,lease_until=CURRENT_TIMESTAMP+interval '30 minutes' WHERE id=?",worker,row.get("id"));return row;
    });if(run==null)return;
        try{
            if(!authorized(run))throw new IllegalStateException("REQUESTER_AUTHORIZATION_INVALID");
            LocalDate month=LocalDate.parse(run.get("month_start").toString());
            tx.executeWithoutResult(status->db.update("UPDATE ops.audit_month SET state='SEALED',sealed_at=COALESCE(sealed_at,CURRENT_TIMESTAMP) WHERE month_start=? AND state='OPEN'",month));
            Path[] roots=roots();UUID id=(UUID)run.get("id");Path first=PrivateFiles.directory(roots[0].resolve(id.toString()).toString()),second=PrivateFiles.directory(roots[1].resolve(id.toString()).toString());
            Path a=first.resolve("records.jsonl.gz"),b=second.resolve("records.jsonl.gz");Map<String,Long> counts=writeArchive(a,month);
            if(Files.size(a)>maxArchive)throw new IOException("ARCHIVE_SIZE_LIMIT");
            Files.copy(a,b);force(b);String digest=digest(a);verify(a,digest,counts);verify(b,digest,counts);
            long schemaVersion=db.count("SELECT max(version::integer) FROM platform_meta.flyway_schema_history WHERE success AND version IS NOT NULL");
            String manifest=Crypto.json(Sql.map("format",1,"schemaVersion",schemaVersion,"id",id,"month",month.toString(),"sha256",digest,"bytes",Files.size(a),"rowCounts",counts));
            for(Path root:List.of(first,second)){Path file=root.resolve("manifest.json");Files.writeString(file,manifest,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);force(file);}
            long size=Files.size(a);tx.executeWithoutResult(status->{
                if(!authorized(run))throw new IllegalStateException("REQUESTER_AUTHORIZATION_INVALID");
                if(db.update("UPDATE ops.audit_archive_run SET state='ARCHIVED',finished_at=CURRENT_TIMESTAMP,sha256=?,byte_size=?,row_counts=?::jsonb WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP",digest,size,Crypto.json(counts),id,worker)!=1)throw new IllegalStateException("ARCHIVE_LEASE_EXPIRED");
                db.update("UPDATE ops.audit_month SET state='ARCHIVED',archived_at=CURRENT_TIMESTAMP WHERE month_start=? AND state='SEALED'",month);
                audit.event((UUID)run.get("requester_id"),"PLATFORM","AUDIT_ARCHIVE",id,"ARCHIVE_VERIFIED","SUCCEEDED",UUID.randomUUID());
            });
        }catch(Exception e){tx.executeWithoutResult(status->{db.update("UPDATE ops.audit_archive_run SET state='FAILED',finished_at=CURRENT_TIMESTAMP,error_code=? WHERE id=? AND state='RUNNING' AND lease_token=?",e instanceof IllegalStateException&&"REQUESTER_AUTHORIZATION_INVALID".equals(e.getMessage())?"REQUESTER_AUTHORIZATION_INVALID":"ARCHIVE_VERIFICATION_FAILED",run.get("id"),worker);audit.event((UUID)run.get("requester_id"),"PLATFORM","AUDIT_ARCHIVE",(UUID)run.get("id"),"ARCHIVE_VERIFIED","FAILED",UUID.randomUUID());});}
    }
    private boolean authorized(Map<String,Object> run){return db.count("SELECT count(*) FROM iam.user_account u JOIN iam.user_role r ON r.user_id=u.id WHERE u.id=? AND u.status='ACTIVE' AND u.auth_version=? AND r.module='PLATFORM' AND r.role_code='IT_ADMIN' AND r.state='ACTIVE' AND r.valid_from<=CURRENT_TIMESTAMP AND (r.valid_until IS NULL OR r.valid_until>CURRENT_TIMESTAMP)",run.get("requester_id"),run.get("auth_version"))>0;}
    /** 配置入口一次性校验隔离关系，不让归档覆盖原件、账本、临时目录或另一份归档。 */
    private Path[] roots(){
        if(primary.isBlank()||secondary.isBlank())throw ApiFault.conflict("请先配置两个独立的日志归档目录");
        Path a=Path.of(primary).normalize().toAbsolutePath(),b=Path.of(secondary).normalize().toAbsolutePath();
        if(!Path.of(primary).isAbsolute()||!Path.of(secondary).isAbsolute()||a.startsWith(b)||b.startsWith(a))throw ApiFault.conflict("日志归档目录必须相互独立");
        for(String property:List.of("platform.storage.primary","platform.storage.secondary","platform.security-ledger","platform.mailbox","platform.reading.temporary","platform.reading.audit-root","platform.office.temporary","platform.export.primary","platform.export.secondary","platform.export.temporary")){
            String configured=environment.getProperty(property,"");if(configured.isBlank())continue;Path other=Path.of(configured).normalize().toAbsolutePath();
            if(a.startsWith(other)||other.startsWith(a)||b.startsWith(other)||other.startsWith(b))throw ApiFault.conflict("日志归档不能与业务持久目录相互包含");
        }
        for(Path root:List.of(a,b))try{PrivateFiles.directory(root.toString());if(Files.getFileStore(root).getUsableSpace()<minFree+maxArchive)throw new IOException("space required");}catch(IOException e){throw ApiFault.conflict("日志归档卷空间不足");}
        return new Path[]{a,b};
    }
    /** JDBC游标每批500行，压缩文件大小有上限；不会把整月JSON装入Java集合。 */
    private Map<String,Long> writeArchive(Path file,LocalDate month)throws Exception{
        Map<String,Long> counts=new LinkedHashMap<>();
        try(Connection connection=source.getConnection()){
            connection.setAutoCommit(false);try(var setup=connection.createStatement()){setup.execute("SET LOCAL statement_timeout='300s'");}
            try(var output=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)),StandardCharsets.UTF_8))){
                for(String table:ARCHIVE_TABLES){long count=0;try(var statement=connection.prepareStatement("SELECT row_to_json(t)::text FROM ops."+table+" t WHERE occurred_at>=? AND occurred_at<? ORDER BY "+(table.equals("audit_scope")?"event_id":"id"))){
                    statement.setFetchSize(500);statement.setQueryTimeout(300);statement.setTimestamp(1,Timestamp.from(month.atStartOfDay().toInstant(ZoneOffset.UTC)));statement.setTimestamp(2,Timestamp.from(month.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
                    try(var result=statement.executeQuery()){while(result.next()){output.write("{\"table\":\""+table+"\",\"row\":"+result.getString(1)+"}\n");count++;if(count%500==0){output.flush();if(Files.size(file)>maxArchive)throw new IOException("ARCHIVE_SIZE_LIMIT");}}}
                }counts.put(table,count);}
            }connection.commit();
        }force(file);return counts;
    }
    /** 摘要、GZIP完整性和各表逐行数量都要一致；文件仍留给后续独立恢复演练。 */
    public static void verify(Path file,String expected,Map<String,Long> counts)throws Exception{
        if(!digest(file).equals(expected))throw new IOException("ARCHIVE_HASH_MISMATCH");Map<String,Long> actual=new LinkedHashMap<>();for(String table:ARCHIVE_TABLES)actual.put(table,0L);
        try(var input=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),StandardCharsets.UTF_8))){String line;while((line=input.readLine())!=null){var row=Crypto.JSON.readTree(line);String table=row.path("table").asString();if(!actual.containsKey(table)||!row.path("row").isObject())throw new IOException("ARCHIVE_FORMAT_INVALID");actual.merge(table,1L,Long::sum);}}
        if(!actual.equals(counts))throw new IOException("ARCHIVE_COUNTS_MISMATCH");
    }
    private static String digest(Path file)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(var input=new DigestInputStream(Files.newInputStream(file),hash)){input.transferTo(OutputStream.nullOutputStream());}return HexFormat.of().formatHex(hash.digest());}
    private static void force(Path file)throws IOException{try(var channel=FileChannel.open(file,StandardOpenOption.WRITE)){channel.force(true);}}
}
