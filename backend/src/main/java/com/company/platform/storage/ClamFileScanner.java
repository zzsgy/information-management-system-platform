package com.company.platform.storage;

import com.company.platform.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** 隔离进程文件检测适配器；任何错误、超时、跳过文件或病毒库缺失均不能认定为安全。 */
public final class ClamFileScanner {
    /** 唯一执行文件、签名库及隔离临时目录均由可信部署配置提供。 */
    private final Path executable,database,temporary;
    private final String executableHash;
    private final Duration timeout;
    /** 正式运行必须使用官方签名库及有效期；false 仅由测试代码显式构造。 */
    private final boolean officialOnly;
    private static final Pattern SCANNED=Pattern.compile("(?m)^Scanned files:\\s*([0-9]+)\\s*$");
    private static final Pattern INFECTED=Pattern.compile("(?m)^Infected files:\\s*([0-9]+)\\s*$");

    /** 限定检测器版本的文件摘要，防止配置被悄悄替换为其他程序。 */
    public ClamFileScanner(Path executable,Path database,Path temporary,String executableHash,Duration timeout,boolean officialOnly) {
        if(!executable.isAbsolute() || !database.isAbsolute() || !temporary.isAbsolute() || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10))>0) throw new IllegalArgumentException("检测器配置无效");
        this.executable=executable.normalize(); this.database=database.normalize(); this.temporary=temporary.normalize();
        this.executableHash=UploadRules.sha(executableHash); this.timeout=timeout; this.officialOnly=officialOnly;
    }
    /** CLEAN 表示真实检测进程完整扫描且未发现威胁；感染和不可判定严格区分。 */
    public enum Verdict { CLEAN, SUSPICIOUS, UNAVAILABLE }
    /** 两个副本的固定对象在调用方扫描前后都须重新校验；此方法只负责病毒扫描。 */
    public Verdict scan(Path source) {
        try {
            safePath(executable,false); safePath(database,true); safePath(temporary,true); safePath(source,false);
            if(!Crypto.equal(hash(executable),executableHash) || (officialOnly && !validOfficialDatabase())) return Verdict.UNAVAILABLE;
            List<String> args=new ArrayList<>(List.of(executable.toString(),"--database="+database,"--tempdir="+temporary,
                "--stdout","--alert-encrypted=yes","--alert-exceeds-max=yes","--scan-pdf=yes","--scan-archive=yes",
                "--max-filesize=5120M","--max-scansize=5120M","--max-files=10000","--max-recursion=32"));
            if(officialOnly) { args.add("--official-db-only=yes"); args.add("--fail-if-cvd-older-than=7"); }
            args.add(source.toString());
            ProcessBuilder builder=new ProcessBuilder(args).directory(temporary.toFile()).redirectErrorStream(true);
            // 检测进程不继承数据库口令、令牌或外部连接配置。
            String systemRoot=System.getenv("SystemRoot"); builder.environment().clear();
            if(systemRoot!=null) builder.environment().put("SystemRoot",systemRoot);
            builder.environment().put("TEMP",temporary.toString()); builder.environment().put("TMP",temporary.toString());
            Process process=builder.start(); ExecutorService output=Executors.newSingleThreadExecutor();
            try {
                Future<byte[]> captured=output.submit(()->process.getInputStream().readNBytes(65537));
                if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)) return Verdict.UNAVAILABLE;
                byte[] raw=captured.get(2,TimeUnit.SECONDS); if(raw.length>65536) return Verdict.UNAVAILABLE;
                String report=new String(raw,StandardCharsets.UTF_8); Matcher scanned=SCANNED.matcher(report), infected=INFECTED.matcher(report);
                // 有效检测必须报告至少扫描一个文件；不能把“跳过”当作 CLEAN。
                if(!scanned.find() || !infected.find() || Long.parseLong(scanned.group(1))<1) return Verdict.UNAVAILABLE;
                long found=Long.parseLong(infected.group(1));
                if(process.exitValue()==1 && found>0) return Verdict.SUSPICIOUS;
                if(process.exitValue()==0 && found==0) return Verdict.CLEAN;
                return Verdict.UNAVAILABLE;
            } finally { process.destroyForcibly(); try { process.getInputStream().close(); } catch(IOException ignored) { } output.shutdownNow(); }
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); return Verdict.UNAVAILABLE; }
        catch(Exception e) { return Verdict.UNAVAILABLE; }
    }
    /** 缺失主要官方库或每日库时不允许进入正式检测；每日库超过七天拒绝。 */
    private boolean validOfficialDatabase() throws IOException {
        Path main=existing("main"),daily=existing("daily");
        return main!=null && daily!=null && !Files.getLastModifiedTime(daily,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(Instant.now().minus(Duration.ofDays(7)));
    }
    /** 只查找预期 CVD/CLD 文件，不相信其他自定义签名库。 */
    private Path existing(String name) { for(String ext:List.of(".cvd",".cld")) { Path path=database.resolve(name+ext); if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) return path; } return null; }
    /** 所有路径必须为绝对且无符号链接；只读原件，不能执行用户文件。 */
    private void safePath(Path path,boolean directory) throws IOException {
        if(!path.isAbsolute()) throw new IOException("absolute required");
        for(Path part=path;part!=null;part=part.getParent()) if(Files.isSymbolicLink(part)) throw new IOException("link rejected");
        if(directory?!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS):!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("path unavailable");
    }
    /** 小块流式摘要，不把可执行文件一次读入内存。 */
    private String hash(Path path) throws IOException,GeneralSecurityException {
        MessageDigest md=MessageDigest.getInstance("SHA-256"); byte[] block=new byte[65536];
        try(InputStream in=Files.newInputStream(path)) { int n; while((n=in.read(block))!=-1) md.update(block,0,n); }
        return HexFormat.of().formatHex(md.digest());
    }
}
