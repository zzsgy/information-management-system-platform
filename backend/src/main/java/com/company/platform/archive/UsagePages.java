package com.company.platform.archive;

import com.company.platform.*;
import java.util.*;

/** 审批与交付共用的页码规范化；页码升序去重，禁止越界和隐式扩大范围。 */
public final class UsagePages {
    private UsagePages() { }
    /** 页码从1开始，空输入表示批准集合；单项可写1-3,5，总字符数最多2000。 */
    public static List<Integer> parse(Object input,List<Integer> allowed) {
        if(input==null||input.toString().isBlank()) return List.copyOf(allowed);
        if(!(input instanceof String value)||value.length()>2000||!value.matches("[0-9, -]+")) throw ApiFault.invalid("页码请填写1-3,5格式");
        TreeSet<Integer> pages=new TreeSet<>();
        try {for(String part:value.split(",",-1)) {String[] range=part.trim().split("-",-1);if(range.length<1||range.length>2) throw new IllegalArgumentException();int start=Integer.parseInt(range[0].trim()),end=range.length==1?start:Integer.parseInt(range[1].trim());if(start<1||end<start||end>10000) throw new IllegalArgumentException();for(int p=start;p<=end;p++) pages.add(p);}}
        catch(RuntimeException failure) {throw ApiFault.invalid("页码范围无效");}
        if(pages.isEmpty()||!new HashSet<>(allowed).containsAll(pages)) throw ApiFault.invalid("页码超出批准范围或PDF实际页数");return List.copyOf(pages);
    }
    /** 数据库整数数组已由Sql转换为列表，此处拒绝未知来源。 */
    public static List<Integer> list(Object value) {if(!(value instanceof List<?> values)) throw SystemFailure.ARCHIVE_PAGE_DATA_INVALID.fault();return values.stream().map(v->((Number)v).intValue()).toList();}
    /** PDF页数已在绑定阶段经隔离工作器核验。 */
    public static List<Integer> all(int count) {if(count<1||count>10000) throw SystemFailure.ARCHIVE_PAGE_DATA_INVALID.fault();return java.util.stream.IntStream.rangeClosed(1,count).boxed().toList();}
    /** 父子动作集合均规范为固定次序；重复和未知动作直接拒绝。 */
    public static List<String> actions(Object input) {if(!(input instanceof List<?> values)||values.isEmpty()||values.size()>3||new HashSet<>(values).size()!=values.size()||!List.of("VIEW","DOWNLOAD","PRINT").containsAll(values)) throw ApiFault.invalid("请选择独立的查看、下载或打印动作");return List.of("VIEW","DOWNLOAD","PRINT").stream().filter(values::contains).toList();}
}
