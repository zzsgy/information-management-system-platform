package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/** 受控阅读使用IT部署配置；普通业务接口不能指定Java、JAR或临时目录。 */
@Component
public class ReadingRuntime {
    private final String java,jar,temp;
    /** 公盘、档案和候选质检共用两个进程名额，避免各模块独立扩容挤满主机内存。 */
    private final Semaphore slots=new Semaphore(2);
    public ReadingRuntime(@Value("${platform.reading.java:}") String java,@Value("${platform.reading.worker-jar:}") String jar,@Value("${platform.reading.temporary:}") String temp) { this.java=java;this.jar=jar;this.temp=temp; }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.company.platform.operations.DeploymentSettings deployment;
    private String settingJava(){return deployment==null?java:deployment.text("platform.reading.java",java);}
    private String settingJar(){return deployment==null?jar:deployment.text("platform.reading.worker-jar",jar);}
    private String settingTemp(){return deployment==null?temp:deployment.text("platform.reading.temporary",temp);}

    /** 临时目录每次验证绝对路径、无链接和服务账号权限；缺配置即拒绝解析。 */
    public PdfRenderClient get() {
        if(settingJava().isBlank()||settingJar().isBlank()||settingTemp().isBlank()) throw SystemFailure.READING_NOT_CONFIGURED.fault();
        try {return new PdfRenderClient(Path.of(settingJava()),Path.of(settingJar()),PrivateFiles.directory(settingTemp()),Duration.ofSeconds(30));}
        catch(RuntimeException failure) {throw SystemFailure.READING_RUNTIME_UNAVAILABLE.fault(failure);}
    }
    /** 只尝试领取，不让HTTP线程无限排队等待大文件；调用方负责finally释放。 */
    public boolean tryAcquire() {return slots.tryAcquire();}
    public void release() {slots.release();}
    /** 候选页数解析与受控阅读共享有界资源，文件校验也应在该作用域内执行。 */
    public <T> T withClient(Function<PdfRenderClient,T> work) {if(!tryAcquire()) throw SystemFailure.READING_BUSY.fault();try{return work.apply(get());}finally{release();}}
}
