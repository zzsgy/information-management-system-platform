package com.company.platform.storage;

import com.company.platform.*;
import com.company.platform.intake.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实XLSX容器验证六列及五列电子清单，不复制生产解析逻辑。 */
class ArchiveChecklistWorkbookTest {
    @TempDir Path root;
    @Test void originalSixColumnsKeepPaperCopiesAndNumericSequence()throws Exception{
        var rows=ArchiveChecklistWorkbook.read(file(workbook(true,List.of(List.of("BOX-1","10","2025.4.17","房屋租赁合同","8","原件八份"),List.of("BOX-1","2","45923","另一合同","1","")))));
        assertEquals(2,rows.size());assertEquals(10,rows.getFirst().sequence());assertEquals(8,rows.getFirst().paperCopies());assertEquals("2025-04-17",rows.getFirst().date().toString());assertEquals("2025-09-23",rows.getLast().date().toString());
    }
    @Test void fiveColumnsAndBlankDateAreAccepted()throws Exception{var rows=ArchiveChecklistWorkbook.read(file(workbook(false,List.of(List.of("1","","合同","45","")))));assertNull(rows.getFirst().boxNumber());assertNull(rows.getFirst().date());assertEquals(45,rows.getFirst().paperCopies());}
    @Test void duplicateSequenceAndInvalidCopiesRejectWholeChecklist()throws Exception{assertThrows(ApiFault.class,()->ArchiveChecklistWorkbook.read(file(workbook(false,List.of(List.of("1","","甲","1",""),List.of("1","","乙","2",""))))));assertThrows(ApiFault.class,()->ArchiveChecklistWorkbook.read(file(workbook(false,List.of(List.of("1","","甲","0",""))))));}
    @Test void invalidDateAndEmptyListAreNotSuccess()throws Exception{assertThrows(ApiFault.class,()->ArchiveChecklistWorkbook.read(file(workbook(false,List.of(List.of("1","2025-02-30","甲","1",""))))));assertThrows(ApiFault.class,()->ArchiveChecklistWorkbook.read(file(workbook(false,List.of()))));}
    @Test void leadingBlankColumnAndExcelDecimalIntegersAreAccepted()throws Exception{var rows=ArchiveChecklistWorkbook.read(file(workbook(false,List.of(List.of("2.0","2025.4.17","合同","45.0","")),2)));assertEquals(2,rows.getFirst().sequence());assertEquals(45,rows.getFirst().paperCopies());assertThrows(ApiFault.class,()->ArchiveChecklistWorkbook.read(file(workbook(false,List.of(List.of("2.5","","合同","1",""))))));}
    /** 合同清单八列全部读取；年份保留精度，不补成月日或误转为Excel序号日期。 */
    @Test void contractColumnsAndYearOnlyDateArePreserved()throws Exception{
        var rows=ArchiveChecklistWorkbook.read(file(workbook(List.of("存放编号","序号","日期","签订主体","存放内容","合同编号","存档份数","备注"),List.of(List.of("BOX-1","1","2021","合成主体","合同一","0001-A","2","原件"),List.of("BOX-1","2","2022.5.26","合成主体","合同二","A-2","1","原件")),1)));
        assertEquals(2,rows.size());assertNull(rows.getFirst().date());assertEquals("2021",rows.getFirst().year());assertEquals("合成主体",rows.getFirst().signingEntity());assertEquals("0001-A",rows.getFirst().contractNumber());assertEquals("BOX-1",rows.getFirst().boxNumber());assertEquals("2022-05-26",rows.getLast().date().toString());assertNull(rows.getLast().year());
    }
    @Test void columnsAreMatchedByHeaderRatherThanPosition()throws Exception{
        var rows=ArchiveChecklistWorkbook.read(file(workbook(List.of("备注","合同编号","序号","存档份数","存放内容","日期","签订主体"),List.of(List.of("原件","0002","10","45","合同","2021.0","合成主体")),2)));
        var entry=rows.getFirst();assertNull(entry.boxNumber());assertEquals(10,entry.sequence());assertEquals(45,entry.paperCopies());assertEquals("合同",entry.title());assertEquals("2021",entry.year());assertEquals("0002",entry.contractNumber());
    }
    @Test void optionalContractColumnsAndValuesMayBeBlank()throws Exception{
        var entry=ArchiveChecklistWorkbook.read(file(workbook(List.of("存放编号","序号","日期","签订主体","存放内容","合同编号","存档份数","备注"),List.of(List.of("","1","","","合同","","1","")),1))).getFirst();
        assertNull(entry.boxNumber());assertNull(entry.date());assertNull(entry.year());assertEquals("",entry.signingEntity());assertEquals("",entry.contractNumber());
    }
    @Test void missingDuplicateAndUnknownHeadersExplainWhichField()throws Exception{
        assertTrue(assertThrows(ApiFault.class,()->ArchiveChecklistWorkbook.read(file(workbook(List.of("序号","日期","存放内容","存档份数"),List.of(),1)))).getMessage().contains("备注"));
        assertTrue(assertThrows(ApiFault.class,()->ArchiveChecklistWorkbook.read(file(workbook(List.of("序号","日期","存放内容","存档份数","备注","备注"),List.of(),1)))).getMessage().contains("表头重复"));
        assertTrue(assertThrows(ApiFault.class,()->ArchiveChecklistWorkbook.read(file(workbook(List.of("序号","日期","存放内容","存档份数","备注","未知字段"),List.of(),1)))).getMessage().contains("未知字段"));
    }
    @Test void oversizedContractFieldRejectsTheExactSourceRow()throws Exception{
        var error=assertThrows(ApiFault.class,()->ArchiveChecklistWorkbook.read(file(workbook(List.of("序号","日期","存放内容","存档份数","备注","合同编号"),List.of(List.of("1","2021","合同","1","","A".repeat(257))),1))));assertTrue(error.getMessage().contains("第 2 行"));assertTrue(error.getMessage().contains("合同编号"));
    }
    private Path file(byte[] bytes)throws IOException{Path path=root.resolve(UUID.randomUUID()+".xlsx");Files.write(path,bytes);assertTrue(ImportWorkbook.validContainer(path));return path;}
    /** 合成工作表仅用于生成测试输入，包结构沿用真实台账导出器。 */
    static byte[] workbook(boolean includeBox,List<List<String>> rows)throws IOException{return workbook(includeBox,rows,1);}
    static byte[] workbook(boolean includeBox,List<List<String>> rows,int firstColumn)throws IOException{
        var headers=includeBox?List.of("存放编号","序号","日期","存放内容","存档份数","备注"):List.of("序号","日期","存放内容","存档份数","备注");
        return workbook(headers,rows,firstColumn);
    }
    static byte[] workbook(List<String> headers,List<List<String>> rows,int firstColumn)throws IOException{
        StringBuilder sheet=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");List<List<String>> all=new ArrayList<>();all.add(headers);all.addAll(rows);
        for(int i=0;i<all.size();i++){sheet.append("<row r=\"").append(i+1).append("\">");for(int c=0;c<all.get(i).size();c++){String value=all.get(i).get(c).replace("&","&amp;").replace("<","&lt;");sheet.append("<c r=\"").append((char)('A'+c+firstColumn-1)).append(i+1).append("\" t=\"inlineStr\"><is><t>").append(value).append("</t></is></c>");}sheet.append("</row>");}sheet.append("</sheetData></worksheet>");
        var result=new ByteArrayOutputStream();try(var in=new ZipInputStream(new ByteArrayInputStream(ArchiveBoxWorkbook.template()));var out=new ZipOutputStream(result)){ZipEntry entry;while((entry=in.getNextEntry())!=null){byte[] bytes=in.readAllBytes();out.putNextEntry(new ZipEntry(entry.getName()));out.write(entry.getName().equals("xl/worksheets/sheet1.xml")?sheet.toString().getBytes(StandardCharsets.UTF_8):bytes);out.closeEntry();}}return result.toByteArray();
    }
}
