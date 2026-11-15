package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.storage.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

/** 派生使用副本专用双目录，禁止与原件、账本、回执或邮件目录重叠。 */
@Component
public class ExportFiles {
    /** 所有路径均由部署配置提供，客户端不能选择物理文件位置。 */
    private final Sql db;private final String primary,secondary,temporary;private final List<String> protectedRoots;private QuarantineFiles store;
    public ExportFiles(Sql db,@Value("${platform.export.primary:}") String primary,@Value("${platform.export.secondary:}") String secondary,@Value("${platform.export.temporary:}") String temporary,@Value("${platform.storage.primary:}") String sourceA,@Value("${platform.storage.secondary:}") String sourceB,@Value("${platform.security-ledger}") String ledger,@Value("${platform.mailbox}") String mailbox,@Value("${platform.reading.audit-root:}") String audit,@Value("${platform.reading.temporary:}") String reading) {this.db=db;this.primary=primary;this.secondary=secondary;this.temporary=temporary;this.protectedRoots=List.of(sourceA,sourceB,ledger,mailbox,audit,reading);}
    /** 缺配置失败关闭；输出缓存不能污染原件，也不能成为公开静态文件。 */
    public synchronized QuarantineFiles get() {
        List<Path> outputs=List.of(path(primary),path(secondary),path(temporary));
        for(int i=0;i<outputs.size();i++) {for(int j=i+1;j<outputs.size();j++) separate(outputs.get(i),outputs.get(j));for(String root:protectedRoots) if(!root.isBlank()) separate(outputs.get(i),path(root));}
        if(store==null) {store=new QuarantineFiles(outputs.get(0),outputs.get(1),(UUID)db.required("SELECT id FROM platform_meta.instance").get("id"));}store.bind();return store;
    }
    /** 私有工作目录只容纳有界的本次输出；清理只处理本次显式创建的文件。 */
    public Path temporary() {get();return PrivateFiles.directory(temporary);}
    private Path path(String value) {if(value.isBlank()||!Path.of(value).isAbsolute()) throw ApiFault.protection();return Path.of(value).normalize();}
    private void separate(Path a,Path b) {if(a.startsWith(b)||b.startsWith(a)) throw ApiFault.protection();}
}
