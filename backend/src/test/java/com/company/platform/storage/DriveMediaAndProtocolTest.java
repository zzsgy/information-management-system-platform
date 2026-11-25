package com.company.platform.storage;

import com.company.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.*;

class DriveMediaAndProtocolTest {
    @TempDir Path temporary;
    @Test void officeFormatRequiresMatchingMainPartAndNoMacros() throws Exception {
        Path valid=temporary.resolve("valid.docx");writeOffice(valid,false);
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",FileMediaCheck.detect(valid,"合同.docx","application/octet-stream"));
        assertNull(FileMediaCheck.detect(valid,"合同.xlsx","application/octet-stream"));
        Path macro=temporary.resolve("macro.docx");writeOffice(macro,true);assertNull(FileMediaCheck.detect(macro,"合同.docx","application/octet-stream"));
    }
    @Test void recognizesRelocatedWordMainPartAndMatchesPackageUris() throws Exception {
        Path file=temporary.resolve("非默认路径.docx");
        Files.write(file,relocatedWordPackage());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            FileMediaCheck.detect(file,"非默认路径.docx","application/octet-stream"));
        assertNull(FileMediaCheck.detect(file,"伪装为工作簿.xlsx","application/octet-stream"));
    }

    /** 用包关系定位主部件，覆盖不同路径、大小写及默认内容类型。 */
    static byte[] relocatedWordPackage() throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {
            var entries=Map.of(
                "[Content_Types].xml","""
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """,
                "_rels/.rels","""
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="/content/main.xml"/>
                    </Relationships>
                    """,
                "Content/Main.xml","""
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>合成验收文本</w:t></w:r></w:p></w:body>
                    </w:document>
                    """);
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    @Test void renamedZipAndBinaryTextAreRejected() throws Exception {
        Path file=temporary.resolve("plain.zip");try(var out=new ZipOutputStream(Files.newOutputStream(file))){out.putNextEntry(new ZipEntry("text.txt"));out.write("测试".getBytes(StandardCharsets.UTF_8));out.closeEntry();}
        assertEquals("application/zip",FileMediaCheck.detect(file,"文件.zip","application/octet-stream"));assertNull(FileMediaCheck.detect(file,"文件.docx","application/octet-stream"));
        Files.write(file,new byte[]{65,0,66});assertNull(FileMediaCheck.detect(file,"文件.txt","application/octet-stream"));Files.writeString(file,"中文文本\n合法内容");assertEquals("text/plain",FileMediaCheck.detect(file,"文件.txt","application/octet-stream"));
    }
    @Test void downloadSupportsOneRangeAndRejectsInvalidIntervals() {
        assertArrayEquals(new long[]{0,99,0},DriveLifecycleController.range(null,100));assertArrayEquals(new long[]{90,99,1},DriveLifecycleController.range("bytes=-10",100));assertArrayEquals(new long[]{20,99,1},DriveLifecycleController.range("bytes=20-",100));assertArrayEquals(new long[]{20,30,1},DriveLifecycleController.range("bytes=20-30",100));
        for(String value:List.of("bytes=100-","bytes=20-10","bytes=1-2,4-5","bytes=-0","bytes=-","bytes=999999999999999999999-"))assertEquals(416,assertThrows(ApiFault.class,()->DriveLifecycleController.range(value,100)).status);
    }
    @Test void callbackSignatureCannotReuseBrowserKeyOrExpiredToken(){
        String inbox="a".repeat(40),outbox="b".repeat(40);Map<String,Object> payload=Sql.map("key","synthetic-key","status",2,"exp",java.time.Instant.now().plusSeconds(60).getEpochSecond());
        assertEquals(2,OfficeGateway.verify(OfficeGateway.jwt(payload,outbox),outbox).get("status"));assertThrows(ApiFault.class,()->OfficeGateway.verify(OfficeGateway.jwt(payload,inbox),outbox));
        payload.put("exp",1);assertThrows(ApiFault.class,()->OfficeGateway.verify(OfficeGateway.jwt(payload,outbox),outbox));assertThrows(ApiFault.class,()->OfficeGateway.verify("eyJhbGciOiJub25lIn0.e30.",outbox));
    }
    /** 真实HTTP响应超过配置大小时，流式订阅拒绝结果并清理本次临时文件。 */
    @Test void candidateBodyLimitRejectsOversizedResult()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/cache/files/large",exchange->{byte[] bytes=new byte[32768];exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();
        try{Path inbox=temporary.resolve("in.key"),outbox=temporary.resolve("out.key"),results=temporary.resolve("results");Files.writeString(inbox,"i".repeat(40));Files.writeString(outbox,"o".repeat(40));String origin="http://127.0.0.1:"+server.getAddress().getPort();var gateway=new OfficeGateway(true,origin,origin,origin,"test",inbox.toString(),outbox.toString(),results.toString(),UUID.randomUUID().toString(),1024,false);
            assertEquals("OFFICE_CHECKPOINT_DISABLED",assertThrows(ApiFault.class,gateway::requireCheckpoint).code);
            assertThrows(java.io.IOException.class,()->gateway.candidate(origin+"/cache/files/large"));try(var files=Files.list(results)){assertEquals(0,files.count());}
        }finally{server.stop(0);}
    }
    private void writeOffice(Path path,boolean macro)throws Exception{try(var out=new ZipOutputStream(Files.newOutputStream(path))){for(var item:Map.of("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>","_rels/.rels","<Relationships/>","word/document.xml","<document/>").entrySet()){out.putNextEntry(new ZipEntry(item.getKey()));out.write(item.getValue().getBytes(StandardCharsets.UTF_8));out.closeEntry();}if(macro){out.putNextEntry(new ZipEntry("word/vbaProject.bin"));out.write(1);out.closeEntry();}}}
}
