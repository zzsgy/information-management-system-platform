package com.company.ims.archive;

import java.io.*;
import java.nio.file.Path;

/** 附件存储端口；未来公盘/NAS/对象存储通过适配器接入，业务不依赖绝对路径。 */
public interface AttachmentStorage {
    /** 保存流并计算完整性元数据，filename 只作显示名，不能决定磁盘路径。 */
    StoredFile save(long archiveId,String filename,InputStream content) throws IOException;
    /** 安全解析存储键，仅供服务器读取自身管理的文件。 */
    Path resolve(String storageKey) throws IOException;
    /** 回滚未提交附件，禁止删除存储目录外的对象。 */
    void remove(String storageKey) throws IOException;
    /** 返回实际存储根目录，供备份和完整性验证使用。 */
    Path root();
    /** 保存后的不可变结果；key为相对路径，size为字节数，sha256为内容摘要。 */
    record StoredFile(String key,long size,String sha256) {}
}
