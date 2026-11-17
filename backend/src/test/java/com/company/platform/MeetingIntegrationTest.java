package com.company.platform;

import com.company.platform.meeting.MeetingBookings;
import com.company.platform.meeting.MeetingFollowUp;
import com.company.platform.portal.PortalWork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.*;

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
    void onlyRescheduleResetsResponsesAndMaterialsRemain() {
        Instant starts = Instant.now().plusSeconds(7200);
        var body = meeting(roomA, starts, employee);
        body.put("collectMaterials", true);
        body.put("materialUserIds", List.of(employee.userId().toString()));
        UUID id = bookings.create(organizer, request(), body);
        bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED"));
        bookings.materials(employee, id, request(), Map.of("topics", "测试议题"));
        bookings.change(organizer, id, request(), 1, schedule(roomB, starts, List.of(employee), List.of(employee)));
        assertEquals("ACCEPTED", attendee(organizer, id, employee).get("reply"));
        assertFalse(hasPending(employee, id));
        bookings.change(organizer, id, request(), 2, schedule(roomB, starts.plusSeconds(3600),
                List.of(employee, outsider), List.of(employee)));
        assertTrue(hasPending(employee, id));
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
        assertEquals("PENDING", attendee(organizer, id, employee).get("reply"));
        assertFalse(hasPending(employee, id));
        var history = values(portal.tasks(employee, Map.of("category", "COMPLETED")), "items");
        assertTrue(history.stream().anyMatch(row -> ("#/meetings?id=" + id).equals(row.get("entry"))
                && "INVITE_EXPIRED".equals(row.get("state")) && row.get("completedAt") != null));
        assertEquals(409, assertThrows(ApiFault.class,
                () -> bookings.materials(employee, id, request(), Map.of("topics", "迟交"))).status);
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
        body.put("materialTypes", List.of("TOPICS", "COORDINATION"));
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
        body.put("materialTypes", List.of("TOPICS", "COORDINATION"));
        UUID id = bookings.create(organizer, request(), body);
        db.update("UPDATE portal.meeting_booking SET share_all=NULL WHERE id=?", id);
        var material = Map.of("subject", "待讨论事项", "expected", "明确要求");
        bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED", "materials", List.of(
                Map.of("kind", "TOPICS", "rows", List.of(material)),
                Map.of("kind", "COORDINATION", "rows", List.of(material)))));
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
        assertEquals(400, assertThrows(ApiFault.class,
                () -> bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED"))).status);
        bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED", "materials",
                List.of(Map.of("kind", "WEEKLY_PLAN", "text", "1. 完成排期"))));
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
            body.put("materialTypes", List.of("REPORT", "TOPICS"));
            UUID id = bookings.create(organizer, request(), body);
            db.update("UPDATE portal.meeting_booking SET share_all=NULL WHERE id=?", id);
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
            body.put("materialTypes", List.of("REPORT", "TOPICS", "COORDINATION"));
            body.put("trackFollowUp", true);
            if (share) body.put("shareAll", true);
            UUID id = bookings.create(organizer, request(), body);
            assertEquals(share, bookings.detail(secondAttendee, id).get("shareAll"));
            Map<String, String> material = Map.of("subject", "会前事项", "expected", "协调");
            bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED", "materials", List.of(
                    Map.of("kind", "REPORT", "html", "<strong>会前汇报</strong>", "text", "会前汇报"),
                    Map.of("kind", "TOPICS", "rows", List.of(material)),
                    Map.of("kind", "COORDINATION", "rows", List.of(material)))));
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
            assertEquals(share ? 2 : 0, ((List<?>) minutes.get("materialTables")).size());
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
        body.put("materialTypes", List.of("REPORT", "TOPICS", "COORDINATION"));
        body.put("trackFollowUp", true);
        UUID id = bookings.create(organizer, request(), body);
        String entry = "#/meetings?id=" + id;
        var row = attendeeTask(employee, entry);
        var taskId = row.get("id");
        assertEquals("MEETING_REPLY_PENDING", row.get("state"));
        bookings.reply(employee, id, request(), Map.of("decision", "ACCEPTED",
                "materials", List.of(Map.of("kind", "REPORT", "html", "汇报", "text", "汇报"))));
        assertEquals("MEETING_MATERIALS_PENDING", attendeeTask(employee, entry).get("state"));
        bookings.start(organizer, id, request());
        assertEquals("MATERIAL_EXPIRED", attendeeTask(employee, entry).get("state"));
        bookings.end(organizer, id, request());
        var first = Map.of("content", "第一项", "assigneeId", employee.userId().toString(),
                "dueAt", Instant.now().minusSeconds(60).toString());
        var second = Map.of("content", "第二项", "assigneeId", employee.userId().toString(),
                "dueAt", Instant.now().plusSeconds(3600).toString());
        followUp.saveMinutes(organizer, id, request(), 3, Map.of("decisions", "通过", "publish", true,
                "items", List.of(first, second)));
        assertEquals(taskId, attendeeTask(employee, entry).get("id"));
        assertEquals("OVERDUE", attendeeTask(employee, entry).get("state"));
        var details = values(bookings.detail(employee, id), "items");
        for (var item : details) {
            followUp.feedback(employee, (UUID) item.get("id"), request(), Map.of("feedback", "已完成"));
        }
        assertEquals("MEETING_FEEDBACK_DONE", attendeeTask(employee, entry).get("state"));
        assertEquals("COMPLETED", attendeeTask(employee, entry).get("category"));
        assertEquals(taskId, attendeeTask(employee, entry).get("id"));
        assertTrue(values(portal.tasks(employee, Map.of()), "items").stream()
                .noneMatch(task -> details.stream().anyMatch(item -> item.get("id").equals(task.get("id")))));
        followUp.review(organizer, (UUID) details.getFirst().get("id"), request(),
                Map.of("decision", "RETURNED", "reason", "继续补充"));
        assertEquals("HANDLING", attendeeTask(employee, entry).get("category"));
        assertEquals(taskId, attendeeTask(employee, entry).get("id"));
    }

    private Map<String, Object> attendeeTask(Actor actor, String entry) {
        var tasks = values(portal.tasks(actor, Map.of()), "items").stream()
                .filter(task -> entry.equals(task.get("entry"))).toList();
        assertEquals(1, tasks.size());
        return tasks.getFirst();
    }

    private boolean hasPending(Actor actor, UUID id) {
        return values(portal.tasks(actor, Map.of("category", "HANDLING")), "items").stream()
                .anyMatch(row -> ("#/meetings?id=" + id).equals(row.get("entry")));
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

