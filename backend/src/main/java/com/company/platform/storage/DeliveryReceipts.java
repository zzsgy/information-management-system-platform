package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.time.Instant;

/** 库外页面授权回执；每次交付前独立刷盘，不把数据库日志当作唯一证据。 */
@Component
public class DeliveryReceipts {
    private final String configured;private final Sql db;
    public DeliveryReceipts(Sql db,@Value("${platform.reading.audit-root:}") String root) { this.db=db;this.configured=root; }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.company.platform.operations.DeploymentSettings deployment;
    private String settingConfigured(){return deployment==null?configured:deployment.text("platform.reading.audit-root",configured);}

    /** 记录服务器已准许交付，不声称用户实际看过或打印；写入失败则不交付页面。 */
    public synchronized UUID persist(Actor actor,UUID object,UUID version,int page,UUID request) {
        return write(actor,Sql.map("objectId",object.toString(),"versionId",version.toString(),"pageNo",page),request);
    }
    /** 导出回执固定独立动作、版本清单和实际输出摘要；不声称客户端收全或实际出纸。 */
    public synchronized UUID export(Actor actor,UUID usage,String action,List<Map<String,Object>> items,String sha,long bytes,int page,UUID request) {
        return write(actor,Sql.map("usageId",usage.toString(),"action",action,"items",items,"sha256",sha,"byteSize",bytes,"pageNo",page),request);
    }
    /** 实物交接/归还复核先刷盘记录意图；不把数据库提交前的意图声称为已完成交接。 */
    public synchronized UUID custody(Actor actor,UUID item,UUID loan,String event,UUID request) {return write(actor,Sql.map("physicalItemId",item.toString(),"loanId",loan.toString(),"event",event,"result","CUSTODY_RECORD_INTENT"),request);}
    /** 先创建独立不可覆盖的回执，再允许领域服务开始交付。 */
    private UUID write(Actor actor,Map<String,Object> detail,UUID request) {
        try {
            UUID instance=(UUID)db.required("SELECT id FROM platform_meta.instance").get("id");
            Path root=verifiedRoot(instance);
            UUID receipt=UUID.randomUUID();var record=Sql.map("receiptId",receipt.toString(),"instanceId",instance.toString(),"userId",actor.userId().toString(),"sessionId",actor.sessionId().toString(),"authVersion",actor.authVersion(),"requestId",request.toString(),"at",Instant.now().toString(),"result","AUTHORIZED_FOR_DELIVERY");record.putAll(detail);byte[] data=Crypto.json(record).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(FileChannel file=FileChannel.open(root.resolve(receipt+".json"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) { ByteBuffer bytes=ByteBuffer.wrap(data);while(bytes.hasRemaining()) file.write(bytes);file.force(true); }
            return receipt;
        } catch(ApiFault fault) { throw fault; }
        catch(Exception e) { throw SystemFailure.RECEIPT_WRITE_FAILED.fault(e); }
    }
    /** 环境检查与实际交付共用实例绑定，检查不能创建一个缺失标记的回执目录。 */
    public synchronized Path verifyStorage(){return verifiedRoot((UUID)db.required("SELECT id FROM platform_meta.instance").get("id"));}
    private Path verifiedRoot(UUID instance){
        if(settingConfigured().isBlank())throw SystemFailure.RECEIPT_STORAGE_UNAVAILABLE.fault();
        try{
            Path root=Path.of(settingConfigured());if(!root.isAbsolute())throw SystemFailure.RECEIPT_STORAGE_UNAVAILABLE.fault();
            for(Path item=root;item!=null;item=item.getParent())if(Files.isSymbolicLink(item))throw SystemFailure.RECEIPT_STORAGE_UNAVAILABLE.fault();
            if(!Files.exists(root)){Files.createDirectories(root);PrivateFiles.restrict(root);Files.writeString(root.resolve("instance"),instance.toString(),StandardOpenOption.CREATE_NEW);}
            if(!Files.isRegularFile(root.resolve("instance"),LinkOption.NOFOLLOW_LINKS)||!Files.readString(root.resolve("instance")).equals(instance.toString()))throw SystemFailure.RECEIPT_STORAGE_UNAVAILABLE.fault();return root;
        }catch(ApiFault fault){throw fault;}catch(Exception failure){throw SystemFailure.RECEIPT_STORAGE_UNAVAILABLE.fault(failure);}
    }

}
