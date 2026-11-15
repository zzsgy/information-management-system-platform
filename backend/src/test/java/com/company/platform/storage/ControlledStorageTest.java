package com.company.platform.storage;

import com.company.platform.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独立文件目录和内存权限替身；不访问业务数据库，也不接触旧附件。 */
class ControlledStorageTest {
    /** JUnit 为每个测试创建独立临时目录。 */
    @TempDir Path root;
    /** 全局唯一实例用于绑定两个副本。 */
    private final UUID instance=UUID.randomUUID();
    /** 标准分片测试内容和相应摘要。 */
    private final byte[] bytes="受控测试内容".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final String digest=Crypto.hash("受控测试内容");

    /** 固定用途限额、路径字符、扩展名和媒体类型必须在创建会话前验证。 */
    @Test void validatesPurposeAndDeclaredSize() {
        var body=Sql.map("module","ARCHIVE","businessContextId",UUID.randomUUID().toString(),"originalName","档案.pdf","expectedSize",1073741824L,"expectedSha256",digest,"expectedMediaType","application/pdf","purpose","ARCHIVE_PDF");
        assertEquals(1073741824L,UploadRules.spec(body).size());
        body.put("expectedSize",1073741825L); assertEquals(413,assertThrows(ApiFault.class,()->UploadRules.spec(body)).status);
        body.put("expectedSize",20); body.put("originalName","../档案.pdf"); assertThrows(ApiFault.class,()->UploadRules.spec(body));
        body.put("originalName","档案.docx"); assertEquals(415,assertThrows(ApiFault.class,()->UploadRules.spec(body)).status);
        body.put("originalName","档案.pdf"); body.put("module","DRIVE"); assertThrows(ApiFault.class,()->UploadRules.spec(body));
        body.put("module","ARCHIVE"); body.put("purpose","EVIDENCE"); body.put("originalName","同意录音.m4a"); body.put("expectedMediaType","audio/mp4");
        assertEquals("EVIDENCE",UploadRules.spec(body).purpose());
    }
    /** 分片位置由固定大小推导；错误总长度、重叠区间和小数不能被接受。 */
    @Test void rejectsInconsistentRangesAndLengths() {
        long total=UploadRules.PART_SIZE+3;
        assertEquals(UploadRules.PART_SIZE,UploadRules.part(total,2,"bytes 8388608-8388610/8388611",3).offset());
        assertThrows(ApiFault.class,()->UploadRules.part(total,2,"bytes 8388607-8388610/8388611",4));
        assertThrows(ApiFault.class,()->UploadRules.part(total,3,"bytes 8388608-8388610/8388611",3));
        assertThrows(ApiFault.class,()->UploadRules.part(total,1,"bytes 0-8388607/8388611",3));
        assertEquals(640,UploadRules.count(5368709120L));
    }
    /** 正常写入后两份数据相同；任一副本损坏即视为不可用。 */
    @Test void copiesAndIndependentlyChecksBothReplicas() throws Exception {
        QuarantineFiles files=new QuarantineFiles(root.resolve("a"),root.resolve("b"),instance);
        String key=files.receive(new ByteArrayInputStream(bytes),bytes.length,digest);
        assertTrue(files.verified(key,bytes.length,digest));
        Files.writeString(root.resolve("b").resolve(key),"损坏");
        assertFalse(files.verified(key,bytes.length,digest));
        assertArrayEquals(bytes,Files.readAllBytes(root.resolve("a").resolve(key)));
    }
    /** 合并会产生全新的双副本键；任何分片损坏或完整摘要不一致都不能返回结果。 */
    @Test void assemblesImmutableCopiesAndRejectsTampering() throws Exception {
        QuarantineFiles files=new QuarantineFiles(root.resolve("a"),root.resolve("b"),instance);
        byte[] first="first".getBytes(java.nio.charset.StandardCharsets.UTF_8), second="second".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String a=files.receive(new ByteArrayInputStream(first),first.length,Crypto.hash("first"));
        String b=files.receive(new ByteArrayInputStream(second),second.length,Crypto.hash("second"));
        var parts=List.of(new QuarantineFiles.SourcePart(a,first.length,Crypto.hash("first")),new QuarantineFiles.SourcePart(b,second.length,Crypto.hash("second")));
        String merged=files.assemble(parts,first.length+second.length,Crypto.hash("firstsecond"));
        assertNotEquals(a,merged); assertNotEquals(b,merged); assertTrue(files.verified(merged,11,Crypto.hash("firstsecond")));
        assertThrows(ApiFault.class,()->files.assemble(parts,11,"0".repeat(64)));
        Files.writeString(root.resolve("b").resolve(b),"被修改");
        assertThrows(ApiFault.class,()->files.assemble(parts,11,Crypto.hash("firstsecond")));
        assertTrue(files.verified(merged,11,Crypto.hash("firstsecond")));
    }
    /** 摘要或实际长度不一致时删除本次新建的文件，目录和其他文件原样保留。 */
    @Test void badContentCleansOnlyAttemptFiles() throws Exception {
        QuarantineFiles files=new QuarantineFiles(root.resolve("a"),root.resolve("b"),instance);
        files.bind(); Files.writeString(root.resolve("a").resolve("existing"),"原有标记");
        assertEquals(422,assertThrows(ApiFault.class,()->files.receive(new ByteArrayInputStream(bytes),bytes.length,"0".repeat(64))).status);
        assertThrows(ApiFault.class,()->files.receive(new ByteArrayInputStream(bytes),bytes.length-1,digest));
        try(var first=Files.list(root.resolve("a")); var second=Files.list(root.resolve("b"))) {
            assertEquals(2,first.count()); assertEquals(1,second.count());
        }
        assertEquals("原有标记",Files.readString(root.resolve("a").resolve("existing")));
    }
    /** 非空外部目录、不同实例或嵌套副本不得被接管或覆盖。 */
    @Test void rejectsForeignDirectoriesAndInstanceMixing() throws Exception {
        Path a=root.resolve("a"),b=root.resolve("b"); Files.createDirectory(a); Files.writeString(a.resolve("old"),"保留");
        assertThrows(ApiFault.class,()->new QuarantineFiles(a,b,instance).bind());
        assertEquals("保留",Files.readString(a.resolve("old")));
        assertThrows(IllegalStateException.class,()->new QuarantineFiles(a,a.resolve("nested"),instance));
        QuarantineFiles good=new QuarantineFiles(root.resolve("c"),root.resolve("d"),instance); good.bind();
        assertThrows(ApiFault.class,()->new QuarantineFiles(root.resolve("c"),root.resolve("d"),UUID.randomUUID()).bind());
    }
    /** 缺少领域策略或保护状态时拒绝；一个模块的能力不能转作另一模块权限。 */
    @Test void boundariesAreClosedWhenDomainOrProtectionMissing() {
        DefaultListableBeanFactory factory=new DefaultListableBeanFactory();
        StorageBoundaries boundaries=new StorageBoundaries(factory.getBeanProvider(StorageDomainPolicy.class),factory.getBeanProvider(StorageProtectionPolicy.class));
        Actor actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),1,false,Map.of(),List.of(),Set.of("PLATFORM.ROLES_MANAGE"),"");
        assertEquals(503,assertThrows(ApiFault.class,()->boundaries.require(actor,"ARCHIVE","ARCHIVE_PDF",UUID.randomUUID(),"UPLOAD_CREATE",true)).status);
        factory.registerSingleton("domain",new StorageDomainPolicy() {
            public boolean supports(String module,String purpose) { return module.equals("ARCHIVE") && purpose.equals("ARCHIVE_PDF"); }
            public void require(Actor person,String module,String purpose,UUID context,String action) { throw ApiFault.denied(); }
        });
        assertEquals(403,assertThrows(ApiFault.class,()->boundaries.require(actor,"ARCHIVE","ARCHIVE_PDF",UUID.randomUUID(),"UPLOAD_CREATE",true)).status);
        assertEquals(503,assertThrows(ApiFault.class,()->boundaries.require(actor,"DRIVE","DRIVE_FILE",UUID.randomUUID(),"UPLOAD_CREATE",true)).status);
    }
    /** 同一页面对不同人员生成不同的水印像素；不接受原 PDF 伪装成 PNG。 */
    @Test void createsUserSpecificWatermarkedPageWithoutServingOriginal() throws Exception {
        var original=new java.awt.image.BufferedImage(720,900,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=original.createGraphics(); graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0,0,720,900); graphics.dispose();
        var raw=new ByteArrayOutputStream(); javax.imageio.ImageIO.write(original,"png",raw); byte[] input=raw.toByteArray();
        var now=java.time.Instant.parse("2026-10-27T08:00:00Z");
        byte[] alice=WatermarkedPage.render(input,new WatermarkedPage.Stamp("张三","A001","FILE-1",now));
        byte[] bob=WatermarkedPage.render(input,new WatermarkedPage.Stamp("李四","A002","FILE-1",now));
        assertFalse(Arrays.equals(input,alice)); assertFalse(Arrays.equals(alice,bob));
        assertNotNull(javax.imageio.ImageIO.read(new ByteArrayInputStream(alice)));
        assertThrows(ApiFault.class,()->WatermarkedPage.render("%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.UTF_8),new WatermarkedPage.Stamp("张三","A001","FILE-1",now)));
        assertThrows(ApiFault.class,()->WatermarkedPage.render(input,new WatermarkedPage.Stamp("","A001","FILE-1",now)));
    }
}
