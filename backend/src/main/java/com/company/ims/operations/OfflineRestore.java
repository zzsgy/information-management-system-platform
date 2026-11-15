package com.company.ims.operations;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/**
 * 依赖JDK21即可运行的隔离恢复工具；仅恢复到新空库和全新附件目录，绝不覆盖原库或原附件。
 * 使用java --source 21 OfflineRestore.java运行；参数仅为备份文件绝对路径。
 * 目标连接由IMS_RESTORE_DB_URL、IMS_RESTORE_DB_USER、IMS_RESTORE_DB_PASSWORD提供，
 * 全新附件目录由IMS_RESTORE_STORAGE_ROOT提供，工具目录由IMS_PG_BIN提供。
 */
public final class OfflineRestore {
    /** 备份格式版本，未来格式变化必须明确升级。 */
    static final String FORMAT="ims-backup-v1";
    /** 清单最大32MiB，防止损坏压缩包耗尽内存。 */
    private static final int MANIFEST_LIMIT=32*1024*1024;

    /** 工具类禁止实例化。 */
    private OfflineRestore() { }

    /** 清单中的文件路径、大小与校验值；路径始终为包内相对路径。 */
    record Entry(String path,long size,String sha256) { }
    /** 已验证的备份清单及数据库对象数量。 */
    record Manifest(String createdAt,long archives,long files,List<Entry> entries) { }
    /** 从JDBC URL解析的PostgreSQL连接，不在命令行中传递密码。 */
    record Connection(String host,int port,String database,String user,String password,Map<String,String> options) {
        /** 返回不含凭据的连接位置，便于生成独立命令参数。 */
        List<String> arguments() { return List.of("--host",host,"--port",Integer.toString(port),"--username",user,"--no-password"); }
        /** 将密码和SSL配置仅写入子进程环境。 */
        Map<String,String> environment() {
            Map<String,String> result=new HashMap<>(options); // 只使用已识别的PostgreSQL环境项。
            result.put("PGPASSWORD",password); result.put("PGCONNECT_TIMEOUT","10"); return result;
        }
    }

    /** 校验环境并执行隔离恢复；任何失败均以非零退出码结束，不宣称回滚或恢复成功。 */
    public static void main(String[] args) {
        try {
            if(args.length!=1) throw new IOException("用法：java --source 21 OfflineRestore.java <备份包绝对路径>");
            Path backup=Path.of(args[0]).toAbsolutePath().normalize(); // 用户明确指定的本地备份包，只读。
            Connection target=connection(requiredEnvironment("IMS_RESTORE_DB_URL"),requiredEnvironment("IMS_RESTORE_DB_USER"),System.getenv().getOrDefault("IMS_RESTORE_DB_PASSWORD","")); // 仅恢复用目标连接。
            Path attachments=Path.of(requiredEnvironment("IMS_RESTORE_STORAGE_ROOT")).toAbsolutePath().normalize(); // 必须不存在的新目录。
            String pgBin=System.getenv().getOrDefault("IMS_PG_BIN",""); // PostgreSQL工具位置。
            restore(backup,target,attachments,pgBin);
            System.out.println("隔离恢复完成，目标数据库和新附件目录已验证。原库、原附件和当前服务配置未改变；请先验收，再人工切换配置。");
        } catch(Exception failure) {
            System.err.println("恢复未完成："+failure.getMessage());
            System.err.println("原库和原附件未修改。新目标库或临时目录可能已有恢复内容，请检查后再重试，不能视为成功。");
            System.exit(1);
        }
    }

