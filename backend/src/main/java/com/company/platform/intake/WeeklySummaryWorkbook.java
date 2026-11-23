package com.company.platform.intake;

import com.company.platform.ApiFault;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipFile;

import static com.company.platform.intake.ImportWorkbook.*;

/** 读取按日期和部门归档的周会汇总，只取约定的表头、正文单元格及协调事项。 */
public final class WeeklySummaryWorkbook {
    public static final int MAX_BYTES = 50 * 1024 * 1024;
    private static final int XML_LIMIT = 16 * 1024 * 1024;
    // WPS 会把整列样式写到最后一行，即使有正文的行只有几十行。
    private static final int SHEET_XML_LIMIT = 64 * 1024 * 1024;
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final List<String> REPORT_HEADERS = List.of("日期", "名称", "序号", "事项说明", "备注");
    private static final List<String> COORD_KEYS = List.of("sequence", "category", "subject", "support", "expected",
            "authorDepartment", "author", "department", "assignee", "dueAt");
    private static final List<String> COORD_HEADERS = List.of("序号", "事项类别", "具体事宜", "需协调/配合什么",
            "期望结果/验收标准", "填报部门", "填报人+工号", "配合部门", "责任人(唯一,姓名+工号)", "截止时间");
    private static final List<String> OLD_COORD_HEADERS = List.of("序号", "具体事宜", "需协调/配合什么",
            "期望结果/验收标准", "填报部门", "填报人+工号", "配合部门", "责任人(唯一,姓名+工号)", "事项类别");
    private static final List<String> OLD_COORD_KEYS = List.of("sequence", "subject", "support", "expected",
            "authorDepartment", "author", "department", "assignee", "category");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-M-d")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);

    private WeeklySummaryWorkbook() {}

    /** 部门材料按单元格日期拆块，并保留原序号与备注。 */
    public record Department(String sheet, String work, String plan) {}

    /** 协调事宜独立于部门页，同一文件可以包括多位填报人。 */
    public record Summary(List<Department> departments, List<Row> coordination, List<String> emptyDepartments) {}

    /** 使用工作簿的关系查找 XML；图形和图片不参与文本导入。 */
    public static Summary read(Path source, LocalDate meetingDate) {
        try (var zip = new ZipFile(source.toFile())) {
            var book = xml(entry(zip, "xl/workbook.xml", XML_LIMIT));
            var relations = new HashMap<String, String>();
            for (var rel : elements(xml(entry(zip, "xl/_rels/workbook.xml.rels", XML_LIMIT)), "Relationship")) {
                if (rel.getAttribute("Type").equals(REL + "/worksheet"))
                    relations.put(rel.getAttribute("Id"), rel.getAttribute("Target"));
            }
            var sheets = elements(book, "sheet");
            if (sheets.isEmpty() || sheets.size() > 50) throw ApiFault.invalid("周会汇总应包含协调事宜及部门工作表，最多 50 页");
            var shared = zip.getEntry("xl/sharedStrings.xml") == null ? List.<String>of()
                    : elements(xml(entry(zip, "xl/sharedStrings.xml", XML_LIMIT)), "si").stream().map(s -> text(s, "t")).toList();
            boolean from1904 = elements(book, "workbookPr").stream()
                    .anyMatch(p -> Set.of("1", "true").contains(p.getAttribute("date1904")));
            LocalDate epoch = from1904 ? LocalDate.of(1904, 1, 1) : LocalDate.of(1899, 12, 30);
            var reports = new ArrayList<Department>();
            var emptyDepartments = new ArrayList<String>();
            List<Row> coordination = null;
            for (var sheet : sheets) {
                String name = sheet.getAttribute("name").strip();
                if ("WpsReserved_CellImgList".equals(name)) continue;
                String target = relations.getOrDefault(sheet.getAttributeNS(REL, "id"), "");
                String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                if (!path.matches("xl/worksheets/[A-Za-z0-9_-]+\\.xml"))
                    throw ApiFault.invalid("「" + name + "」工作表结构无法识别");
                var rows = readRows(zip, path, shared);
                if ("协调事宜".equals(name) || matchesHeader(rows, OLD_COORD_HEADERS)) {
                    if (coordination != null) throw ApiFault.invalid("周会汇总存在多页协调事宜");
                    coordination = coordination(rows, epoch);
                }
                else if (matchesHeader(rows, REPORT_HEADERS)) {
                    var report = report(name, rows, meetingDate, epoch);
                    if (report.work() != null || report.plan() != null) reports.add(report);
                    else emptyDepartments.add(name);
                }
            }
            if (coordination == null) throw ApiFault.invalid("周会汇总缺少「协调事宜」工作表");
            if (reports.isEmpty() && !emptyDepartments.isEmpty())
                throw ApiFault.invalid("部门页中找不到 " + meetingDate + " 的上周总结或本周计划，请核对 Excel 中的周会日期");
            return new Summary(reports, coordination, emptyDepartments);
        } catch (ApiFault failure) {
            throw failure;
        } catch (Exception failure) {
            throw ApiFault.invalid("无法读取周会汇总 Excel，请使用 .xlsx 文件");
        }
    }

    /** 只解压当前正在解析的 XML，避免把工作簿的图片或历史附件载入内存。 */
    private static byte[] entry(ZipFile zip, String path, int limit) throws Exception {
        var item = zip.getEntry(path);
        if (item == null || item.getSize() > limit) throw ApiFault.invalid("周会汇总中的数据页缺失或过大");
        try (var stream = zip.getInputStream(item)) {
            var data = stream.readNBytes(limit + 1);
            if (data.length > limit) throw ApiFault.invalid("周会汇总中的数据页过大");
            return data;
        }
    }

