package com.company.platform.intake;

import com.company.platform.ApiFault;
import com.company.platform.InlineImages;
import com.company.platform.SystemFailure;
import org.w3c.dom.Element;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static com.company.platform.intake.ImportWorkbook.*;

/** 会议部门在线编辑的原生 Excel 模板与固定列读取。 */
public final class MeetingWeeklyWorkbook {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final List<String> NAMES = List.of("协调事宜", "上周总结", "本周计划");
    public static final List<String> COORDINATION = List.of("序号", "具体事宜", "需协调 / 配合什么", "期望结果 / 验收标准",
            "填报部门", "填报人+工号", "配合部门", "责任人（唯一，姓名+工号）", "事项类别");
    public static final List<String> NARRATIVE = List.of("序号", "事项说明", "备注", "填报人+工号");
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RELATION = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String SHEET_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml";

    private MeetingWeeklyWorkbook() {}

    /** 行号从 Excel 的第 2 行开始，图片跟随锚点所在行。 */
    public record Line(int number, List<String> cells, List<InlineImages.Image> images) {}
    /** 可复用的工作表版本，标题和列保持业务约定。 */
    public record Sheet(String name, List<String> headers, List<Line> lines) {}

    /** 生成原生 XLSX；文件可用于文档服务和 Excel 客户端。 */
    public static byte[] write(List<Sheet> sheets) {
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
            var types = new StringBuilder("""
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    """);
            var book = new StringBuilder("<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + RELATION
                    + "\"><bookViews><workbookView/></bookViews><sheets>");
            var relations = new StringBuilder(relation(sheets.size() + 1, "styles", "styles.xml"));
            for (int i = 0; i < sheets.size(); i++) {
                int part = i + 1;
                var sheet = sheets.get(i);
                book.append("<sheet name=\"").append(escape(sheet.name())).append("\" sheetId=\"").append(part)
                        .append("\" r:id=\"rId").append(part).append("\"/>");
                relations.append(relation(part, "worksheet", "worksheets/sheet" + part + ".xml"));
                types.append("<Override PartName=\"/xl/worksheets/sheet").append(part)
                        .append(".xml\" ContentType=\"").append(SHEET_TYPE).append("\"/>");
                writeSheet(zip, types, part, sheet);
            }
            put(zip, "[Content_Types].xml", types.append("</Types>").toString());
            put(zip, "_rels/.rels", relations(relation(1, "officeDocument", "xl/workbook.xml")));
            put(zip, "xl/workbook.xml", book.append("</sheets></workbook>").toString());
            put(zip, "xl/_rels/workbook.xml.rels", relations(relations.toString()));
            put(zip, "xl/styles.xml", STYLES);
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw SystemFailure.WORKBOOK_GENERATION_FAILED.fault(failure);
        }
    }

    private static void writeSheet(ZipOutputStream zip, StringBuilder types, int part, Sheet sheet) throws IOException {
        var body = new StringBuilder();
        var header = new StringBuilder();
        appendRow(header, 1, sheet.headers());
        body.append(header.toString().replace("<c ", "<c s=\"1\" "));
        for (Line row : sheet.lines()) appendRow(body, row.number(), row.cells());
        boolean hasImages = sheet.lines().stream().anyMatch(row -> !row.images().isEmpty());
        if (hasImages) writePictures(zip, types, part, sheet.lines());
        String views = "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>";
        String widths = "<cols><col min=\"1\" max=\"1\" width=\"8\" customWidth=\"1\"/><col min=\"2\" max=\""
                + sheet.headers().size() + "\" width=\"26\" customWidth=\"1\" style=\"2\"/></cols>";
        put(zip, "xl/worksheets/sheet" + part + ".xml", "<worksheet xmlns=\"" + MAIN + "\" xmlns:r=\"" + RELATION
                + "\">" + views + widths + "<sheetData>" + body + "</sheetData>" + (hasImages ? "<drawing r:id=\"rId1\"/>" : "") + "</worksheet>");
    }

    /** 将旧在线表的截图放回原行，文件中的 JPEG 和 PNG 不需二次编码。 */
    private static void writePictures(ZipOutputStream zip, StringBuilder types, int part, List<Line> lines) throws IOException {
        var drawing = new StringBuilder("<xdr:wsDr xmlns:xdr=\"" + DRAWING
                + "\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + RELATION + "\">");
        var links = new StringBuilder();
        int id = 0;
        for (Line row : lines) {
            for (int j = 0; j < row.images().size(); j++) {
                var image = row.images().get(j);
                id++;
                String asset = "weekly" + part + "-" + id + (image.mediaType().equals("image/png") ? ".png" : ".jpeg");
                zip.putNextEntry(new ZipEntry("xl/media/" + asset));
                zip.write(image.bytes());
                zip.closeEntry();
                links.append(relation(id, "image", "../media/" + asset));
                drawing.append("""
                        <xdr:oneCellAnchor><xdr:from><xdr:col>%d</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>%d</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>
                        <xdr:ext cx="900000" cy="650000"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="%d" name="图片 %d"/><xdr:cNvPicPr/></xdr:nvPicPr>
                        <xdr:blipFill><a:blip r:embed="rId%d"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>
                        <xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>
                        """.formatted(4 + j, row.number() - 1, id, id, id));
            }
        }
        types.append("<Override PartName=\"/xl/drawings/drawing").append(part)
                .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>");
        put(zip, "xl/worksheets/_rels/sheet" + part + ".xml.rels",
                relations(relation(1, "drawing", "../drawings/drawing" + part + ".xml")));
        put(zip, "xl/drawings/drawing" + part + ".xml", drawing.append("</xdr:wsDr>").toString());
        put(zip, "xl/drawings/_rels/drawing" + part + ".xml.rels", relations(links.toString()));
    }

    /** 文档服务保存时只校验包本身，字段可以分次填写。 */
    public static void validate(Path file) {
        packageParts(file, List.of());
    }

    /** 部门提交时读取固定页签和列，缺少表头或越界会明确提示。 */
    public static List<Sheet> read(Path file) {
        var parts = packageParts(file, NAMES);
        try {
            var book = elements(xml(parts.get("xl/workbook.xml")), "sheet");
            var sheetLinks = partLinks(parts, "xl/workbook.xml");
            List<String> shared = parts.containsKey("xl/sharedStrings.xml")
                    ? elements(xml(parts.get("xl/sharedStrings.xml")), "si").stream().map(si -> text(si, "t")).toList()
                    : List.of();
            var result = new ArrayList<Sheet>();
            for (int i = 0; i < NAMES.size(); i++) {
                String path = sheetLinks.get(book.get(i).getAttributeNS(RELATION, "id"));
                String name = NAMES.get(i);
                var headers = i == 0 ? COORDINATION : NARRATIVE;
                var lines = readSheet(parts, path, name, headers, shared);
                result.add(new Sheet(name, headers, lines));
            }
            return result;
        } catch (ApiFault failure) {
            throw failure;
        } catch (Exception failure) {
            throw ApiFault.invalid("无法读取本部门周会 Excel，请保留三个页签、原有表头和图片所在行");
        }
    }

    /** 已保存字节使用同一有界校验，临时文件只服务于 ZIP 解析。 */
    public static List<Sheet> read(byte[] bytes) {
        try {
            var file = Files.createTempFile("ims-weekly-", ".xlsx");
            try {
                Files.write(file, bytes);
                return read(file);
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException failure) {
            throw ApiFault.invalid("读取已保存的在线 Excel 失败，请重试");
        }
    }

    private static Map<String, byte[]> packageParts(Path file, List<String> names) {
        try {
            if (Files.size(file) > MAX_BYTES) throw ApiFault.invalid("在线周会 Excel 大小不能超过 16 MB");
            return parts(file, names, true);
        } catch (ApiFault failure) {
            throw failure;
        } catch (Exception failure) {
            throw ApiFault.invalid("工作簿包含外部链接、公式或不支持的内容，请保留原模板并使用文本和图片填报");
        }
    }

    private static List<Line> readSheet(Map<String, byte[]> parts, String path, String name, List<String> headers, List<String> shared)
            throws Exception {
        var doc = xml(parts.get(path));
        var pictures = readPictures(parts, path, doc);
        var cells = new TreeMap<Integer, List<String>>();
        for (Element element : elements(doc, "c")) {
            String type = element.getAttribute("t");
            String val = switch (type) {
                case "inlineStr" -> text(element, "t");
                case "s" -> shared.get(Integer.parseInt(text(element, "v")));
                default -> text(element, "v");
            };
            if (val.isBlank()) continue;
            String ref = element.getAttribute("r");
            if (!ref.matches("[A-I][1-9][0-9]{0,2}") || ref.charAt(0) - 'A' >= headers.size())
                throw ApiFault.invalid("「" + name + "」" + ref + " 超出模板填写范围");
            int row = Integer.parseInt(ref.substring(1));
            if (row > 101) throw ApiFault.invalid("「" + name + "」最多填写 100 行");
            cells.computeIfAbsent(row, ignored -> new ArrayList<>(Collections.nCopies(headers.size(), "")))
                    .set(ref.charAt(0) - 'A', val.strip());
        }
        if (!headers.equals(cells.remove(1))) throw ApiFault.invalid("「" + name + "」表头有变化，请恢复原表头后提交");
        pictures.keySet().forEach(row -> cells.computeIfAbsent(row, ignored -> new ArrayList<>(Collections.nCopies(headers.size(), ""))));
        return cells.entrySet().stream().filter(row -> row.getValue().subList(1, headers.size()).stream().anyMatch(value -> !value.isEmpty())
                        || pictures.containsKey(row.getKey()))
                .map(row -> new Line(row.getKey(), row.getValue(), pictures.getOrDefault(row.getKey(), List.of()))).toList();
    }

    /** OOXML 截图按锚点所在行提取，外部资源和未绑定行的截图不会进入业务材料。 */
    private static Map<Integer, List<InlineImages.Image>> readPictures(Map<String, byte[]> parts, String sheet, org.w3c.dom.Document doc)
            throws Exception {
        Map<Integer, List<InlineImages.Image>> result = new HashMap<>();
        for (Element ref : elements(doc, "drawing")) {
            String path = partLinks(parts, sheet).get(ref.getAttributeNS(RELATION, "id"));
            var imageLinks = partLinks(parts, path);
            var drawing = xml(parts.get(path)).getDocumentElement();
            for (var anchor = drawing.getFirstChild(); anchor != null; anchor = anchor.getNextSibling()) {
                if (!(anchor instanceof Element node) || elements(node, "blip").isEmpty()) continue;
                var starts = elements(node, "from");
                if (starts.isEmpty()) throw ApiFault.invalid("图片需放在对应事项行内");
                int row = Integer.parseInt(text(starts.getFirst(), "row")) + 1;
                if (row < 2 || row > 101) throw ApiFault.invalid("图片需放在模板第 2 至 101 行");
                var images = result.computeIfAbsent(row, ignored -> new ArrayList<>());
                for (Element blip : elements(node, "blip")) {
                    byte[] bytes = parts.get(imageLinks.get(blip.getAttributeNS(RELATION, "embed")));
                    if (bytes == null || bytes.length > 2 * 1024 * 1024 || images.size() >= 3)
                        throw ApiFault.invalid("每行最多 3 张不超过 2 MB 的 PNG 或 JPEG 图片");
                    UUID key = UUID.randomUUID();
                    images.add(InlineImages.prepare(List.of(Map.of("id", key.toString(),
                            "data", Base64.getEncoder().encodeToString(bytes)))).get(key));
                }
            }
        }
        return result;
    }

    private static Map<String, String> partLinks(Map<String, byte[]> parts, String file) throws Exception {
        int slash = file.lastIndexOf('/') + 1;
        byte[] rels = parts.get(file.substring(0, slash) + "_rels/" + file.substring(slash) + ".rels");
        Map<String, String> links = new HashMap<>();
        if (rels == null) return links;
        for (Element link : elements(xml(rels), "Relationship")) {
            String target = link.getAttribute("Target");
            String path = target.startsWith("/") ? target.substring(1)
                    : Path.of(file.substring(0, slash)).resolve(target).normalize().toString().replace('\\', '/');
            links.put(link.getAttribute("Id"), path);
        }
        return links;
    }

    private static String relation(int id, String type, String target) {
        return "<Relationship Id=\"rId" + id + "\" Type=\"" + RELATION + "/" + type + "\" Target=\"" + target + "\"/>";
    }

    private static String relations(String body) {
        return "<Relationships xmlns=\"" + PACKAGE + "\">" + body + "</Relationships>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }

    private static final String STYLES = """
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>
                <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>
              <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
                <fill><patternFill patternType="solid"><fgColor rgb="FF087F88"/><bgColor indexed="64"/></patternFill></fill></fills>
              <borders count="1"><border/></borders>
              <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
              <cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0"/>
                <xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf>
              </cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
            </styleSheet>
            """;
}