    /** 将有效备份导入空目标库，核验行数后发布新附件目录；不清空任何既存目标。 */
    static void restore(Path backup,Connection target,Path attachments,String pgBin) throws Exception {
        Manifest manifest=inspect(backup,true); // 在写任何目标前完整核验压缩包。
        if(Files.exists(attachments,LinkOption.NOFOLLOW_LINKS)) throw new IOException("新附件目录已存在，请指定尚不存在的独立目录");
        Path parent=attachments.getParent(); // 新附件目录的现有父目录。
        if(parent==null || !Files.isDirectory(parent) || Files.isSymbolicLink(parent)) throw new IOException("新附件目录的父目录不存在或为符号链接");
        Path staging=Files.createTempDirectory(parent,".ims-restore-"); // 只写新临时目录，失败保留便于核对。
        Path log=staging.resolve("restore-process.log"); // 工具输出保留在新目录，不回传Web接口。
        String tableCount=psql(target,pgBin,"select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema')",log,30); // 防止覆盖有数据的数据库。
        if(!"0".equals(tableCount.trim())) throw new IOException("目标数据库不是空库，拒绝恢复");
        String otherConnections=psql(target,pgBin,"select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid()",log,30); // 已有应用连接表示目标可能正在使用。
        if(!"0".equals(otherConnections.trim())) throw new IOException("目标数据库仍有其他连接，请确认是未投入使用的新库");
        extract(backup,manifest,staging);
        List<String> command=new ArrayList<>(List.of(executable(pgBin,"pg_restore"))); // 参数数组不经过shell解析。
        command.addAll(target.arguments()); command.addAll(List.of("--dbname",target.database(),"--single-transaction","--exit-on-error","--no-owner","--no-acl",staging.resolve("database.dump").toString()));
        run(command,target.environment(),log,900);
        String restored=psql(target,pgBin,"select (select count(*) from archive)::text || ':' || (select count(*) from archive_file)::text",log,30); // 关键对象数必须与备份清单一致。
        if(!restored.trim().equals(manifest.archives()+":"+manifest.files())) throw new IOException("目标数据库对象数量与备份清单不一致");
        verifyRestoredReferences(target,pgBin,manifest,staging);
        psql(target,pgBin,"update ims_session set revoked_at=CURRENT_TIMESTAMP where revoked_at is null; insert into operation_log(module,action,target_type,detail_json) values ('security','offline_restore','backup','isolated restore; all old sessions revoked')",log,30);
        Path stagedAttachments=staging.resolve("attachments"); // 已逐文件校验的新附件树。
        Files.createDirectories(stagedAttachments);
        try { Files.move(stagedAttachments,attachments,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException unavailable) { Files.move(stagedAttachments,attachments); }
        System.out.println("已恢复档案记录 "+manifest.archives()+" 条，附件元数据 "+manifest.files()+" 条。");
        System.out.println("恢复过程记录与数据库dump保留于："+staging);
    }

    /** 逐条核对目标数据库的附件引用、长度和哈希与实际提取清单，防止只有文件数相同却内容错配。 */
    private static void verifyRestoredReferences(Connection target,String pgBin,Manifest manifest,Path staging) throws IOException,InterruptedException {
        Map<String,Entry> files=new HashMap<>(); // 包内附件按存储键索引，数据库dump不参与附件映射。
        for(Entry entry:manifest.entries()) if(entry.path().startsWith("attachments/")) files.put(entry.path().substring("attachments/".length()),entry);
        String sql="select encode(convert_to(storage_key,'UTF8'),'hex') || ':' || sha256 || ':' || file_size::text from archive_file order by id"; // 十六进制键避免换行或分隔符歧义。
        Path metadata=staging.resolve("restored-attachment-references.txt"); // 新目标库的附件引用核验材料。
        List<String> command=new ArrayList<>(List.of(executable(pgBin,"psql"))); // 仍使用参数数组，SQL仅来自程序常量。
        command.addAll(target.arguments()); command.addAll(List.of("--dbname",target.database(),"--no-psqlrc","-v","ON_ERROR_STOP=1","-At","-c",sql));
        run(command,target.environment(),metadata,60);
        long count=0; // 实际核对条数必须等于备份元数据数。
        Set<String> seen=new HashSet<>(); // 重复引用也作为异常拒绝。
        try(BufferedReader reader=Files.newBufferedReader(metadata,StandardCharsets.UTF_8)) {
            String line; // 每行是一个稳定格式的数据库附件引用。
            while((line=reader.readLine())!=null) {
                String[] parts=line.split(":",3); // 固定三个字段，任何额外工具输出均不能当成成功。
                if(parts.length!=3) throw new IOException("目标库附件引用校验输出不合法");
                try {
                    String key=new String(HexFormat.of().parseHex(parts[0]),StandardCharsets.UTF_8); // 还原数据库存储键。
                    Entry actual=files.get(key); // 已通过实体内容SHA256验证的清单条目。
                    if(actual==null || !seen.add(key) || actual.size()!=Long.parseLong(parts[2]) || !actual.sha256().equals(parts[1])) throw new IOException("目标库附件引用与备份实体不一致");
                } catch(IllegalArgumentException malformed) { throw new IOException("目标库附件引用校验格式不正确",malformed); }
                if(++count>manifest.files()) throw new IOException("目标库附件引用数量超过备份清单");
            }
        }
        if(count!=manifest.files()) throw new IOException("目标库附件引用数量少于备份清单");
    }

    /** 将已核验包提取到全新目录；逐条检查长度和哈希，拒绝穿越、绝对路径和重复文件。 */
    static void extract(Path backup,Manifest manifest,Path target) throws IOException {
        Path root=target.toAbsolutePath().normalize(); // 提取边界。
        try(ZipFile zip=new ZipFile(backup.toFile(),StandardCharsets.UTF_8)) {
            for(Entry entry:manifest.entries()) {
                Path output=root.resolve(entry.path()).normalize(); // 清单只允许安全相对路径。
                if(!output.startsWith(root) || output.equals(root)) throw new IOException("备份文件路径超出恢复目录");
                Files.createDirectories(output.getParent());
                if(Files.exists(output,LinkOption.NOFOLLOW_LINKS)) throw new IOException("恢复目录出现重复文件");
                try(InputStream input=zip.getInputStream(zip.getEntry(entry.path()));OutputStream destination=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)) {
                    copyAndVerify(input,destination,entry);
                }
            }
        }
    }

