package com.company.platform.storage;

import com.company.platform.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 本机隔离文件适配器：每片独立随机键、双路径同步写入和摘要核验，不提供下载。 */
public final class QuarantineFiles {
    /** 两个隔离目录必须相互独立；实际跨机器故障域由部署层另行验证。 */
    private final Path first,second;
    /** 本目录只属于唯一的新平台数据库实例。 */
    private final UUID instance;
    public QuarantineFiles(Path first,Path second,UUID instance) {
        this.first=first.toAbsolutePath().normalize(); this.second=second.toAbsolutePath().normalize(); this.instance=instance;
        if(!first.isAbsolute() || !second.isAbsolute() || this.first.startsWith(this.second) || this.second.startsWith(this.first)) throw SystemFailure.STORAGE_PATH_INVALID.fault();
    }
    /** 建立新目录或验证已有配对；不能把旧附件目录静默改造成新文件区。 */
    public synchronized void bind() {
        try { prepare(first); prepare(second); }
        catch(ApiFault fault) { throw fault; }
        catch(IOException ex) { throw SystemFailure.STORAGE_UNAVAILABLE.fault(ex); }
    }
    private void prepare(Path root) throws IOException {
        for(Path p=root;p!=null;p=p.getParent()) if(Files.isSymbolicLink(p)) throw SystemFailure.STORAGE_PATH_INVALID.fault();
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root); PrivateFiles.restrict(root);
            Files.writeString(root.resolve("platform-instance"),instance.toString(),StandardOpenOption.CREATE_NEW);
        }
        Path marker=root.resolve("platform-instance");
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS) || !Files.readString(marker).equals(instance.toString())) throw SystemFailure.STORAGE_INSTANCE_MISMATCH.fault();
    }
    /** 流式读取并同时写入两份；只有实际大小和 SHA256 完全一致才返回随机键。 */
    public String receive(InputStream input,long expectedBytes,String expectedHash) {
        bind(); String key=UUID.randomUUID().toString(); Path a=path(first,key), b=path(second,key);
        boolean ownA=false,ownB=false;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(FileChannel outA=FileChannel.open(a,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                ownA=true;
                try(FileChannel outB=FileChannel.open(b,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                    ownB=true; byte[] block=new byte[65536]; long received=0; int n;
                    while((n=input.read(block))!=-1) {
                        received+=n; if(received>expectedBytes) throw new ApiFault(422,"HASH_OR_PART_MISMATCH","分片实测长度不一致");
                        digest.update(block,0,n); write(outA,block,n); write(outB,block,n);
                    }
                    if(received!=expectedBytes || !Crypto.equal(HexFormat.of().formatHex(digest.digest()),expectedHash)) throw new ApiFault(422,"HASH_OR_PART_MISMATCH","分片长度或摘要不一致");
                    outA.force(true); outB.force(true);
                }
            }
            // 两份都从磁盘独立重算，不能仅把写入时的同一摘要当作双副本校验。
            if(!verified(key,expectedBytes,expectedHash)) throw SystemFailure.STORAGE_INTEGRITY_FAILED.fault();
            return key;
        } catch(ApiFault ex) { cleanup(a,ownA); cleanup(b,ownB); throw ex; }
        catch(Exception ex) { cleanup(a,ownA); cleanup(b,ownB); throw SystemFailure.STORAGE_WRITE_FAILED.fault(ex); }
    }
    /** 校验指定副本的实存内容；失败绝不改写或回退到未经校验的文件。 */
    public boolean verified(String key,long size,String digest) {
        bind(); try { Path a=path(first,key), b=path(second,key); return Files.isRegularFile(a,LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(b,LinkOption.NOFOLLOW_LINKS) && Files.size(a)==size && Files.size(b)==size && Crypto.equal(hash(a),digest) && Crypto.equal(hash(b),digest); }
        catch(Exception ex) { return false; }
    }
    /** 仅可信内部处理器可取得已验证的源路径；不得把结果放入接口或日志。 */
    public Path verifiedPrimary(String key,long size,String digest) { if(!verified(key,size,digest)) throw SystemFailure.STORAGE_INTEGRITY_FAILED.fault(); return path(first,key); }
    /** 合并输入清单的一片，键和摘要均来自已验证的受控数据库元数据。 */
    public record SourcePart(
        /** 服务端生成的内部键。 */ String key,
        /** 固定的分片字节数。 */ long size,
        /** 接收时核验的分片摘要。 */ String sha256) { }
    /** 合并完整原件到新的双份不可变键；每片及完整内容均重新校验，不修改输入分片。 */
    public String assemble(List<SourcePart> parts,long expectedBytes,String expectedHash) {
        bind(); if(parts.isEmpty() || parts.size()>640) throw ApiFault.invalid("合并分片数量不正确");
        String key=UUID.randomUUID().toString(); Path a=path(first,key),b=path(second,key); boolean ownA=false,ownB=false;
        try {
            MessageDigest full=MessageDigest.getInstance("SHA-256"); long total=0;
            try(FileChannel outA=FileChannel.open(a,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                ownA=true;
                try(FileChannel outB=FileChannel.open(b,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                    ownB=true; byte[] block=new byte[65536];
                    for(SourcePart part:parts) {
                        UploadRules.sha(part.sha256());
                        if(part.size()<=0 || part.size()>UploadRules.PART_SIZE || !verified(part.key(),part.size(),part.sha256())) throw SystemFailure.STORAGE_INTEGRITY_FAILED.fault();
                        MessageDigest partDigest=MessageDigest.getInstance("SHA-256"); long read=0;
                        try(InputStream in=Files.newInputStream(path(first,part.key()))) {
                            int n; while((n=in.read(block))!=-1) {
                                read+=n; total+=n;
                                if(read>part.size() || total>expectedBytes) throw new ApiFault(422,"HASH_OR_PART_MISMATCH","合并文件长度不一致");
                                partDigest.update(block,0,n); full.update(block,0,n); write(outA,block,n); write(outB,block,n);
                            }
                        }
                        if(read!=part.size() || !Crypto.equal(HexFormat.of().formatHex(partDigest.digest()),part.sha256())) throw new ApiFault(422,"HASH_OR_PART_MISMATCH","合并分片内容不一致");
                    }
                    if(total!=expectedBytes || !Crypto.equal(HexFormat.of().formatHex(full.digest()),expectedHash)) throw new ApiFault(422,"HASH_OR_PART_MISMATCH","完整文件长度或摘要不一致");
                    outA.force(true); outB.force(true);
                }
            }
            if(!verified(key,expectedBytes,expectedHash)) throw SystemFailure.STORAGE_INTEGRITY_FAILED.fault();
            return key;
        } catch(ApiFault ex) { cleanup(a,ownA); cleanup(b,ownB); throw ex; }
        catch(Exception ex) { cleanup(a,ownA); cleanup(b,ownB); throw SystemFailure.STORAGE_WRITE_FAILED.fault(ex); }
    }
    /** 仅在对应新建数据库事务回滚时删除该次生成的唯一键，不扫描或清理历史文件。 */
    public void rollbackNew(String key) { try { Files.deleteIfExists(path(first,key)); Files.deleteIfExists(path(second,key)); } catch(Exception ignored) { /* 保留无引用孤儿等待受控清理，不影响旧数据。 */ } }
    /** 仅供派生缓存库使用：实例核验后删除明确键，两侧均不存在才确认清理成功。 */
    public boolean purgeDerived(String key) {bind();try {Files.deleteIfExists(path(first,key));Files.deleteIfExists(path(second,key));return !Files.exists(path(first,key),LinkOption.NOFOLLOW_LINKS)&&!Files.exists(path(second,key),LinkOption.NOFOLLOW_LINKS);}catch(IOException failure){return false;}}
    /** 存储键只接受服务端生成的标准 UUID，用户文件名永不进入路径。 */
    private Path path(Path root,String key) { if(!UUID.fromString(key).toString().equals(key)) throw SystemFailure.STORAGE_INTEGRITY_FAILED.fault(); return root.resolve(key); }
    /** 完整写入当前块，防止 FileChannel 的短写被误认为成功。 */
    private void write(FileChannel out,byte[] block,int length) throws IOException { ByteBuffer data=ByteBuffer.wrap(block,0,length); while(data.hasRemaining()) out.write(data); }
    /** 固定内存计算磁盘内容摘要。 */
    private String hash(Path path) throws IOException,GeneralSecurityException {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] block=new byte[65536];
        try(InputStream in=Files.newInputStream(path)) { int n; while((n=in.read(block))!=-1) digest.update(block,0,n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    /** 只清理本方法确实创建的新文件。 */
    private void cleanup(Path path,boolean owned) { if(owned) try { Files.deleteIfExists(path); } catch(IOException ignored) { /* 留待受控孤儿核查。 */ } }
}
