package com.company.platform.storage;

import com.company.platform.*;
import com.company.platform.intake.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/** 原中文台账与生产导出器共同验收，不复制业务映射或使用业务库。 */
class ArchiveBoxWorkbookTest {
    @TempDir Path root;
    @Test void chineseMergedHeadersAndAllColumnsRoundTrip()throws Exception{
        var input=row();var path=file(ArchiveBoxWorkbook.workbook(List.of(input)));assertTrue(ImportWorkbook.validContainer(path));
        var rows=ImportWorkbook.read(path,"ARCHIVE");assertEquals(4,rows.getFirst().number());
        input.forEach((key,value)->assertEquals(value,rows.getFirst().values().get(key),key));
        assertEquals("BOX",rows.getFirst().values().get("objectType"));assertEquals(ArchiveBoxWorkbook.FORMAT,rows.getFirst().values().get("sourceFormat"));
        assertEquals(ImportWorkbook.MEDIA,FileMediaCheck.detect(path,"档案台账.xlsx",ImportWorkbook.MEDIA));
    }
    /** 新增纸质标签列可往返；旧十八列表头继续可读。 */
    @Test void paperTagsRoundTripAndLegacyColumnsRemainReadable()throws Exception {
        var values=row();values.put("preservationType","纸质+电子");values.put("paperTags","纸质原件、纸质复印件");
        byte[] bytes=ArchiveBoxWorkbook.workbook(List.of(values));
        assertEquals("纸质原件、纸质复印件",ImportWorkbook.read(file(bytes),"ARCHIVE").getFirst().values().get("paperTags"));
        var legacy=alter(bytes,"xl/worksheets/sheet1.xml",text->text.replaceAll("<c r=\"S[234]\"[^>]*>.*?</c>",""));
        assertFalse(ImportWorkbook.read(legacy,"ARCHIVE").getFirst().values().containsKey("paperTags"));
    }
    @Test void blankTemplateIsNotAZeroRowSuccess()throws Exception{var path=file(ArchiveBoxWorkbook.template());assertThrows(ApiFault.class,()->ImportWorkbook.read(path,"ARCHIVE"));}
    @Test void departmentsDoNotChangeTemplate()throws Exception{var path=file(ArchiveBoxWorkbook.workbook(List.of(row(),Sql.map("title","另一部门档案","departmentName","乙部门"))));assertEquals(2,ImportWorkbook.read(path,"ARCHIVE").size());}
    @Test void excelNumericDatesAndOriginalFormulaAreRecomputed()throws Exception{
        var input=row();input.put("archiveDate","45923");input.put("sourceExpiryDate","1");
        var path=alter(ArchiveBoxWorkbook.workbook(List.of(input)),"xl/worksheets/sheet1.xml",text->text.replace("<c r=\"O4\" s=\"2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">1</t></is></c>","<c r=\"O4\"><f>M4+365*N4</f><v>1</v></c>"));
        assertTrue(ImportWorkbook.validContainer(path));var values=ImportWorkbook.read(path,"ARCHIVE").getFirst().values();assertEquals("2025-09-23",values.get("archiveDate"));assertEquals("2035-09-21",values.get("sourceExpiryDate"));
    }
    @Test void arbitraryFormulaRejectedEvenWithCachedValue()throws Exception{var path=alter(ArchiveBoxWorkbook.workbook(List.of(row())),"xl/worksheets/sheet1.xml",text->text.replace("</row>","<c r=\"O4\"><f>WEBSERVICE(\"https://example.invalid\")</f><v>1</v></c></row>"));assertFalse(ImportWorkbook.validContainer(path));}
    @Test void changedChineseHeadersRejected()throws Exception{var path=alter(ArchiveBoxWorkbook.workbook(List.of(row())),"xl/worksheets/sheet1.xml",text->text.replace("保存份数","自行改名"));assertThrows(ApiFault.class,()->ImportWorkbook.read(path,"ARCHIVE"));}
    @Test void extraPopulatedColumnRejectedButBlankFormattingAllowed()throws Exception{
        byte[] bytes=ArchiveBoxWorkbook.workbook(List.of(row()));
        // 只在数据行追加空白样式格，避免造出标题行坐标不一致的反例。
        var blank=alter(bytes,"xl/worksheets/sheet1.xml",text->text.replace("<row r=\"4\">","<row r=\"4\"><c r=\"T4\"/>"));assertEquals(1,ImportWorkbook.read(blank,"ARCHIVE").size());
        var extra=alter(bytes,"xl/worksheets/sheet1.xml",text->text.replace("<row r=\"4\">","<row r=\"4\"><c r=\"S4\" t=\"inlineStr\"><is><t>额外内容</t></is></c>"));assertThrows(ApiFault.class,()->ImportWorkbook.read(extra,"ARCHIVE"));
    }
    @Test void thousandRowsAreAccepted()throws Exception{var path=file(ArchiveBoxWorkbook.workbook(Collections.nCopies(1000,row())));assertEquals(1000,ImportWorkbook.read(path,"ARCHIVE").size());assertThrows(ApiFault.class,()->ArchiveBoxWorkbook.workbook(Collections.nCopies(1001,row())));}
    /** 原件只读验证涵盖 WPS 共享字符串、数字日期、原公式、两行合并表头和人员参考表。 */
    @Test @EnabledIfEnvironmentVariable(named="IMS_ARCHIVE_LEDGER_SOURCE",matches=".+") void originalUserWorkbookIsRecognizedWithoutReadingReferenceAsAccounts()throws Exception{
        Path path=Path.of(System.getenv("IMS_ARCHIVE_LEDGER_SOURCE"));assertTrue(ImportWorkbook.validContainer(path));var rows=ImportWorkbook.read(path,"ARCHIVE");assertEquals(2,rows.size());assertEquals(4,rows.getFirst().number());assertEquals("45",rows.getFirst().values().get("copyCount"));assertEquals("40",rows.getLast().values().get("copyCount"));assertEquals("2025-09-23",rows.getFirst().values().get("archiveDate"));assertEquals("2035-09-21",rows.getFirst().values().get("sourceExpiryDate"));assertTrue(rows.stream().allMatch(row->row.values().get("objectType").equals("BOX")));
    }
    private Map<String,Object> row(){return Sql.map("originalNumber","OLD-001","title","合成档案盒","fileType","合同","departmentName","合成部门","ownerName","合成人员","cabinetNo","13","columnNo","A","layerNo","3","inventory","合同清单","preservationType","纸质原件","copyCount","45","classification","重要","archiveDate","2025-09-23","retentionCode","10","sourceExpiryDate","2035-09-21","sourceStatus","正常","notes","合成说明","sourceUpdatedAt","2026-11-03 10:00");}
    private Path file(byte[] bytes)throws IOException{var path=root.resolve(UUID.randomUUID()+".xlsx");Files.write(path,bytes);return path;}
    private Path alter(byte[] bytes,String name,java.util.function.UnaryOperator<String> change)throws IOException{var result=new ByteArrayOutputStream();try(var in=new ZipInputStream(new ByteArrayInputStream(bytes));var out=new ZipOutputStream(result)){ZipEntry entry;while((entry=in.getNextEntry())!=null){byte[] content=in.readAllBytes();out.putNextEntry(new ZipEntry(entry.getName()));out.write(entry.getName().equals(name)?change.apply(new String(content,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8):content);out.closeEntry();}}return file(result.toByteArray());}
}
