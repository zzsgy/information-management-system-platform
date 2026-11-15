package com.company.ims.archive;

import com.company.ims.shared.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 本地/NAS 文件系统适配器；随机文件键和真实路径检查阻止穿越或覆盖。 */
@Component
public class LocalAttachmentStorage implements AttachmentStorage {
    /** 外置可写根目录，不能放在随版本替换的程序目录中。 */
    private final Path root;
    /** 初始化存储目录，失败时阻止服务启动。 */
    public LocalAttachmentStorage(@Value("${ims.storage-root}") String directory) throws IOException {root=Path.of(directory).toAbsolutePath().normalize();Files.createDirectories(root);}
    /** 分块落盘并同步计算SHA256；失败时清理未完成文件。 */
    public StoredFile save(long archiveId,String filename,InputStream content) throws IOException {
        // 路径仅由服务器生成的标识构成，原始文件名不会进入存储键。
        String key=archiveId+"/"+UUID.randomUUID();
        // 经过规范化和存储范围验证的实际文件路径。
        Path path=resolve(key);
        Files.createDirectories(path.getParent());
        // 创建父目录后再次检查真实路径，防止符号链接跳出存储根目录。
        path=resolve(key);
        // 本次读取的数量或内容字节数，受调用路径限制。
        long size=0;
        // 随数据流增量计算的SHA256摘要，验证附件内容完整性。
        MessageDigest digest;
        try {digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
        try(OutputStream output=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)) {
            // 固定大小的流式缓冲区，避免整份附件一次性进入内存。
            byte[] buffer=new byte[65536];
            // 本次流读取的有效字节数，负值表示结束。
            int count;
            while((count=content.read(buffer))!=-1){output.write(buffer,0,count);digest.update(buffer,0,count);size+=count;}
        }catch(IOException|RuntimeException error){Files.deleteIfExists(path);throw error;}
        return new StoredFile(key,size,HexFormat.of().formatHex(digest.digest()));
    }
    /** 检查规范路径及已存在祖先的真实位置，禁止符号链接逃逸。 */
    public Path resolve(String key) throws IOException {
        // 经过规范化和存储范围验证的实际文件路径。
        Path path=root.resolve(key).normalize();
        if(!path.startsWith(root)||path.equals(root)) throw new BusinessException(400,"附件存储路径不合法");
        // 用于验证的已有配置或最近存在的路径祖先。
        Path existing=path;
        while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) existing=existing.getParent();
        if(!existing.toRealPath().startsWith(root.toRealPath())) throw new BusinessException(400,"附件路径超出存储范围");
        return path;
    }
    /** 清理事务失败的附件，不处理已经入库的历史版本。 */
    public void remove(String key) throws IOException {Files.deleteIfExists(resolve(key));}
    /** 返回备份使用的根目录。 */
    public Path root(){return root;}
}
