package com.company.render;

import org.junit.jupiter.api.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真正解析输出PDF，验证页序、方向、水印像素与原件只读，不以字节前缀代替格式验证。 */
class UsagePdfWorkerTest {
    /** 横竖版合成原件与私有计划，目录中不含正式业务内容。 */
    @Test void outputFlattensPagesAndExpandsApprovedCopies() throws Exception {
        Path root=Files.createTempDirectory("usage-worker-test"),source=root.resolve("original.pdf"),plan=root.resolve("plan.properties"),copy=root.resolve("copy.pdf");
        try {
            try(PDDocument pdf=new PDDocument()){pdf.addPage(new PDPage(PDRectangle.A4));PDPage horizontal=new PDPage(new PDRectangle(PDRectangle.A4.getHeight(),PDRectangle.A4.getWidth()));pdf.addPage(horizontal);try(PDPageContentStream content=new PDPageContentStream(pdf,horizontal)){content.setNonStrokingColor(java.awt.Color.BLACK);content.addRect(0,0,horizontal.getMediaBox().getWidth(),12);content.fill();}pdf.save(source.toFile());}
            byte[] original=Files.readAllBytes(source);Properties stamp=new Properties();stamp.setProperty("pages","2,1");stamp.setProperty("copies","2");stamp.setProperty("name","合成验收员工".repeat(16));stamp.setProperty("employeeNo","TEST-20261028");stamp.setProperty("fileNo","BLK27-F-TEST");stamp.setProperty("at","2026-10-28 10:00:00 +08:00");stamp.setProperty("usage",UUID.randomUUID().toString());stamp.setProperty("action","PRINT");
            try(Writer writer=Files.newBufferedWriter(plan)){stamp.store(writer,"合成批准打印计划");}try(OutputStream output=Files.newOutputStream(copy)){UsagePdfWorker.export(source,plan,output);}
            assertArrayEquals(original,Files.readAllBytes(source));try(PDDocument pdf=Loader.loadPDF(copy.toFile())){assertEquals(4,pdf.getNumberOfPages());assertTrue(pdf.getPage(0).getMediaBox().getWidth()>pdf.getPage(0).getMediaBox().getHeight());assertTrue(pdf.getPage(1).getMediaBox().getWidth()<pdf.getPage(1).getMediaBox().getHeight());assertNull(pdf.getDocumentCatalog().getAcroForm());assertNull(pdf.getDocumentCatalog().getNames());assertTrue(pdf.getSignatureDictionaries().isEmpty());}
            var image=PdfPageWorker.render(copy,1,72);int colored=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)&0xffffff)!=0xffffff)colored++;assertTrue(colored>1000,"空白原页输出应含实际可见水印像素");
            assertTrue((image.getRGB(100,590)&0xffffff)<0x505050,"原页底部黑色正文标记应保留在新增页脚上方");
            String evidence=System.getenv("IMS_WORKER_EVIDENCE");if(evidence!=null){Path dir=Path.of(evidence);Files.copy(copy,dir.resolve("orientation-watermark.pdf"),StandardCopyOption.REPLACE_EXISTING);javax.imageio.ImageIO.write(image,"png",dir.resolve("orientation-watermark.png").toFile());}
            stamp.setProperty("unwatermarked","true");
            stamp.setProperty("pages","1");
            stamp.setProperty("copies","1");
            try(Writer writer=Files.newBufferedWriter(plan)){stamp.store(writer,"合成无水印批准计划");}
            try(OutputStream output=Files.newOutputStream(copy)){UsagePdfWorker.export(source,plan,output);}
            var plain=PdfPageWorker.render(copy,1,72);
            var blank=PdfPageWorker.render(source,1,72);
            assertEquals(blank.getWidth(),plain.getWidth(),1);
            assertEquals(blank.getHeight(),plain.getHeight(),1);
            assertTrue(Arrays.stream(plain.getRGB(0,0,plain.getWidth(),plain.getHeight(),null,0,plain.getWidth()))
                .allMatch(pixel->(pixel&0xffffff)==0xffffff),"无水印输出须保留空白页，不生成个人标记或页脚");
            assertArrayEquals(original,Files.readAllBytes(source));
        }finally{for(Path path:List.of(copy,plan,source))Files.deleteIfExists(path);Files.deleteIfExists(root);}
    }
}
