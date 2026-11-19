package com.company.platform.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.company.platform.*;
import java.nio.file.Path;
import java.time.Duration;

/** 正式配置永远要求官方有效签名库；测试用自定义库没有配置开关。 */
@Component
public class ConfiguredClamScanner implements FileScanEngine {
    private final String executable,database,temporary,hash;
    private final int timeoutSeconds;
    public ConfiguredClamScanner(@Value("${platform.scanner.executable:}") String executable,@Value("${platform.scanner.database:}") String database,
        @Value("${platform.scanner.temporary:}") String temporary,@Value("${platform.scanner.executable-sha256:}") String hash,@Value("${platform.scanner.timeout-seconds:300}") int timeoutSeconds) {
        this.executable=executable;this.database=database;this.temporary=temporary;this.hash=hash;this.timeoutSeconds=timeoutSeconds;
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.company.platform.operations.DeploymentSettings deployment;
    private String settingExecutable(){return deployment==null?executable:deployment.text("platform.scanner.executable",executable);}
    private String settingDatabase(){return deployment==null?database:deployment.text("platform.scanner.database",database);}
    private String settingTemporary(){return deployment==null?temporary:deployment.text("platform.scanner.temporary",temporary);}
    private String settingHash(){return deployment==null?hash:deployment.text("platform.scanner.executable-sha256",hash);}
    private int settingTimeoutSeconds(){return deployment==null?timeoutSeconds:Integer.parseInt(deployment.text("platform.scanner.timeout-seconds",String.valueOf(timeoutSeconds)));}

    /** 配置不全即拒绝判定，不使用系统PATH搜索任意检测程序。 */
    @Override public ClamFileScanner.Verdict scan(Path source) {
        try {
            if(settingExecutable().isBlank()||settingDatabase().isBlank()||settingTemporary().isBlank()||settingHash().isBlank()) throw SystemFailure.SCANNER_NOT_CONFIGURED.fault();
            return new ClamFileScanner(Path.of(settingExecutable()),Path.of(settingDatabase()),Path.of(settingTemporary()),settingHash(),Duration.ofSeconds(settingTimeoutSeconds()),true).scanOrThrow(source);
        } catch(ApiFault fault) { if(fault.status<500) throw SystemFailure.SCANNER_NOT_CONFIGURED.fault(fault); throw fault; }
        catch(RuntimeException e) { throw SystemFailure.SCANNER_NOT_CONFIGURED.fault(e); }
    }
}