    /** 仅提取 A:J 的显示值，公式只读取文件已经保存的计算结果。 */
    private static SortedMap<Integer, String[]> readRows(ZipFile zip, String path, List<String> shared) throws Exception {
        var rows = new TreeMap<Integer, String[]>();
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(entry(zip, path, SHEET_XML_LIMIT)));
        try {
            String ref = null;
            String type = null;
            StringBuilder value = new StringBuilder();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "c" -> {
                            ref = reader.getAttributeValue(null, "r");
                            type = reader.getAttributeValue(null, "t");
                            value.setLength(0);
                        }
                        case "v", "t" -> {
                            if (ref != null) value.append(reader.getElementText());
                        }
                        default -> { }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "c".equals(reader.getLocalName())) {
                    if (!value.isEmpty() && ref != null && ref.matches("[A-J][1-9][0-9]{0,6}")) {
                        String stored = "s".equals(type) ? shared.get(Integer.parseInt(value.toString())) : value.toString();
                        if (!stored.isBlank()) rows.computeIfAbsent(Integer.parseInt(ref.substring(1)), ignored -> new String[10])
                                [ref.charAt(0) - 'A'] = stored.strip();
                    }
                    ref = null;
                } else if (event == XMLStreamConstants.END_ELEMENT && "sheetData".equals(reader.getLocalName())) {
                    break;
                }
            }
        } finally {
            reader.close();
        }
        return rows;
    }

    /** Excel 与 WPS 对全角括号、空格和斜线的写法可不同。 */
    private static boolean matchesHeader(SortedMap<Integer, String[]> rows, List<String> expected) {
        var header = rows.get(1);
        if (header == null) return false;
        for (int i = 0; i < expected.size(); i++) {
            String actual = java.text.Normalizer.normalize(Objects.toString(header[i], ""), java.text.Normalizer.Form.NFKC)
                    .replaceAll("\\s+", "");
            if (!expected.get(i).equals(actual)) return false;
        }
        return true;
    }

    private static List<Row> coordination(SortedMap<Integer, String[]> rows, LocalDate epoch) {
        var keys = COORD_KEYS;
        if (matchesHeader(rows, OLD_COORD_HEADERS)) keys = OLD_COORD_KEYS;
        else if (!matchesHeader(rows, COORD_HEADERS)) throw ApiFault.invalid("「协调事宜」第 1 行表头与周会汇总格式不符");
        var output = new ArrayList<Row>();
        for (var entry : rows.tailMap(2).entrySet()) {
            var cells = entry.getValue();
            var values = new LinkedHashMap<String, Object>();
            for (int i = 1; i < keys.size(); i++)
                if (cells[i] != null) values.put(keys.get(i), cells[i]);
            if (values.isEmpty()) continue;
            if (values.containsKey("dueAt")) {
                var date = dateTime(cells[9], epoch);
                if (date == null) throw ApiFault.invalid("「协调事宜」J" + entry.getKey() + " 的截止时间无法识别");
                values.put("dueAt", date.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString());
            }
            output.add(new Row(entry.getKey(), values));
        }
        return output;
    }

    /** 日期和名称取合并单元格的起始值，切换日期时不延续上一个栏目。 */
    private static Department report(String name, SortedMap<Integer, String[]> rows, LocalDate selected, LocalDate epoch) {
        LocalDate date = null;
        String section = "";
        var work = new ArrayList<String>();
        var plan = new ArrayList<String>();
        for (var cells : rows.tailMap(2).values()) {
            if (cells[0] != null) {
                var stamp = dateTime(cells[0], epoch);
                var nextDate = stamp == null ? null : stamp.toLocalDate();
                if (!Objects.equals(date, nextDate)) section = "";
                date = nextDate;
            }
            if (cells[1] != null) section = cells[1].replaceAll("\\s+", "");
            if (!selected.equals(date) || (cells[3] == null && cells[4] == null)) continue;
            String notes = cells[4] == null ? "" : "\n备注：" + cells[4];
            String line = (cells[2] == null ? "" : cells[2] + ". ") + Objects.toString(cells[3], "") + notes;
            switch (section) {
                case "上周总结", "上周工作总结", "上周工作内容" -> work.add(line);
                case "本周计划", "本周工作计划" -> plan.add(line);
                default -> { }
            }
        }
        return new Department(name, work.isEmpty() ? null : String.join("\n", work),
                plan.isEmpty() ? null : String.join("\n", plan));
    }

    /** 支持 Excel 日期序号及台账中常见的中文、斜线和点分隔日期。 */
    private static LocalDateTime dateTime(String value, LocalDate epoch) {
        try {
            if (value.matches("[0-9]{4,6}(?:\\.[0-9]+)?")) {
                double serial = Double.parseDouble(value);
                return epoch.atStartOfDay().plusSeconds(Math.round(serial * 86400));
            }
            var parts = value.replace('年', '-').replace('月', '-').replace("日", " ")
                    .replace('/', '-').replace('.', '-').strip().split("[T\\s]+", 2);
            var date = LocalDate.parse(parts[0], DATE);
            if (parts.length == 1) return date.atStartOfDay();
            return date.atTime(LocalTime.parse(parts[1], DateTimeFormatter.ofPattern("H:mm[:ss]")));
        } catch (DateTimeException invalid) {
            return null;
        }
    }
}
