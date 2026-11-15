package com.company.platform.archive;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 计算借阅截止，并保留切换前已送审申请所绑定的日历版本。 */
@Service
public class WorkCalendarService {
    /** 借阅期限按中国业务日期计算。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 历史公司版本为正数，负值标记新申请采用国家年度安排。 */
    private static final int NATIONAL_CALENDAR = -1;
    private final Sql db;

    public WorkCalendarService(Sql db) {
        this.db = db;
    }

    /** 新申请选择国家安排，实际覆盖范围在计算截止时检查。 */
    public int current() {
        return NATIONAL_CALENDAR;
    }
    /** 校验固定期限规则；本轮电子阅读支持工作日或自然日，默认三工作日，最多30自然日。 */
    public Map<String,Object> period(Object input) {
        if(!(input instanceof Map<?,?> raw)) throw ApiFault.invalid("请指定借阅期限");
        @SuppressWarnings("unchecked") Map<String,Object> body=(Map<String,Object>)raw;Inputs.only(body,"mode","amount","requestedUntil");
        String mode=Inputs.choice(body.getOrDefault("mode","WORKDAYS"),"WORKDAYS","NATURAL_DAYS");int amount=Inputs.integer(body.get("amount"),3,1,30);
        if(body.get("requestedUntil")!=null) Instant.parse(Inputs.text(body,"requestedUntil",64));
        return Sql.map("mode",mode,"amount",amount,"requestedUntil",body.get("requestedUntil"));
    }
    /** 从批准生效日后的第一日开始计，工作日截止当日23:59:59，原日历版本不会被新版本覆盖。 */
    public Instant until(int version,Map<String,Object> period,Instant effective) {
        return until(version,period,effective,LocalTime.of(23,59,59));
    }
    /** 新纸质期限按批准或续期起点的本地时刻到期，沿用相同的工作日历。 */
    public Instant paperUntil(int version,Map<String,Object> period,Instant effective) {
        return until(version,period,effective,effective.atZone(ZONE).toLocalTime());
    }
    private Instant until(int version,Map<String,Object> period,Instant effective,LocalTime cutoff) {
        LocalDate start=effective.atZone(ZONE).toLocalDate(),end=null;int days=((Number)period.get("amount")).intValue();
        if("NATURAL_DAYS".equals(period.get("mode"))) end=start.plusDays(days);
        else {
            int counted=0;for(int day=1;day<=30;day++) {
                LocalDate date = start.plusDays(day);
                if (isWorkday(version, date) && ++counted == days) {
                    end = date;
                    break;
                }
            }
        }
        if(end==null||end.isAfter(start.plusDays(30))) throw ApiFault.invalid("借阅期限超过30自然日");
        Instant result=end.atTime(cutoff).atZone(ZONE).toInstant();
        if(period.get("requestedUntil")!=null) {Instant requested=Instant.parse(period.get("requestedUntil").toString());if(requested.isBefore(result)) result=requested;}
        if(!result.isAfter(effective)) throw ApiFault.conflict("申请期限已失效，请重新送审");return result;
    }
    /** 历史申请继续读取原版本，避免切换政策时改变审批依据。 */
    private boolean isWorkday(int version, LocalDate date) {
        if (version == NATIONAL_CALENDAR) return NationalWorkdays.isWorkday(date);
        var entry = db.one("SELECT is_workday FROM iam.work_calendar WHERE calendar_version=? AND calendar_date=?", version, date);
        if (entry == null) throw ApiFault.conflict("原批准日历版本缺少必要日期，请重新送审");
        return Boolean.TRUE.equals(entry.get("is_workday"));
    }
}
