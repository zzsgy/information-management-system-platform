package com.company.platform.intake;

import com.company.platform.*;
import java.nio.file.Path;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.w3c.dom.*;
import static com.company.platform.intake.ImportWorkbook.*;

/** 电子清单只读取登记信息，不把纸质份数解释为需要上传的电子文件数量。 */
public final class ArchiveChecklistWorkbook {
    public record Entry(int row,int sequence,String title,LocalDate date,int paperCopies,String notes,String boxNumber,String signingEntity,String contractNumber,String year) { }
    private ArchiveChecklistWorkbook() { }
    /** 按中文表头对应列，合同清单增加签订主体、合同编号，兼容原五列及六列清单。 */
    public static List<Entry> read(Path path){return read(path,false);}

    /** 补录时原序号可以重复，实际序号由档案盒分配。 */
    public static List<Entry> read(Path path,boolean allowRepeatedSequence){
        try{
            var parts=parts(path);var document=xml(parts.get("xl/worksheets/sheet1.xml"));
            if(elements(xml(parts.get("xl/workbook.xml")),"sheet").size()!=1||!elements(document,"f").isEmpty())throw ApiFault.invalid("电子清单仅支持一个工作表且不能包含公式");
            List<String> shared=new ArrayList<>();if(parts.containsKey("xl/sharedStrings.xml"))for(var item:elements(xml(parts.get("xl/sharedStrings.xml")),"si"))shared.add(text(item,"t"));
            boolean date1904=elements(xml(parts.get("xl/workbook.xml")),"workbookPr").stream().anyMatch(e->Set.of("1","true").contains(e.getAttribute("date1904")));
            List<Entry> result=new ArrayList<>();Set<Integer> sequences=new HashSet<>();Map<String,Integer> columns=null;int last=0;
            for(Element row:elements(document,"row")){
                int number=Inputs.integer(row.getAttribute("r"),0,1,1003);if(number<=last)throw ApiFault.invalid("清单行号重复或乱序");last=number;
                Map<Integer,String> cells=new TreeMap<>();Set<Integer> seen=new HashSet<>();
                for(Element cell:elements(row,"c")){
                    String coordinate=cell.getAttribute("r");if(!coordinate.matches("[A-Z]{1,2}"+number))throw ApiFault.invalid("清单单元格坐标不一致");
                    int column=0;for(char letter:coordinate.replaceAll("[0-9]","").toCharArray())column=column*26+letter-'A'+1;
                    if(!seen.add(column))throw ApiFault.invalid("清单单元格重复");
                    String kind=cell.getAttribute("t"),value=kind.equals("inlineStr")?text(cell,"t"):text(cell,"v");
                    if(kind.equals("s"))value=shared.get(Inputs.integer(value,0,0,shared.size()-1));else if(!Set.of("","n","str","inlineStr","d").contains(kind))throw ApiFault.invalid("清单包含不支持的单元格类型");
                    if(value.length()>20000)throw ApiFault.invalid("清单单元格超过长度上限");if(!value.isBlank())cells.put(column,value.trim());
                }
                if(cells.isEmpty())continue;
                if(columns==null){columns=headers(cells);continue;}
                try{
                    if(!columns.values().containsAll(cells.keySet()))throw ApiFault.invalid("存在没有表头的内容列，请补充表头或移除该列");
                    int sequence=positiveInteger(cells.get(columns.get("序号")),"序号");if(!allowRepeatedSequence&&!sequences.add(sequence))throw ApiFault.invalid("序号重复："+sequence);
                    String title=Inputs.text(Sql.map("存放内容",cells.get(columns.get("存放内容"))),"存放内容",512);int copies=positiveInteger(cells.get(columns.get("存档份数")),"存档份数");
                    String raw=cells.get(columns.get("日期"));
                    // 2021在用户原表中是普通数值年份，不能当作Excel日期序号或补造月日。
                    String year=raw!=null&&raw.matches("[1-9][0-9]{3}(?:\\.0+)?")?raw.substring(0,4):null;
                    LocalDate date=raw==null||year!=null?null:LocalDate.parse(ArchiveBoxWorkbook.dateValue(raw,date1904,false),DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT));
                    String boxNumber=optionalText(cells,columns,"存放编号",128);
                    result.add(new Entry(number,sequence,title,date,copies,optionalText(cells,columns,"备注",2000),boxNumber.isBlank()?null:boxNumber,optionalText(cells,columns,"签订主体",512),optionalText(cells,columns,"合同编号",256),year));
                }catch(RuntimeException invalid){throw ApiFault.invalid("第 "+number+" 行："+(invalid instanceof ApiFault?invalid.getMessage():"日期格式不正确"));}
                if(result.size()>1000)throw ApiFault.invalid("电子清单每次最多1000条");
            }
            if(result.isEmpty())throw ApiFault.invalid("电子清单没有有效记录");return result;
        }catch(ApiFault error){throw error;}catch(Exception error){throw ApiFault.invalid("电子清单不是有效的 XLSX 文件");}
    }
    /** 字段按名称定位，列顺序及空白前置列不改变对应关系；未知列明确报错，不能静默丢失。 */
    private static Map<String,Integer> headers(Map<Integer,String> cells){
        Set<String> required=Set.of("序号","日期","存放内容","存档份数","备注"),allowed=Set.of("存放编号","序号","日期","签订主体","存放内容","合同编号","存档份数","备注");Map<String,Integer> result=new LinkedHashMap<>();
        for(var cell:cells.entrySet()){String name=cell.getValue().replaceAll("\\s","");if(!allowed.contains(name))throw ApiFault.invalid("电子清单无法识别表头："+name+"。支持：存放编号、序号、日期、签订主体、存放内容、合同编号、存档份数、备注");if(result.put(name,cell.getKey())!=null)throw ApiFault.invalid("电子清单表头重复："+name);}
        var missing=required.stream().filter(name->!result.containsKey(name)).sorted().toList();if(!missing.isEmpty())throw ApiFault.invalid("电子清单缺少表头："+String.join("、",missing));return result;
    }
    /** 选填列可省略或留空；编号保留文本，不作为数值重新格式化。 */
    private static String optionalText(Map<Integer,String> cells,Map<String,Integer> columns,String field,int maximum){Integer column=columns.get(field);String value=column==null?"":cells.getOrDefault(column,"");if(value.length()>maximum)throw ApiFault.invalid(field+"最多"+maximum+"字");return value;}
    /** Excel可能将整数保存为1.0，只有精确整数才接收，不能截断小数。 */
    private static int positiveInteger(String raw,String field){try{int value=new java.math.BigDecimal(raw).intValueExact();if(value<1||value>1000000)throw new ArithmeticException();return value;}catch(RuntimeException invalid){throw ApiFault.invalid(field+"必须是1至1000000的整数");}}

}
