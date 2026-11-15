package com.company.platform.archive;

import com.company.platform.ApiFault;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Map;
import java.util.Set;

/** 以双休为基线，按国务院办公厅年度通知覆盖放假和补班日期。 */
final class NationalWorkdays {
    /** 年度通知按实际公历日期归档，发布新版程序时可添加已公布年度。 */
    private static final Map<Integer, Arrangement> ANNOUNCED = Map.of(2026, new Arrangement(
        // 国办发明电〔2025〕7号：https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm
        Set.of(MonthDay.of(1, 4), MonthDay.of(2, 14), MonthDay.of(2, 28),
            MonthDay.of(5, 9), MonthDay.of(9, 20), MonthDay.of(10, 10)),
        Map.of(MonthDay.of(1, 1), MonthDay.of(1, 3),
            MonthDay.of(2, 15), MonthDay.of(2, 23),
            MonthDay.of(4, 4), MonthDay.of(4, 6),
            MonthDay.of(5, 1), MonthDay.of(5, 5),
            MonthDay.of(6, 19), MonthDay.of(6, 21),
            MonthDay.of(9, 25), MonthDay.of(9, 27),
            MonthDay.of(10, 1), MonthDay.of(10, 7))));

    /** 补班日期及首尾均包含的放假区间。 */
    private record Arrangement(Set<MonthDay> makeupDays, Map<MonthDay, MonthDay> holidays) {}

    /** 只在实际遇到尚未载入官方安排的年度时提示。 */
    static boolean isWorkday(LocalDate date) {
        var arrangement = ANNOUNCED.get(date.getYear());
        if (arrangement == null)
            throw ApiFault.conflict("系统尚未载入" + date.getYear() + "年国家节假日安排，暂无法计算跨年工作日借期");
        MonthDay day = MonthDay.from(date);
        if (arrangement.makeupDays().contains(day)) return true;
        if (date.getDayOfWeek().getValue() >= 6) return false;
        return arrangement.holidays().entrySet().stream()
            .noneMatch(range -> day.compareTo(range.getKey()) >= 0 && day.compareTo(range.getValue()) <= 0);
    }
}
