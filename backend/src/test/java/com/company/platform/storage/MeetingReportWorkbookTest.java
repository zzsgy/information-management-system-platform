package com.company.platform.storage;

import com.company.platform.ApiFault;
import com.company.platform.intake.MeetingReportWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实模板压缩包验证 Excel 分行填写兼容性，不连接业务数据库。 */
class MeetingReportWorkbookTest {
    @Test
    void mergedRowsAndSplitColumnsImportInWorksheetOrder() throws Exception {
        UUID meeting = UUID.randomUUID();
        byte[] workbook = filled(meeting, Map.of(
                "xl/worksheets/sheet1.xml", "<row r=\"1\">" + cell("A1", "上周工作内容") + "</row>"
                        + "<row r=\"10\">" + cell("A10", "最终核算") + "</row>"
                        + "<row r=\"2\">" + cell("A2", "1、生产清洗\n2、精馏处理") + "</row>"
                        + "<row r=\"3\">" + cell("A3", "分装产品：14 个批次") + "</row>"
                        + "<row r=\"4\">" + cell("A4", "液废入库：27.06 吨\n库存：9.58 吨") + "</row>"
                        + "<row r=\"6\">" + cell("H6", "完成审核") + cell("A6", "安装资料") + "</row>",
                "xl/worksheets/sheet2.xml", "<row r=\"1\">" + cell("A1", "本周工作计划") + "</row>"
                        + "<row r=\"2\">" + cell("A2", "核对订单") + "</row>"
                        + "<row r=\"7\">" + cell("A7", "完成物料核算") + "</row>"));

        var report = MeetingReportWorkbook.read(workbook, meeting);
        assertEquals("张三 BLK27001", report.author());
        assertEquals("1、生产清洗\n2、精馏处理\n分装产品：14 个批次\n液废入库：27.06 吨\n库存：9.58 吨\n安装资料\t完成审核\n最终核算", report.work());
        assertEquals("核对订单\n完成物料核算", report.plan());
    }

    @Test
    void originalSingleCellAndBlankPlanStillImport() throws Exception {
        UUID meeting = UUID.randomUUID();
        var report = MeetingReportWorkbook.read(filled(meeting, Map.of(
                "xl/worksheets/sheet1.xml", "<row r=\"2\">" + cell("A2", "原有内容\n第二段") + "</row>")), meeting);
        assertEquals("原有内容\n第二段", report.work());
        assertNull(report.plan());
    }

    @Test
    void reportsIdentifyOutOfRangeDuplicateAndFormulaCells() throws Exception {
        UUID meeting = UUID.randomUUID();
        for (var example : Map.of(
                cell("I3", "超出列"), "I3 超出填写范围",
                cell("A1002", "超出行"), "A1002 超出填写范围",
                cell("A3", "第一份") + cell("A3", "第二份"), "A3 存在重复单元格").entrySet()) {
            byte[] workbook = filled(meeting, Map.of("xl/worksheets/sheet1.xml",
                    "<row r=\"3\">" + example.getKey() + "</row>"));
            assertTrue(assertThrows(ApiFault.class, () -> MeetingReportWorkbook.read(workbook, meeting))
                    .getMessage().startsWith("「上周工作内容」" + example.getValue()));
        }
        byte[] formula = filled(meeting, Map.of("xl/worksheets/sheet1.xml",
                "<row r=\"3\"><c r=\"A3\"><f>1+1</f><v>2</v></c></row>"));
        assertEquals(400, assertThrows(ApiFault.class, () -> MeetingReportWorkbook.read(formula, meeting)).status);
    }

    @Test
    void extraRowsStillRequireMeetingBindingAndEmployeeIdentity() throws Exception {
        UUID meeting = UUID.randomUUID();
        byte[] workbook = filled(meeting, Map.of("xl/worksheets/sheet1.xml",
                "<row r=\"7\">" + cell("A7", "工作内容") + "</row>"));
        assertTrue(assertThrows(ApiFault.class, () -> MeetingReportWorkbook.read(workbook, UUID.randomUUID()))
                .getMessage().contains("不属于本场会议"));
        byte[] missingAuthor = filled(meeting, Map.of("xl/worksheets/sheet3.xml", "<row r=\"2\"/>"));
        assertTrue(assertThrows(ApiFault.class, () -> MeetingReportWorkbook.read(missingAuthor, meeting))
                .getMessage().contains("F2 的填报人+工号"));
    }

    /** 仅替换指定页的正文行，保留模板的会议标识、表头、样式及合并设置。 */
    private byte[] filled(UUID meeting, Map<String, String> sheetData) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var source = new ZipInputStream(new ByteArrayInputStream(MeetingReportWorkbook.template(meeting)));
                var output = new ZipOutputStream(bytes)) {
            for (ZipEntry entry; (entry = source.getNextEntry()) != null;) {
                String xml = new String(source.readAllBytes(), StandardCharsets.UTF_8);
                if (sheetData.containsKey(entry.getName())) {
                    String data = sheetData.get(entry.getName());
                    if (entry.getName().endsWith("sheet3.xml")) {
                        int headerEnd = xml.indexOf("</row>") + "</row>".length();
                        xml = xml.substring(0, headerEnd) + data + xml.substring(xml.indexOf("</sheetData>"));
                    } else {
                        int start = xml.indexOf("<sheetData>") + "<sheetData>".length();
                        xml = xml.substring(0, start) + data + xml.substring(xml.indexOf("</sheetData>"));
                    }
                } else if (entry.getName().endsWith("sheet3.xml")) {
                    xml = xml.replace("<row r=\"2\">", "<row r=\"2\">" + cell("F2", "张三 BLK27001"));
                }
                output.putNextEntry(new ZipEntry(entry.getName()));
                output.write(xml.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private String cell(String ref, String text) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t>" + text + "</t></is></c>";
    }
}
