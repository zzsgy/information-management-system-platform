package com.company.platform.intake;

import com.company.platform.ApiFault;
import com.company.platform.Inputs;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipOutputStream;

import static com.company.platform.intake.ImportWorkbook.*;

/** 三页会议填报表。一份文件对应一位员工，协调页 F2 为固定身份入口。 */
public final class MeetingReportWorkbook {
    private static final List<String> SHEETS = List.of("上周工作内容", "本周工作计划", "协调事宜");
    private static final List<String> HEADERS = List.of("序号", "具体事宜", "需协调 / 配合什么",
            "期望结果 /验收标准", "填报部门", "填报人+工号", "配合部门", "责任人（唯一，姓名+工号）", "事项类别");
    private static final List<String> KEYS = List.of("sequence", "subject", "support", "expected",
            "authorDepartment", "author", "department", "assignee", "category");
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String SHEET_PATH = "xl/worksheets/sheet";
    private static final String MARKER = "_IMS_Meeting";

    private MeetingReportWorkbook() {}

    /** 姓名工号组合、两个工作正文及带原表行号的协调明细。 */
    public record Report(String author, String work, String plan, List<Row> coordination) {}

    /** 按附件布局生成三页，会议标识存于工作簿名称，避免占用可填写单元格。 */
    public static byte[] template(UUID meeting) {
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
            StringBuilder types = new StringBuilder("""
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    """);
            StringBuilder workbook = new StringBuilder("<workbook xmlns=\"" + NS + "\" xmlns:r=\"" + REL + "\"><sheets>");
            StringBuilder relationships = new StringBuilder("<Relationships xmlns=\"" + PACKAGE_REL + "\">");
            for (int i = 1; i <= SHEETS.size(); i++) {
                types.append("<Override PartName=\"/").append(SHEET_PATH).append(i)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                workbook.append("<sheet name=\"").append(SHEETS.get(i - 1)).append("\" sheetId=\"")
                        .append(i).append("\" r:id=\"rId").append(i).append("\"/>");
                relationships.append(relationship(i, "worksheet", "worksheets/sheet" + i + ".xml"));
            }
            put(zip, "[Content_Types].xml", types.append("</Types>").toString());
            put(zip, "_rels/.rels", "<Relationships xmlns=\"" + PACKAGE_REL + "\">"
                    + relationship(1, "officeDocument", "xl/workbook.xml") + "</Relationships>");
            put(zip, "xl/workbook.xml", workbook.append("</sheets><definedNames><definedName name=\"")
                    .append(MARKER).append("\" hidden=\"1\">").append(meetingMarker(meeting))
                    .append("</definedName></definedNames></workbook>").toString());
            put(zip, "xl/_rels/workbook.xml.rels", relationships.append(relationship(4, "styles", "styles.xml"))
                    .append("</Relationships>").toString());
            put(zip, "xl/styles.xml", STYLES);
            for (int i = 1; i <= 2; i++)
                put(zip, SHEET_PATH + i + ".xml", worksheet("<col min=\"1\" max=\"8\" width=\"14\" customWidth=\"1\"/>",
                        "<row r=\"1\" ht=\"32\" customHeight=\"1\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>"
                                + SHEETS.get(i - 1) + "</t></is></c></row>"
                                + "<row r=\"2\" ht=\"270\" customHeight=\"1\"><c r=\"A2\" s=\"2\"/></row>",
                        "<mergeCells count=\"2\"><mergeCell ref=\"A1:H1\"/><mergeCell ref=\"A2:H2\"/></mergeCells>"));
            StringBuilder coordinate = new StringBuilder();
            appendRow(coordinate, 1, HEADERS);
            for (int i = 1; i <= 5; i++) appendRow(coordinate, i + 1, List.of(Integer.toString(i)));
            put(zip, SHEET_PATH + "3.xml", worksheet(
                    "<col min=\"1\" max=\"1\" width=\"8\" customWidth=\"1\"/>"
                            + "<col min=\"2\" max=\"4\" width=\"30\" style=\"2\" customWidth=\"1\"/>"
                            + "<col min=\"5\" max=\"7\" width=\"22\" style=\"2\" customWidth=\"1\"/>"
                            + "<col min=\"8\" max=\"8\" width=\"30\" style=\"2\" customWidth=\"1\"/>"
                            + "<col min=\"9\" max=\"9\" width=\"22\" style=\"2\" customWidth=\"1\"/>",
                    coordinate.toString().replaceAll("(<c r=\"[A-I]1\")", "$1 s=\"1\""),
                    "<dataValidations count=\"3\"><dataValidation type=\"list\" allowBlank=\"1\" showErrorMessage=\"1\" sqref=\"I2:I31\">"
                            + "<formula1>\"需公司协调,需其他部门配合\"</formula1></dataValidation>"
                            + "<dataValidation showInputMessage=\"1\" promptTitle=\"填报人+工号\" prompt=\"请填写姓名和工号，例如 张三 BLK27001。无协调事宜时也要填此格。\" sqref=\"F2\"/>"
                            + "<dataValidation showInputMessage=\"1\" promptTitle=\"责任人（唯一）\" prompt=\"填写一名配合部门员工的姓名和工号。\" sqref=\"H2:H31\"/>"
                            + "</dataValidations>"));
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw ApiFault.protection();
        }
    }

    /** 仅接受本次会议的三页表，沿用批量导入的有界 ZIP 和 XML 检查。 */
    public static Report read(byte[] bytes, UUID meeting) {
        if (bytes.length == 0 || bytes.length > 4 * 1024 * 1024)
            throw ApiFault.invalid("会议 Excel 文件须在 4 MB 以内");
        try {
            Path temporary = Files.createTempFile("ims-meeting-report-", ".xlsx");
            try {
                Files.write(temporary, bytes);
                var parts = parts(temporary, SHEETS);
                var workbook = xml(parts.get("xl/workbook.xml"));
                if (elements(workbook, "definedName").stream().noneMatch(name ->
                        MARKER.equals(name.getAttribute("name")) && meetingMarker(meeting).equals(name.getTextContent())))
                    throw ApiFault.invalid("填报表不属于本场会议，请重新下载模板");
                var relationships = elements(xml(parts.get("xl/_rels/workbook.xml.rels")), "Relationship");
                var worksheets = elements(workbook, "sheet");
                var paths = new ArrayList<String>();
                for (int i = 0; i < SHEETS.size(); i++) {
                    String id = worksheets.get(i).getAttributeNS(REL, "id");
                    String path = SHEET_PATH + (i + 1) + ".xml";
                    if (relationships.stream().noneMatch(rel -> id.equals(rel.getAttribute("Id"))
                            && REL.concat("/worksheet").equals(rel.getAttribute("Type"))
                            && Set.of(path.substring(3), "/" + path).contains(rel.getAttribute("Target"))))
                        throw ApiFault.invalid("工作表顺序或结构有误，请使用当前模板");
                    paths.add(path);
                }
                var shared = parts.containsKey("xl/sharedStrings.xml")
                        ? elements(xml(parts.get("xl/sharedStrings.xml")), "si").stream().map(e -> text(e, "t")).toList()
                        : List.<String>of();
                var work = readCells(parts.get(paths.get(0)), shared, 2, 8);
                var plan = readCells(parts.get(paths.get(1)), shared, 2, 8);
                var coordination = readCells(parts.get(paths.get(2)), shared, 1001, 9);
                // 已下载的模板仍可按旧 H 列标题导入，责任人填写要求相同。
                if ("责任人（唯一）".equals(coordination.get("H1"))) coordination.put("H1", HEADERS.get(7));
                for (int i = 0; i < HEADERS.size(); i++) {
                    if (!HEADERS.get(i).equals(coordination.get((char) ('A' + i) + "1")))
                        throw ApiFault.invalid("「协调事宜」表头有误，请使用当前模板");
                }
                String author = coordination.getOrDefault("F2", "");
                if (author.isBlank()) throw ApiFault.invalid("请填写「协调事宜」F2 的填报人+工号");
                var rows = new ArrayList<Row>();
                for (int row = 2; row <= 1001; row++) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    for (int col = 1; col < KEYS.size(); col++) {
                        String value = coordination.get((char) ('A' + col) + Integer.toString(row));
                        if (value != null) data.put(KEYS.get(col), value);
                    }
                    if (!data.isEmpty()) rows.add(new Row(row, data));
                }
                return new Report(author, work.get("A2"), plan.get("A2"), rows);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (ApiFault failure) {
            throw failure;
        } catch (Exception failure) {
            throw ApiFault.invalid("无法读取会议 Excel，请使用本场会议下载的三页模板");
        }
    }

    private static Map<String, String> readCells(byte[] worksheet, List<String> shared, int lastRow, int lastColumn)
            throws Exception {
        Map<String, String> cells = new HashMap<>();
        for (Element cell : elements(xml(worksheet), "c")) {
            if (!elements(cell, "f").isEmpty()) throw ApiFault.invalid("会议 Excel 请填写文本，不使用公式");
            String type = cell.getAttribute("t"), ref = cell.getAttribute("r");
            String value = switch (type) {
                case "inlineStr" -> text(cell, "t");
                case "s" -> shared.get(Inputs.integer(text(cell, "v"), -1, 0, shared.size() - 1));
                case "", "n", "str" -> text(cell, "v");
                default -> throw ApiFault.invalid("会议 Excel 请填写文本");
            };
            if (value.isBlank()) continue;
            if (!ref.matches("[A-I][1-9][0-9]{0,3}") || ref.charAt(0) >= 'A' + lastColumn
                    || Integer.parseInt(ref.substring(1)) > lastRow || value.length() > 20000
                    || cells.putIfAbsent(ref, value.strip()) != null)
                throw ApiFault.invalid("会议 Excel 存在超出模板范围或重复的单元格");
        }
        return cells;
    }

    private static String meetingMarker(UUID meeting) { return "\"IMS-MEETING-2:" + meeting + "\""; }

    private static String relationship(int id, String type, String target) {
        return "<Relationship Id=\"rId" + id + "\" Type=\"" + REL + "/" + type + "\" Target=\"" + target + "\"/>";
    }

    private static String worksheet(String columns, String data, String afterData) {
        return "<worksheet xmlns=\"" + NS + "\"><cols>" + columns + "</cols><sheetData>" + data
                + "</sheetData>" + afterData + "</worksheet>";
    }

    /** 正文自动换行，工号按文本保存，标题使用附件的蓝色分区。 */
    private static final String STYLES = """
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>
                <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>
              <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
                <fill><patternFill patternType="solid"><fgColor rgb="FF5B9BD5"/><bgColor indexed="64"/></patternFill></fill></fills>
              <borders count="1"><border/></borders>
              <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
              <cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
                <xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf>
              </cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
            </styleSheet>
            """;
}
