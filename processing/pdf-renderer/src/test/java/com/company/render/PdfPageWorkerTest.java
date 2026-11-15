package com.company.render;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** 使用临时生成的合成 PDF，不读取实际业务档案。 */
class PdfPageWorkerTest {
    @TempDir Path root;
    /** 测试多页与页序，原文件摘要不能因读取而改变。 */
    @Test void rendersOnlyRequestedPageAndPreservesOriginal() throws Exception {
        Path source=root.resolve("sample.pdf");
        try(PDDocument document=new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(300,400)));
            document.addPage(new PDPage(new PDRectangle(400,300)));
            document.save(source.toFile());
        }
        byte[] before=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source));
        var first=PdfPageWorker.render(source,1,72); var second=PdfPageWorker.render(source,2,72);
        assertEquals(300,first.getWidth()); assertEquals(400,first.getHeight());
        assertEquals(400,second.getWidth()); assertEquals(300,second.getHeight());
        assertTrue(Arrays.equals(before,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
        assertThrows(java.io.IOException.class,()->PdfPageWorker.render(source,3,72));
    }
    /** 损坏、超大页面、非绝对路径及超范围 DPI 都不能返回渲染结果。 */
    @Test void rejectsMalformedAndUnboundedRequests() throws Exception {
        Path broken=root.resolve("broken.pdf"); Files.writeString(broken,"%PDF-malformed");
        assertThrows(java.io.IOException.class,()->PdfPageWorker.render(broken,1,72));
        Path huge=root.resolve("huge-page.pdf");
        try(PDDocument document=new PDDocument()) { document.addPage(new PDPage(new PDRectangle(20000,20000))); document.save(huge.toFile()); }
        assertThrows(java.io.IOException.class,()->PdfPageWorker.render(huge,1,72));
        assertThrows(java.io.IOException.class,()->PdfPageWorker.render(huge,1,300));
        assertThrows(java.io.IOException.class,()->PdfPageWorker.render(Path.of("relative.pdf"),1,72));
    }
}
