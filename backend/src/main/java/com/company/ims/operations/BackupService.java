package com.company.ims.operations;

import com.company.ims.archive.LocalAttachmentStorage;
import com.company.ims.identity.Actor;
import com.company.ims.shared.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** 数据库和附件整体备份，受单实例维护门禁保护；不提供危险的在线覆盖恢复。 */
@Service
public class BackupService {
    /** 工具诊断仅写入服务端日志，不向客户端暴露绝对路径或数据库配置。 */
    private static final Logger LOG=LoggerFactory.getLogger(BackupService.class);
    /** 精确控制本服务可读写的备份根目录。 */
    private final Path root;
    /** 数据库读取与操作审计入口。 */
    private final Db db;
    /** 附件存储适配器，提供安全路径解析。 */
    private final LocalAttachmentStorage storage;
    /** 单实例所有HTTP请求共享的维护门禁。 */
    private final MaintenanceGate gate;
    /** 每个运维动作都记录真实结果。 */
    private final AuditService audit;
    /** 外置数据库地址，备份时再解析，避免测试环境被强制限定PostgreSQL。 */
    private final String jdbcUrl;
    /** 外置数据库账号，不作为Web请求参数接受。 */
    private final String username;
    /** 仅通过子进程环境传递，不进入清单或命令行。 */
    private final String password;
    /** PostgreSQL工具目录，可为空表示PATH。 */
    private final String pgBin;
    /** 完整备份作业的超时预算，秒。 */
    private final int timeout;

    /** 注入存储与外置配置；备份目录与附件目录不能重叠，以免递归打包备份自身。 */
    public BackupService(Db db,LocalAttachmentStorage storage,MaintenanceGate gate,AuditService audit,
            @Value("${ims.backup-root}") String directory,@Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,@Value("${spring.datasource.password:}") String password,
            @Value("${ims.pg-bin:}") String pgBin,@Value("${ims.backup-timeout-seconds:240}") int timeout) throws IOException {
        this.db=db; this.storage=storage; this.gate=gate; this.audit=audit;
        this.jdbcUrl=jdbcUrl; this.username=username; this.password=password; this.pgBin=pgBin;
        if(timeout<30 || timeout>3600) throw new IllegalArgumentException("备份超时必须在30至3600秒之间");
        this.timeout=timeout;
        root=Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(root);
        if(Files.isSymbolicLink(root) || Files.isSymbolicLink(storage.root())) throw new IOException("运维目录不能是符号链接");
        if(root.toRealPath().startsWith(storage.root().toRealPath()) || storage.root().toRealPath().startsWith(root.toRealPath())) throw new IOException("备份目录和附件目录不能相互包含");
    }

    /** 在独占维护窗口执行完整性检查，不把缺失文件或读取异常算作成功。 */
    public Map<String,Object> integrity(Actor actor) {
        try(MaintenanceGate.Lease ignored=gate.exclusive()) {
            Map<String,Object> result=checkFiles(deadline()); // 所有入库版本均应存在，不只检查当前版本。
            audit.record(actor.id(),"security","integrity_check","storage",null,"正常="+result.get("ok")+";异常="+result.get("failed"));
            return result;
        }
    }

