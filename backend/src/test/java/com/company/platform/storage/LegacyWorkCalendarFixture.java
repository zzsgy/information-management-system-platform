package com.company.platform.storage;

import com.company.platform.Sql;
import com.company.platform.archive.WorkCalendarService;
import java.time.LocalDate;
import java.util.UUID;

/** 交付补批用例固定一段旧版假期，重现交付推迟后跨七日的历史申请。 */
final class LegacyWorkCalendarFixture extends WorkCalendarService {
    private final Sql db;
    private Integer version;

    LegacyWorkCalendarFixture(Sql db) {
        super(db);
        this.db = db;
    }

    /** 非指定用例继续使用实际国家安排。 */
    void reset() {
        version = null;
    }

    /** 仅在隔离库中建立历史日历，借阅与交付仍走真实服务。 */
    void useDelayedDeliveryCalendar(UUID publisher) {
        version = (int) db.count("SELECT COALESCE(max(calendar_version),0)+1 FROM iam.work_calendar");
        db.update("""
            INSERT INTO iam.work_calendar(calendar_version,calendar_date,is_workday,published_by,change_reason,approval_reference)
            SELECT ?, ?::date+n, n<6 OR n>10, ?, '合成交付跨日假期', 'TEST-DELAY'
            FROM generate_series(0,89) AS n
            """, version, LocalDate.now(ZONE), publisher);
    }

    @Override public int current() {
        return version == null ? super.current() : version;
    }
}
