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
        boolean active = row.get("removed_at") == null;
        return Sql.map("id", id, "content", row.get("content"), "assigneeId", row.get("assignee_id"),
                "assigneeName", row.get("assignee_name"), "dueAt", row.get("due_at"), "state", row.get("state"),
                "overdue", row.get("overdue"), "history", Crypto.JSON.readValue((String) row.get("history_json"), List.class),
                "canSubmit", active && actor.userId().equals(row.get("assignee_id")) && waiting(row),
                "canReview", active && actor.userId().equals(row.get("organizer_id")) && "SUBMITTED".equals(row.get("state")));
    }

    /** 发起人结束会议后编辑底稿；发布须填写决议，未开启跟踪时随发布办结。 */
    @Transactional
    public void saveMinutes(Actor actor, UUID id, UUID request, int version, Map<String, Object> body) {
        Inputs.only(body, "materialsText", "decisions", "notes", "noteReaderIds", "shareReports", "shareTopics", "shareCoordination", "items", "coordinationTrackers", "publish");
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
        var coordination = bookings.coordinationResults(meeting);
        var tracked = coordination.stream().filter(result -> Boolean.TRUE.equals(result.get("followUp"))).toList();
        var extra = coordinationTrackers(body, tracked);
        if (publish) {
            if (decisions == null && coordination.isEmpty()) throw ApiFault.invalid("请填写会议决议");
            for (var result : tracked) {
                String key = (String) result.get("key");
                var assignees = coordinationAssignees(id, key, Inputs.uuid(result.get("assigneeId")));
                assignees.addAll(extra.getOrDefault(key, Set.of()));
                items.add(new DraftItem(result.get("subject") + "\n" + result.get("conclusion"),
                        assignees, MeetingBookings.inputTime(result.get("dueAt")), key));
            }
        }
        if (!trackFollowUp && !items.isEmpty()) throw ApiFault.invalid("未开启会后反馈跟踪，不能分派会后待办");
        var draft = Sql.map("materialsText", materials, "decisions", decisions, "notes", notes,
                "items", publish ? List.of() : items.stream().map(item -> Sql.map("content", item.content(),
                        "trackers", bookings.trackerOptions(item.assignees()), "dueAt", item.due().toString())).toList());
        if (!publish) draft.put("coordinationTrackers", extra.entrySet().stream()
                .map(entry -> Sql.map("key", entry.getKey(), "trackers", bookings.trackerOptions(entry.getValue()))).toList());
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
                UUID group = UUID.randomUUID();
                for (UUID employee : item.assignees()) {
                    bookings.employee(employee);
                    assign(id, group, employee, item);
                }
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
            var assignments = db.rows("""
                    SELECT * FROM portal.meeting_item WHERE meeting_id=? AND coordination_key=? AND removed_at IS NULL
                    ORDER BY id FOR NO KEY UPDATE
                    """, id, result.key());
            if (!assignments.isEmpty()) {
                var assignees = coordinationAssignees(id, result.key(), result.assignee());
                UUID previous = bookings.coordinationResults(meeting).stream()
                        .filter(saved -> result.key().equals(saved.get("key")))
                        .map(saved -> Inputs.uuid(saved.get("assigneeId"))).findFirst().orElseThrow();
                for (var assignment : assignments) {
                    UUID employee = (UUID) assignment.get("assignee_id");
                    if (!employee.equals(previous)) assignees.add(employee);
                }
                reviseGroup(actor, id, assignments, new DraftItem(result.subject() + "\n" + result.conclusion(),
                        assignees, result.due(), result.key()));
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
        var item = db.required("SELECT group_id,coordination_key FROM portal.meeting_item WHERE id=?", id);
        if (item.get("coordination_key") != null) throw ApiFault.invalid("请在关联协调事项中更正此待办");
        var assignments = db.rows("""
                SELECT * FROM portal.meeting_item WHERE group_id=? AND removed_at IS NULL ORDER BY id FOR NO KEY UPDATE
                """, item.get("group_id"));
        if (assignments.isEmpty()) throw ApiFault.hidden();
        reviseGroup(actor, meetingId, assignments, draft);
        bookings.event(actor, request, meetingId, "MEETING_ITEM_REVISE");
    }

    /** 共用内容和期限，保留退出人员的反馈历史；原先的单人改派继续使用原链接。 */
    private void reviseGroup(Actor actor, UUID meeting, List<Map<String, Object>> assignments, DraftItem draft) {
        for (UUID employee : draft.assignees()) bookings.employee(employee);
        UUID group = (UUID) assignments.getFirst().get("group_id");
        if (assignments.size() == 1 && draft.assignees().size() == 1) {
            reviseAssignment(actor, meeting, assignments.getFirst(), draft.assignees().iterator().next(), draft);
            return;
        }
        var additions = new LinkedHashSet<>(draft.assignees());
        for (var row : assignments) {
            UUID employee = (UUID) row.get("assignee_id");
            if (additions.remove(employee)) {
                reviseAssignment(actor, meeting, row, employee, draft);
            } else {
                db.update("UPDATE portal.meeting_item SET removed_at=CURRENT_TIMESTAMP WHERE id=?", row.get("id"));
            }
        }
        for (UUID employee : additions) assign(meeting, group, employee, draft);
        if (!additions.isEmpty()) reopen(meeting);
    }

    /** 每名员工使用独立的通知、待办直达链接及反馈记录。 */
    private void assign(UUID meeting, UUID group, UUID employee, DraftItem draft) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO portal.meeting_item(id,meeting_id,group_id,assignee_id,content,due_at,coordination_key)
                VALUES (?,?,?,?,?,?,?)
                """, id, meeting, group, employee, draft.content(), Timestamp.from(draft.due()), draft.coordinationKey());
        bookings.notify(meeting, id, employee, "MEETING_ITEM_ASSIGNED", "你有一项会后待办");
    }

    /** 变更责任人或任务内容需按新要求重新反馈，单独调整截止时间保留已有办理状态。 */
    private void reviseAssignment(Actor actor, UUID meeting, Map<String, Object> item, UUID employee, DraftItem draft) {
        boolean newFeedback = !employee.equals(item.get("assignee_id")) || !draft.content().equals(item.get("content"));
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
                """, draft.content(), employee, Timestamp.from(draft.due()), newFeedback, newFeedback, Crypto.json(entry), id);
        if (newFeedback) reopen(meeting);
        bookings.notify(meeting, id, employee, "MEETING_ITEM_ASSIGNED",
                newFeedback ? "会后待办已更正，请按最新要求反馈" : "会后待办的截止时间已调整");
    }

    private void reopen(UUID meeting) {
        db.update("""
                UPDATE portal.meeting_booking SET closed_at=NULL,row_version=row_version+1 WHERE id=? AND closed_at IS NOT NULL
                """, meeting);
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
                WHERE i.id=? AND (i.removed_at IS NULL OR (m.organizer_id=? AND NOT ?)) AND m.minutes_published_at IS NOT NULL
                    AND (i.assignee_id=? OR m.organizer_id=? OR (m.kind='WEEKLY' AND ?)
                        OR (m.share_all AND EXISTS (
                            SELECT 1 FROM portal.meeting_attendee a
                            WHERE a.meeting_id=m.id AND a.user_id=? AND a.removed_at IS NULL)))
                """ + (lock ? " FOR NO KEY UPDATE OF i" : ""),
                id, actor.userId(), lock, actor.userId(), actor.userId(), bookings.generalManager(actor.userId()), actor.userId());
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

    /** 同一事项对应一组员工，每名员工之后独立反馈。 */
    private record DraftItem(String content, Set<UUID> assignees, Instant due, String coordinationKey) {}

    private DraftItem draftItem(Map<String, Object> body) {
        Inputs.only(body, "content", "assigneeId", "assigneeIds", "dueAt");
        var assignees = body.containsKey("assigneeIds") ? MeetingBookings.users(body.get("assigneeIds"))
                : Set.of(Inputs.uuid(body.get("assigneeId")));
        if (assignees.isEmpty()) throw ApiFault.invalid("每项会后待办至少选择一位跟踪人员");
        return new DraftItem(Inputs.text(body, "content", 4000), assignees,
                MeetingBookings.inputTime(body.get("dueAt")), null);
    }

    /** 仅允许为本场勾选了会后跟踪的协调事项增加人员。 */
    private Map<String, Set<UUID>> coordinationTrackers(Map<String, Object> body, List<Map<String, Object>> tracked) {
        var keys = tracked.stream().map(row -> row.get("key")).collect(java.util.stream.Collectors.toSet());
        var extra = new LinkedHashMap<String, Set<UUID>>();
        for (var selection : Inputs.objects(body.getOrDefault("coordinationTrackers", List.of()), keys.size())) {
            Inputs.only(selection, "key", "assigneeIds");
            String key = Inputs.text(selection, "key", 48);
            if (!keys.contains(key) || extra.putIfAbsent(key, MeetingBookings.users(selection.get("assigneeIds"))) != null)
                throw ApiFault.invalid("协调跟踪事项已变更，请刷新后重试");
        }
        return extra;
    }

    /** 由协调事宜的原材料记录定位发起人，责任人使用最新协调结果。 */
    private Set<UUID> coordinationAssignees(UUID meeting, String key, UUID assignee) {
        UUID attendee = Inputs.uuid(key.split(":", 2)[0]);
        UUID author = (UUID) db.required("SELECT user_id FROM portal.meeting_attendee WHERE id=? AND meeting_id=?",
                attendee, meeting).get("user_id");
        var assignees = new LinkedHashSet<UUID>();
        assignees.add(assignee);
        assignees.add(author);
        return assignees;
    }
}

