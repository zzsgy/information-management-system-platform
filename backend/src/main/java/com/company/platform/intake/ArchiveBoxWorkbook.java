package com.company.platform.intake;

import com.company.platform.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.w3c.dom.*;
import static com.company.platform.intake.ImportWorkbook.*;

/** 档案盒台账追加可选纸质标签列，兼容原十八列；第二张人员表只作参考，不触发账号或角色维护。 */
public final class ArchiveBoxWorkbook {
    public static final String FORMAT="BOX_LEDGER";
    /** 列顺序对应原 Excel，存放位置保留三列。 */
    public static final List<String> KEYS=List.of("originalNumber","title","fileType","departmentName","ownerName","cabinetNo","columnNo","layerNo","inventory","preservationType","copyCount","classification","archiveDate","retentionCode","sourceExpiryDate","sourceStatus","notes","sourceUpdatedAt","paperTags");
    public static final List<String> LABELS=List.of("档案编号","档案名称","文件类型","所属部门","档案负责人","存放柜号","存放列号","存放层号","清单","保存类型","保存份数","保密级别","存档日期","有效期限 / 年","到期日期","档案状态","备注","最后更新时间","纸质标签");
    private ArchiveBoxWorkbook() { }

    /** 格式识别不依赖文件名、部门或工作表名字。 */
    static boolean matches(Document sheet,List<String> shared){
        Map<String,String> cells=new HashMap<>();
        for(Element cell:elements(sheet,"c"))if(Set.of("A2","B2","F2").contains(cell.getAttribute("r")))cells.put(cell.getAttribute("r"),value(cell,shared));
        return "档案编号".equals(cells.get("A2"))&&"档案名称".equals(cells.get("B2"))&&"存放位置".equals(cells.get("F2"));
    }

    /** 只接受原表 O 列同一行的固定到期公式；从日期和年数重新计算，不执行表达式或信任缓存。 */
    static boolean allowedExpiryFormula(Element cell){
        String ref=cell.getAttribute("r");if(!ref.matches("O(?:[4-9]|[1-9][0-9]{1,3})"))return false;
        String row=ref.substring(1);return elements(cell,"f").size()==1&&text(cell,"f").replace(" ","").equals("M"+row+"+365*N"+row);
    }

    /** 保留 Excel 原始行号，空行不产生对象；中文表头、Excel 日期和文本日期均在入口转换。 */
    static List<ImportWorkbook.Row> read(Map<String,byte[]> entries,List<String> shared)throws Exception{
        Document sheet=xml(entries.get("xl/worksheets/sheet1.xml"));
        boolean date1904=elements(xml(entries.get("xl/workbook.xml")),"workbookPr").stream().anyMatch(e->Set.of("1","true").contains(e.getAttribute("date1904")));
        List<ImportWorkbook.Row> result=new ArrayList<>();Map<Integer,Map<Integer,String>> headers=new HashMap<>();int last=0;
        for(Element row:elements(sheet,"row")){
            int number=Inputs.integer(row.getAttribute("r"),0,1,1003);if(number<=last)throw ApiFault.invalid("行号重复或乱序");last=number;
            Map<Integer,String> cells=new TreeMap<>();Set<Integer> seen=new HashSet<>();Element expiryFormula=null;
            for(Element cell:elements(row,"c")){
                String ref=cell.getAttribute("r");if(!ref.matches("[A-Z]{1,2}"+number))throw ApiFault.invalid("单元格坐标不一致");
                int column=0;for(char c:ref.replaceAll("[0-9]","").toCharArray())column=column*26+c-'A'+1;
                String value=value(cell,shared);if(!seen.add(column))throw ApiFault.invalid("单元格重复");
                // WPS 会保留表格右侧空白样式单元格，只有有值的额外列才算格式变更。
                if(column>19){if(!value.isBlank())throw ApiFault.invalid("存在台账格式以外的列");continue;}
                if(!elements(cell,"f").isEmpty())expiryFormula=cell;
                if(number>=4&&Set.of(13,15,18).contains(column)&&!value.isBlank())value=dateValue(value,date1904,column==18);
                if(!value.isBlank())cells.put(column,value);
            }
            if(number<=3){headers.put(number,cells);continue;}
            if(cells.isEmpty())continue;
            if(expiryFormula!=null){
                try{LocalDate archived=LocalDate.parse(cells.get(13));int years=new BigDecimal(cells.get(14)).intValueExact();if(years<1||years>999)throw new IllegalArgumentException();cells.put(15,archived.plusDays(Math.multiplyExact(365L,years)).toString());}
                catch(RuntimeException bad){throw ApiFault.invalid("第 "+number+" 行的存档日期或有效期限不能用于到期日期计算");}
            }
            Map<String,Object> values=new LinkedHashMap<>();values.put("sourceFormat",FORMAT);values.put("objectType","BOX");values.put("rowKey","BOX-"+number);
            for(int column=1;column<=19;column++)if(cells.containsKey(column))values.put(KEYS.get(column-1),cells.get(column));
            result.add(new ImportWorkbook.Row(number,values));
        }
        Map<Integer,String> top=headers.getOrDefault(2,Map.of()),sub=headers.getOrDefault(3,Map.of());
        for(int column=1;column<=18;column++){
            String expected=column==6?"存放位置":column==7||column==8?null:LABELS.get(column-1);
            if(expected!=null&&!expected.replaceAll("\\s","").equals(top.getOrDefault(column,"").replaceAll("\\s","")))throw ApiFault.invalid("台账第 2 行表头不匹配："+expected);
            if(column>=6&&column<=8&&!LABELS.get(column-1).equals(sub.get(column)))throw ApiFault.invalid("台账第 3 行表头不匹配："+LABELS.get(column-1));
        }
        if(top.containsKey(19)&&!"纸质标签".equals(top.get(19)))throw ApiFault.invalid("台账第 19 列须为纸质标签");
        if(entries.containsKey("xl/worksheets/sheet2.xml")){
            Map<String,String> reference=new HashMap<>();for(Element cell:elements(xml(entries.get("xl/worksheets/sheet2.xml")),"c"))if(cell.getAttribute("r").matches("[A-E]1"))reference.put(cell.getAttribute("r"),value(cell,shared));
            for(int i=0;i<5;i++)if(!List.of("姓名","角色","部门","直属主管","备注").get(i).equals(reference.get(""+(char)('A'+i)+1)))throw ApiFault.invalid("第二张表仅支持原模板的部门及档案负责人参考表");
        }
        if(result.isEmpty())throw ApiFault.invalid("台账没有有效数据，请从第 4 行填写档案盒");return result;
    }

