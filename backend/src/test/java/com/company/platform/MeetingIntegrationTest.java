package com.company.platform;

import com.company.platform.meeting.MeetingBookings;
import com.company.platform.meeting.MeetingFollowUp;
import com.company.platform.meeting.MeetingWorkbooks;
import com.company.platform.meeting.MeetingWeeklyImports;
import com.company.platform.meeting.MeetingIncompleteExport;
import com.company.platform.portal.PortalWork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 在独立 PostgreSQL 调用正式会话和事务服务，验证会议规则与工作台投影。 */
@EnabledIfEnvironmentVariable(named = "IMS_MEETING_INTEGRATION", matches = "yes")
@SpringBootTest(classes = PlatformApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=platform", "server.ssl.enabled=false", "platform.messaging.schedule-enabled=false",
                "platform.notifications.schedule-enabled=false", "platform.file-worker.enabled=false",
                "platform.scan-intake.worker-enabled=false", "platform.borrow-lifecycle.enabled=false"})
class MeetingIntegrationTest {
    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        String url = Objects.toString(System.getenv("IMS_PLATFORM_DB_URL"), "");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:55514/ims_platform_dev_[0-9]+"))
            throw new IllegalStateException("会议验收只能连接独立数据库");
    }

    @Autowired Sql db;
    @Autowired IdentityService identity;
    @Autowired MeetingBookings bookings;
    @Autowired MeetingFollowUp followUp;
    @Autowired MeetingWorkbooks workbooks;
    @Autowired MeetingWeeklyImports weeklyImports;
    @Autowired MeetingIncompleteExport incomplete;
    @Autowired PortalWork portal;

    private Actor admin, organizer, employee, outsider;
    private UUID roomA, roomB, department;

    @BeforeEach
    void arrange() {
        admin = login(System.getenv("IMS_PLATFORM_ADMIN_NO"), System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));
        department = request();
        db.update("INSERT INTO iam.department(id,code,name) VALUES (?,?,?)",
                department, "MEETING-" + department.toString().substring(0, 8), "会议隔离验收部门");
        organizer = person();
        employee = person();
        outsider = person();
        roomA = bookings.saveRoom(admin, null, request(), Map.of("name", "隔离会议室 A " + request()));
        roomB = bookings.saveRoom(admin, null, request(), Map.of("name", "隔离会议室 B " + request()));
    }

    /** V81、V82 已保存的数据随部署保留，原在线周会回到会前填报、导入及正常开会流程。 */
    @Test
    void formerOnlineWeeklyMeetingUsesStandardMaterials() throws IOException {
        Actor host = grant(organizer, "MEETING_ORGANIZER");
        var body = schedule(roomA, Instant.now().plusSeconds(3600), List.of(employee), List.of());
        body.putAll(Map.of("kind", "WEEKLY", "subject", "历史在线周会",
                "materialTypes", List.of("REPORT", "WEEKLY_PLAN", "COORDINATION")));
        UUID meeting = bookings.create(host, request(), body);
        bookings.reply(employee, meeting, request(), Map.of("decision", "ACCEPTED"));
        bookings.materials(employee, meeting, request(), Map.of("kind", "REPORT", "text", "已提取的上周汇报"));
        UUID attendeeId = (UUID) attendee(host, meeting, employee).get("id");
        byte[] workbook = workbooks.download(host, meeting, true);
        db.update("UPDATE portal.meeting_booking SET online_weekly_report=true WHERE id=?", meeting);
        db.update("UPDATE portal.meeting_attendee SET weekly_department_id=? WHERE id=?", department, attendeeId);
        db.update("""
                UPDATE portal.meeting_material SET payload=payload || '{"source":"ONLINE_WEEKLY"}'::jsonb
                WHERE attendee_id=? AND kind='REPORT'
                """, attendeeId);
        db.update("INSERT INTO portal.meeting_weekly_department(meeting_id,department_id,name) VALUES (?,?,?)",
                meeting, department, "历史填报部门");
        db.update("INSERT INTO portal.meeting_weekly_excel(meeting_id,department_id,workbook) VALUES (?,?,?)",
                meeting, department, workbook);
        db.update("""
                INSERT INTO portal.meeting_weekly_excel_session(id,meeting_id,department_id,state,base_revision)
                VALUES (?,?,?,'EDITING',1)
                """, request(), meeting, department);

        assertEquals(true, bookings.detail(employee, meeting).get("canSubmitMaterials"));
        assertEquals("MEETING_MATERIALS_PENDING", attendeeTask(employee, "#/meetings?id=" + meeting + "&action=materials").get("state"));
        bookings.materials(employee, meeting, request(), Map.of("kind", "COORDINATION", "rows",
                List.of(ownedCoordination(Map.of("subject", "协调工位", "expected", "确认可用工位")))));
        String file = filledWorkbook(workbook, "", "Excel 导入的本周计划", Map.of("F", employeeLabel(employee)));
        var preview = workbooks.importMaterials(host, meeting, request(), Map.of("file", file));
        assertEquals(List.of(), preview.get("errors"));
        assertEquals(1, workbooks.importMaterials(host, meeting, request(),
                Map.of("file", file, "preview", preview.get("preview"))).get("imported"));

        bookings.start(host, meeting, request());
        assertEquals("IN_PROGRESS", bookings.detail(host, meeting).get("state"));
        var materials = values(attendee(host, meeting, employee), "materials").toString();
        assertTrue(materials.contains("已提取的上周汇报"));
        assertTrue(materials.contains("Excel 导入的本周计划"));
        assertArrayEquals(workbook, (byte[]) db.required("""
                SELECT workbook FROM portal.meeting_weekly_excel WHERE meeting_id=? AND department_id=?
                """, meeting, department).get("workbook"));
    }

    @Test
    void refusalReleasesEmployeeAndUrgentNeverOverridesRoom() {
        Instant starts = Instant.now().plusSeconds(1800);
        UUID first = bookings.create(organizer, request(), meeting(roomA, starts, employee));
        assertEquals("MEETING_PERSON_CONFLICT", assertThrows(ApiFault.class,
                () -> bookings.create(outsider, request(), meeting(roomB, starts, employee))).code);
        assertEquals("MEETING_ROOM_CONFLICT", assertThrows(ApiFault.class,
                () -> bookings.create(outsider, request(), meeting(roomA, starts, employee))).code);
        assertEquals(400, assertThrows(ApiFault.class,
                () -> bookings.reply(employee, first, request(), Map.of("decision", "DECLINED"))).status);
        bookings.reply(employee, first, request(), Map.of("decision", "DECLINED", "reason", "此前已有安排"));
        assertFalse(hasPending(employee, first));
        UUID second = bookings.create(outsider, request(), meeting(roomB, starts, employee));
        assertEquals("MEETING_PERSON_CONFLICT", assertThrows(ApiFault.class,
                () -> bookings.reply(employee, first, request(), Map.of("decision", "ACCEPTED"))).code);
        assertEquals(403, assertThrows(ApiFault.class,
                () -> bookings.create(outsider, request(), urgent(roomA, starts, employee))).status);
        Actor authorized = grant(outsider, "MEETING_URGENT");
        assertEquals("MEETING_ROOM_CONFLICT", assertThrows(ApiFault.class,
                () -> bookings.create(authorized, request(), urgent(roomA, starts, employee))).code);
        UUID roomC = bookings.saveRoom(admin, null, request(), Map.of("name", "隔离会议室 C " + request()));
        UUID urgent = bookings.create(authorized, request(), urgent(roomC, starts, employee));
        assertTrue(hasPending(employee, urgent));
        bookings.cancel(authorized, second, request(), Map.of("reason", "临时取消"));
        assertFalse(hasPending(employee, second));
        assertTrue(values(portal.inbox(employee, Map.of("pageSize", "100")), "items").stream()
                .anyMatch(message -> Objects.toString(message.get("content"), "").contains("临时取消")));
        assertEquals("CANCELLED", bookings.detail(employee, second).get("state"));
    }

    @Test
    void meetingChangesPreserveResponsesAndMaterials() {
        Instant starts = Instant.now().plusSeconds(7200);
        var body = meeting(roomA, starts, employee);
        body.put("collectMaterials", true);
        body.put("materialUserIds", List.of(employee.userId().toString()));
        UUID id = bookings.create(organizer, request(), body);
        bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED"));
        Object repliedAt = db.one("SELECT replied_at FROM portal.meeting_attendee WHERE meeting_id=? AND user_id=?",
                id, employee.userId()).get("replied_at");
        bookings.materials(employee, id, request(), Map.of("topics", "测试议题"));
        bookings.change(organizer, id, request(), 1, schedule(roomB, starts, List.of(employee), List.of(employee)));
        assertEquals("ACCEPTED", attendee(organizer, id, employee).get("reply"));
        assertFalse(hasPending(employee, id));
        bookings.change(organizer, id, request(), 2, schedule(roomB, starts.plusSeconds(3600),
                List.of(employee, outsider), List.of(employee)));
        assertEquals("ACCEPTED", attendee(organizer, id, employee).get("reply"));
        assertEquals(repliedAt, db.one("SELECT replied_at FROM portal.meeting_attendee WHERE meeting_id=? AND user_id=?",
                id, employee.userId()).get("replied_at"));
        assertFalse(hasPending(employee, id));
        assertTrue(hasPending(outsider, id));
        assertEquals("测试议题", attendee(organizer, id, employee).get("topics"));
        assertFalse(attendee(outsider, id, employee).containsKey("topics"));
        bookings.change(organizer, id, request(), 3, schedule(roomB, starts.plusSeconds(3600),
                List.of(employee), List.of(employee)));
        assertFalse(hasPending(outsider, id));
        assertEquals(404, assertThrows(ApiFault.class, () -> bookings.detail(outsider, id)).status);

        bookings.start(organizer, id, request());
        bookings.end(organizer, id, request());
        assertEquals("AWAITING_MINUTES", bookings.detail(employee, id).get("state"));
        assertEquals("ACCEPTED", attendee(organizer, id, employee).get("reply"));
        assertFalse(hasPending(employee, id));
        var history = values(portal.tasks(employee, Map.of("category", "COMPLETED")), "items");
        assertTrue(history.stream().anyMatch(row -> ("#/meetings?id=" + id).equals(row.get("entry"))
                && "ACCEPTED".equals(row.get("state")) && row.get("completedAt") != null));
        assertEquals(409, assertThrows(ApiFault.class,
                () -> bookings.materials(employee, id, request(), Map.of("topics", "迟交"))).status);
    }

    @Test
    void rescheduleKeepsDeclineAndReinvitingKeepsPreviousReply() {
        Instant starts = Instant.now().plusSeconds(10800);
        UUID id = bookings.create(organizer, request(), meeting(roomA, starts, employee));
        bookings.reply(employee, id, request(), Map.of("decision", "DECLINED", "reason", "已有安排"));
        Object repliedAt = db.one("SELECT replied_at FROM portal.meeting_attendee WHERE meeting_id=? AND user_id=?",
                id, employee.userId()).get("replied_at");
        bookings.create(outsider, request(), meeting(roomB, starts.plusSeconds(3600), employee));
        bookings.change(organizer, id, request(), 1, schedule(roomA, starts.plusSeconds(3600),
                List.of(employee), List.of()));
        assertEquals("DECLINED", attendee(organizer, id, employee).get("reply"));
        assertEquals("已有安排", attendee(organizer, id, employee).get("declineReason"));
        assertEquals(repliedAt, db.one("SELECT replied_at FROM portal.meeting_attendee WHERE meeting_id=? AND user_id=?",
                id, employee.userId()).get("replied_at"));
        assertFalse(hasPending(employee, id));

        bookings.change(organizer, id, request(), 2, schedule(roomA, starts.plusSeconds(7200),
                List.of(outsider), List.of()));
        bookings.change(organizer, id, request(), 3, schedule(roomA, starts.plusSeconds(7200),
                List.of(employee, outsider), List.of()));
        assertEquals("DECLINED", attendee(organizer, id, employee).get("reply"));
        assertEquals("PENDING", attendee(organizer, id, outsider).get("reply"));
        bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED"));
        assertEquals("ACCEPTED", attendee(organizer, id, employee).get("reply"));
    }

    @Test
    void weeklyManagerAndOutsideAssigneeFollowDifferentPermissions() {
        Instant starts = Instant.now().plusSeconds(10800);
        var body = meeting(roomA, starts, employee);
        body.put("kind", "WEEKLY");
        assertEquals(403, assertThrows(ApiFault.class, () -> bookings.create(organizer, request(), body)).status);
        Actor weekly = grant(organizer, "MEETING_ORGANIZER");
        assertFalse(weekly.actions().contains("PLATFORM.MEETING_URGENT"));
        db.update("UPDATE iam.company_duty SET valid_until=CURRENT_TIMESTAMP WHERE duty_code='GENERAL_MANAGER' AND valid_until IS NULL");
        Actor general = person();
        db.update("""
                INSERT INTO iam.company_duty(id,duty_code,user_id,valid_from,changed_by,change_reason)
                VALUES (?,'GENERAL_MANAGER',?,CURRENT_TIMESTAMP,?,'会议验收')
                """, request(), general.userId(), admin.userId());
        db.update("""
                INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by)
                VALUES (?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'会议验收',?)
                """, request(), department, employee.userId(), admin.userId());
        UUID id = bookings.create(weekly, request(), body);
        db.update("UPDATE portal.meeting_booking SET share_all=NULL WHERE id=?", id);
        bookings.reply(employee, id, request(), Map.of("decision", "DECLINED", "reason", "出差"));
        assertTrue(bookings.detail(employee, id).get("canSubmitMaterials").equals(true));
        bookings.materials(employee, id, request(), Map.of("report", "部门汇报"));
        assertEquals("部门汇报", attendee(general, id, employee).get("report"));
        bookings.start(weekly, id, request());
        bookings.end(weekly, id, request());
        var minutes = (Map<?, ?>) bookings.detail(weekly, id).get("minutes");
        assertTrue(minutes.get("materialsText").toString().contains("部门汇报"));
        assertNull(bookings.detail(employee, id).get("minutes"));
        var task = Map.of("content", "落实决议", "assigneeId", outsider.userId().toString(),
                "dueAt", Instant.now().minusSeconds(60).toString());
        followUp.saveMinutes(weekly, id, request(), 3, Sql.map("materialsText", minutes.get("materialsText"),
                "decisions", "周会决议", "items", List.of(task), "publish", false));
        assertEquals(outsider.user().get("name"),
                values((Map<String, Object>) bookings.detail(general, id).get("minutes"), "items").getFirst().get("assigneeName"));
        followUp.saveMinutes(weekly, id, request(), 4, Sql.map("materialsText", minutes.get("materialsText"),
                "decisions", "周会决议", "items", List.of(task), "publish", true));
        assertNotNull(bookings.detail(employee, id).get("minutes"));
        assertEquals(false, values(bookings.detail(employee, id), "items").getFirst().get("canOpen"));
        assertEquals(404, assertThrows(ApiFault.class, () -> bookings.detail(outsider, id)).status);
        UUID item = (UUID) values(bookings.detail(weekly, id), "items").getFirst().get("id");
        assertEquals(true, followUp.item(outsider, item).get("overdue"));
        assertEquals(404, assertThrows(ApiFault.class, () -> followUp.item(employee, item)).status);
        assertEquals(409, assertThrows(ApiFault.class, () -> followUp.close(weekly, id, request())).status);
        followUp.feedback(outsider, item, request(), Map.of("feedback", "初次结果"));
        followUp.review(weekly, item, request(), Map.of("decision", "RETURNED", "reason", "请补证据"));
        assertEquals(true, followUp.item(outsider, item).get("overdue"));
        followUp.feedback(outsider, item, request(), Map.of("feedback", "补充结果"));
        followUp.review(weekly, item, request(), Map.of("decision", "CONFIRMED"));
        followUp.close(weekly, id, request());
        assertEquals("CLOSED", bookings.detail(general, id).get("state"));
        assertEquals(4, values(followUp.item(outsider, item), "history").size());
        assertTrue(values(portal.tasks(outsider, Map.of("category", "COMPLETED")), "items").stream()
                .anyMatch(row -> item.equals(row.get("id"))));
    }

    @Test
    void delayedStartClosesPendingTasksAndOrdinaryMeetingRequiresMinutes() {
        var body = meeting(roomA, Instant.now().plusSeconds(3600), employee);
        body.put("materialTypes", List.of("REPORT", "COORDINATION"));
        UUID id = bookings.create(organizer, request(), body);
        // 只调整预约时刻以模拟推迟，状态变化和实际时间仍由业务接口写入。
        db.update("UPDATE portal.meeting_booking SET starts_at=clock_timestamp()-interval '1 minute' WHERE id=?", id);
        assertEquals("SCHEDULED", bookings.detail(employee, id).get("state"));
        assertTrue(hasPending(employee, id));
        assertEquals(404, assertThrows(ApiFault.class, () -> bookings.start(employee, id, request())).status);
        assertEquals(409, assertThrows(ApiFault.class, () -> bookings.end(organizer, id, request())).status);
        bookings.start(organizer, id, request());
        assertEquals("IN_PROGRESS", bookings.detail(employee, id).get("state"));
        assertFalse(hasPending(employee, id));
        assertEquals(1, values(portal.tasks(employee, Map.of("category", "COMPLETED")), "items").stream()
                .filter(row -> row.get("entry").toString().contains(id.toString())).count());
        assertEquals(1, db.count("SELECT count(*) FROM portal.meeting_message WHERE meeting_id=? AND kind='MEETING_STARTED'", id));
        assertEquals(409, assertThrows(ApiFault.class, () -> bookings.start(organizer, id, request())).status);
        assertEquals(409, assertThrows(ApiFault.class,
                () -> bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED"))).status);
        bookings.end(organizer, id, request());
        assertEquals("AWAITING_MINUTES", bookings.detail(organizer, id).get("state"));
        assertTrue(hasPending(organizer, id));
        assertEquals(400, assertThrows(ApiFault.class,
                () -> followUp.saveMinutes(organizer, id, request(), 3, Map.of("publish", true, "decisions", " "))).status);
        followUp.saveMinutes(organizer, id, request(), 3, Map.of("publish", true, "decisions", "会议已达成共识"));
        assertEquals("CLOSED", bookings.detail(employee, id).get("state"));
        assertNotNull(bookings.detail(employee, id).get("minutes"));
        assertFalse(hasPending(organizer, id));
    }

    @Test
    void earlyStartChecksRoomAndPeopleAndExpiredReservationCanBeRescheduled() {
        Instant start = Instant.now().plusSeconds(3600);
        UUID occupied = bookings.create(outsider, request(), meeting(roomA, start, employee));
        UUID sameRoom = bookings.create(organizer, request(), meeting(roomA, start.plusSeconds(1800), employee));
        UUID differentRoom = bookings.create(organizer, request(), meeting(roomB, start.plusSeconds(3600), employee));
        db.update("UPDATE portal.meeting_booking SET starts_at=clock_timestamp()-interval '1 minute' WHERE id=?", occupied);
        assertEquals("MEETING_ROOM_CONFLICT", assertThrows(ApiFault.class,
                () -> bookings.start(organizer, sameRoom, request())).code);
        assertEquals("MEETING_PERSON_CONFLICT", assertThrows(ApiFault.class,
                () -> bookings.start(organizer, differentRoom, request())).code);
        assertEquals("SCHEDULED", bookings.detail(organizer, sameRoom).get("state"));
        db.update("UPDATE portal.meeting_booking SET starts_at=clock_timestamp()-interval '2 hours',ends_at=clock_timestamp()-interval '1 hour' WHERE id=?", sameRoom);
        assertEquals(409, assertThrows(ApiFault.class, () -> bookings.start(organizer, sameRoom, request())).status);
        bookings.change(organizer, sameRoom, request(), 1,
                schedule(roomA, start.plusSeconds(7200), List.of(employee), List.of()));
        bookings.cancel(outsider, occupied, request(), Map.of("reason", "结束隔离占用"));
        bookings.cancel(organizer, differentRoom, request(), Map.of("reason", "结束隔离占用"));
        bookings.start(organizer, sameRoom, request());
        assertEquals("IN_PROGRESS", bookings.detail(organizer, sameRoom).get("state"));
    }

    @Test
    void meetingNotesAndSummaryTablesAreFilteredOnTheServer() {
        var body = meeting(roomA, Instant.now().plusSeconds(3600), employee);
        body.put("attendeeIds", List.of(employee.userId().toString(), outsider.userId().toString()));
        body.put("materialTypes", List.of("COORDINATION"));
        UUID id = bookings.create(organizer, request(), body);
        db.update("UPDATE portal.meeting_booking SET share_all=NULL,material_types='{TOPICS,COORDINATION}' WHERE id=?", id);
        var material = Map.of("subject", "待讨论事项", "expected", "明确要求");
        bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED", "materials", List.of(
                Map.of("kind", "TOPICS", "rows", List.of(material)),
                Map.of("kind", "COORDINATION", "rows", List.of(ownedCoordination(material))))));
        assertEquals(409, assertThrows(ApiFault.class,
                () -> bookings.saveLiveNotes(organizer, id, Map.of("notes", "还没开始"))).status);
        bookings.start(organizer, id, request());
        assertEquals(404, assertThrows(ApiFault.class,
                () -> bookings.saveLiveNotes(employee, id, Map.of("notes", "未授权"))).status);
        bookings.saveLiveNotes(organizer, id, Map.of("notes", "现场笔记"));
        assertEquals("现场笔记", bookings.detail(organizer, id).get("liveNotes"));
        assertFalse(bookings.detail(employee, id).containsKey("liveNotes"));
        bookings.end(organizer, id, request());
        assertEquals(409, assertThrows(ApiFault.class,
                () -> bookings.saveLiveNotes(organizer, id, Map.of("notes", "已结束"))).status);
        assertEquals("现场笔记", ((Map<?, ?>) bookings.detail(organizer, id).get("minutes")).get("notes"));
        assertNull(bookings.detail(employee, id).get("minutes"));
        assertEquals(400, assertThrows(ApiFault.class, () -> followUp.saveMinutes(organizer, id, request(), 3,
                Map.of("publish", true, "decisions", "同意", "noteReaderIds", List.of(admin.userId().toString())))).status);
        followUp.saveMinutes(organizer, id, request(), 3, Map.of("publish", true, "decisions", "同意",
                "notes", "已整理记录", "noteReaderIds", List.of(employee.userId().toString()), "shareTopics", true));
        var reader = (Map<?, ?>) bookings.detail(employee, id).get("minutes");
        var other = (Map<?, ?>) bookings.detail(outsider, id).get("minutes");
        var owner = (Map<?, ?>) bookings.detail(organizer, id).get("minutes");
        assertEquals("已整理记录", reader.get("notes"));
        assertFalse(other.containsKey("notes"));
        assertFalse(reader.containsKey("noteReaderIds"));
        assertEquals(List.of("TOPICS"), other.get("summaryTypes"));
        assertEquals(1, ((List<?>) other.get("materialTables")).size());
        assertEquals(2, ((List<?>) owner.get("materialTables")).size());
    }

    @Test
    void requestedWeeklyPlanAndLiveDecisionCarryThroughToMinutes() {
        var body = meeting(roomA, Instant.now().plusSeconds(3600), employee);
        body.put("materialTypes", List.of("WEEKLY_PLAN"));
        UUID id = bookings.create(organizer, request(), body);
        assertTrue(hasPending(employee, id));
        bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED"));
        assertTrue(hasPending(employee, id));
        bookings.materials(employee, id, request(), Map.of("kind", "WEEKLY_PLAN", "text", "1. 完成排期"));
        assertFalse(hasPending(employee, id));
        assertEquals("WEEKLY_PLAN", values(attendee(organizer, id, employee), "materials").getFirst().get("kind"));
        assertEquals(409, assertThrows(ApiFault.class,
                () -> bookings.saveLiveDecisions(organizer, id, Map.of("decisions", "尚未开始"))).status);
        bookings.start(organizer, id, request());
        assertEquals(404, assertThrows(ApiFault.class,
                () -> bookings.saveLiveDecisions(employee, id, Map.of("decisions", "受邀员工不能编辑"))).status);
        bookings.saveLiveDecisions(organizer, id, Map.of("decisions", "1. 确认排期"));
        assertEquals("1. 确认排期", bookings.detail(organizer, id).get("liveDecisions"));
        assertFalse(bookings.detail(employee, id).containsKey("liveDecisions"));
        bookings.end(organizer, id, request());
        var draft = (Map<?, ?>) bookings.detail(organizer, id).get("minutes");
        assertEquals("1. 确认排期", draft.get("decisions"));
        assertTrue(draft.get("materialsText").toString().contains("本周计划 1. 完成排期"));
        followUp.saveMinutes(organizer, id, request(), 3, Map.of("publish", true, "decisions", draft.get("decisions")));
        assertEquals("1. 确认排期", ((Map<?, ?>) bookings.detail(employee, id).get("minutes")).get("decisions"));
    }

    @Test
    void reportSharingControlsBothAuthorReportsAndTheMinutesSummary() {
        for (boolean share : List.of(false, true)) {
            var body = meeting(roomA, Instant.now().plusSeconds(3600), employee);
            body.put("attendeeIds", List.of(employee.userId().toString(), outsider.userId().toString()));
            body.put("materialTypes", List.of("REPORT"));
            UUID id = bookings.create(organizer, request(), body);
            db.update("UPDATE portal.meeting_booking SET share_all=NULL,material_types='{REPORT,TOPICS}' WHERE id=?", id);
            bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED", "materials", List.of(
                    Map.of("kind", "REPORT", "html", "<strong>工作汇报正文</strong>", "text", "工作汇报正文"),
                    Map.of("kind", "TOPICS", "rows", List.of(Map.of("subject", "私人议题", "expected", "协调"))))));
            bookings.start(organizer, id, request());
            bookings.end(organizer, id, request());
            assertFalse(attendee(outsider, id, employee).containsKey("materials"));
            followUp.saveMinutes(organizer, id, request(), 3, Map.of("publish", true, "decisions", "同意",
                    "materialsText", "汇报汇总", "shareReports", share));
            var reader = (Map<?, ?>) bookings.detail(outsider, id).get("minutes");
            var other = attendee(outsider, id, employee);
            assertEquals(share, reader.containsKey("materialsText"));
            assertEquals(share, other.containsKey("materials"));
            if (share) assertEquals(List.of("REPORT"), values(other, "materials").stream().map(m -> m.get("kind")).toList());
            assertEquals(2, values(attendee(employee, id, employee), "materials").size());
            assertEquals("汇报汇总", ((Map<?, ?>) bookings.detail(organizer, id).get("minutes")).get("materialsText"));
        }
    }

    @Test
    void shareAllIsSetAtBookingAndAppliesToReadingOnly() {
        Actor secondAttendee = person();
        for (boolean share : List.of(false, true)) {
            var body = meeting(roomA, Instant.now().plusSeconds(3600), employee);
            body.put("attendeeIds", List.of(employee.userId().toString(), secondAttendee.userId().toString()));
            body.put("materialTypes", List.of("REPORT", "WEEKLY_PLAN", "COORDINATION"));
            body.put("trackFollowUp", true);
            if (share) body.put("shareAll", true);
            UUID id = bookings.create(organizer, request(), body);
            assertEquals(share, bookings.detail(secondAttendee, id).get("shareAll"));
            Map<String, String> material = Map.of("subject", "会前事项", "expected", "协调");
            bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED", "materials", List.of(
                    Map.of("kind", "REPORT", "html", "<strong>会前汇报</strong>", "text", "会前汇报"),
                    Map.of("kind", "COORDINATION", "rows", List.of(ownedCoordination(material))))));
            var submitted = attendee(secondAttendee, id, employee);
            assertEquals(share, submitted.containsKey("materials"));
            assertEquals(share, submitted.containsKey("reply"));
            assertEquals(404, assertThrows(ApiFault.class, () -> bookings.detail(outsider, id)).status);
            bookings.start(organizer, id, request());
            bookings.saveLiveNotes(organizer, id, Map.of("notes", "会中记录"));
            assertEquals(share, bookings.detail(secondAttendee, id).containsKey("liveNotes"));
            assertEquals(false, bookings.detail(secondAttendee, id).get("canEnd"));
            bookings.end(organizer, id, request());
            assertEquals(share, bookings.detail(secondAttendee, id).get("minutes") != null);
            var task = Map.of("content", "落实决议", "assigneeId", outsider.userId().toString(),
                    "dueAt", Instant.now().plusSeconds(3600).toString());
            followUp.saveMinutes(organizer, id, request(), 3, Map.of("decisions", "通过", "publish", true,
                    "notes", "完整记录", "materialsText", "汇报汇总", "items", List.of(task)));
            var detail = bookings.detail(secondAttendee, id);
            var minutes = (Map<?, ?>) detail.get("minutes");
            assertEquals("通过", minutes.get("decisions"));
            assertEquals(share, minutes.containsKey("notes"));
            assertEquals(share, minutes.containsKey("materialsText"));
            assertEquals(share ? 1 : 0, ((List<?>) minutes.get("materialTables")).size());
            assertEquals(share ? 1 : 0, values(detail, "items").size());
            UUID item = (UUID) values(bookings.detail(organizer, id), "items").getFirst().get("id");
            followUp.feedback(outsider, item, request(), Map.of("feedback", "已落实"));
            if (share) {
                var feedback = followUp.item(secondAttendee, item);
                assertEquals(false, feedback.get("canSubmit"));
                assertEquals(false, feedback.get("canReview"));
                assertEquals("已落实", values(feedback, "history").getFirst().get("text"));
            } else {
                assertEquals(404, assertThrows(ApiFault.class, () -> followUp.item(secondAttendee, item)).status);
            }
        }
    }

    @Test
    void attendeeKeepsOneTaskFromInvitationThroughMultipleFeedbackItems() {
        var body = meeting(roomA, Instant.now().plusSeconds(3600), employee);
        body.put("materialTypes", List.of("REPORT", "WEEKLY_PLAN", "COORDINATION"));
        body.put("trackFollowUp", true);
        UUID id = bookings.create(organizer, request(), body);
        String entry = "#/meetings?id=" + id;
        var row = attendeeTask(employee, entry + "&action=reply");
        var taskId = row.get("id");
        assertEquals("MEETING_REPLY_PENDING", row.get("state"));
        bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED",
                "materials", List.of(Map.of("kind", "REPORT", "html", "汇报", "text", "汇报"))));
        assertEquals("MEETING_MATERIALS_PENDING", attendeeTask(employee, entry + "&action=materials").get("state"));
        bookings.start(organizer, id, request());
        assertEquals("MATERIAL_EXPIRED", attendeeTask(employee, entry).get("state"));
        bookings.end(organizer, id, request());
        var first = Map.of("content", "第一项", "assigneeId", employee.userId().toString(),
                "dueAt", Instant.now().minusSeconds(60).toString());
        var second = Map.of("content", "第二项", "assigneeId", employee.userId().toString(),
                "dueAt", Instant.now().plusSeconds(3600).toString());
        followUp.saveMinutes(organizer, id, request(), 3, Map.of("decisions", "通过", "publish", true,
                "items", List.of(first, second)));
        var details = values(bookings.detail(employee, id), "items");
        UUID firstItem = (UUID) details.stream().filter(item -> "第一项".equals(item.get("content")))
                .findFirst().orElseThrow().get("id");
        UUID secondItem = (UUID) details.stream().filter(item -> "第二项".equals(item.get("content")))
                .findFirst().orElseThrow().get("id");
        // 首页直达最早到期的一项，提交后同一条待办改为指向下一项。
        String firstEntry = "#/meetings?item=" + firstItem;
        assertEquals(taskId, attendeeTask(employee, firstEntry).get("id"));
        assertEquals("OVERDUE", attendeeTask(employee, firstEntry).get("state"));
        assertTrue(values(portal.tasks(employee, Map.of("category", "RECENT")), "items").stream()
                .anyMatch(task -> taskId.equals(task.get("id")) && firstEntry.equals(task.get("entry"))));
        followUp.feedback(employee, firstItem, request(), Map.of("feedback", "已完成"));
        assertEquals(taskId, attendeeTask(employee, "#/meetings?item=" + secondItem).get("id"));
        followUp.feedback(employee, secondItem, request(), Map.of("feedback", "已完成"));
        var completed = attendeeTask(employee, entry + "&action=follow-up");
        assertEquals("MEETING_FEEDBACK_DONE", completed.get("state"));
        assertEquals("COMPLETED", completed.get("category"));
        assertEquals(taskId, completed.get("id"));
        assertTrue(values(portal.tasks(employee, Map.of()), "items").stream()
                .noneMatch(task -> details.stream().anyMatch(item -> item.get("id").equals(task.get("id")))));
        followUp.review(organizer, firstItem, request(),
                Map.of("decision", "RETURNED", "reason", "继续补充"));
        assertEquals("HANDLING", attendeeTask(employee, firstEntry).get("category"));
        assertEquals(taskId, attendeeTask(employee, firstEntry).get("id"));
    }

    /** 覆盖表格冲突、按人确认阅读与从会中结论分派会后反馈。 */
    @Test
    void companyCoordinationAcceptsEmployeesAcrossDepartments() throws IOException {
        UUID company = (UUID) db.required("SELECT id FROM iam.department WHERE code='ROOT'").get("id");
        UUID otherDepartment = (UUID) db.required("SELECT id FROM iam.department WHERE code='PD'").get("id");
        db.update("UPDATE iam.user_account SET department_id=? WHERE id=?", otherDepartment, outsider.userId());
        String keyword = outsider.user().get("employeeNo").toString();
        assertTrue(values(bookings.people(employee, Map.of("departmentId", company.toString(), "keyword", keyword)), "items").isEmpty());
        assertEquals(outsider.userId(), values(bookings.people(employee, Map.of("departmentId", company.toString(),
                "scope", "COORDINATION", "keyword", keyword)), "items").getFirst().get("id"));
        assertTrue(values(bookings.people(employee, Map.of("departmentId", department.toString(),
                "scope", "COORDINATION", "keyword", keyword)), "items").isEmpty());

        var booking = meeting(roomA, Instant.now().plusSeconds(3600), employee);
        booking.put("materialTypes", List.of("COORDINATION"));
        UUID id = bookings.create(organizer, request(), booking);
        var item = ownedCoordination(Map.of("subject", "跨部门协调", "expected", "完成"));
        item.put("personId", outsider.userId().toString());
        var body = Map.<String, Object>of("kind", "COORDINATION", "rows", List.of(item));
        assertEquals(400, assertThrows(ApiFault.class, () -> bookings.materials(employee, id, request(), body)).status);
        item.put("category", "COMPANY");
        item.put("departmentId", company.toString());
        bookings.materials(employee, id, request(), body);
        var material = values(attendee(organizer, id, employee), "materials").getFirst();
        bookings.start(organizer, id, request());
        bookings.end(organizer, id, request());
        String key = material.get("attendeeId") + ":0";
        var change = Sql.map("key", key, "conclusion", "调整配合部门", "departmentId", otherDepartment.toString(),
                "assigneeId", employee.userId().toString());
        assertEquals(404, assertThrows(ApiFault.class, () -> followUp.saveCoordination(employee, id, request(), change)).status);
        assertEquals(400, assertThrows(ApiFault.class, () -> followUp.saveCoordination(organizer, id, request(), change)).status);
        assertTrue(values(bookings.detail(organizer, id), "coordinationResults").isEmpty());
        change.put("assigneeId", outsider.userId().toString());
        followUp.saveCoordination(organizer, id, request(), change);

        // 旧客户端省略部门时仍按最近改派的部门校验与保存。
        change.remove("departmentId");
        change.put("assigneeId", employee.userId().toString());
        assertEquals(400, assertThrows(ApiFault.class, () -> followUp.saveCoordination(organizer, id, request(), change)).status);
        change.put("assigneeId", outsider.userId().toString());
        followUp.saveCoordination(organizer, id, request(), change);
        var details = bookings.detail(organizer, id);
        var result = values(details, "coordinationResults").getFirst();
        String departmentName = db.required("SELECT name FROM iam.department WHERE id=?", otherDepartment).get("name").toString();
        assertEquals(otherDepartment.toString(), result.get("departmentId"));
        assertEquals(departmentName, result.get("department"));
        assertEquals(outsider.userId().toString(), result.get("assigneeId"));
        followUp.saveMinutes(organizer, id, request(), ((Number) details.get("rowVersion")).intValue(), Map.of("publish", true));
        change.put("conclusion", "发布后更正");
        followUp.saveCoordination(organizer, id, request(), change);
        var corrected = bookings.detail(organizer, id);
        assertEquals("CLOSED", corrected.get("state"));
        assertEquals("发布后更正", values(corrected, "coordinationResults").getFirst().get("conclusion"));
        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbooks.download(organizer, id, false)))) {
            boolean hasDepartment = false;
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.getName().startsWith("xl/worksheets/"))
                    hasDepartment |= new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains(departmentName);
            }
            assertTrue(hasDepartment);
        }
    }

    @Test
    void importedMaterialsReadingAndCoordinationWorkThroughPublishing() throws IOException {
        var booking = meeting(roomA, Instant.now().plusSeconds(3600), employee);
        booking.put("subject", "周报");
        booking.put("attendeeIds", List.of(employee.userId().toString(), outsider.userId().toString()));
        booking.put("materialTypes", List.of("REPORT", "WEEKLY_PLAN", "COORDINATION"));
        booking.put("shareAll", true);
        booking.put("trackFollowUp", true);
        UUID id = bookings.create(organizer, request(), booking);
        bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED"));
        bookings.materials(employee, id, request(), Map.of("kind", "REPORT", "text", "线上内容"));
        assertNull(bookings.detail(employee, id).get("readingRevision"));
        String reportRevision = bookings.detail(outsider, id).get("readingRevision").toString();
        bookings.confirmReading(outsider, id, request(), reportRevision);
        assertNotNull(attendee(organizer, id, outsider).get("readMaterialAt"));
        assertFalse(attendee(employee, id, outsider).containsKey("readMaterialAt"));

        byte[] template = workbooks.download(organizer, id, true);
        var wrongAuthor = filledWorkbook(template, "错误归属", "", Map.of("F", "姓名不符 " +
                attendee(organizer, id, employee).get("employeeNo")));
        assertEquals(400, assertThrows(ApiFault.class, () -> workbooks.importMaterials(organizer, id, request(),
                Map.of("file", wrongAuthor))).status);
        var outsideDepartment = ownedCoordination(Map.of("subject", "测试事项", "expected", "测试结果"));
        outsideDepartment.put("personId", admin.userId().toString());
        assertEquals(400, assertThrows(ApiFault.class, () -> bookings.materials(employee, id, request(),
                Map.of("kind", "COORDINATION", "rows", List.of(outsideDepartment)))).status);
        String file = filledWorkbook(template, "线下冲突内容", "", Map.of(
                "B", "协调合同", "C", "确定条款", "D", "确认合同", "F", employeeLabel(employee),
                "G", "ROOT", "H", employeeLabel(employee), "I", "需公司协调"));
        assertEquals(404, assertThrows(ApiFault.class, () -> workbooks.download(employee, id, false)).status);
        var preview = workbooks.importMaterials(organizer, id, request(), Map.of("file", file));
        assertEquals(List.of(), preview.get("errors"));
        assertEquals(true, values(preview, "materials").getFirst().get("conflict"));
        var imported = workbooks.importMaterials(organizer, id, request(), Map.of("file", file, "preview", preview.get("preview")));
        assertEquals(1, imported.get("imported"));
        assertEquals(1, imported.get("skipped"));
        assertTrue(values(attendee(organizer, id, employee), "materials").toString().contains("线上内容"));
        assertNull(attendee(organizer, id, outsider).get("readMaterialAt"));
        assertEquals(409, assertThrows(ApiFault.class,
                () -> bookings.confirmReading(outsider, id, request(), reportRevision)).status);
        assertEquals(409, assertThrows(ApiFault.class, () -> workbooks.importMaterials(organizer, id, request(),
                Map.of("file", file, "preview", preview.get("preview")))).status);
        var overwrite = workbooks.importMaterials(organizer, id, request(), Map.of("file", file));
        String reportKey = values(overwrite, "materials").getFirst().get("key").toString();
        assertEquals(1, workbooks.importMaterials(organizer, id, request(),
                Map.of("file", file, "preview", overwrite.get("preview"), "overwrite", List.of(reportKey))).get("imported"));
        assertTrue(values(attendee(organizer, id, employee), "materials").toString().contains("线下冲突内容"));

        // 只有 F2 和两段正文也能识别未受邀员工，预览阶段不写入或修改参会回复。
        Actor remoteEmployee = person();
        String remoteFile = filledWorkbook(template, "外地工作", "外地计划", Map.of("F", employeeLabel(remoteEmployee)));
        var remote = workbooks.importMaterials(organizer, id, request(), Map.of("file", remoteFile));
        assertEquals(true, remote.get("newInvitee"));
        assertEquals(2, values(bookings.detail(organizer, id), "attendees").size());
        assertEquals(true, workbooks.importMaterials(organizer, id, request(),
                Map.of("file", remoteFile, "preview", remote.get("preview"))).get("inviteeAdded"));
        var remoteAttendee = attendee(organizer, id, remoteEmployee);
        assertEquals("PENDING", remoteAttendee.get("reply"));
        assertEquals(2, values(remoteAttendee, "materials").size());
        assertTrue(remoteAttendee.toString().contains("外地计划"));
        assertTrue(remoteAttendee.toString().contains("EXCEL"));
        assertEquals(false, workbooks.importMaterials(organizer, id, request(), Map.of("file", remoteFile)).get("newInvitee"));
        bookings.reply(remoteEmployee, id, request(), Map.of("decision", "ACCEPTED"));
        assertFalse(hasPending(remoteEmployee, id));

        // 未阅读和未交材料都不阻止发起人开会。
        bookings.start(organizer, id, request());
        var materials = values(attendee(organizer, id, employee), "materials");
        String key = materials.stream().filter(row -> row.get("kind").equals("COORDINATION"))
                .findFirst().orElseThrow().get("attendeeId") + ":0";
        var conclusion = Sql.map("key", key, "conclusion", "签订补充协议", "assigneeId", employee.userId().toString(),
                "dueAt", Instant.now().plusSeconds(7200).toString(), "followUp", true);
        assertEquals(404, assertThrows(ApiFault.class, () -> followUp.saveCoordination(employee, id, request(), conclusion)).status);
        followUp.saveCoordination(organizer, id, request(), conclusion);
        bookings.end(organizer, id, request());
        conclusion.put("assigneeId", outsider.userId().toString());
        followUp.saveCoordination(organizer, id, request(), conclusion);
        int version = ((Number) bookings.detail(organizer, id).get("rowVersion")).intValue();
        Actor additional = person();
        var tracking = List.of(Map.of("key", key, "assigneeIds", List.of(additional.userId().toString())));
        followUp.saveMinutes(organizer, id, request(), version, Map.of("publish", true, "coordinationTrackers", tracking));
        var assigned = values(bookings.detail(outsider, id), "items").getFirst();
        assertEquals("协调合同\n签订补充协议", assigned.get("content"));
        assertEquals(outsider.userId(), assigned.get("assigneeId"));
        assertEquals(key, assigned.get("coordinationKey"));
        assertEquals(1, values(bookings.detail(organizer, id), "items").size());
        assertEquals(Set.of(employee.userId(), outsider.userId(), additional.userId()),
                new HashSet<>(values(assigned, "trackers").stream().map(row -> row.get("assigneeId")).toList()));
        UUID item = (UUID) assigned.get("id");
        UUID originatorItem = (UUID) values(bookings.detail(employee, id), "items").getFirst().get("id");
        UUID additionalItem = (UUID) db.required("SELECT id FROM portal.meeting_item WHERE meeting_id=? AND assignee_id=?",
                id, additional.userId()).get("id");
        followUp.feedback(outsider, item, request(), Map.of("feedback", "原责任人已核对"));
        followUp.review(organizer, item, request(), Map.of("decision", "CONFIRMED"));
        assertEquals(409, assertThrows(ApiFault.class, () -> followUp.close(organizer, id, request())).status);
        followUp.feedback(employee, originatorItem, request(), Map.of("feedback", "发起人已核对"));
        followUp.review(organizer, originatorItem, request(), Map.of("decision", "CONFIRMED"));
        followUp.feedback(additional, additionalItem, request(), Map.of("feedback", "补充跟踪人员已核对"));
        followUp.review(organizer, additionalItem, request(), Map.of("decision", "CONFIRMED"));
        followUp.close(organizer, id, request());

        conclusion.put("departmentId", department.toString());
        conclusion.put("assigneeId", employee.userId().toString());
        conclusion.put("conclusion", "复核补充协议");
        Instant revisedDue = Instant.now().plusSeconds(10800).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        conclusion.put("dueAt", revisedDue.toString());
        followUp.saveCoordination(organizer, id, request(), conclusion);
        Map<String, Object> corrected = followUp.item(employee, originatorItem);
        assertEquals("协调合同\n复核补充协议", corrected.get("content"));
        assertEquals("PENDING", corrected.get("state"));
        assertEquals(revisedDue.toString(), corrected.get("dueAt"));
        assertTrue(values(corrected, "history").stream().anyMatch(entry -> "发起人已核对".equals(entry.get("text"))));
        assertEquals("CORRECTED", values(corrected, "history").getLast().get("state"));
        assertEquals(404, assertThrows(ApiFault.class, () -> followUp.item(outsider, item)).status);
        assertTrue(values(followUp.item(organizer, item), "history").stream().anyMatch(entry -> "原责任人已核对".equals(entry.get("text"))));
        assertEquals("PENDING", followUp.item(additional, additionalItem).get("state"));
        assertEquals("FOLLOW_UP", bookings.detail(organizer, id).get("state"));
        assertEquals(2, db.count("SELECT count(*) FROM portal.meeting_item WHERE meeting_id=? AND removed_at IS NULL", id));
        assertTrue(workbooks.download(organizer, id, false).length > 0);
    }

    @Test
    void markdownWeeklyMaterialImagesInheritVisibilityAndSurviveEditing() throws IOException {
        Actor otherInvitee = person();
        var body = meeting(roomA, Instant.now().plusSeconds(3600), employee);
        body.put("attendeeIds", List.of(employee.userId().toString(), otherInvitee.userId().toString()));
        body.put("materialTypes", List.of("REPORT", "WEEKLY_PLAN"));
        body.put("shareAll", false);
        UUID id = bookings.create(organizer, request(), body);
        var png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
        UUID image = request();
        String markdown = "1. 完成事项\n  1.1. 细项\n\n![图片](inline-image/" + image + ")";
        var upload = Map.of("id", image.toString(), "data", Base64.getEncoder().encodeToString(png.toByteArray()));
        bookings.materials(employee, id, request(), Map.of("kind", "REPORT", "markdown", markdown, "images", List.of(upload)));
        var report = values(attendee(organizer, id, employee), "materials").getFirst();
        UUID attendeeId = (UUID) report.get("attendeeId");
        var data = Crypto.JSON.valueToTree(report.get("data"));
        assertEquals(markdown, data.path("markdown").asText());
        assertTrue(data.path("text").asText().contains("[图片]"));
        assertEquals("image/png", bookings.materialImage(organizer, id, attendeeId, "REPORT", image).mediaType());
        assertNotNull(bookings.materialImage(employee, id, attendeeId, "REPORT", image));
        assertEquals(404, assertThrows(ApiFault.class,
                () -> bookings.materialImage(otherInvitee, id, attendeeId, "REPORT", image)).status);
        db.update("UPDATE portal.meeting_booking SET share_all=true WHERE id=?", id);
        assertNotNull(bookings.materialImage(otherInvitee, id, attendeeId, "REPORT", image));
        assertEquals(404, assertThrows(ApiFault.class,
                () -> bookings.materialImage(outsider, id, attendeeId, "REPORT", image)).status);

        bookings.materials(employee, id, request(), Map.of("kind", "REPORT", "markdown", markdown + "\n\n补充",
                "imageKeys", List.of(image.toString())));
        assertNotNull(bookings.materialImage(organizer, id, attendeeId, "REPORT", image));
        assertEquals(404, assertThrows(ApiFault.class,
                () -> bookings.materialImage(organizer, id, attendeeId, "WEEKLY_PLAN", image)).status);
        bookings.materials(employee, id, request(), Map.of("kind", "REPORT", "markdown", "已移除图片"));
        assertEquals(404, assertThrows(ApiFault.class,
                () -> bookings.materialImage(organizer, id, attendeeId, "REPORT", image)).status);
    }

    @Test
    void organizerCanCorrectPublishedDecisionsAndManualTaskDeadline() {
        var body = meeting(roomA, Instant.now().plusSeconds(3600), employee);
        body.put("trackFollowUp", true);
        UUID id = bookings.create(organizer, request(), body);
        bookings.start(organizer, id, request());
        bookings.end(organizer, id, request());
        Instant due = Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var task = Sql.map("content", "更新纪要附件", "assigneeId", employee.userId().toString(), "dueAt", due.toString());
        int version = ((Number) bookings.detail(organizer, id).get("rowVersion")).intValue();
        followUp.saveMinutes(organizer, id, request(), version, Map.of("publish", true, "decisions", "原决议", "items", List.of(task)));
        var submitted = bookings.detail(organizer, id);
        var item = (UUID) values(submitted, "items").getFirst().get("id");
        followUp.feedback(employee, item, request(), Map.of("feedback", "附件已更新"));
        followUp.review(organizer, item, request(), Map.of("decision", "CONFIRMED"));
        followUp.close(organizer, id, request());
        var closed = bookings.detail(organizer, id);
        int closedVersion = ((Number) closed.get("rowVersion")).intValue();
        var revisedDecisions = Map.<String, Object>of("decisions", "更正后的决议");
        assertEquals(404, assertThrows(ApiFault.class,
                () -> followUp.reviseDecisions(employee, id, request(), closedVersion, revisedDecisions)).status);
        assertEquals(409, assertThrows(ApiFault.class,
                () -> followUp.reviseDecisions(organizer, id, request(), version, revisedDecisions)).status);
        followUp.reviseDecisions(organizer, id, request(), closedVersion, revisedDecisions);
        var beforeMinutes = (Map<?,?>) closed.get("minutes");
        var afterMinutes = (Map<?,?>) bookings.detail(organizer, id).get("minutes");
        assertEquals(beforeMinutes.get("publishedAt"), afterMinutes.get("publishedAt"));
        assertEquals("更正后的决议", afterMinutes.get("decisions"));

        task.put("dueAt", due.plusSeconds(3600).toString());
        assertEquals(404, assertThrows(ApiFault.class, () -> followUp.reviseItem(employee, item, request(), task)).status);
        followUp.reviseItem(organizer, item, request(), task);
        var adjusted = followUp.item(employee, item);
        assertEquals("CONFIRMED", adjusted.get("state"));
        assertEquals(task.get("dueAt"), adjusted.get("dueAt"));
        assertEquals("CLOSED", bookings.detail(organizer, id).get("state"));
        assertEquals("附件已更新", values(adjusted, "history").getFirst().get("text"));
    }

    /** 手工事项在草稿中保留多人选择，发布后共同展示、独立反馈和审核。 */
    @Test
    void multipleTrackersShareOneManualItemWithIndependentFeedback() {
        var body = meeting(roomA, Instant.now().plusSeconds(3600), employee);
        body.putAll(Map.of("trackFollowUp", true, "shareAll", false));
        UUID id = bookings.create(organizer, request(), body);
        bookings.start(organizer, id, request());
        bookings.end(organizer, id, request());
        String due = Instant.now().plusSeconds(7200).toString();
        var task = Sql.map("content", "共同复核设备", "dueAt", due,
                "assigneeIds", List.of(employee.userId().toString(), outsider.userId().toString()));
        var minutes = Sql.map("decisions", "共同复核", "items", List.of(task), "publish", false);
        int version = ((Number) bookings.detail(organizer, id).get("rowVersion")).intValue();
        followUp.saveMinutes(organizer, id, request(), version, minutes);
        var saved = ((Map<?, ?>) bookings.detail(organizer, id).get("minutes")).get("items").toString();
        assertTrue(saved.contains(employee.userId().toString()));
        assertTrue(saved.contains(outsider.userId().toString()));
        minutes.put("publish", true);
        followUp.saveMinutes(organizer, id, request(), version + 1, minutes);

        var group = values(bookings.detail(employee, id), "items").getFirst();
        assertEquals(1, values(bookings.detail(organizer, id), "items").size());
        UUID personal = (UUID) group.get("id");
        var other = values(group, "trackers").stream().filter(row -> outsider.userId().equals(row.get("assigneeId")))
                .findFirst().orElseThrow();
        UUID restricted = (UUID) other.get("id");
        assertNull(other.get("state"));
        assertEquals(false, other.get("canOpen"));
        assertEquals(404, assertThrows(ApiFault.class, () -> followUp.item(employee, restricted)).status);
        assertEquals("PENDING", attendeeTask(outsider, "#/meetings?item=" + restricted).get("state"));
        followUp.feedback(employee, personal, request(), Map.of("feedback", "已检查接线"));
        followUp.review(organizer, personal, request(), Map.of("decision", "CONFIRMED"));
        assertEquals("PENDING", followUp.item(outsider, restricted).get("state"));

        Actor replacement = person();
        task.put("assigneeIds", List.of(employee.userId().toString(), replacement.userId().toString()));
        followUp.reviseItem(organizer, personal, request(), task);
        assertEquals("CONFIRMED", followUp.item(employee, personal).get("state"));
        assertEquals(404, assertThrows(ApiFault.class, () -> followUp.item(outsider, restricted)).status);
        UUID replacementItem = (UUID) db.required("SELECT id FROM portal.meeting_item WHERE meeting_id=? AND assignee_id=?",
                id, replacement.userId()).get("id");
        followUp.feedback(replacement, replacementItem, request(), Map.of("feedback", "已检查外观"));
        followUp.review(organizer, replacementItem, request(), Map.of("decision", "CONFIRMED"));
        followUp.close(organizer, id, request());
        assertEquals("CLOSED", bookings.detail(organizer, id).get("state"));
    }

    /** 线上员工和 Excel 代填员工同时汇总，未回复邀请不应造成线上材料漏导。 */
    @Test
    void workbookIncludesEveryOnlineAndImportedEmployee() throws IOException {
        var booking = meeting(roomA, Instant.now().plusSeconds(3600), employee);
        booking.put("attendeeIds", List.of(employee.userId().toString(), outsider.userId().toString()));
        booking.put("materialTypes", List.of("REPORT", "WEEKLY_PLAN", "COORDINATION"));
        UUID id = bookings.create(organizer, request(), booking);
        bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED"));
        for (var person : List.of(employee, outsider)) {
            bookings.materials(person, id, request(), Map.of("kind", "REPORT", "text", "线上汇报 " + person.userId()));
            bookings.materials(person, id, request(), Map.of("kind", "WEEKLY_PLAN", "text", "线上计划 " + person.userId()));
            bookings.materials(person, id, request(), Map.of("kind", "COORDINATION", "rows", List.of(
                    ownedCoordination(Map.of("subject", "线上协调 " + person.userId(), "expected", "完成核对")))));
        }
        var remote = person();
        String file = filledWorkbook(workbooks.download(organizer, id, true), "外地汇报", "外地计划",
                Map.of("F", employeeLabel(remote)));
        var preview = workbooks.importMaterials(organizer, id, request(), Map.of("file", file));
        workbooks.importMaterials(organizer, id, request(), Map.of("file", file, "preview", preview.get("preview")));
        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbooks.download(organizer, id, false)))) {
            for (ZipEntry part; (part = zip.getNextEntry()) != null;) {
                if (!part.getName().equals("xl/worksheets/sheet1.xml")) continue;
                String sheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                for (var person : List.of(employee, outsider)) {
                    assertTrue(sheet.contains("线上汇报 " + person.userId()));
                    assertTrue(sheet.contains("线上计划 " + person.userId()));
                    assertTrue(sheet.contains("线上协调 " + person.userId()));
                    assertTrue(sheet.contains(attendee(organizer, id, person).get("employeeNo").toString()));
                }
                assertTrue(sheet.contains("外地汇报"));
                assertTrue(sheet.contains("外地计划"));
                assertTrue(sheet.contains(attendee(organizer, id, remote).get("employeeNo").toString()));
                return;
            }
        }
        fail("会议汇总缺少数据工作表");
    }

    @Test
    void incompleteExportDistinguishesReplyAndOnlineOrImportedMaterial()throws Exception{
        var body=meeting(roomA,Instant.now().plusSeconds(3600),employee);
        body.put("attendeeIds",List.of(employee.userId().toString(),outsider.userId().toString()));
        body.put("materialTypes",List.of("REPORT","WEEKLY_PLAN","COORDINATION"));
        UUID id=bookings.create(organizer,request(),body);
        bookings.reply(employee,id,request(),Map.of("decision","ACCEPTED"));
        bookings.materials(employee,id,request(),Map.of("kind","REPORT","text","无新增事项"));
        String file=filledWorkbook(workbooks.download(organizer,id,true),"Excel已完成工作","无新增计划",Map.of("F",employeeLabel(outsider)));
        var preview=workbooks.importMaterials(organizer,id,request(),Map.of("file",file));
        workbooks.importMaterials(organizer,id,request(),Map.of("file",file,"preview",preview.get("preview")));
        var rows=incompleteRows(incomplete.download(organizer,id));
        assertEquals(3,rows.stream().filter(row->row.get("环节").equals("会前填写")).count());
        assertEquals(Set.of("本周工作计划","协调事宜"),rows.stream().filter(row->row.get("姓名").equals(employee.user().get("name"))).map(row->row.get("具体事项")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(rows.stream().anyMatch(row->row.get("姓名").equals(outsider.user().get("name"))&&row.get("环节").equals("参会回复")));
        assertFalse(rows.stream().anyMatch(row->row.get("具体事项").equals("上周工作内容")));
        assertTrue(rows.stream().allMatch(row->row.get("逾期情况").equals("待完成（未到期）")));
    }

    @Test
    void incompleteExportUsesCurrentSharedReadingRevisionAndSkipsDeclinedPeople()throws Exception{
        var body=meeting(roomA,Instant.now().plusSeconds(3600),employee);
        body.put("attendeeIds",List.of(employee.userId().toString(),outsider.userId().toString()));body.put("materialTypes",List.of("REPORT"));body.put("shareAll",true);
        UUID id=bookings.create(organizer,request(),body);
        bookings.materials(employee,id,request(),Map.of("kind","REPORT","text","第一版"));
        String revision=bookings.detail(outsider,id).get("readingRevision").toString();
        bookings.confirmReading(outsider,id,request(),revision);
        assertFalse(incompleteRows(incomplete.download(organizer,id)).stream().anyMatch(row->row.get("环节").equals("会前资料确认")));
        bookings.materials(employee,id,request(),Map.of("kind","REPORT","text","第二版"));
        var rows=incompleteRows(incomplete.download(organizer,id));
        assertEquals(1,rows.stream().filter(row->row.get("环节").equals("会前资料确认")&&row.get("姓名").equals(outsider.user().get("name"))).count());
        bookings.reply(outsider,id,request(),Map.of("decision","DECLINED","reason","无法参会"));
        assertFalse(incompleteRows(incomplete.download(organizer,id)).stream().anyMatch(row->row.get("姓名").equals(outsider.user().get("name"))));
    }

    @Test
    void incompleteExportTracksActualPublishedFollowUpAndNextResponsiblePerson()throws Exception{
        var body=meeting(roomA,Instant.now().plusSeconds(3600),employee);body.put("materialTypes",List.of());body.put("trackFollowUp",true);
        UUID id=bookings.create(organizer,request(),body);bookings.reply(employee,id,request(),Map.of("decision","ACCEPTED"));
        bookings.start(organizer,id,request());bookings.end(organizer,id,request());
        var item=Map.of("content","=这是一项待办而不是公式","assigneeId",outsider.userId().toString(),"dueAt",Instant.now().minusSeconds(60).toString());
        followUp.saveMinutes(organizer,id,request(),3,Map.of("decisions","落实安排","items",List.of(item),"publish",false));
        assertTrue(incompleteRows(incomplete.download(organizer,id)).isEmpty());
        followUp.saveMinutes(organizer,id,request(),4,Map.of("decisions","落实安排","items",List.of(item),"publish",true));
        var rows=incompleteRows(incomplete.download(organizer,id));assertEquals(1,rows.size());assertEquals("已逾期",rows.getFirst().get("逾期情况"));
        UUID task=(UUID)values(bookings.detail(organizer,id),"items").getFirst().get("id");
        followUp.feedback(outsider,task,request(),Map.of("feedback","已完成，等待确认"));
        var review=incompleteRows(incomplete.download(organizer,id)).getFirst();
        assertEquals("待发起人确认",review.get("当前状态"));assertEquals(organizer.user().get("name"),review.get("下一步处理人"));assertEquals("不适用（未设置确认期限）",review.get("逾期情况"));
        followUp.review(organizer,task,request(),Map.of("decision","RETURNED","reason","请补充凭据"));
        assertEquals("退回待补充",incompleteRows(incomplete.download(organizer,id)).getFirst().get("当前状态"));
        followUp.feedback(outsider,task,request(),Map.of("feedback","补充完成"));followUp.review(organizer,task,request(),Map.of("decision","CONFIRMED"));
        assertTrue(incompleteRows(incomplete.download(organizer,id)).isEmpty());
    }

    @Test
    void incompleteExportKeepsLegacyMergedMaterialRules()throws Exception{
        var body=meeting(roomA,Instant.now().plusSeconds(3600),employee);
        body.put("collectMaterials",true);body.put("materialUserIds",List.of(employee.userId().toString()));
        UUID id=bookings.create(organizer,request(),body);
        assertTrue(incompleteRows(incomplete.download(organizer,id)).stream().anyMatch(row->row.get("具体事项").equals("会前材料（原版合并填报）")));
        bookings.materials(employee,id,request(),Map.of("report","已完成上周工作","topics","暂无新增议题"));
        var rows=incompleteRows(incomplete.download(organizer,id));
        assertEquals(1,rows.size());assertEquals("参会回复",rows.getFirst().get("环节"));
        assertFalse(rows.stream().anyMatch(row->row.get("具体事项").equals("本周工作计划")));
    }

    @Test
    void incompleteAggregateOnlyIncludesOwnMeetingsAndRetainsOlderRows()throws Exception{
        var first=meeting(roomA,Instant.now().plusSeconds(3600),employee);first.put("subject","本人会议甲");first.put("materialTypes",List.of());
        UUID a=bookings.create(organizer,request(),first);
        var second=meeting(roomA,Instant.now().plusSeconds(7200),employee);second.put("subject","本人会议乙");second.put("materialTypes",List.of());UUID b=bookings.create(organizer,request(),second);
        var other=meeting(roomB,Instant.now().plusSeconds(10800),employee);other.put("subject","他人会议");bookings.create(outsider,request(),other);
        assertEquals(Set.of("本人会议甲","本人会议乙"),incompleteRows(incomplete.download(organizer,null)).stream().map(row->row.get("会议名称")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(404,assertThrows(ApiFault.class,()->incomplete.download(employee,a)).status);assertEquals(404,assertThrows(ApiFault.class,()->incomplete.download(admin,a)).status);
        bookings.cancel(organizer,b,request(),Map.of("reason","取消安排"));
        assertEquals(1,incompleteRows(incomplete.download(organizer,null)).size());
        db.update("UPDATE portal.meeting_attendee SET removed_at=CURRENT_TIMESTAMP WHERE meeting_id=?",a);
        assertTrue(incompleteRows(incomplete.download(organizer,null)).isEmpty());
        assertTrue(incompleteRows(incomplete.download(admin,null)).isEmpty());
    }

    /** 读取实际XLSX的表头和单元格，并验证冻结、筛选及文本安全，不复制报表业务判定。 */
    private static List<Map<String,String>> incompleteRows(byte[] workbook)throws Exception{
        try(var zip=new ZipInputStream(new ByteArrayInputStream(workbook))){
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){
                if(!entry.getName().equals("xl/worksheets/sheet1.xml"))continue;
                var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
                var doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
                assertEquals(0,doc.getElementsByTagNameNS("*","f").getLength());assertEquals(1,doc.getElementsByTagNameNS("*","autoFilter").getLength());assertEquals(1,doc.getElementsByTagNameNS("*","pane").getLength());
                var rows=doc.getElementsByTagNameNS("*","row");var headers=new ArrayList<String>();var result=new ArrayList<Map<String,String>>();
                for(int n=1;n<rows.getLength();n++){
                    var cells=((org.w3c.dom.Element)rows.item(n)).getElementsByTagNameNS("*","c");var row=new LinkedHashMap<String,String>();
                    for(int c=0;c<cells.getLength();c++){String value=cells.item(c).getTextContent();if(n==1)headers.add(value);else row.put(headers.get(c),value);}
                    if(n>1)result.add(row);
                }return result;
            }
        }throw new AssertionError("未生成未完成事项工作表");
    }

    private Map<String, Object> ownedCoordination(Map<String, String> material) {
        var row = new HashMap<String, Object>(material);
        row.put("departmentId", department.toString());
        row.put("personId", employee.userId().toString());
        return row;
    }

    private String employeeLabel(Actor actor) {
        return db.required("SELECT name||' '||employee_no AS label FROM iam.user_account WHERE id=?",
                actor.userId()).get("label").toString();
    }

    /** 已有会议补录多部门、多人材料，不将材料作者登记为受邀员工。 */
    @Test
    void importsWeeklySummaryIntoExistingMeetingBeforeMinutesPublication() throws IOException {
        UUID child = request();
        String parentCode = db.required("SELECT code FROM iam.department WHERE id=?", department).get("code").toString();
        String childCode = "QC-" + child.toString().substring(0, 8);
        db.update("INSERT INTO iam.department(id,code,name,parent_id) VALUES (?,?,?,?)",
                child, childCode, "隔离验收子部门", department);
        db.update("UPDATE iam.user_account SET department_id=? WHERE id=?", child, outsider.userId());
        db.update("""
                INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by)
                VALUES (?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'周会补录验收',?)
                """, request(), child, outsider.userId(), admin.userId());
        var booking = meeting(roomA, Instant.now().plusSeconds(3600), employee);
        booking.putAll(Map.of("materialTypes", List.of(), "shareAll", true, "trackFollowUp", true));
        UUID id = bookings.create(organizer, request(), booking);
        bookings.start(organizer, id, request());
        bookings.end(organizer, id, request());

        var bytes = weeklySummary(childCode, parentCode, employeeLabel(outsider), employeeLabel(employee));
        LocalDate date = LocalDate.parse("2026-11-23");
        assertEquals(404, assertThrows(ApiFault.class, () -> weeklyImports.importSummary(employee, id, request(), date,
                null, new ByteArrayInputStream(bytes))).status);
        assertThrows(ApiFault.class, () -> weeklyImports.importSummary(organizer, id, request(), date.plusDays(1),
                null, new ByteArrayInputStream(bytes)));
        var preview = weeklyImports.importSummary(organizer, id, request(), date, null, new ByteArrayInputStream(bytes));
        assertEquals(List.of(), preview.get("errors"));
        assertEquals(4, values(preview, "materials").size());
        assertEquals(1, values(bookings.detail(organizer, id), "attendees").size());
        var imported = weeklyImports.importSummary(organizer, id, request(), date,
                (String) preview.get("preview"), new ByteArrayInputStream(bytes));
        assertEquals(4, imported.get("imported"));
        var author = attendee(organizer, id, outsider);
        assertEquals(true, author.get("materialOnly"));
        assertNull(author.get("reply"));
        assertEquals(404, assertThrows(ApiFault.class, () -> bookings.detail(outsider, id)).status);
        assertFalse(hasPending(outsider, id));
        var details = bookings.detail(organizer, id);
        String drafted = ((Map<?, ?>) details.get("minutes")).get("materialsText").toString();
        assertTrue(drafted.contains("会议隔离验收部门 / 隔离验收子部门"));
        assertTrue(drafted.contains("上周事项"));
        assertTrue(drafted.contains("同一合并名称下的第二行"));
        assertTrue(drafted.contains("本周事项"));
        assertTrue(drafted.contains("备注：保留备注"));
        assertFalse(drafted.contains("上一周旧数据"));
        assertFalse(drafted.contains("忽略问题"));
        assertEquals("AWAITING_MINUTES", details.get("state"));
        var materials = values(author, "materials");
        var coordination = materials.stream().filter(m -> "COORDINATION".equals(m.get("kind"))).findFirst().orElseThrow();
        assertTrue(coordination.get("data").toString().contains("2026-11-25T09:30:00Z"));
        assertTrue(coordination.get("data").toString().contains("DEPARTMENT"));
        assertTrue(values(attendee(organizer, id, employee), "materials").toString().contains("COMPANY"));
        var repeated = weeklyImports.importSummary(organizer, id, request(), date, null, new ByteArrayInputStream(bytes));
        assertTrue(values(repeated, "materials").stream().allMatch(row -> Boolean.TRUE.equals(row.get("conflict"))));
        assertEquals(0, weeklyImports.importSummary(organizer, id, request(), date, (String) repeated.get("preview"),
                new ByteArrayInputStream(bytes)).get("imported"));
    }

    /** 按截图列顺序构造两页可复现的源 XLSX，日期和名称与合并区域一致。 */
    private static byte[] weeklySummary(String child, String partner, String author, String assignee) throws IOException {
        var file = new ByteArrayOutputStream();
        var bindings = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        var workbook = "<workbook xmlns:r=\"" + bindings + "\"><sheets>"
                + "<sheet name=\"协调事宜\" r:id=\"rId1\"/><sheet name=\"" + child + "\" r:id=\"rId2\"/></sheets></workbook>";
        var relationships = "<Relationships><Relationship Id=\"rId1\" Type=\"" + bindings + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + bindings + "/worksheet\" Target=\"/xl/worksheets/sheet2.xml\"/></Relationships>";
        var headers = List.of("序号", "事项类别", "具体事宜", "需协调 / 配合什么", "期望结果 /验收标准", "填报部门", "填报人+工号",
                "配合部门", "责任人（唯一，姓名+工号）", "截止时间");
        var coordination = "<worksheet><sheetData>" + weeklyRow(1, headers)
                + weeklyRow(2, List.of("1", "需其他部门配合", "协调设备", "提供帮助", "核对完成", child,
                author, partner, assignee, "2026/11/25 17:30"))
                + weeklyRow(3, List.of("2", "需公司协调", "同步公司资源", "", "确认资源安排", partner,
                assignee, "公司", author, "")) + "</sheetData></worksheet>";
        var report = "<worksheet><sheetData>" + weeklyRow(1, List.of("日期", "名称", "序号", "事项说明", "备注"))
                + weeklyRow(2, List.of("2026.11.16", "上周总结", "1", "上一周旧数据"))
                + weeklyRow(3, List.of("2026.11.23", "上周总结", "1", "上周事项", "保留备注"))
                + weeklyRow(4, List.of("", "", "2", "同一合并名称下的第二行"))
                + weeklyRow(5, List.of("", "本周计划", "1", "本周事项"))
                + weeklyRow(6, List.of("", "需解决问题", "1", "忽略问题")) + "</sheetData>"
                + "<mergeCells><mergeCell ref=\"A3:A6\"/><mergeCell ref=\"B3:B4\"/></mergeCells></worksheet>";
        try (var zip = new ZipOutputStream(file)) {
            for (var entry : Map.of("xl/workbook.xml", workbook, "xl/_rels/workbook.xml.rels", relationships,
                    "xl/worksheets/sheet1.xml", coordination, "xl/worksheets/sheet2.xml", report).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file.toByteArray();
    }

    private static String weeklyRow(int number, List<String> values) {
        var row = new StringBuilder("<row r=\"" + number + "\">");
        for (int i = 0; i < values.size(); i++) {
            if (!values.get(i).isEmpty()) row.append(cell((char) ('A' + i) + Integer.toString(number), values.get(i)));
        }
        return row.append("</row>").toString();
    }

    /** 使用发起人下载的真实模板，只替换员工可填写的正文和协调行。 */
    private static String filledWorkbook(byte[] template, String report, String plan, Map<String, String> values)
            throws IOException {
        var filled = new ByteArrayOutputStream();
        try (var source = new ZipInputStream(new ByteArrayInputStream(template));
             var target = new ZipOutputStream(filled)) {
            for (ZipEntry part; (part = source.getNextEntry()) != null;) {
                String xml = new String(source.readAllBytes(), StandardCharsets.UTF_8);
                if (part.getName().equals("xl/worksheets/sheet1.xml") && !report.isEmpty())
                    xml = xml.replace("<c r=\"A2\" s=\"2\"/>", cell("A2", report));
                if (part.getName().equals("xl/worksheets/sheet2.xml") && !plan.isEmpty())
                    xml = xml.replace("<c r=\"A2\" s=\"2\"/>", cell("A2", plan));
                if (part.getName().equals("xl/worksheets/sheet3.xml")) {
                    int end = xml.indexOf("</row>", xml.indexOf("<row r=\"2\">"));
                    String cells = new TreeMap<>(values).entrySet().stream().map(entry -> cell(entry.getKey() + "2",
                            entry.getValue())).collect(java.util.stream.Collectors.joining());
                    xml = xml.substring(0, end) + cells + xml.substring(end);
                }
                target.putNextEntry(new ZipEntry(part.getName()));
                target.write(xml.getBytes(StandardCharsets.UTF_8));
                target.closeEntry();
            }
        }
        return Base64.getEncoder().encodeToString(filled.toByteArray());
    }

    private static String cell(String coordinate, String value) {
        return "<c r=\"" + coordinate + "\" t=\"inlineStr\"><is><t>" + value + "</t></is></c>";
    }

    private Map<String, Object> attendeeTask(Actor actor, String entry) {
        var tasks = values(portal.tasks(actor, Map.of()), "items").stream()
                .filter(task -> entry.equals(task.get("entry"))).toList();
        assertEquals(1, tasks.size());
        return tasks.getFirst();
    }

    private boolean hasPending(Actor actor, UUID id) {
        String entry = "#/meetings?id=" + id;
        return values(portal.tasks(actor, Map.of("category", "HANDLING")), "items").stream()
                .map(row -> (String) row.get("entry"))
                .anyMatch(link -> link.equals(entry) || link.startsWith(entry + "&"));
    }

    private Map<String, Object> attendee(Actor viewer, UUID meeting, Actor person) {
        return values(bookings.detail(viewer, meeting), "attendees").stream()
                .filter(row -> person.userId().equals(row.get("userId"))).findFirst().orElseThrow();
    }

    private Map<String, Object> urgent(UUID room, Instant starts, Actor invited) {
        var result = meeting(room, starts, invited);
        result.put("urgent", true);
        return result;
    }

    private Map<String, Object> meeting(UUID room, Instant starts, Actor invited) {
        var result = schedule(room, starts, List.of(invited), List.of());
        result.put("subject", "隔离会议");
        result.put("kind", "ORDINARY");
        return result;
    }

    private Map<String, Object> schedule(UUID room, Instant starts, List<Actor> invited, List<Actor> required) {
        return Sql.map("roomId", room.toString(), "startsAt", starts.toString(), "endsAt", starts.plusSeconds(1800).toString(),
                "attendeeIds", invited.stream().map(a -> a.userId().toString()).toList(),
                "materialUserIds", required.stream().map(a -> a.userId().toString()).toList());
    }

    private Actor person() {
        UUID id = request();
        String no = "MEETING-" + id, password = Crypto.token();
        db.update("""
                INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required)
                VALUES (?,?,?,?,?,?,false)
                """, id, no, "会议验收员工 " + id.toString().substring(0, 8), department, no + "@biolink.com", Crypto.password(password));
        return login(no, password);
    }

    private Actor grant(Actor actor, String role) {
        assertEquals(1, db.update("""
                INSERT INTO iam.user_role(id,user_id,role_id,valid_from,assigned_by,module,role_code)
                SELECT ?,?,id,CURRENT_TIMESTAMP,?,module,code FROM iam.role WHERE module='PLATFORM' AND code=?
                """, request(), actor.userId(), admin.userId(), role));
        return identity.background(actor.sessionId());
    }

    private Actor login(String no, String password) {
        var result = identity.login(Sql.map("employeeNo", no, "password", password), request(), "meeting-test-" + request());
        return identity.authenticate(result.get("token").toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> values(Map<String, Object> response, String field) {
        return (List<Map<String, Object>>) response.get(field);
    }

    private static UUID request() {
        return UUID.randomUUID();
    }
}