    /** 只在pg_dump、清单、哈希和归档格式全部验证成功后发布备份文件。 */
    public Map<String,Object> create(Actor actor) {
        Path work=null; // 当前作业私有暂存目录，失败时只清理此路径。
        try(MaintenanceGate.Lease ignored=gate.exclusive()) {
            long deadline=deadline(); // 整个备份任务预算，而非每个工具各自获得完整预算。
            OfflineRestore.Connection connection=OfflineRestore.connection(jdbcUrl,username,password); // 配置解析不接受客户端指定数据库。
            Map<String,Object> integrity=checkFiles(deadline); // 备份前确认数据库中的附件引用均有有效实体。
            if(((Number)integrity.get("failed")).longValue()!=0) throw new BusinessException(409,"附件完整性校验失败，未创建可恢复备份；请先处理异常文件");
            work=Files.createTempDirectory(root,".pending-");
            Path dump=work.resolve("database.dump"); // PostgreSQL自定义格式导出。
            Path processLog=work.resolve("pg_dump.log"); // 工具诊断仅留在服务端，不把配置回显给客户端。
            List<String> command=new ArrayList<>(List.of(OfflineRestore.executable(pgBin,"pg_dump"))); // 禁止shell拼接。
            command.addAll(connection.arguments()); command.addAll(List.of("--format=custom","--file",dump.toString(),"--dbname",connection.database()));
            OfflineRestore.run(command,connection.environment(),processLog,remaining(deadline));
            if(!Files.isRegularFile(dump) || Files.size(dump)==0) throw new IOException("数据库导出文件为空");
            OfflineRestore.run(List.of(OfflineRestore.executable(pgBin,"pg_restore"),"--list",dump.toString()),Map.of(),work.resolve("pg_restore-list.log"),remaining(deadline));
            String filename="ims_"+Instant.now().toString().replace(":","").replace(".","")+"_"+UUID.randomUUID()+".imsbackup.zip"; // 服务端生成文件名。
            Path pending=work.resolve("backup.zip"); // 只有校验通过后才能移动到备份列表。
            List<OfflineRestore.Entry> entries=new ArrayList<>(); // 实际压缩文件内容的校验清单。
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(pending,StandardOpenOption.CREATE_NEW),StandardCharsets.UTF_8)) {
                entries.add(addFile(zip,dump,"database.dump",deadline));
                try(var paths=Files.walk(storage.root())) {
                    for(Path path:paths.sorted().toList()) {
                        if(Files.isSymbolicLink(path)) throw new IOException("附件目录含符号链接，拒绝备份");
                        if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) continue;
                        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("附件目录含非普通文件");
                        if(entries.size()>=100000) throw new IOException("备份文件数量超过当前清单上限");
                        String relative="attachments/"+storage.root().relativize(path).toString().replace('\\','/'); // 包内路径不包含主机绝对路径。
                        entries.add(addFile(zip,path,relative,deadline));
                    }
                }
                verifyReferences(entries);
                OfflineRestore.Manifest manifest=new OfflineRestore.Manifest(Instant.now().toString(),db.scalarLong("select count(*) from archive"),db.scalarLong("select count(*) from archive_file"),List.copyOf(entries)); // 数据库和附件处于同一维护窗口。
                zip.putNextEntry(new ZipEntry("manifest.properties")); zip.write(OfflineRestore.manifestBytes(manifest)); zip.closeEntry();
            }
            remaining(deadline);
            OfflineRestore.inspect(pending,true,deadline); // 压缩完成后再读一遍，确保可解压且逐文件校验通过。
            remaining(deadline);
            Path published=safeBackup(filename); // 发布位置只能是配置根目录的直接子文件。
            try { Files.move(pending,published,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException unsupported) { Files.move(pending,published); }
            audit.record(actor.id(),"security","backup","backup",null,"file="+filename+";files="+entries.size());
            return Map.of("file",filename,"size",Files.size(published),"includes_attachments",true,"verified",true);
        } catch(BusinessException failure) { throw failure; }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new BusinessException(503,"备份任务已中断，未确认完成"); }
        catch(IOException failure) { LOG.error("整体备份任务失败：{}",failure.getMessage()); preserveDiagnostics(work); throw new BusinessException(500,"备份未完成："+safeMessage(failure)); }
        finally { if(work!=null) cleanupWork(work); }
    }

    /** 仅列出格式正确且已发布的完整备份，不展示临时导出或任意本地文件。 */
    public List<Map<String,Object>> list() {
        List<Map<String,Object>> result=new ArrayList<>(); // 已完成备份的列表。
        try(var paths=Files.list(root)) {
            for(Path path:paths.filter(p -> p.getFileName().toString().endsWith(".imsbackup.zip")).sorted(Comparator.reverseOrder()).toList()) {
                try {
                    safeBackup(path.getFileName().toString());
                    OfflineRestore.Manifest manifest=OfflineRestore.inspect(path,false); // 列表做结构校验，恢复前再逐文件核验。
                    result.add(Map.of("name",path.getFileName().toString(),"size",Files.size(path),"mtime",manifest.createdAt(),"includes_attachments",true));
                } catch(IOException invalid) { /* 损坏或非法包不混入可恢复列表，可在服务器磁盘检查。 */ }
            }
            return result;
        } catch(IOException failure) { throw new BusinessException(500,"无法读取备份目录"); }
    }

    /** 校验选中的完整包后明确拒绝在线覆盖，客户端不能把此结果展示为恢复成功。 */
    public void requestRestore(Map<String,Object> body,Actor actor) {
        if(!Boolean.TRUE.equals(body.get("confirm"))) throw new BusinessException(400,"恢复校验需要confirm=true");
        if(!(body.get("file") instanceof String filename)) throw new BusinessException(400,"请选择备份文件");
        try {
            OfflineRestore.inspect(safeBackup(filename),true,deadline());
            audit.record(actor.id(),"security","restore_requested","backup",null,"file="+filename+";offline_required");
        } catch(IOException failure) { throw new BusinessException(400,"备份包校验失败："+safeMessage(failure)); }
        throw new BusinessException(409,"offline_required：备份包校验通过。请使用OfflineRestore.java恢复到新空数据库和新附件目录，验收后切换配置；当前数据库及附件未修改。");
    }

    /** 删除明确选中的已发布备份，只删除单个普通文件，不接受路径或递归目录。 */
    public void delete(String filename,boolean confirm,Actor actor) {
        if(!confirm) throw new BusinessException(400,"删除备份需要confirm=true");
        try {
            Path path=safeBackup(filename); // 根目录直属文件，禁止符号链接和路径穿越。
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new BusinessException(404,"备份不存在");
            Files.delete(path);
            audit.record(actor.id(),"security","delete_backup","backup",null,"file="+filename);
        } catch(IOException failure) { throw new BusinessException(500,"删除备份失败"); }
    }

    /** 核对全部附件版本的实体、长度及SHA256，逐项汇总错误不暴露主机路径。 */
    private Map<String,Object> checkFiles(long deadline) {
        List<Map<String,Object>> rows=db.list("select id,file_name,storage_key,file_size,sha256 from archive_file order by id"); // 数据库记录的所有历史版本。
        List<Map<String,Object>> failures=new ArrayList<>(); // 给客户端的具体文件异常。
        for(Map<String,Object> row:rows) {
            remaining(deadline);
            String reason=null; // 当前附件错误，空表示通过。
            String actual=null; // 实际哈希，只对可读取文件计算。
            try {
                Path file=storage.resolve((String)row.get("storage_key")); // 复用安全存储路径检查。
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) reason="文件实体缺失或不是普通文件";
                else {
                    actual=OfflineRestore.sha256(file,deadline);
                    if(!actual.equals(row.get("sha256")) || Files.size(file)!=((Number)row.get("file_size")).longValue()) reason="文件长度或SHA256不匹配";
                }
            } catch(IOException | RuntimeException failure) { reason="附件读取失败或存储路径无效"; }
            if(reason!=null) {
                Map<String,Object> entry=new LinkedHashMap<>(); // 保留旧客户端完整性校验列。
                entry.put("file_id",row.get("id")); entry.put("file_name",row.get("file_name")); entry.put("expected",row.get("sha256")); entry.put("actual",actual); entry.put("reason",reason); failures.add(entry);
            }
        }
        return Map.of("total",rows.size(),"ok",rows.size()-failures.size(),"failed",failures.size(),"items",failures);
    }

    /** 添加一个普通文件并记录实际压缩输入的长度及哈希。 */
    private OfflineRestore.Entry addFile(ZipOutputStream zip,Path path,String name,long deadline) throws IOException {
        OfflineRestore.safeEntry(name);
        MessageDigest digest; // 当前文件哈希，写入清单前由实际读取内容生成。
        try { digest=MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        long size=0; // 实际写入压缩流的字节数。
        zip.putNextEntry(new ZipEntry(name));
        try(InputStream input=Files.newInputStream(path)) {
            byte[] buffer=new byte[65536]; // 大文件分块写入。
            int length; // 本次读取长度。
            while((length=input.read(buffer))!=-1) { remaining(deadline); zip.write(buffer,0,length); digest.update(buffer,0,length); size+=length; }
        }
        zip.closeEntry();
        return new OfflineRestore.Entry(name,size,HexFormat.of().formatHex(digest.digest()));
    }

    /** 将实际写入ZIP的内容与库内附件元数据再次交叉核对，避免检查后文件被外部工具改写仍发布备份。 */
    private void verifyReferences(List<OfflineRestore.Entry> entries) throws IOException {
        Map<String,OfflineRestore.Entry> files=new HashMap<>(); // 实际写入压缩包的附件版本。
        for(OfflineRestore.Entry entry:entries) if(entry.path().startsWith("attachments/")) files.put(entry.path().substring("attachments/".length()),entry);
        for(Map<String,Object> row:db.list("select storage_key,file_size,sha256 from archive_file")) {
            OfflineRestore.Entry actual=files.get(row.get("storage_key")); // 以数据库稳定存储键关联实体。
            if(actual==null || actual.size()!=((Number)row.get("file_size")).longValue() || !actual.sha256().equals(row.get("sha256"))) throw new IOException("备份内容与数据库附件元数据不一致");
        }
    }

    /** 用户只能指定由系统生成的包文件名；绝不接收绝对路径、目录或符号链接。 */
    Path safeBackup(String filename) throws IOException {
        if(filename==null || !filename.matches("ims_[A-Za-z0-9_-]+\\.imsbackup\\.zip")) throw new IOException("备份文件名不合法");
        Path path=root.resolve(filename).normalize(); // 已校验文件名不含路径分隔符。
        if(!path.getParent().equals(root) || Files.isSymbolicLink(path)) throw new IOException("备份文件路径不合法");
        return path;
    }

    /** 创建基于单调时钟的任务截止点，避免系统时间校准影响超时。 */
    private long deadline() { return System.nanoTime()+timeout*1_000_000_000L; }
    /** 返回剩余秒数；任务超时或线程中断时立即停止后续步骤。 */
    private int remaining(long deadline) {
        long left=deadline-System.nanoTime(); // 单调时钟剩余预算。
        if(left<=0 || Thread.currentThread().isInterrupted()) throw new BusinessException(503,"运维任务超时或已中断，未确认完成");
        return Math.max(1,(int)(left/1_000_000_000L));
    }

    /** 只清理本服务刚创建的.pending目录，验证边界后逐文件删除且不跟随符号链接。 */
    private void cleanupWork(Path directory) {
        Path normalized=directory.toAbsolutePath().normalize(); // 最终清理边界。
        if(!normalized.getParent().equals(root) || !normalized.getFileName().toString().startsWith(".pending-")) return;
        try(var paths=Files.walk(normalized)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch(IOException ignored) { /* 残留临时目录不列为可恢复备份，运维人员可确认后清理。 */ }
    }

    /** 失败工具的有限长度日志复制到备份目录，清理暂存文件后仍可排查退出码原因。 */
    private void preserveDiagnostics(Path directory) {
        if(directory==null || !directory.toAbsolutePath().normalize().getParent().equals(root)) return;
        Path diagnostic=root.resolve("failed-"+UUID.randomUUID()+".log"); // 随机日志文件不被列为可恢复备份。
        try(OutputStream output=Files.newOutputStream(diagnostic,StandardOpenOption.CREATE_NEW)) {
            for(String filename:List.of("pg_dump.log","pg_restore-list.log")) {
                Path source=directory.resolve(filename); // 固定工具日志名，禁止任意遍历。
                if(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)) try(InputStream input=Files.newInputStream(source)) {
                    output.write((filename+System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                    output.write(input.readNBytes(1024*1024));
                }
            }
        } catch(IOException ignored) { LOG.warn("无法保留本次备份工具诊断日志"); }
    }

    /** 仅公开稳定中文错误，避免底层异常包含服务器路径、连接或工具输出。 */
    private String safeMessage(IOException failure) {
        String message=failure.getMessage(); // 工具封装的业务错误可安全展示。
        if(message!=null && (message.startsWith("备份") || message.startsWith("PostgreSQL") || message.startsWith("数据库导出") || message.startsWith("附件目录"))) return message;
        return "文件访问或数据库工具失败，请检查服务端配置和日志";
    }
}
