package com.company.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 本机试用的库外追加账本：fsync 后才提交收紧事务；正式冗余存储可替换此边界。 */
@Component
public class SecurityLedger {
    /** 不随业务数据库 PITR 回退的受控目录。 */
    private final Path root;
    /** 已验证的账号最低授权代次，用于拒绝数据库回退后的旧权限。 */
    private final Map<UUID,Integer> versions=new HashMap<>();
    /** 已读取字节位置；增量加载避免每个请求重扫完整历史。 */
    private long offset=0;
    /** 前一条记录的链摘要及独立递增序号。 */
    private String tail="0";
    private long sequence=0;
    /** 重新启动时读取的持久化链头；防止完整行被截断后被误当作有效短历史。 */
    private long anchorSequence=0;
    private String anchorDigest="0";
    private boolean anchorVerified=false;
    /** 当前数据库与账本配对标识，不允许两个库复用一个目录。 */
    private UUID instance;
    /** HMAC 密钥仅保存在私有目录，不返回浏览器。 */
    private String secret;
    /** 校验私有路径；业务启动后通过 bind 完成数据库配对。 */
    public SecurityLedger(@Value("${platform.security-ledger}") String root) { this.root=PrivateFiles.directory(root); }
    /** 空库允许创建账本；非空库缺失账本时拒绝静默重建。 */
    public synchronized void bind(UUID instanceId, boolean fresh) {
        try {
            Path marker=root.resolve("instance");
            if (!Files.exists(marker)) {
                if (!fresh) throw ApiFault.protection();
                Files.writeString(marker,instanceId.toString(),StandardOpenOption.CREATE_NEW);
                Files.writeString(root.resolve("key"),Crypto.token(),StandardOpenOption.CREATE_NEW);
                Files.createFile(root.resolve("events.jsonl"));
                Files.writeString(root.resolve("checkpoint"),"0\n0",StandardOpenOption.CREATE_NEW);
            }
            if (!Files.readString(marker).equals(instanceId.toString())) throw ApiFault.protection();
            this.instance=instanceId; this.secret=Files.readString(root.resolve("key"));
            String[] checkpoint=Files.readString(root.resolve("checkpoint")).split("\n");
            anchorSequence=Long.parseLong(checkpoint[0]); anchorDigest=checkpoint[1]; anchorVerified=anchorSequence==0 && anchorDigest.equals("0");
            access(null); // 启动时完整验证既有账本。
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) { throw ApiFault.protection(); }
    }
    /** 为账号或来源生成不可枚举的限速键。 */
    public String bucket(String value) { if (secret==null) throw ApiFault.protection(); return Crypto.hmac(secret,value); }
    /** 每次鉴权核验最新水位；数据库较旧或账本损坏时保持拒绝。 */
    public synchronized void check(UUID user, int version) { access(null); if (versions.getOrDefault(user,0)>version) throw ApiFault.protection(); }
    /** 先持久化收紧事实再提交业务；业务回滚导致账本领先时保守拒绝该主体。 */
    public synchronized void restrict(UUID user, int version, String action, UUID operation) {
        access(Sql.map("subjectId",user.toString(),"authorizationVersion",version,"action",action,"operationId",operation.toString(),"occurredAt",Instant.now().toString()));
    }
    /** 文件锁跨 JVM 串行化追加；读取只处理新增记录，数据库请求不持有全局应用锁。 */
    private void access(Map<String,Object> next) {
        if (instance==null) throw ApiFault.protection();
        try (FileChannel channel=FileChannel.open(root.resolve("events.jsonl"),StandardOpenOption.READ,StandardOpenOption.WRITE); var lock=channel.lock()) {
            if (channel.size()<offset) throw ApiFault.protection();
            // 按固定大小缓冲流式重放，历史增长不会让重启分配整个日志大小的内存。
            channel.position(offset); ByteBuffer buffer=ByteBuffer.allocate(8192); ByteArrayOutputStream line=new ByteArrayOutputStream();
            while(channel.read(buffer)>0) {
                buffer.flip();
                while(buffer.hasRemaining()) {
                    byte b=buffer.get();
                    if(b=='\n') { readRecord(line.toString(StandardCharsets.UTF_8)); line.reset(); }
                    else { line.write(b); if(line.size()>65536) throw ApiFault.protection(); }
                }
                buffer.clear();
            }
            if(line.size()!=0 || sequence<anchorSequence || !anchorVerified) throw ApiFault.protection();
            offset=channel.size();
            if (next!=null) {
                next.put("previous",tail); next.put("sequence",sequence+1); next.put("instance",instance.toString());
                String digest=Crypto.hash(Crypto.json(next)); next.put("digest",digest);
                ByteBuffer bytes=ByteBuffer.wrap((Crypto.json(next)+"\n").getBytes(StandardCharsets.UTF_8));
                channel.position(offset); while(bytes.hasRemaining()) channel.write(bytes); channel.force(true);
                versions.merge(UUID.fromString((String)next.get("subjectId")),((Number)next.get("authorizationVersion")).intValue(),Math::max);
                tail=digest; sequence++; offset=channel.size();
                checkpoint(); // 链记录先 fsync，随后原子更新独立链头。
            }
        } catch (IOException | RuntimeException e) { throw ApiFault.protection(); }
    }
    /** 每行包含完整不可变事实和前向摘要，不能只保留摘要而丢失恢复依据。 */
    @SuppressWarnings("unchecked")
    private void readRecord(String line) {
        Map<String,Object> row=Crypto.JSON.readValue(line,Map.class);
        String digest=(String)row.remove("digest");
        if (!Objects.equals(row.get("previous"),tail) || !Objects.equals(row.get("instance"),instance.toString()) || !Crypto.equal(digest,Crypto.hash(Crypto.json(row))) || ((Number)row.get("sequence")).longValue()!=sequence+1) throw ApiFault.protection();
        versions.merge(UUID.fromString((String)row.get("subjectId")),((Number)row.get("authorizationVersion")).intValue(),Math::max);
        tail=digest; sequence++;
        if(sequence==anchorSequence) { if(!tail.equals(anchorDigest)) throw ApiFault.protection(); anchorVerified=true; }
    }
    /** 原子替换持久化链头；掉电最多留下更旧链头，重放仍能完整验证更长的链。 */
    private void checkpoint() throws IOException {
        Path pending=root.resolve("checkpoint.pending");
        try(FileChannel file=FileChannel.open(pending,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            ByteBuffer bytes=ByteBuffer.wrap((sequence+"\n"+tail).getBytes(StandardCharsets.UTF_8)); while(bytes.hasRemaining()) file.write(bytes); file.force(true);
        }
        Files.move(pending,root.resolve("checkpoint"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