    /** 数字日期采用工作簿日期系统，中文日期按原日历值转换；普通文本留给逐行预检定位错误。 */
    static String dateValue(String raw,boolean date1904,boolean timestamp){
        if(raw.matches("[0-9]+(?:\\.[0-9]+)?")){
            BigDecimal serial=new BigDecimal(raw);long day=serial.longValue();if(day<1||day>2958465||(!date1904&&day==60))throw ApiFault.invalid("Excel 日期超出有效范围");
            LocalDate date=(date1904?LocalDate.of(1904,1,1):day<60?LocalDate.of(1899,12,31):LocalDate.of(1899,12,30)).plusDays(day);
            if(!timestamp)return date.toString();long seconds=serial.remainder(BigDecimal.ONE).multiply(BigDecimal.valueOf(86400)).setScale(0,java.math.RoundingMode.HALF_UP).longValue();return date.atStartOfDay().plusSeconds(seconds).toString();
        }
        return raw.replace("年","-").replace("月","-").replace("日","").replace('/','-').replace('.','-');
    }

    /** 只解码文本和数值单元格，不把错误、布尔值或公式当成人工填写值。 */
    private static String value(Element cell,List<String> shared){
        String kind=cell.getAttribute("t"),value=kind.equals("inlineStr")?text(cell,"t"):text(cell,"v");
        if(kind.equals("s"))value=shared.get(Inputs.integer(value,0,0,shared.size()-1));else if(!Set.of("","n","str","inlineStr","d").contains(kind))throw ApiFault.invalid("台账单元格类型不受支持");
        if(value.length()>20000)throw ApiFault.invalid("单元格超过长度上限");return value.trim();
    }

    /** 所有部门下载同一个空白模板；不复制用户原表中的真实档案和人员。 */
    public static byte[] template(){return workbook(List.of());}

    /** 模板导出与实际批量登记共用十九列定义，兼容旧十八列，数据从第 4 行开始。 */
    public static byte[] workbook(List<Map<String,Object>> rows){
        if(rows.size()>1000)throw ApiFault.invalid("每批台账最多 1000 行");
        try(var bytes=new ByteArrayOutputStream();var zip=new ZipOutputStream(bytes)){
            put(zip,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
            put(zip,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(zip,"xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"档案汇总表\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip,"xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
            put(zip,"xl/styles.xml","<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"宋体\"/></font><font><b/><sz val=\"12\"/><name val=\"宋体\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFC0C0C0\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"2\"><border/><border><left style=\"thin\"/><right style=\"thin\"/><top style=\"thin\"/><bottom style=\"thin\"/></border></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"3\"><xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/><xf numFmtId=\"49\" fontId=\"1\" fillId=\"2\" borderId=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf><xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
            StringBuilder sheet=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"3\" topLeftCell=\"A4\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"24\"/><cols>");
            for(int col=1;col<=19;col++)sheet.append("<col min=\"").append(col).append("\" max=\"").append(col).append("\" width=\"").append(col==2?44:col==18?24:16).append("\" customWidth=\"1\" style=\"0\"/>");
            sheet.append("</cols><sheetData><row r=\"1\"/>");List<String> top=new ArrayList<>(LABELS);top.set(5,"存放位置");top.set(6,"");top.set(7,"");appendRow(sheet,2,top);
            List<String> sub=new ArrayList<>(Collections.nCopies(19,""));for(int i=5;i<8;i++)sub.set(i,LABELS.get(i));appendRow(sheet,3,sub);
            for(int i=0;i<Math.max(1,rows.size());i++){Map<String,Object> row=rows.isEmpty()?Map.of():rows.get(i);appendRow(sheet,i+4,KEYS.stream().map(key->Objects.toString(row.get(key),"")).toList());}
            sheet.append("</sheetData><mergeCells count=\"17\"><mergeCell ref=\"F2:H2\"/>");for(int i=0;i<19;i++)if(i<5||i>7){char col=(char)('A'+i);sheet.append("<mergeCell ref=\"").append(col).append("2:").append(col).append("3\"/>");}sheet.append("</mergeCells></worksheet>");
            String xml=sheet.toString().replace("<row r=\"2\">","<row r=\"2\" ht=\"38\" customHeight=\"1\">").replace("<row r=\"3\">","<row r=\"3\" ht=\"36\" customHeight=\"1\">").replaceAll("(<c r=\"[A-S][23]\")","$1 s=\"1\"").replaceAll("(<c r=\"[A-S](?:[4-9]|[1-9][0-9]+)\")","$1 s=\"2\"");put(zip,"xl/worksheets/sheet1.xml",xml);zip.finish();return bytes.toByteArray();
        }catch(IOException failure){throw SystemFailure.WORKBOOK_GENERATION_FAILED.fault(failure);}
    }
}
