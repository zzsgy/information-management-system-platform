package com.company.platform.meeting;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 纪要草稿、发布、责任人反馈以及发起人逐项审核和办结。 */
@Service
public class MeetingFollowUp {
    private final Sql db;
    private final MeetingBookings bookings;
    private final IdentityService identity;

    public MeetingFollowUp(Sql db, MeetingBookings bookings, IdentityService identity) {
        this.db = db;
        this.bookings = bookings;
        this.identity = identity;
    }

    /** 按实际分工返回单个事项，非参会责任人只取得办理自身事项所需数据。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> item(Actor actor, UUID id) {
        var row = accessibleItem(actor, id, false);
        return Sql.map("id", id, "content", row.get("content"), "assigneeId", row.get("assignee_id"),
                "assigneeName", row.get("assignee_name"), "dueAt", row.get("due_at"), "state", row.get("state"),
                "overdue", row.get("overdue"), "history", Crypto.JSON.readValue((String) row.get("history_json"), List.class),
                "canSubmit", actor.userId().equals(row.get("assignee_id")) && waiting(row),
                "canReview", actor.userId().equals(row.get("organizer_id")) && "SUBMITTED".equals(row.get("state")));
    }

    /** 发起人结束会议后编辑底稿；发布须填写决议，未开启跟踪时随发布办结。 */
    @Transactional
    public void saveMinutes(Actor actor, UUID id, UUID request, int version, Map<String, Object> body) {
        Inputs.only(body, "materialsText", "decisions", "notes", "noteReaderIds", "shareReports", "shareTopics", "shareCoordination", "items", "publish");
        String materials = MeetingBookings.optional(body, "materialsText", 4_100_000);
        boolean publish = MeetingBookings.flag(body, "publish", false);
        String decisions = MeetingBookings.optional(body, "decisions", 12000);
        String notes = MeetingBookings.optional(body, "notes", 12000);
        var items = new ArrayList<>(Inputs.objects(body.getOrDefault("items", List.of()), 200).stream()
                .map(this::draftItem).toList());
        identity.lockActor(actor);
        var meeting = bookings.owned(actor, id);
        MeetingBookings.checkVersion(meeting, version);
        if (meeting.get("actual_ended_at") == null || meeting.get("closed_at") != null || meeting.get("minutes_published_at") != null) {
            throw ApiFault.conflict("当前会议不能编辑纪要");
        }
        boolean trackFollowUp = Boolean.TRUE.equals(meeting.get("track_follow_up"));
        if (publish) {
            var coordination = bookings.coordinationResults(meeting);
            if (decisions == null && coordination.isEmpty()) throw ApiFault.invalid("请填写会议决议");
            for (var result : coordination) {
                if (!Boolean.TRUE.equals(result.get("followUp"))) continue;
                items.add(new DraftItem(result.get("subject") + "\n" + result.get("conclusion"),
                        Inputs.uuid(result.get("assigneeId")), MeetingBookings.inputTime(result.get("dueAt")),
                        (String) result.get("key")));
            }
        }
        if (!trackFollowUp && !items.isEmpty()) throw ApiFault.invalid("未开启会后反馈跟踪，不能分派会后待办");
        for (var item : items) bookings.employee(item.assignee());
        var draft = Sql.map("materialsText", materials, "decisions", decisions, "notes", notes,
                "items", publish ? List.of() : items.stream().map(item -> Sql.map("content", item.content(),
                        "assigneeId", item.assignee(), "dueAt", item.due().toString())).toList());
        if (meeting.get("share_all") == null) addLegacySharing(id, body, draft);
        // 会前填写已经截止，随纪要保存汇总快照，供发布后按纪要权限查看。
        draft.put("materialTables", bookings.minuteTables(id));
        db.update("""
                UPDATE portal.meeting_booking SET minutes=?::jsonb,
                    minutes_published_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                    closed_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,row_version=row_version+1 WHERE id=?
                """, Crypto.json(draft), publish, publish && !trackFollowUp, id);
        if (publish) {
            for (var item : items) {
                UUID itemId = UUID.randomUUID();
                db.update("""
                        INSERT INTO portal.meeting_item(id,meeting_id,assignee_id,content,due_at,coordination_key) VALUES (?,?,?,?,?,?)
                        """, itemId, id, item.assignee(), item.content(), Timestamp.from(item.due()), item.coordinationKey());
                bookings.notify(id, itemId, item.assignee(), "MEETING_ITEM_ASSIGNED", "你有一项会后待办");
            }
            bookings.notifyAttendees(id, "MEETING_MINUTES_PUBLISHED", "会议纪要已发布 " + meeting.get("subject"));
        }
        bookings.event(actor, request, id, publish ? "MEETING_MINUTES_PUBLISH" : "MEETING_MINUTES_SAVE");
    }

