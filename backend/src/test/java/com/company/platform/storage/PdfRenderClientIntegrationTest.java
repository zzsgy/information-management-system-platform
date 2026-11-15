package com.company.platform.storage;

import com.company.platform.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 手动显式启用的独立进程集成测试，输入必须是人工生成的合成 PDF。 */
@EnabledIfEnvironmentVariable(named="IMS_RENDERER_INTEGRATION",matches="yes")
class PdfRenderClientIntegrationTest {
    @TempDir Path temporary;
    /** 真实子进程必须返回重编码PNG；不能把检测通过的原图直接回传为受控页面。 */
    @Test void rendersImageInIsolatedWorkerWithFixedDigest() throws Exception {
        Path sample=temporary.resolve("synthetic.png");var picture=new java.awt.image.BufferedImage(80,40,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=picture.createGraphics();try {graphics.setColor(java.awt.Color.BLUE);graphics.fillRect(0,0,80,40);}finally {graphics.dispose();}
        ImageIO.write(picture,"png",sample.toFile());String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sample)));
        var client=new PdfRenderClient(Path.of(System.getenv("JAVA_HOME"),"bin","java.exe"),Path.of(System.getenv("IMS_RENDERER_TEST_JAR")),temporary,Duration.ofSeconds(20));
        assertEquals(1,client.imagePageCount(sample,sha));var rendered=ImageIO.read(new ByteArrayInputStream(client.renderImagePage(sample,sha,1)));
        assertEquals(80,rendered.getWidth());assertEquals(java.awt.Color.BLUE.getRGB(),rendered.getRGB(20,20));
        assertThrows(ApiFault.class,()->client.renderImagePage(sample,sha,2));
        Files.write(sample,new byte[]{1},StandardOpenOption.APPEND);assertThrows(ApiFault.class,()->client.renderImagePage(sample,sha,1));
    }
    /** 不向测试日志输出文件路径或文档正文。 */
    @Test void rendersIsolatedPageWithPerUserWatermarkAndRejectsBadDigest() throws Exception {
        Path fixture=Path.of(System.getenv("IMS_RENDERER_TEST_PDF")), worker=Path.of(System.getenv("IMS_RENDERER_TEST_JAR"));
        Path sample=Files.copy(fixture,temporary.resolve("synthetic page with spaces.pdf"));
        Path java=Path.of(System.getenv("JAVA_HOME"),"bin","java.exe");
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sample)));
        var client=new PdfRenderClient(java,worker,temporary,Duration.ofSeconds(20));
        Instant now=Instant.parse("2026-10-27T08:00:00Z");
        byte[] first=client.render(sample,sha,1,72,new WatermarkedPage.Stamp("测试人员甲","TEST001","FILE-001",now));
        byte[] second=client.render(sample,sha,1,72,new WatermarkedPage.Stamp("测试人员乙","TEST002","FILE-001",now));
        assertNotNull(ImageIO.read(new ByteArrayInputStream(first))); assertFalse(Arrays.equals(first,second));
        assertFalse(Arrays.equals(first,Files.readAllBytes(sample)));
        assertThrows(ApiFault.class,()->client.render(sample,"0".repeat(64),1,72,new WatermarkedPage.Stamp("测试人员甲","TEST001","FILE-001",now)));
        var veryShort=new PdfRenderClient(java,worker,temporary,Duration.ofNanos(1));
        assertThrows(ApiFault.class,()->veryShort.render(sample,sha,1,72,new WatermarkedPage.Stamp("测试人员甲","TEST001","FILE-001",now)));
        if(System.getenv("IMS_RENDERER_QA_OUTPUT")!=null) Files.write(Path.of(System.getenv("IMS_RENDERER_QA_OUTPUT")),first,StandardOpenOption.CREATE_NEW);
    }
}
