package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/** 受控阅读的可信渲染配置；网页不能提交 Java、JAR 或临时目录路径。 */
@Component
public class ReadingRuntime {
    private final String java,jar,temp;
    /** 公盘、档案和候选质检共用两个进程名额，避免各模块独立扩容挤满主机内存。 */
    private final Semaphore slots=new Semaphore(2);
    public ReadingRuntime(@Value("${platform.reading.java:}") String java,@Value("${platform.reading.worker-jar:}") String jar,@Value("${platform.reading.temporary:}") String temp) { this.java=java;this.jar=jar;this.temp=temp; }
    /** 临时目录每次验证绝对路径、无链接和服务账号权限；缺配置即拒绝解析。 */
    public PdfRenderClient get() {
        if(java.isBlank()||jar.isBlank()||temp.isBlank()) throw SystemFailure.READING_NOT_CONFIGURED.fault();
        try {return new PdfRenderClient(Path.of(java),Path.of(jar),PrivateFiles.directory(temp),Duration.ofSeconds(30));}
        catch(RuntimeException failure) {throw SystemFailure.READING_RUNTIME_UNAVAILABLE.fault(failure);}
    }
    /** 只尝试领取，不让HTTP线程无限排队等待大文件；调用方负责finally释放。 */
    public boolean tryAcquire() {return slots.tryAcquire();}
    public void release() {slots.release();}
    /** 候选页数解析与受控阅读共享有界资源，文件校验也应在该作用域内执行。 */
    public <T> T withClient(Function<PdfRenderClient,T> work) {if(!tryAcquire()) throw SystemFailure.READING_BUSY.fault();try{return work.apply(get());}finally{release();}}
}