    /** 仅发起人更正已发布的决议，保留发布时间、共享范围和已生成待办。 */
    @Transactional
    public void reviseDecisions(Actor actor, UUID id, UUID request, int version, Map<String, Object> body) {
        Inputs.only(body, "decisions");
        if (!body.containsKey("decisions")) throw ApiFault.invalid("缺少会议决议");
        String decisions = MeetingBookings.optional(body, "decisions", 12000);
        identity.lockActor(actor);
        var meeting = bookings.owned(actor, id);
        MeetingBookings.checkVersion(meeting, version);
        if (meeting.get("minutes_published_at") == null) throw ApiFault.conflict("请先发布会议纪要");
        if (decisions == null && bookings.coordinationResults(meeting).isEmpty())
            throw ApiFault.invalid("请填写会议决议");
        db.update("""
                UPDATE portal.meeting_booking SET minutes=jsonb_set(minutes,'{decisions}',?::jsonb),
                    row_version=row_version+1 WHERE id=?
                """, Crypto.json(decisions), id);
        bookings.event(actor, request, id, "MEETING_DECISIONS_REVISE");
    }

    /** 发起人更正协调结果时，已关联的待办在同一事务同步。 */
    @Transactional
    public void saveCoordination(Actor actor, UUID id, UUID request, Map<String, Object> body) {
        identity.lockActor(actor);
        var meeting = bookings.owned(actor, id);
        var result = bookings.saveCoordination(meeting, id, body);
        if (meeting.get("minutes_published_at") != null && result.followUp()) {
            var item = db.one("""
                    SELECT * FROM portal.meeting_item WHERE meeting_id=? AND coordination_key=? FOR NO KEY UPDATE
                    """, id, result.key());
            if (item != null) {
                reviseItem(actor, id, item, new DraftItem(result.subject() + "\n" + result.conclusion(),
                        result.assignee(), result.due(), result.key()));
            }
        }
        bookings.event(actor, request, id, "MEETING_COORDINATION_SAVE");
    }

    /** 仅发起人可更正手工待办。关联待办通过原协调事项更新，保持两处一致。 */
    @Transactional
    public void reviseItem(Actor actor, UUID id, UUID request, Map<String, Object> body) {
        var draft = draftItem(body);
        identity.lockActor(actor);
        UUID meetingId = (UUID) db.required("SELECT meeting_id FROM portal.meeting_item WHERE id=?", id).get("meeting_id");
        var meeting = bookings.owned(actor, meetingId);
        if (meeting.get("minutes_published_at") == null) throw ApiFault.conflict("请先发布会议纪要");
        var item = db.required("SELECT * FROM portal.meeting_item WHERE id=? FOR NO KEY UPDATE", id);
        if (item.get("coordination_key") != null) throw ApiFault.invalid("请在关联协调事项中更正此待办");
        bookings.employee(draft.assignee());
        reviseItem(actor, meetingId, item, draft);
        bookings.event(actor, request, meetingId, "MEETING_ITEM_REVISE");
    }

    /** 变更责任人或任务内容需按新要求重新反馈，单独调整截止时间保留已有办理状态。 */
    private void reviseItem(Actor actor, UUID meeting, Map<String, Object> item, DraftItem draft) {
        boolean newFeedback = !draft.assignee().equals(item.get("assignee_id")) || !draft.content().equals(item.get("content"));
        if (!newFeedback && draft.due().equals(MeetingBookings.time(item, "due_at"))) return;
        UUID id = (UUID) item.get("id");
        var entry = Sql.map("state", "CORRECTED", "actorName", actor.user().get("name"), "at", bookings.now().toString(),
                "text", newFeedback ? "任务内容或责任人已更正，需重新反馈。" : "截止时间已更正。",
                "previous", Sql.map("content", item.get("content"), "assigneeId", item.get("assignee_id"),
                        "dueAt", item.get("due_at"), "state", item.get("state")));
        db.update("""
                UPDATE portal.meeting_item SET content=?,assignee_id=?,due_at=?,
                    state=CASE WHEN ? THEN 'PENDING' ELSE state END,
                    confirmed_at=CASE WHEN ? THEN NULL ELSE confirmed_at END,
                    history=history||jsonb_build_array(?::jsonb) WHERE id=?
                """, draft.content(), draft.assignee(), Timestamp.from(draft.due()), newFeedback, newFeedback, Crypto.json(entry), id);
        if (newFeedback) {
            db.update("""
                    UPDATE portal.meeting_booking SET closed_at=NULL,row_version=row_version+1 WHERE id=? AND closed_at IS NOT NULL
                    """, meeting);
        }
        bookings.notify(meeting, id, draft.assignee(), "MEETING_ITEM_ASSIGNED",
                newFeedback ? "会后待办已更正，请按最新要求反馈" : "会后待办的截止时间已调整");
    }

    /** 旧预约沿用原有逐项共享设置。 */
    private void addLegacySharing(UUID meeting, Map<String, Object> body, Map<String, Object> draft) {
        var noteReaders = MeetingBookings.users(body.getOrDefault("noteReaderIds", List.of()));
        var invited = bookings.attendees(meeting).stream().map(row -> row.get("user_id")).toList();
        if (!invited.containsAll(noteReaders)) throw ApiFault.invalid("会议记录只能共享给受邀员工");
        draft.put("noteReaderIds", noteReaders);
        draft.put("sharedSummaries", Sql.map("REPORT", MeetingBookings.flag(body, "shareReports", false),
                "TOPICS", MeetingBookings.flag(body, "shareTopics", false),
                "COORDINATION", MeetingBookings.flag(body, "shareCoordination", false)));
    }

