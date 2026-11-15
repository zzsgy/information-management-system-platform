package com.company.platform.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
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
    /** 配置不全即拒绝判定，不使用系统PATH搜索任意检测程序。 */
    @Override public ClamFileScanner.Verdict scan(Path source) {
        try {
            if(executable.isBlank()||database.isBlank()||temporary.isBlank()||hash.isBlank()) return ClamFileScanner.Verdict.UNAVAILABLE;
            return new ClamFileScanner(Path.of(executable),Path.of(database),Path.of(temporary),hash,Duration.ofSeconds(timeoutSeconds),true).scan(source);
        } catch(RuntimeException e) { return ClamFileScanner.Verdict.UNAVAILABLE; }
    }
}
