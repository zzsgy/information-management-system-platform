package com.company.platform.storage;

import com.company.platform.*;
import com.company.platform.intake.ImportWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

/** XLSX 输入边界使用实际压缩包与 XML，不以文件扩展名替代内容验证。 */
class ImportWorkbookTest {
    /** 仅测试临时目录，不接触业务附件。 */
    @TempDir Path root;
    /** 保留工号前导零及原表行号。 */
    @Test void textIdentifiersAndLineNumbers()throws Exception{
        var input=Sql.map("employeeNo","00001","name","员工甲","departmentCode","行政部",
            "jobTitle","工程师","supervisorEmployeeNo","00002","mobilePhone","+86 13800000000",
            "contactEmail","contact@example.com","companyEmail","a@biolink.com");
        Path file=file(ImportWorkbook.workbook("EMPLOYEE",List.of(input)));
        assertTrue(ImportWorkbook.validContainer(file));
        var row=ImportWorkbook.read(file,"EMPLOYEE").getFirst();
        assertEquals(2,row.number());
        assertEquals(input,row.values());
        assertEquals(ImportWorkbook.MEDIA,FileMediaCheck.detect(file,"台账.xlsx",ImportWorkbook.MEDIA));
    }
    /** 会议识别码隐藏后仍能导入，旧的可见识别行也继续兼容。 */
    @Test void meetingMarkerIsHiddenAndKeepsImportBinding()throws Exception{
        String marker="IMS-MEETING-1:"+UUID.randomUUID();
        var columns=List.of("employeeNo|工号","report|上周工作内容");
        var values=List.of(Sql.map("employeeNo","00001","report","本周内容"));
        byte[] bytes=ImportWorkbook.meetingWorkbook("会议填报",marker,columns,values);
        String sheet;
        try(var zip=new ZipFile(file(bytes).toFile())){
            sheet=new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(sheet.contains("<row hidden=\"1\" r=\"1\">"));
        assertTrue(sheet.contains("activeCell=\"A2\""));
        assertEquals(values.getFirst(),ImportWorkbook.readMeeting(bytes,marker,columns).getFirst().values());
        assertThrows(ApiFault.class,()->ImportWorkbook.readMeeting(bytes,"IMS-MEETING-1:"+UUID.randomUUID(),columns));
        var legacy=new ByteArrayOutputStream();
        try(var input=new ZipInputStream(new ByteArrayInputStream(bytes));var output=new ZipOutputStream(legacy)){
            ZipEntry entry;
            while((entry=input.getNextEntry())!=null){
                output.putNextEntry(new ZipEntry(entry.getName()));
                byte[] content=input.readAllBytes();
                if(entry.getName().equals("xl/worksheets/sheet1.xml"))content=sheet.replace(" hidden=\"1\"", "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                output.write(content);output.closeEntry();
            }
        }
        assertEquals(values.getFirst(),ImportWorkbook.readMeeting(legacy.toByteArray(),marker,columns).getFirst().values());
    }
    /** 跨类型模板不能被误读为另一个领域。 */
    @Test void wrongTypeRejected()throws Exception{Path file=file(ImportWorkbook.workbook("EMPLOYEE",List.of(Sql.map("employeeNo","1"))));assertThrows(ApiFault.class,()->ImportWorkbook.read(file,"ARCHIVE"));}
    /** 空模板不是成功的零行批次。 */
    @Test void emptyWorkbookRejected()throws Exception{Path file=file(ImportWorkbook.template("EMPLOYEE"));assertThrows(ApiFault.class,()->ImportWorkbook.read(file,"EMPLOYEE"));}
    /** 相同文本以文本单元格保存，前导等号不会成为公式。 */
    @Test void leadingEqualsRemainsText()throws Exception{Path file=file(ImportWorkbook.workbook("EMPLOYEE",List.of(Sql.map("name","=1+1"))));assertEquals("=1+1",ImportWorkbook.read(file,"EMPLOYEE").getFirst().values().get("name"));}
    /** 公式即使带缓存值也不能通过检测。 */
    @Test void formulaRejected()throws Exception{Path file=alter("xl/worksheets/sheet1.xml",text->text.replace("<is><t", "<f>1+1</f><is><t"));assertFalse(ImportWorkbook.validContainer(file));}
    /** 外部关系不能触发读取本地路径或网络。 */
    @Test void externalLinkRejected()throws Exception{Path file=alter("xl/_rels/workbook.xml.rels",text->text.replace("Target=", "TargetMode=\"External\" Target="));assertFalse(ImportWorkbook.validContainer(file));}
    /** 重复单元格不允许覆盖最先读取的值。 */
    @Test void duplicateCellRejected()throws Exception{Path file=alter("xl/worksheets/sheet1.xml",text->text.replace("</row>","<c r=\"A1\" t=\"inlineStr\"><is><t>篡改</t></is></c></row>"));assertThrows(ApiFault.class,()->ImportWorkbook.read(file,"EMPLOYEE"));}
    /** 禁止多表误导，以免隐藏表内容未被预检。 */
    @Test void hiddenSheetRejected()throws Exception{Path file=alter("xl/workbook.xml",text->text.replace("</sheets>","<sheet name=\"隐藏\" sheetId=\"2\" state=\"hidden\"/></sheets>"));assertFalse(ImportWorkbook.validContainer(file));}
    /** 台账生成与解析均采用同一千行上限。 */
    @Test void rowLimit()throws Exception{assertThrows(ApiFault.class,()->ImportWorkbook.workbook("EMPLOYEE",Collections.nCopies(1001,Sql.map("name","测试"))));Path file=file(ImportWorkbook.workbook("EMPLOYEE",Collections.nCopies(1000,Sql.map("name","测试"))));assertEquals(1000,ImportWorkbook.read(file,"EMPLOYEE").size());}
    /** 错误媒体声明不能把合法台账当成 PDF。 */
    @Test void mediaMismatchRejected()throws Exception{Path file=file(ImportWorkbook.template("EMPLOYEE"));assertNull(FileMediaCheck.detect(file,"假文件.pdf","application/pdf"));}
    /** 每个样本使用独立随机文件名。 */
    private Path file(byte[] bytes)throws Exception{Path path=root.resolve(UUID.randomUUID()+".xlsx");Files.write(path,bytes);return path;}
    /** 在测试包的指定 XML 中构造单一边界反例。 */
    private Path alter(String target,java.util.function.UnaryOperator<String> transform)throws Exception{byte[] original=ImportWorkbook.workbook("EMPLOYEE",List.of(Sql.map("employeeNo","1")));var bytes=new ByteArrayOutputStream();try(var input=new ZipInputStream(new ByteArrayInputStream(original));var output=new ZipOutputStream(bytes)){ZipEntry entry;while((entry=input.getNextEntry())!=null){byte[] content=input.readAllBytes();output.putNextEntry(new ZipEntry(entry.getName()));output.write(entry.getName().equals(target)?transform.apply(new String(content,java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8):content);output.closeEntry();}}return file(bytes.toByteArray());}
}
