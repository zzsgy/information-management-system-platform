package com.company.platform.storage;

import com.company.platform.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 独立PDF子进程边界；仅领域受控阅读服务可调用，内部路径不进入HTTP响应。 */
public final class PdfRenderClient {
    /** 缓冲与水印输入上限，超过上限不会转交客户端。 */
    private static final int MAX_PAGE_BYTES=32*1024*1024;
    /** 由部署管理员配置的可信 Java、工作器和私有临时目录。 */
    private final Path java,worker,privateTemp;
    private final Duration deadline;
    /** 执行器必须是绝对路径；超时时间上限 60 秒，不能由网页参数扩大。 */
    public PdfRenderClient(Path java,Path worker,Path privateTemp,Duration deadline) {
        if(!java.isAbsolute() || !worker.isAbsolute() || !privateTemp.isAbsolute() || deadline.isZero() || deadline.isNegative() || deadline.compareTo(Duration.ofSeconds(60))>0) throw new IllegalArgumentException("处理进程配置无效");
        this.java=java.normalize(); this.worker=worker.normalize(); this.privateTemp=privateTemp.normalize(); this.deadline=deadline;
    }
    /** 绑定固定源摘要并生成本人水印图；调用前后都需要领域授权，读取时不得返回原 PDF。 */
    public byte[] render(Path source,String expectedHash,int page,int dpi,WatermarkedPage.Stamp stamp) {
        return WatermarkedPage.render(renderPage(source,expectedHash,page,dpi),stamp);
    }
    /** 公盘受控页不默认增加个人水印；仍只返回PNG，不交付原PDF。 */
    public byte[] renderPage(Path source,String expectedHash,int page,int dpi) { return execute(source,expectedHash,List.of(Integer.toString(page),Integer.toString(dpi)),MAX_PAGE_BYTES); }
    /** 页数由同一隔离进程核验，禁止在主服务解析不可信PDF结构。 */
    public int pageCount(Path source,String expectedHash) {
        try { var data=Crypto.JSON.readValue(execute(source,expectedHash,List.of("info"),4096),Map.class); return Inputs.integer(data.get("pageCount"),0,1,10000); }
        catch(RuntimeException e) { throw ApiFault.protection(); }
    }
    /** 图片也在同一受限进程解析；TIFF多页保留页序，输出不携带原图元数据。 */
    public int imagePageCount(Path source,String expectedHash) {
        try {var data=Crypto.JSON.readValue(execute(source,expectedHash,List.of("image-info"),4096),Map.class);return Inputs.integer(data.get("pageCount"),0,1,10000);}
        catch(RuntimeException failure) {throw ApiFault.protection();}
    }
    public byte[] renderImagePage(Path source,String expectedHash,int page) {return execute(source,expectedHash,List.of("image-page",Integer.toString(page)),MAX_PAGE_BYTES);}
    /** 导出计划只写入服务私有目录；最多128MiB输出，失败不返回部分PDF。 */
    public byte[] exportPdf(Path source,String expectedHash,Properties plan) {
        Path file=null;
        try {file=Files.createTempFile(privateTemp,"export-plan-",".properties");PrivateFiles.restrict(file);try(Writer writer=Files.newBufferedWriter(file)){plan.store(writer,"服务端冻结的个人水印使用计划");}return execute(source,expectedHash,List.of("export",file.toString()),128*1024*1024);}
        catch(IOException failure) {throw ApiFault.protection();}
        finally {if(file!=null) try{Files.deleteIfExists(file);}catch(IOException ignored){/* 私有计划由运维核查，不交给浏览器。 */}}
    }
    /** 固定源摘要、受限进程与输出上限的公共执行边界。 */
    private byte[] execute(Path source,String expectedHash,List<String> arguments,int outputLimit) {
        UploadRules.sha(expectedHash);
        if(!source.isAbsolute() || !Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(java,LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(worker,LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(privateTemp,LinkOption.NOFOLLOW_LINKS)) throw ApiFault.protection();
        try { if(Files.size(source)>1073741824L) throw ApiFault.protection(); } catch(IOException error) { throw ApiFault.protection(); }
        for(Path node=source;node!=null;node=node.getParent()) if(Files.isSymbolicLink(node)) throw ApiFault.protection();
        for(Path trusted:List.of(java,worker,privateTemp)) for(Path node=trusted;node!=null;node=node.getParent()) if(Files.isSymbolicLink(node)) throw ApiFault.protection();
        if(!Crypto.equal(fileHash(source),expectedHash)) throw ApiFault.protection();
        Process process=null; ExecutorService reader=Executors.newSingleThreadExecutor();
        try {
            // 参数直接传递给操作系统，不经过 shell；子进程堆和运行时间有独立上限。
            List<String> command=new ArrayList<>(List.of(java.toString(),"-Xmx256m","-Djava.awt.headless=true","-Djava.io.tmpdir="+privateTemp,"-jar",worker.toString(),source.toString()));
            command.addAll(arguments);
            ProcessBuilder builder=new ProcessBuilder(command)
                .directory(privateTemp.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            // 子进程不得继承数据库口令、服务凭据或业务配置；仅保留 Windows 启动必需项和私有临时目录。
            String windowsRoot=System.getenv("SystemRoot"); builder.environment().clear();
            if(windowsRoot!=null) builder.environment().put("SystemRoot",windowsRoot);
            builder.environment().put("TEMP",privateTemp.toString()); builder.environment().put("TMP",privateTemp.toString());
            process=builder.start();
            Process running=process;
            Future<byte[]> result=reader.submit(()-> {
                byte[] data=running.getInputStream().readNBytes(outputLimit+1);
                if(data.length>outputLimit) { running.destroyForcibly(); throw new IOException("page too large"); }
                return data;
            });
            if(!process.waitFor(deadline.toMillis(),TimeUnit.MILLISECONDS)) throw new IOException("worker timeout");
            if(process.exitValue()!=0) throw new IOException("worker failed");
            byte[] png=result.get(1,TimeUnit.SECONDS);
            if(!Crypto.equal(fileHash(source),expectedHash)) throw ApiFault.protection();
            return png;
        } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw ApiFault.protection(); }
        catch(Exception error) { throw ApiFault.protection(); }
        finally {
            if(process!=null) { process.destroyForcibly(); try { process.getInputStream().close(); } catch(IOException ignored) { } }
            reader.shutdownNow();
        }
    }
    /** 固定大小缓冲计算原件版本摘要；不得记录原始文件内容或路径。 */
    private String fileHash(Path source) {
        try(InputStream input=Files.newInputStream(source)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] block=new byte[65536]; int n;
            while((n=input.read(block))!=-1) digest.update(block,0,n);
            return HexFormat.of().formatHex(digest.digest());
        } catch(IOException | GeneralSecurityException error) { throw ApiFault.protection(); }
    }
}
