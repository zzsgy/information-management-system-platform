package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 只消费独立保护代理签发的短时证据；客户端及业务数据库都不能自行宣布保护正常。 */
@Component
@ConditionalOnProperty(prefix="platform.protection",name="enabled",havingValue="true")
public class SignedProtectionGate implements StorageProtectionPolicy {
    /** 证据和密钥来自独立受控目录，不能位于数据库快照或公开网页目录。 */
    private final Path evidence,key;
    private final String node;
    private final Sql db;
    /** 当前进程观测的最高接管代次，拒绝重复投递较旧证据。 */
    private long minimumEpoch=0;
    public SignedProtectionGate(Sql db,@Value("${platform.protection.evidence-file}") String evidence,@Value("${platform.protection.key-file}") String key,@Value("${platform.protection.node-id}") String node) {
        this.db=db; this.evidence=Path.of(evidence); this.key=Path.of(key); this.node=node;
    }
    /** 写入需当前主节点、旧主隔离、双副本与不超过30分钟的已验证完整恢复点。 */
    @Override public synchronized void requireWritable(String module,String action) {
        Map<String,Object> state=validated();
        if(!"NORMAL".equals(state.get("mode")) || !Boolean.TRUE.equals(state.get("oldPrimaryFenced"))) throw ApiFault.protection();
        if(!(state.get("writableModules") instanceof List<?> modules) || !modules.contains(module)) throw ApiFault.protection();
        Instant now=Instant.now(), replica=Instant.parse((String)state.get("replicaVerifiedAt")),backup=Instant.parse((String)state.get("backupProtectedAt"));
        if(replica.isBefore(now.minusSeconds(30)) || replica.isAfter(now.plusSeconds(5)) || backup.isBefore(now.minusSeconds(1800)) || backup.isAfter(now.plusSeconds(5))) throw ApiFault.protection();
        Inputs.uuid(state.get("backupId"));
    }
    /** 恢复锁定或证据失效时拒绝敏感页面；只读模式仍可读取已经核验的版本。 */
    @Override public synchronized void requireReadable(String module) {
        Map<String,Object> state=validated();
        if(!Set.of("NORMAL","ARCHIVE_READ_ONLY","ALL_READ_ONLY").contains(state.get("mode")) || !Boolean.TRUE.equals(state.get("oldPrimaryFenced"))) throw ApiFault.protection();
    }
    /** 仅返回可显示的状态，不公开签名、密钥或路径。 */
    public Map<String,Object> status() {
        try { var state=validated(); return Sql.map("ready",true,"mode",state.get("mode"),"expiresAt",state.get("expiresAt")); }
        catch(RuntimeException e) { return Sql.map("ready",false,"mode","PROTECTION_NOT_READY"); }
    }
    /** 签名覆盖全部字段，且证据绑定数据库实例、当前节点和最多60秒有效期。 */
    @SuppressWarnings("unchecked")
    private Map<String,Object> validated() {
        try {
            checkPath(evidence); checkPath(key);
            if(Files.size(evidence)>32768 || Files.size(key)>1024) throw ApiFault.protection();
            Map<String,Object> state=new LinkedHashMap<>(Crypto.JSON.readValue(Files.readString(evidence),Map.class));
            Inputs.only(state,"instanceId","nodeId","epoch","issuedAt","expiresAt","mode","oldPrimaryFenced","replicaVerifiedAt","backupId","backupProtectedAt","writableModules","signature");
            String signature=(String)state.remove("signature"),secret=Files.readString(key).trim();
            if(secret.length()<43 || !Crypto.equal(Crypto.hmac(secret,Crypto.json(state)),signature) || !node.equals(state.get("nodeId"))) throw ApiFault.protection();
            if(!Inputs.uuid(state.get("instanceId")).equals(db.required("SELECT id FROM platform_meta.instance").get("id"))) throw ApiFault.protection();
            long epoch=((Number)state.get("epoch")).longValue();
            Instant now=Instant.now(),issued=Instant.parse((String)state.get("issuedAt")),expires=Instant.parse((String)state.get("expiresAt"));
            if(epoch<1 || epoch<minimumEpoch || issued.isAfter(now.plusSeconds(5)) || !now.isBefore(expires) || !issued.isBefore(expires) || Duration.between(issued,expires).getSeconds()>60) throw ApiFault.protection();
            advanceEpoch(epoch,secret,(String)state.get("instanceId"));minimumEpoch=epoch; return state;
        } catch(Exception e) { throw ApiFault.protection(); }
    }
    /** 接管代次在库外持久化，服务重启或业务库回退后也拒绝旧主证据；独占锁支持多进程。 */
    private void advanceEpoch(long epoch,String secret,String instance) throws IOException {
        Path floor=key.resolveSibling(key.getFileName()+".epoch"),lock=key.resolveSibling(key.getFileName()+".epoch.lock");
        if(Files.isSymbolicLink(floor)||Files.isSymbolicLink(lock)) throw ApiFault.protection();
        try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var held=channel.tryLock()) {
            if(held==null) throw ApiFault.protection();
            long previous=0;
            if(Files.exists(floor,LinkOption.NOFOLLOW_LINKS)) {
                if(!Files.isRegularFile(floor,LinkOption.NOFOLLOW_LINKS)||Files.size(floor)>4096) throw ApiFault.protection();
                Map<?,?> stored=Crypto.JSON.readValue(Files.readString(floor),Map.class);
                previous=((Number)stored.get("epoch")).longValue();
                if(!Crypto.equal((String)stored.get("signature"),Crypto.hmac(secret,instance+":"+node+":"+previous))) throw ApiFault.protection();
            }
            if(epoch<previous) throw ApiFault.protection();
            if(epoch==previous) return;
            Path pending=floor.resolveSibling(floor.getFileName()+"."+UUID.randomUUID()+".pending");
            byte[] content=Crypto.json(Sql.map("epoch",epoch,"signature",Crypto.hmac(secret,instance+":"+node+":"+epoch))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(FileChannel output=FileChannel.open(pending,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {ByteBuffer bytes=ByteBuffer.wrap(content);while(bytes.hasRemaining()) output.write(bytes);output.force(true);}
            PrivateFiles.restrict(pending);Files.move(pending,floor,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
    }
    /** 拒绝路径链接和相对路径，不从HTTP或数据库字段读取本地路径。 */
    private void checkPath(Path path) throws IOException {
        if(!path.isAbsolute() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("unavailable");
        for(Path item=path;item!=null;item=item.getParent()) if(Files.isSymbolicLink(item)) throw new IOException("link rejected");
    }
}
