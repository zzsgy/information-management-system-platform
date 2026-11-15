package com.company.platform.archive;

import com.company.platform.ApiFault;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 用官方公告中的实际日期校验借期，不依赖测试机当天日期或公司配置。 */
class NationalWorkCalendarTest {
    private final WorkCalendarService calendar = new WorkCalendarService(null);

    @Test void nationalHolidayAndWeekendMakeupAffectDeadline() {
        assertEquals(Instant.parse("2026-10-08T15:59:59Z"),
            calendar.until(calendar.current(), Map.of("mode", "WORKDAYS", "amount", 1),
                Instant.parse("2026-09-30T03:00:00Z")));
        assertEquals(Instant.parse("2026-10-12T15:59:59Z"),
            calendar.until(calendar.current(), Map.of("mode", "WORKDAYS", "amount", 2),
                Instant.parse("2026-10-09T03:00:00Z")));
        assertEquals(Instant.parse("2026-11-10T15:59:59Z"),
            calendar.until(calendar.current(), Map.of("mode", "WORKDAYS", "amount", 3),
                Instant.parse("2026-11-05T03:00:00Z")));
    }

    @Test void onlyDatesNeededForWorkdayDeadlineRequireAnnouncedData() {
        Instant start = Instant.parse("2026-12-30T03:00:00Z");
        assertEquals(Instant.parse("2026-12-31T15:59:59Z"),
            calendar.until(calendar.current(), Map.of("mode", "WORKDAYS", "amount", 1), start));
        assertEquals(Instant.parse("2027-01-01T15:59:59Z"),
            calendar.until(calendar.current(), Map.of("mode", "NATURAL_DAYS", "amount", 2), start));
        var failure = assertThrows(ApiFault.class, () ->
            calendar.until(calendar.current(), Map.of("mode", "WORKDAYS", "amount", 1),
                Instant.parse("2199-12-31T03:00:00Z")));
        assertTrue(failure.getMessage().contains("2200年"));
    }
}