    /** 已分派的在职责任人可提交首份反馈或补充退回事项。 */
    @Transactional
    public void feedback(Actor actor, UUID id, UUID request, Map<String, Object> body) {
        Inputs.only(body, "feedback");
        String feedback = Inputs.text(body, "feedback", 8000);
        identity.lockActor(actor);
        var row = accessibleItem(actor, id, true);
        if (!actor.userId().equals(row.get("assignee_id"))) throw ApiFault.denied();
        if (!waiting(row)) throw ApiFault.conflict("当前事项不处于待反馈状态");
        transition(actor, id, "SUBMITTED", feedback);
        UUID meeting = (UUID) row.get("meeting_id");
        bookings.notify(meeting, id, (UUID) row.get("organizer_id"), "MEETING_ITEM_SUBMITTED", "会后事项已提交反馈，待确认");
        bookings.event(actor, request, meeting, "MEETING_ITEM_FEEDBACK");
    }

    /** 发起人逐项确认。退回理由同时保留在事项历史和本人的通知里。 */
    @Transactional
    public void review(Actor actor, UUID id, UUID request, Map<String, Object> body) {
        Inputs.only(body, "decision", "reason");
        String decision = Inputs.choice(body.get("decision"), "CONFIRMED", "RETURNED");
        String reason = decision.equals("RETURNED") ? Inputs.text(body, "reason", 1000) : null;
        identity.lockActor(actor);
        var row = accessibleItem(actor, id, true);
        if (!actor.userId().equals(row.get("organizer_id"))) throw ApiFault.denied();
        if (!"SUBMITTED".equals(row.get("state"))) throw ApiFault.conflict("当前事项没有待确认的反馈");
        transition(actor, id, decision, reason);
        UUID meeting = (UUID) row.get("meeting_id");
        if (reason != null) bookings.notify(meeting, id, (UUID) row.get("assignee_id"),
                "MEETING_ITEM_RETURNED", "会后事项需要补充\n退回原因 " + reason);
        bookings.event(actor, request, meeting, "MEETING_ITEM_" + decision);
    }

    /** 改为办结前确认纪要已发布且每项反馈通过。 */
    @Transactional
    public void close(Actor actor, UUID id, UUID request) {
        identity.lockActor(actor);
        var row = bookings.owned(actor, id);
        if (row.get("minutes_published_at") == null || row.get("closed_at") != null || !bookings.allItemsConfirmed(id))
            throw ApiFault.conflict("请先发布纪要并确认所有会后待办");
        db.update("UPDATE portal.meeting_booking SET closed_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=?", id);
        bookings.event(actor, request, id, "MEETING_CLOSE");
    }

    /** 办理时锁住事项行，审核与反馈以同一条状态记录为准。 */
    private Map<String, Object> accessibleItem(Actor actor, UUID id, boolean lock) {
        return db.required("""
                SELECT i.*,i.history::text AS history_json,u.name AS assignee_name,m.organizer_id,
                    (i.due_at<CURRENT_TIMESTAMP AND i.state IN ('PENDING','RETURNED')) AS overdue
                FROM portal.meeting_item i
                JOIN iam.user_account u ON u.id=i.assignee_id
                JOIN portal.meeting_booking m ON m.id=i.meeting_id
                WHERE i.id=? AND m.minutes_published_at IS NOT NULL
                    AND (i.assignee_id=? OR m.organizer_id=? OR (m.kind='WEEKLY' AND ?)
                        OR (m.share_all AND EXISTS (
                            SELECT 1 FROM portal.meeting_attendee a
                            WHERE a.meeting_id=m.id AND a.user_id=? AND a.removed_at IS NULL)))
                """ + (lock ? " FOR NO KEY UPDATE OF i" : ""),
                id, actor.userId(), actor.userId(), bookings.generalManager(actor.userId()), actor.userId());
    }

    private static boolean waiting(Map<String, Object> row) {
        return "PENDING".equals(row.get("state")) || "RETURNED".equals(row.get("state"));
    }

    private void transition(Actor actor, UUID id, String state, String text) {
        var entry = Sql.map("state", state, "text", text, "actorName", actor.user().get("name"),
                "at", bookings.now().toString());
        db.update("""
                UPDATE portal.meeting_item SET state=?,history=history||jsonb_build_array(?::jsonb),
                    confirmed_at=CASE WHEN ?='CONFIRMED' THEN CURRENT_TIMESTAMP ELSE NULL END WHERE id=?
                """, state, Crypto.json(entry), state, id);
    }

    private record DraftItem(String content, UUID assignee, Instant due, String coordinationKey) {}

    private DraftItem draftItem(Map<String, Object> body) {
        Inputs.only(body, "content", "assigneeId", "dueAt");
        return new DraftItem(Inputs.text(body, "content", 4000), Inputs.uuid(body.get("assigneeId")),
                MeetingBookings.inputTime(body.get("dueAt")), null);
    }
}

