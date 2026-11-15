package com.company.platform.intake;

import com.company.platform.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** 受限 XLSX 台账：最多 1000 行，拒绝公式、宏、外链和压缩炸弹，不执行工作簿内容。 */
public final class ImportWorkbook {
    /** 固定模板契约，版本变化后必须重新预检。 */
    public static final String VERSION="IMS-IMPORT-1";
    /** 实测 XLSX 的标准媒体类型。 */
    public static final String MEDIA="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** 字段及中文含义，空白可选字段不会成为覆盖指令。 */
    private static final Map<String,String> HEADERS=Map.of(
        "EMPLOYEE","employeeNo|工号,name|姓名,departmentCode|部门编码,companyEmail|公司邮箱",
        "DEPARTMENT","code|部门编码,name|部门名称,parentCode|上级部门编码,sortOrder|排序号",
        "ARCHIVE","objectType|BOX或RECORD,rowKey|本批稳定行号,boxKey|本批盒行号,boxNo|已有系统盒号,departmentCode|部门编码,title|题名,safeTitle|安全题名,classification|GENERAL或IMPORTANT或CORE,retentionCode|05或10或30或YJ,archiveDate|实际存档日期,numberMonth|编号月首日期,categoryCode|业务类别,carrierType|ELECTRONIC或PAPER或MIXED,ownerEmployeeNo|责任人工号,templateVersionId|目录模板版本标识,itemNo|盒内件号,location|原件位置,keywords|关键词以分号分隔,abstract|摘要,formedDate|形成日期,notes|备注,extensionValues|扩展字段JSON,originalNumber|原有编号");
    private ImportWorkbook() { }
    /** 类型白名单不能由文件名推导权限。 */
    public static String type(Object value){return Inputs.choice(value,"EMPLOYEE","DEPARTMENT","ARCHIVE");}
    /** 模板和页面使用同一固定列定义。 */
    public static List<String> headers(String type){return List.of(HEADERS.get(type(type)).split(","));}
    /** 原表行号及固定列值，用于精确错误定位。 */
    public record Row(/** Excel 原行号。 */ int number,/** 列名到文本。 */ Map<String,Object> values) { }
    /** 类型检测只读取有界包结构，业务字段交由预检校验。 */
    public static boolean validContainer(Path path){try{parts(path);return true;}catch(Exception failure){return false;}}
    /** 解析当前模板；编号和日期应使用文本格式以保留前导零及日历日期。 */
    public static List<Row> read(Path path,String type) {
        try {
            Map<String,byte[]> entries=parts(path);
            List<String> shared=new ArrayList<>();
            if(entries.containsKey("xl/sharedStrings.xml"))for(Element item:elements(xml(entries.get("xl/sharedStrings.xml")),"si"))shared.add(text(item,"t"));
            if(type.equals("ARCHIVE")&&ArchiveBoxWorkbook.matches(xml(entries.get("xl/worksheets/sheet1.xml")),shared))return ArchiveBoxWorkbook.read(entries,shared);
            if(entries.containsKey("xl/worksheets/sheet2.xml"))throw ApiFault.invalid("只有档案盒通用台账可附带人员参考表");
            List<Element> rows=elements(xml(entries.get("xl/worksheets/sheet1.xml")),"row");
            if(rows.size()>1002)throw ApiFault.invalid("每批台账最多 1000 行");
            List<String> columns=headers(type);List<Row> result=new ArrayList<>();int last=0;
            for(Element row:rows){
                int number=Inputs.integer(row.getAttribute("r"),0,1,1002);if(number<=last)throw ApiFault.invalid("行号重复或乱序");last=number;
                Map<Integer,String> cells=new TreeMap<>();
                for(Element cell:elements(row,"c")){
                    String ref=cell.getAttribute("r");if(!ref.matches("[A-Z]{1,2}"+number))throw ApiFault.invalid("单元格坐标不一致");
                    int column=0;for(char letter:ref.replaceAll("[0-9]", "").toCharArray())column=column*26+letter-'A'+1;
                    if(column>columns.size()||cells.containsKey(column))throw ApiFault.invalid("存在模板外列或重复单元格");
                    if(!elements(cell,"f").isEmpty())throw ApiFault.invalid("不能包含公式，请粘贴为文本值");
                    String kind=cell.getAttribute("t"),value=kind.equals("inlineStr")?text(cell,"t"):text(cell,"v");
                    if(kind.equals("s"))value=shared.get(Inputs.integer(value,0,0,shared.size()-1));
                    else if(!Set.of("","n","str","inlineStr").contains(kind))throw ApiFault.invalid("请使用文本单元格");
                    if(value.length()>20000)throw ApiFault.invalid("单元格超过长度上限");cells.put(column,value.trim());
                }
                if(number==1){if(!Objects.equals(cells.get(1),VERSION+":"+type)||cells.size()!=1)throw ApiFault.invalid("模板版本或类型不匹配");}
                else if(number==2){for(int c=0;c<columns.size();c++)if(!columns.get(c).equals(cells.get(c+1)))throw ApiFault.invalid("模板列名或顺序已变化");}
                else if(cells.values().stream().anyMatch(value->!value.isBlank())){
                    Map<String,Object> values=new LinkedHashMap<>();for(int c=0;c<columns.size();c++){String value=cells.get(c+1);if(value!=null&&!value.isBlank())values.put(columns.get(c).split("\\|")[0],value);}result.add(new Row(number,values));
                }
            }
            if(rows.size()<2||!rows.get(0).getAttribute("r").equals("1")||!rows.get(1).getAttribute("r").equals("2")||result.isEmpty())throw ApiFault.invalid("模板缺少标题或有效数据行");
            return result;
        }catch(ApiFault fault){throw fault;}catch(Exception failure){throw ApiFault.invalid("XLSX 结构损坏或不受支持，请使用当前模板");}
    }
    /** 模板不包含真实员工、档案或秘密。 */
    public static byte[] template(String type){return workbook(type,List.of());}
    /** 生成文本工作簿；模拟数据测试复用同一编码实现。 */
    public static byte[] workbook(String type,List<Map<String,Object>> data){
        if(data.size()>1000)throw ApiFault.invalid("台账超过 1000 行");
        try(var bytes=new ByteArrayOutputStream();var zip=new ZipOutputStream(bytes)){
            put(zip,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            put(zip,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(zip,"xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"导入台账\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip,"xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            StringBuilder sheet=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            appendRow(sheet,1,List.of(VERSION+":"+type(type)));appendRow(sheet,2,headers(type));
            for(int i=0;i<data.size();i++){Map<String,Object> row=data.get(i);appendRow(sheet,i+3,headers(type).stream().map(h->Objects.toString(row.get(h.split("\\|")[0]),"")).toList());}
            put(zip,"xl/worksheets/sheet1.xml",sheet.append("</sheetData></worksheet>").toString());zip.finish();return bytes.toByteArray();
        }catch(IOException failure){throw ApiFault.protection();}
    }
    /** 包内文件不释放到磁盘；每项 20MiB、总量 32MiB、最多 128 项。 */
    static Map<String,byte[]> parts(Path path) throws Exception {
        Map<String,byte[]> entries=new HashMap<>();long total=0;
        try(ZipFile zip=new ZipFile(path.toFile())){var iterator=zip.entries();while(iterator.hasMoreElements()){
            ZipEntry entry=iterator.nextElement();String name=entry.getName();
            if(entries.size()>=128||name.contains("..")||name.startsWith("/")||name.contains("\\")||entries.containsKey(name))throw new IOException("invalid package");
            if(entry.isDirectory())continue;
            if(!name.endsWith(".xml")&&!name.endsWith(".rels"))throw new IOException("embedded objects");
            byte[] content;try(InputStream in=zip.getInputStream(entry)){content=in.readNBytes(20*1024*1024+1);}
            total+=content.length;if(content.length>20*1024*1024||total>32*1024*1024)throw new IOException("expanded limit");
            Document doc=xml(content);if(!elements(doc,"externalReference").isEmpty())throw new IOException("active content");
            for(Element cell:elements(doc,"c"))if(!elements(cell,"f").isEmpty()&&(!name.equals("xl/worksheets/sheet1.xml")||!ArchiveBoxWorkbook.allowedExpiryFormula(cell)))throw new IOException("active formula");
            for(Element rel:elements(doc,"Relationship"))if("External".equalsIgnoreCase(rel.getAttribute("TargetMode")))throw new IOException("external link");entries.put(name,content);
        }}
        if(!entries.keySet().containsAll(Set.of("[Content_Types].xml","xl/workbook.xml","xl/worksheets/sheet1.xml")))throw new IOException("missing XLSX parts");
        List<Element> sheets=elements(xml(entries.get("xl/workbook.xml")),"sheet");
        if(sheets.size()!=1&&!(sheets.size()==2&&sheets.get(0).getAttribute("name").equals("档案汇总表")&&sheets.get(1).getAttribute("name").equals("部门及档案负责人")&&entries.containsKey("xl/worksheets/sheet2.xml")))throw new IOException("unsupported worksheets");
        String types=new String(entries.get("[Content_Types].xml"),StandardCharsets.UTF_8);if(!types.contains("spreadsheetml.sheet.main+xml")||types.contains("macroEnabled"))throw new IOException("not XLSX");return entries;
    }
    /** 禁用外部实体、DOCTYPE 和 XInclude，不读取外部文件或网络。 */
    static Document xml(byte[] bytes)throws Exception{var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));}
    /** 按本地名读取命名空间，兼容 Excel 的前缀差异。 */
    static List<Element> elements(Node root,String name){NodeList nodes=root instanceof Document doc?doc.getElementsByTagNameNS("*",name):((Element)root).getElementsByTagNameNS("*",name);List<Element> result=new ArrayList<>();for(int i=0;i<nodes.getLength();i++)result.add((Element)nodes.item(i));return result;}
    /** 富文本按原顺序拼接。 */
    static String text(Element root,String name){return elements(root,name).stream().map(Node::getTextContent).reduce("",String::concat);}
    /** 写入固定 ZIP 项。 */
    static void put(ZipOutputStream zip,String name,String value)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    /** 转义文本，前导等号仍是文本，不生成公式。 */
    static void appendRow(StringBuilder target,int number,List<String> values){target.append("<row r=\"").append(number).append("\">");for(int i=0;i<values.size();i++){int column=i+1;String letter=column<=26?""+(char)('A'+column-1):"A"+(char)('A'+column-27);target.append("<c r=\"").append(letter).append(number).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(values.get(i).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")).append("</t></is></c>");}target.append("</row>");}
}
