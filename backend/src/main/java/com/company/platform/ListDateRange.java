package com.company.platform;

import java.sql.Timestamp;
import java.time.*;
import java.util.Map;

/** 日期筛选按北京时间自然日，结束日期包含整天，不依赖数据库或浏览器默认时区。 */
public record ListDateRange(Timestamp from,Timestamp until) {
    /** 查询边界可单独填写，结束日期转换为次日零点。 */
    public static ListDateRange parse(Map<String,String> query,String fromKey,String toKey){
        try{
            LocalDate from=query.get(fromKey)==null?null:LocalDate.parse(query.get(fromKey));
            LocalDate to=query.get(toKey)==null?null:LocalDate.parse(query.get(toKey));
            if(from!=null&&to!=null&&to.isBefore(from))throw ApiFault.invalid("结束日期不能早于开始日期");
            ZoneId zone=ZoneId.of("Asia/Shanghai");
            return new ListDateRange(from==null?null:Timestamp.from(from.atStartOfDay(zone).toInstant()),to==null?null:Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
        }catch(DateTimeException invalid){throw ApiFault.invalid("日期须为有效年月日");}
    }
}
