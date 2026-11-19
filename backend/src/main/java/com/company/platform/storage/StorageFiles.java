package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

/** 文件适配器按需绑定实例；未配置双隔离目录时原有身份平台仍可运行。 */
@Component
public class StorageFiles {
    private final Sql db;
    private final String primary,secondary,ledger,mailbox;
    private QuarantineFiles store;
    private String storePaths="";
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.company.platform.operations.DeploymentSettings deployment;
    /** 路径来自受再验证的IT部署配置，业务上传请求不能选择物理目录。 */
    public StorageFiles(Sql db,@Value("${platform.storage.primary:}") String primary,@Value("${platform.storage.secondary:}") String secondary,
                        @Value("${platform.security-ledger}") String ledger,@Value("${platform.mailbox}") String mailbox) {
        this.db=db; this.primary=primary; this.secondary=secondary; this.ledger=ledger; this.mailbox=mailbox;
    }
    /** 拒绝与账本、邮箱目录相互嵌套；缺失配置不自动选择旧附件路径。 */
    public synchronized QuarantineFiles get() {
        String primary=deployment==null?this.primary:deployment.text("platform.storage.primary",this.primary),secondary=deployment==null?this.secondary:deployment.text("platform.storage.secondary",this.secondary);
        String paths=primary+"\n"+secondary;
        if(!paths.equals(storePaths)){store=null;storePaths=paths;}
        if(primary.isBlank() || secondary.isBlank()) throw SystemFailure.STORAGE_NOT_CONFIGURED.fault();
        if(store==null) {
            Path a=Path.of(primary).normalize(),b=Path.of(secondary).normalize();
            for(Path protectedRoot:List.of(Path.of(ledger).normalize(),Path.of(mailbox).normalize()))
                if(a.startsWith(protectedRoot)||protectedRoot.startsWith(a)||b.startsWith(protectedRoot)||protectedRoot.startsWith(b)) throw SystemFailure.STORAGE_PATH_INVALID.fault();
            UUID id=(UUID)db.required("SELECT id FROM platform_meta.instance").get("id");
            store=new QuarantineFiles(a,b,id); store.bind();
        }
        store.bind(); // 每次重新核验目录与实例配对，不能因缓存掩盖路径故障。
        return store;
    }
}
