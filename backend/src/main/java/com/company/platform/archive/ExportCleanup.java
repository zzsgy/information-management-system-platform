package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.storage.StorageBoundaries;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 有界派生缓存清理：仅处理已过使用截止和24小时保护期的明确输出键，保留全部审计元数据。 */
@Component @ConditionalOnProperty(prefix="platform.file-worker",name="enabled",havingValue="true")
public class ExportCleanup {
    private final Sql db;private final ExportFiles files;private final StorageBoundaries gate;private final TransactionTemplate tx;
    public ExportCleanup(Sql db,ExportFiles files,StorageBoundaries gate,PlatformTransactionManager manager) {this.db=db;this.files=files;this.gate=gate;this.tx=new TransactionTemplate(manager);}
    /** 每分钟最多10件，多实例跳过锁定行；保护失效时不删除任何文件。 */
    @Scheduled(fixedDelay=60000) public void tick() {
        try {tx.executeWithoutResult(status->{gate.requireWritable("ARCHIVE","EXPORT");for(var row:db.rows("SELECT a.id,a.storage_key FROM archive.export_asset a JOIN archive.export_request u ON u.id=a.usage_id WHERE a.storage_state='LIVE' AND a.purge_after<CURRENT_TIMESTAMP AND u.expires_at<CURRENT_TIMESTAMP ORDER BY a.purge_after,a.id LIMIT 10 FOR UPDATE OF a SKIP LOCKED")) {gate.requireWritable("ARCHIVE","EXPORT");if(files.get().purgeDerived(row.get("storage_key").toString()))db.update("UPDATE archive.export_asset SET storage_state='PURGED' WHERE id=?",row.get("id"));}});}
        catch(ApiFault unavailable) {/* 独立保护未就绪则保留缓存，绝不降级到原件目录清理。 */}
    }
}