    /** 验证清单、压缩包成员和可选逐文件哈希，不信任ZIP声明的文件大小。 */
    static Manifest inspect(Path backup,boolean verifyContent) throws IOException {
        return inspect(backup,verifyContent,Long.MAX_VALUE);
    }

    /** Web运维使用的限时校验版本，截止时间为System.nanoTime标尺。 */
    static Manifest inspect(Path backup,boolean verifyContent,long deadline) throws IOException {
        if(!Files.isRegularFile(backup,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(backup)) throw new IOException("备份文件不存在或不是普通文件");
        try(ZipFile zip=new ZipFile(backup.toFile(),StandardCharsets.UTF_8)) {
            ZipEntry manifestEntry=zip.getEntry("manifest.properties"); // 唯一可信格式入口。
            if(manifestEntry==null) throw new IOException("备份缺少校验清单");
            Properties properties=new Properties(); // 使用标准Properties，使独立恢复无需第三方JSON库。
            try(InputStream input=zip.getInputStream(manifestEntry)) {
                byte[] bytes=input.readNBytes(MANIFEST_LIMIT+1); // 限定清单最大内存占用。
                if(bytes.length>MANIFEST_LIMIT) throw new IOException("备份清单过大");
                properties.load(new ByteArrayInputStream(bytes));
            }
            if(!FORMAT.equals(properties.getProperty("format"))) throw new IOException("不支持的备份格式版本");
            int count=(int)positive(properties,"entry.count",1,100000); // 文件数量上限同时限制验证工作量。
            List<Entry> entries=new ArrayList<>(); // 按清单顺序保留文件信息。
            Set<String> names=new HashSet<>(); // 检测重复路径，禁止ZIP覆盖攻击。
            for(int index=0;index<count;index++) {
                String prefix="entry."+index+"."; // 当前文件属性前缀。
                String name=properties.getProperty(prefix+"path",""); // 包内相对路径。
                safeEntry(name);
                if(!names.add(name)) throw new IOException("备份清单含重复文件");
                long size=positive(properties,prefix+"size",0,1L<<40); // 单文件最多1TiB，拒绝异常声明。
                String hash=properties.getProperty(prefix+"sha256",""); // 十六进制SHA256。
                if(!hash.matches("[0-9a-f]{64}")) throw new IOException("备份清单哈希格式不正确");
                ZipEntry stored=zip.getEntry(name); // 必须与压缩包真实成员一一对应。
                if(stored==null || stored.isDirectory()) throw new IOException("备份清单引用的文件缺失");
                Entry entry=new Entry(name,size,hash); entries.add(entry); // 已通过结构验证的清单条目。
                if(verifyContent) try(InputStream input=zip.getInputStream(stored)) { copyAndVerify(input,OutputStream.nullOutputStream(),entry,deadline); }
            }
            if(!names.contains("database.dump")) throw new IOException("备份缺少数据库dump");
            Set<String> actual=new HashSet<>(); // 额外成员不允许被静默忽略。
            for(ZipEntry entry:Collections.list(zip.entries())) if(!actual.add(entry.getName())) throw new IOException("备份ZIP包含重复成员");
            names.add("manifest.properties");
            if(!actual.equals(names)) throw new IOException("备份文件与清单不一致");
            return new Manifest(properties.getProperty("created_at",""),positive(properties,"db.archive_count",0,Long.MAX_VALUE),positive(properties,"db.file_count",0,Long.MAX_VALUE),List.copyOf(entries));
        } catch(IllegalArgumentException malformed) { throw new IOException("备份清单格式错误",malformed); }
    }

    /** 将清单写为标准Properties格式，中文和非ASCII路径由标准转义保存。 */
    static byte[] manifestBytes(Manifest manifest) throws IOException {
        Properties properties=new Properties(); // 恢复工具可独立解析的格式。
        properties.setProperty("format",FORMAT); properties.setProperty("created_at",manifest.createdAt());
        properties.setProperty("db.archive_count",Long.toString(manifest.archives())); properties.setProperty("db.file_count",Long.toString(manifest.files()));
        properties.setProperty("entry.count",Integer.toString(manifest.entries().size()));
        for(int index=0;index<manifest.entries().size();index++) {
            Entry entry=manifest.entries().get(index); // 当前备份文件校验信息。
            String prefix="entry."+index+"."; // 保持文件顺序的属性前缀。
            properties.setProperty(prefix+"path",entry.path()); properties.setProperty(prefix+"size",Long.toString(entry.size())); properties.setProperty(prefix+"sha256",entry.sha256());
        }
        ByteArrayOutputStream output=new ByteArrayOutputStream(); // 清单体积受调用方文件数上限约束。
        properties.store(output,"IMS database and attachment backup manifest"); return output.toByteArray();
    }

    /** 文件分块读取并计算SHA256，避免大附件一次载入内存。 */
    static String sha256(Path path) throws IOException {
        return sha256(path,Long.MAX_VALUE);
    }

    /** 支持Web维护预算的分块哈希，每块均响应超时和线程中断。 */
    static String sha256(Path path,long deadline) throws IOException {
        MessageDigest digest=digest(); // 标准SHA256实例。
        try(InputStream input=Files.newInputStream(path)) {
            byte[] buffer=new byte[65536]; // 固定大小读缓冲。
            int length; // 当前块长度。
            while((length=input.read(buffer))!=-1) { checkDeadline(deadline); digest.update(buffer,0,length); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 限定路径只能为database.dump或attachments下的安全相对文件。 */
    static void safeEntry(String path) throws IOException {
        if(!path.equals("database.dump") && !path.startsWith("attachments/")) throw new IOException("备份包含未允许的路径");
        if(path.contains("\\") || path.contains(":") || path.startsWith("/") || path.endsWith("/")) throw new IOException("备份路径不合法");
        for(String part:path.split("/",-1)) if(part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("备份路径包含非法层级");
    }

    /** 解析外置PostgreSQL JDBC连接，禁止通过URL夹带账号口令。 */
    static Connection connection(String jdbcUrl,String username,String password) throws IOException {
        if(jdbcUrl==null || !jdbcUrl.startsWith("jdbc:postgresql://")) throw new IOException("备份恢复只支持PostgreSQL连接");
        URI uri; // 解析后的连接地址。
        try { uri=URI.create(jdbcUrl.substring(5)); } catch(IllegalArgumentException invalid) { throw new IOException("数据库连接地址格式不正确"); }
        String database=uri.getPath()==null ? "" : uri.getPath().substring(1); // 数据库名称，不允许额外路径层级。
        if(uri.getHost()==null || database.isBlank() || database.contains("/") || username==null || username.isBlank() || uri.getUserInfo()!=null) throw new IOException("数据库连接参数不完整或不受支持");
        Map<String,String> options=new HashMap<>(); // 只传递明确支持的SSL选项。
        Map<String,String> supported=Map.of("sslmode","PGSSLMODE","sslrootcert","PGSSLROOTCERT","sslcert","PGSSLCERT","sslkey","PGSSLKEY"); // JDBC与libpq的对应项。
        if(uri.getRawQuery()!=null) for(String pair:uri.getRawQuery().split("&")) {
            String[] parts=pair.split("=",2); // 查询选项键值。
            String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8); // 解码JDBC查询参数名称。
            if(supported.containsKey(key) && parts.length==2) options.put(supported.get(key),URLDecoder.decode(parts[1],StandardCharsets.UTF_8));
            else if("ssl".equals(key) && parts.length==2) {
                String value=URLDecoder.decode(parts[1],StandardCharsets.UTF_8); // 不能将JDBC的显式TLS要求静默降级。
                if("true".equalsIgnoreCase(value)) options.putIfAbsent("PGSSLMODE","verify-full");
                else if("false".equalsIgnoreCase(value)) options.putIfAbsent("PGSSLMODE","disable");
                else throw new IOException("PostgreSQL的ssl选项必须明确为true或false");
            } else if(key.toLowerCase(Locale.ROOT).startsWith("ssl")) throw new IOException("PostgreSQL备份工具不支持此SSL配置，请使用sslmode及标准证书路径");
        }
        return new Connection(uri.getHost(),uri.getPort()<0?5432:uri.getPort(),database,username,password==null?"":password,Map.copyOf(options));
    }

    /** 解析运维配置的PostgreSQL工具目录，不通过shell搜索或拼接命令。 */
    static String executable(String directory,String name) {
        String file=name+(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?".exe":""); // Windows工具扩展名。
        return directory==null || directory.isBlank()?file:Path.of(directory).toAbsolutePath().normalize().resolve(file).toString();
    }

    /** 执行PostgreSQL工具并严格检查退出码；超时或中断强制结束本次子进程并报错。 */
    static void run(List<String> command,Map<String,String> environment,Path log,int timeoutSeconds) throws IOException,InterruptedException {
        ProcessBuilder builder=new ProcessBuilder(command); // 参数列表避免空格、反引号等被shell解释。
        builder.environment().putAll(environment); builder.redirectErrorStream(true); builder.redirectOutput(log.toFile());
        Process process=builder.start(); // 只结束此工具自身，不扫描或终止其他数据库连接。
        try {
            if(!process.waitFor(timeoutSeconds,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10,TimeUnit.SECONDS); throw new IOException("PostgreSQL工具执行超时，任务未完成"); }
            if(process.exitValue()!=0) throw new IOException("PostgreSQL工具执行失败，退出码="+process.exitValue()+"；请检查服务端运维日志");
        } catch(InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw interrupted; }
    }

    /** 执行仅包含工具内部固定SQL的psql核验，错误码非零时禁止继续。 */
    private static String psql(Connection connection,String pgBin,String sql,Path log,int seconds) throws IOException,InterruptedException {
        List<String> command=new ArrayList<>(List.of(executable(pgBin,"psql"))); // 数据库校验命令，不接受用户SQL。
        command.addAll(connection.arguments()); command.addAll(List.of("--dbname",connection.database(),"--no-psqlrc","-v","ON_ERROR_STOP=1","-At","-c",sql));
        Path checkLog=log.resolveSibling("verify-"+UUID.randomUUID()+".log"); // 每次验证使用独立日志，不覆盖pg_restore真实输出。
        run(command,connection.environment(),checkLog,seconds);
        if(Files.size(checkLog)>4096) throw new IOException("数据库验证输出异常");
        return Files.readString(checkLog,StandardCharsets.UTF_8);
    }

    /** 流式复制时同时限制声明长度并核对哈希，发现损坏立即失败。 */
    private static void copyAndVerify(InputStream input,OutputStream output,Entry entry) throws IOException {
        copyAndVerify(input,output,entry,Long.MAX_VALUE);
    }

    /** 带维护预算的流式校验，不允许大压缩成员绕过任务截止点。 */
    private static void copyAndVerify(InputStream input,OutputStream output,Entry entry,long deadline) throws IOException {
        MessageDigest digest=digest(); // 当前文件摘要。
        byte[] buffer=new byte[65536]; // 固定缓冲防止ZIP炸弹造成内存膨胀。
        long count=0; // 实际解压长度，不信任ZIP元数据。
        int length; // 当前读取块长度。
        while((length=input.read(buffer))!=-1) {
            checkDeadline(deadline);
            count+=length; if(count>entry.size()) throw new IOException("备份文件长度超过清单声明");
            digest.update(buffer,0,length); output.write(buffer,0,length);
        }
        if(count!=entry.size() || !HexFormat.of().formatHex(digest.digest()).equals(entry.sha256())) throw new IOException("备份文件校验失败："+entry.path());
    }

    /** 截止或中断时立即中止后续读写；离线恢复可使用不设期限的Long.MAX_VALUE。 */
    private static void checkDeadline(long deadline) throws IOException {
        if(Thread.currentThread().isInterrupted() || deadline!=Long.MAX_VALUE && System.nanoTime()>=deadline) throw new IOException("备份文件校验超时或已中断");
    }

    /** 读取有范围限制的清单整数属性。 */
    private static long positive(Properties properties,String key,long min,long max) throws IOException {
        try {
            long value=Long.parseLong(properties.getProperty(key,"")); // 只接受十进制整数。
            if(value<min || value>max) throw new NumberFormatException(); return value;
        } catch(NumberFormatException invalid) { throw new IOException("备份清单数值不合法："+key); }
    }

    /** 创建SHA256实例；JDK必须提供此标准算法。 */
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** 读取必填环境配置，避免错误默认值指向现有数据库或目录。 */
    private static String requiredEnvironment(String name) throws IOException {
        String value=System.getenv(name); // 明确由运维人员设置的恢复目标。
        if(value==null || value.isBlank()) throw new IOException("必须设置环境变量："+name); return value;
    }
}
