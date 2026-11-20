package com.company.platform.meeting;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 会议可见范围、预约占用、邀请与会前材料。 */
@Service
public class MeetingBookings {
    static final String WEEKLY = "PLATFORM.MEETING_WEEKLY";
    static final String URGENT = "PLATFORM.MEETING_URGENT";
    private static final String SUMMARY = "id,subject,kind,urgent,starts_at,ends_at,room_name,state";
    private static final String PEOPLE = "id,name,employee_no,department_id";
    private static final String ACTIVE_EMPLOYEE = "status='ACTIVE' AND NOT it_shared_account";
    private static final String VIEW = """
            SELECT m.*, m.minutes::text AS minutes_json, COALESCE(r.name, '其他地点') AS room_name, u.name AS organizer_name,
                CASE
                    WHEN m.cancelled_at IS NOT NULL THEN 'CANCELLED'
                    WHEN m.closed_at IS NOT NULL THEN 'CLOSED'
                    WHEN m.actual_started_at IS NULL THEN 'SCHEDULED'
                    WHEN m.actual_ended_at IS NULL THEN 'IN_PROGRESS'
                    WHEN m.minutes_published_at IS NULL THEN 'AWAITING_MINUTES'
                    ELSE 'FOLLOW_UP'
                END AS state
            FROM portal.meeting_booking m
            LEFT JOIN portal.meeting_room r ON r.id=m.room_id
            JOIN iam.user_account u ON u.id=m.organizer_id
            """;
    private static final String VISIBLE = """
            (m.organizer_id=? OR (m.kind='WEEKLY' AND ?) OR EXISTS (
                SELECT 1 FROM portal.meeting_attendee a
                WHERE a.meeting_id=m.id AND a.user_id=? AND a.removed_at IS NULL))
            """;
    private static final DateTimeFormatter CONFLICT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final Sql db;
    private final IdentityService identity;
    private final Audit audit;

    public MeetingBookings(Sql db, IdentityService identity, Audit audit) {
        this.db = db;
        this.identity = identity;
        this.audit = audit;
    }

    /** 候选名单只返回在职个人的基础资料。协调事宜选择公司时，查询覆盖全部部门。 */
    public Map<String, Object> people(Actor actor, Map<String, String> query) {
        if (!actor.systemAdministrator()) employee(actor.userId());
        Inputs.only(query, "keyword", "departmentId", "page", "scope");
        String keyword = optional(query, "keyword", 128);
        UUID department = Inputs.nullableUuid(query.get("departmentId"));
        if ("COORDINATION".equals(Inputs.choice(query.getOrDefault("scope", "DEPARTMENT"), "DEPARTMENT", "COORDINATION")))
            department = coordinationDepartmentScope(department);
        int page = Inputs.integer(query.get("page"), 1, 1, 100000);
        var rows = db.rows("""
                SELECT id,name,employee_no,department_id FROM iam.user_account
                WHERE %s AND (?::uuid IS NULL OR department_id=?)
                  AND position(lower(?) in lower(name||' '||employee_no))>0
                ORDER BY name,id LIMIT 21 OFFSET ?
                """.formatted(ACTIVE_EMPLOYEE), department, department, Objects.toString(keyword, ""), (page - 1) * 20);
        return Sql.map("items", rows.stream().limit(20).map(row -> Sql.view(row, PEOPLE)).toList(),
                "hasNext", rows.size() > 20);
    }

    /** 下拉只返回启用地点，另外提供服务端计算的入口资格。 */
    public Map<String, Object> options(Actor actor) {
        return Sql.map("rooms", db.rows("SELECT id,name FROM portal.meeting_room WHERE enabled ORDER BY name,id"),
                "departments", db.rows("SELECT id,name FROM iam.department WHERE status='ACTIVE' ORDER BY sort_order,name,id"),
                "canCreate", isEmployee(actor.userId()), "canWeekly", actor.actions().contains(WEEKLY),
                "canUrgent", actor.actions().contains(URGENT), "canManageRooms", actor.systemAdministrator());
    }

    /** 先限制可见范围，再计算分页数量。 */
    public Map<String, Object> list(Actor actor, Map<String, String> query) {
        Inputs.only(query, "page", "pageSize");
        int page = Inputs.integer(query.get("page"), 1, 1, 100000);
        int size = Inputs.integer(query.get("pageSize"), 10, 1, 100);
        boolean generalManager = generalManager(actor.userId());
        long total = db.count("SELECT count(*) FROM portal.meeting_booking m WHERE " + VISIBLE,
                actor.userId(), generalManager, actor.userId());
        var rows = db.rows(VIEW + " WHERE " + VISIBLE + """
                 ORDER BY (m.starts_at<=CURRENT_TIMESTAMP), m.starts_at DESC, m.id LIMIT ? OFFSET ?
                """, actor.userId(), generalManager, actor.userId(), size, (page - 1) * size);
        return Sql.map("items", rows.stream().map(row -> Sql.view(row, SUMMARY)).toList(),
                "page", page, "pageSize", size, "total", total, "hasNext", (long) page * size < total);
    }

    /** 仅会议主体可查询会议。事项责任人从独立事项接口读取。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(Actor actor, UUID id) {
        boolean manager = generalManager(actor.userId());
        var row = db.required(VIEW + " WHERE m.id=? AND " + VISIBLE, id, actor.userId(), manager, actor.userId());
        boolean organizer = actor.userId().equals(row.get("organizer_id"));
        // 已通过会议可见范围检查，开启共享时扩展读取范围，办理仍按发起人和受邀身份计算。
        boolean full = organizer || ("WEEKLY".equals(row.get("kind")) && manager) || Boolean.TRUE.equals(row.get("share_all"));
        boolean legacy = row.get("share_all") == null;
        boolean scheduled = "SCHEDULED".equals(row.get("state"));
        boolean published = row.get("minutes_published_at") != null;

        var attendees = attendees(id);
        var result = Sql.view(row, SUMMARY
                + ",organizer_id,organizer_name,room_id,description,collect_materials,material_types,track_follow_up,cancel_reason,row_version,actual_started_at,actual_ended_at,share_all");
        result.put("canEdit", organizer && scheduled);
        result.put("canStart", organizer && scheduled);
        result.put("canEnd", organizer && "IN_PROGRESS".equals(row.get("state")));
        result.put("canWriteMinutes", organizer && "AWAITING_MINUTES".equals(row.get("state")));
        result.put("canClose", organizer && "FOLLOW_UP".equals(row.get("state")) && allItemsConfirmed(id));
        // 阅读版本和确认时间仅发给发起人及确认人本人，沿用本次详情的事务快照。
        boolean sharedReading = Boolean.TRUE.equals(row.get("share_all")) && (organizer ||
                attendees.stream().anyMatch(person -> actor.userId().equals(person.get("user_id"))));
        var readingMaterials = sharedReading ? readingMaterials(id) : List.<Map<String, Object>>of();
        if (full && summaryTypes(row).contains("COORDINATION"))
            result.put("coordinationResults", coordinationResults(row));
        var minutes = published || full ? minutes(row, attendees, full, actor.userId()) : null;
        boolean reportsShared = legacy && published && Boolean.TRUE.equals(minutes.get("reportsShared"));
        result.put("attendees", attendees.stream().map(person -> {
            boolean ownOrFull = full || actor.userId().equals(person.get("user_id"));
            String fields = "user_id,name,employee_no";
            if (ownOrFull) {
                fields += ",id,reply,decline_reason,material_required,report,topics,material_at";
            } else if (reportsShared) {
                fields += ",report";
            }
            var visible = Sql.view(person, fields);
            if (sharedReading && (organizer || actor.userId().equals(person.get("user_id")))
                    && !"DECLINED".equals(person.get("reply"))) {
                String revision = readingRevision(readingMaterials, (UUID) person.get("user_id"));
                if (revision != null) {
                    visible.put("readingRequired", true);
                    if (revision.equals(person.get("read_material_revision")))
                        visible.put("readMaterialAt", person.get("read_material_at"));
                    else if (scheduled && actor.userId().equals(person.get("user_id")))
                        result.put("readingRevision", revision);
                }
            }
            if ((ownOrFull || reportsShared) && row.get("material_types") != null) {
                visible.put("materials", db.rows("""
                        SELECT kind,payload::text AS payload,submitted_at FROM portal.meeting_material
                        WHERE attendee_id=? AND (? OR kind='REPORT')
                        """, person.get("id"), ownOrFull).stream().map(m -> Sql.map("kind",m.get("kind"),
                        "data",Crypto.JSON.readTree(m.get("payload").toString()),"submittedAt",m.get("submitted_at"),
                        "attendeeId", person.get("id"))).toList());
            }
            return visible;
        }).toList());
        var own = attendees.stream().filter(a -> actor.userId().equals(a.get("user_id"))).findFirst().orElse(null);
        result.put("canReply", scheduled && own != null);
        if (own != null && row.get("material_types") != null) {
            result.put("materialAuthor", Sql.view(db.required("""
                    SELECT u.name,d.name AS department FROM iam.user_account u
                    JOIN iam.department d ON d.id=u.department_id WHERE u.id=?
                    """, actor.userId()), "name,department"));
        }
        result.put("canSubmitMaterials", scheduled && own != null && eligibleForMaterials(row, own));
        if (full && "IN_PROGRESS".equals(row.get("state"))) {
            result.put("liveDecisions", row.get("live_decisions"));
            if (row.get("live_notes") != null) result.put("liveNotes", row.get("live_notes"));
        }
        result.put("minutes", minutes);
        result.put("items", published ? db.rows("""
                SELECT i.id,i.content,i.assignee_id,u.name AS assignee_name,i.due_at,i.coordination_key,
                    CASE WHEN ? OR i.assignee_id=? THEN i.state END AS state,
                    (? OR i.assignee_id=?) AS can_open
                FROM portal.meeting_item i JOIN iam.user_account u ON u.id=i.assignee_id
                WHERE i.meeting_id=? AND (? OR i.assignee_id=?) ORDER BY i.created_at,i.id
                """, full, actor.userId(), full, actor.userId(), id, full || legacy, actor.userId()).stream()
                .map(item -> Sql.view(item, "id,content,assignee_id,assignee_name,due_at,coordination_key,state,can_open")).toList() : List.of());
        return result;
    }

    /** 初次发布同时确认占用、写入会前责任与站内通知。 */
    @Transactional
    public UUID create(Actor actor, UUID request, Map<String, Object> body) {
        Inputs.only(body, "subject", "description", "kind", "urgent", "startsAt", "endsAt", "roomId",
                "attendeeIds", "collectMaterials", "materialUserIds", "trackFollowUp", "materialTypes", "shareAll");
        String subject = Inputs.text(body, "subject", 200);

        String kind = Inputs.choice(body.get("kind"), "ORDINARY", "WEEKLY");
        boolean weekly = kind.equals("WEEKLY");
        boolean urgent = flag(body, "urgent", false);
        Set<String> types = null;
        if (body.containsKey("materialTypes")) {
            if (!(body.get("materialTypes") instanceof List<?> values) || values.size()>3)
                throw ApiFault.invalid("请选择有效的会前材料类型");
            types = new LinkedHashSet<>();
            for (Object value : values)
                if (!types.add(Inputs.choice(value, "REPORT", "WEEKLY_PLAN", "COORDINATION")))
                    throw ApiFault.invalid("会前材料类型不能重复");
        }
        boolean collect = weekly || (types == null ? flag(body, "collectMaterials", false) : !types.isEmpty());
        boolean followUp = flag(body, "trackFollowUp", false) || weekly;
        boolean shareAll = flag(body, "shareAll", false);
        Schedule schedule = schedule(body);
        if (weekly) actor.require(WEEKLY);
        if (urgent) actor.require(URGENT);
        reservationLock();
        identity.lockActor(actor);
        employee(actor.userId());
        requireFutureStart(schedule.starts());
        String description = locationDescription(schedule.room(), optional(body, "description", 2000));
        if (schedule.room() != null) requireRoom(schedule.room());
        validatePeople(actor, schedule.attendees());
        Set<UUID> materials = types == null ? materialUsers(schedule, weekly, collect) : types.isEmpty() ? Set.of() : schedule.attendees();
        checkConflicts(null, actor.userId(), schedule.room(), schedule.starts(), schedule.ends(),
                schedule.attendees(), urgent);
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO portal.meeting_booking
                    (id,organizer_id,room_id,kind,subject,description,urgent,collect_materials,track_follow_up,starts_at,ends_at,material_types,share_all)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?::text[],?)
                """, id, actor.userId(), schedule.room(), kind, subject, description, urgent, collect, followUp,
                Timestamp.from(schedule.starts()), Timestamp.from(schedule.ends()),
                types == null ? null : "{" + String.join(",", types) + "}", shareAll);
        for (UUID user : schedule.attendees()) {
            invite(id, user, materials.contains(user));
            notify(id, null, user, "MEETING_INVITED", (urgent ? "紧急会议邀请 " : "会议邀请 ") + subject);
        }
        event(actor, request, id, "MEETING_CREATE");
        return id;
    }

    /** 改期重置有效邀请回复，换室保留回复，增减只影响相应员工。 */
    @Transactional
    public void change(Actor actor, UUID id, UUID request, int version, Map<String, Object> body) {
        Inputs.only(body, "startsAt", "endsAt", "roomId", "attendeeIds", "materialUserIds", "description");
        Schedule schedule = schedule(body);
        reservationLock();
        identity.lockActor(actor);
        var row = owned(actor, id);
        checkVersion(row, version);
        requireNotStarted(row);
        if (!schedule.starts().equals(time(row, "starts_at"))) requireFutureStart(schedule.starts());
        if (!now().isBefore(schedule.ends())) throw ApiFault.conflict("预约时段已过，请重新安排会议时间");
        boolean timeChanged = !schedule.starts().equals(time(row, "starts_at"))
                || !schedule.ends().equals(time(row, "ends_at"));
        String description = locationDescription(schedule.room(), body.containsKey("description")
                ? optional(body, "description", 2000) : (String) row.get("description"));
        boolean roomChanged = !Objects.equals(schedule.room(), row.get("room_id"))
                || (schedule.room() == null && !Objects.equals(description, row.get("description")));
        boolean weekly = "WEEKLY".equals(row.get("kind"));
        if (roomChanged && schedule.room() != null) requireRoom(schedule.room());
        var current = new HashMap<UUID, Map<String, Object>>();
        for (var person : attendees(id)) current.put((UUID) person.get("user_id"), person);
        Set<UUID> added = new LinkedHashSet<>(schedule.attendees());
        added.removeAll(current.keySet());
        validatePeople(actor, added);
        boolean legacy = row.get("material_types") == null;
        Set<UUID> materials = legacy ? materialUsers(schedule, weekly, Boolean.TRUE.equals(row.get("collect_materials")))
                : ((List<?>) row.get("material_types")).isEmpty() ? Set.of() : schedule.attendees();
        Set<UUID> occupying = new LinkedHashSet<>(schedule.attendees());
        occupying.removeIf(user -> current.containsKey(user) && "DECLINED".equals(current.get(user).get("reply")));
        boolean urgent = Boolean.TRUE.equals(row.get("urgent"));
        if (timeChanged || roomChanged) {
            checkConflicts(id, actor.userId(), schedule.room(), schedule.starts(), schedule.ends(), occupying, urgent);
        } else if (!urgent && !added.isEmpty()) {
            checkPeople(id, added, schedule.starts(), schedule.ends());
        }
        db.update("""
                UPDATE portal.meeting_booking SET room_id=?,description=?,starts_at=?,ends_at=?,row_version=row_version+1 WHERE id=?
                """, schedule.room(), description, Timestamp.from(schedule.starts()), Timestamp.from(schedule.ends()), id);
        String subject = (String) row.get("subject");
        String invitation = (Boolean.TRUE.equals(row.get("urgent")) ? "紧急会议邀请 " : "会议邀请 ") + subject;
        for (UUID user : current.keySet()) {
            if (schedule.attendees().contains(user)) continue;
            db.update("UPDATE portal.meeting_attendee SET removed_at=CURRENT_TIMESTAMP WHERE meeting_id=? AND user_id=?", id, user);
            notify(id, null, user, "MEETING_REMOVED", "你已从会议移除 " + subject);
        }
        for (UUID user : schedule.attendees()) {
            if (added.contains(user)) {
                invite(id, user, materials.contains(user));
                notify(id, null, user, "MEETING_INVITED", invitation);
            } else {
                // 周会保留对原受邀部门经理的会前安排，新增人员按发起修改时的任职判断。
                if (legacy && !weekly) db.update("UPDATE portal.meeting_attendee SET material_required=? WHERE meeting_id=? AND user_id=?",
                        materials.contains(user), id, user);
                if (timeChanged) {
                    // 参会回复属于整场会议，改期只通知，不清空员工已作出的选择。
                    notify(id, null, user, "MEETING_RESCHEDULED", "会议时间已调整，原参会回复保留 " + subject);
                } else if (roomChanged) notify(id, null, user, "MEETING_ROOM_CHANGED", "会议地点已调整 " + subject);
            }
        }
        event(actor, request, id, "MEETING_CHANGE");
    }

    /** 发起人手动开始；提前开会时校验新增占用并同时通知有效受邀人员。 */
    @Transactional
    public void start(Actor actor, UUID id, UUID request) {
        reservationLock();
        identity.lockActor(actor);
        var row = owned(actor, id);
        requireNotStarted(row);
        Instant actualStart = now();
        Instant starts = time(row, "starts_at");
        Instant ends = time(row, "ends_at");
        if (!actualStart.isBefore(ends)) throw ApiFault.conflict("预约时段已过，请先修改安排");
        if (actualStart.isBefore(starts)) {
            Set<UUID> occupying = new LinkedHashSet<>();
            for (var attendee : attendees(id)) {
                if (!"DECLINED".equals(attendee.get("reply"))) occupying.add((UUID) attendee.get("user_id"));
            }
            checkConflicts(id, actor.userId(), (UUID) row.get("room_id"), actualStart, ends, occupying,
                    Boolean.TRUE.equals(row.get("urgent")));
        }
        db.update("UPDATE portal.meeting_booking SET actual_started_at=?,row_version=row_version+1 WHERE id=?",
                Timestamp.from(actualStart), id);
        notifyAttendees(id, "MEETING_STARTED", "会议已开始 " + row.get("subject"));
        event(actor, request, id, "MEETING_START");
    }

    /** 进行中的会议只允许发起人更新现场记录。 */
    @Transactional
    public void saveLiveNotes(Actor actor, UUID id, Map<String, Object> body) {
        Inputs.only(body, "notes");
        if (!body.containsKey("notes")) throw ApiFault.invalid("缺少会议记录");
        String notes = optional(body, "notes", 12000);
        identity.lockActor(actor);
        var row = owned(actor, id);
        if (row.get("actual_started_at") == null || row.get("actual_ended_at") != null)
            throw ApiFault.conflict("只有进行中的会议可以记录");
        db.update("UPDATE portal.meeting_booking SET live_notes=? WHERE id=?", notes, id);
    }

    /** 发起人可在实际开会到结束之间保存决议，结束后改由纪要编辑。 */
    @Transactional
    public void saveLiveDecisions(Actor actor, UUID id, Map<String, Object> body) {
        Inputs.only(body, "decisions");
        if (!body.containsKey("decisions")) throw ApiFault.invalid("缺少会议决议");
        String decisions = optional(body, "decisions", 12000);
        identity.lockActor(actor);
        var row = owned(actor, id);
        if (row.get("actual_started_at") == null || row.get("actual_ended_at") != null)
            throw ApiFault.conflict("只有进行中的会议可以编辑会中决议");
        db.update("UPDATE portal.meeting_booking SET live_decisions=? WHERE id=?", decisions, id);
    }

    /** 保存后提供已校验的字段，供已发布纪要同步关联待办。 */
    record CoordinationUpdate(String key, String subject, String conclusion, UUID assignee, Instant due, boolean followUp) {}

    /** 在发起人的保存事务中更新协调结果，发布后仍使用原材料行和原跟踪设置。 */
    CoordinationUpdate saveCoordination(Map<String, Object> meeting, UUID id, Map<String, Object> body) {
        Inputs.only(body, "key", "conclusion", "departmentId", "assigneeId", "dueAt", "followUp");
        String key = Inputs.text(body, "key", 48);
        String conclusion = optional(body, "conclusion", 3000);
        UUID assignee = Inputs.nullableUuid(body.get("assigneeId"));
        Instant due = body.get("dueAt") == null ? null : inputTime(body.get("dueAt"));
        if (meeting.get("actual_started_at") == null) throw ApiFault.conflict("会议开始后才可修改协调事宜");
        boolean published = meeting.get("minutes_published_at") != null;
        tools.jackson.databind.JsonNode source = null;
        for (var table : minuteTables(id)) {
            if (!"COORDINATION".equals(table.get("kind"))) continue;
            var rows = Crypto.JSON.valueToTree(table.get("data")).path("rows");
            for (int index = 0; index < rows.size(); index++) {
                if (key.equals(table.get("attendeeId") + ":" + index))
                    source = rows.get(index);
            }
        }
        if (source == null) throw ApiFault.invalid("未找到本次会议的协调事项");
        var results = new ArrayList<>(coordinationResults(meeting));
        var previous = results.stream().filter(result -> key.equals(result.get("key"))).findFirst().orElse(Map.of());
        boolean wasTracked = Boolean.TRUE.equals(previous.get("followUp"));
        boolean followUp = flag(body, "followUp", published && wasTracked);
        if (published && followUp != wasTracked) throw ApiFault.invalid("纪要发布后不支持变更该事项的反馈跟踪设置");
        if (followUp && (!Boolean.TRUE.equals(meeting.get("track_follow_up")) || assignee == null || due == null || conclusion == null))
            throw ApiFault.invalid("跟踪事项须填写结论、责任人和截止时间");
        // 兼容未传部门的客户端。已经改派的部门优先于会前材料中的原部门。
        var departmentId = previous.getOrDefault("departmentId", source.path("departmentId").asText(null));
        UUID department = Inputs.nullableUuid(departmentId);
        var departmentName = previous.getOrDefault("department", source.path("department").asText(null));
        if (body.containsKey("departmentId")) {
            department = Inputs.uuid(body.get("departmentId"));
            departmentName = coordinationDepartmentName(department);
        }
        if (assignee == null && (source.hasNonNull("assigneeId") || body.containsKey("departmentId")))
            throw ApiFault.invalid("请选择一名责任人");
        Object assigneeName = assignee == null ? null : coordinationAssignee(assignee, department).get("name");
        results.removeIf(result -> key.equals(result.get("key")));
        String subject = source.path("subject").asText();
        results.add(Sql.map("key", key, "subject", subject, "conclusion", conclusion,
                "departmentId", department, "department", departmentName, "assigneeId", assignee,
                "assigneeName", assigneeName, "dueAt", due == null ? null : due.toString(), "followUp", followUp));
        db.update("UPDATE portal.meeting_booking SET coordination_results=?::jsonb WHERE id=?", Crypto.json(results), id);
        return new CoordinationUpdate(key, subject, conclusion, assignee, due, followUp);
    }

    /** 数据库映射保留结论快照，供详情、导出和发布纪要共同使用。 */
    List<Map<String, Object>> coordinationResults(Map<String, Object> meeting) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = Crypto.JSON.readValue(meeting.get("coordination_results").toString(), List.class);
        return results;
    }

    /** 发起人手动结束后开放纪要填写，并以实际结束时间释放剩余预约时段。 */
    @Transactional
    public void end(Actor actor, UUID id, UUID request) {
        reservationLock();
        identity.lockActor(actor);
        var row = owned(actor, id);
        if (row.get("actual_started_at") == null || row.get("actual_ended_at") != null)
            throw ApiFault.conflict("只有进行中的会议可以结束");
        db.update("UPDATE portal.meeting_booking SET actual_ended_at=clock_timestamp(),row_version=row_version+1 WHERE id=?", id);
        event(actor, request, id, "MEETING_END");
    }

    /** 取消原因同时进入会议详情和受邀人员的站内消息。 */
    @Transactional
    public void cancel(Actor actor, UUID id, UUID request, Map<String, Object> body) {
        Inputs.only(body, "reason");
        String reason = Inputs.text(body, "reason", 1000);
        reservationLock();
        identity.lockActor(actor);
        var row = owned(actor, id);
        requireNotStarted(row);
        db.update("""
                UPDATE portal.meeting_booking SET cancelled_at=CURRENT_TIMESTAMP,cancel_reason=?,row_version=row_version+1
                WHERE id=?
                """, reason, id);
        notifyAttendees(id, "MEETING_CANCELLED", "会议已取消 " + row.get("subject") + "\n取消原因 " + reason);
        event(actor, request, id, "MEETING_CANCEL");
    }

    /** 未回复已占用时段，只有从拒绝恢复参加时需要重新校验该员工。 */
    @Transactional
    public void reply(Actor actor, UUID id, UUID request, Map<String, Object> body) {
        Inputs.only(body, "decision", "reason", "materials");
        String decision = Inputs.choice(body.get("decision"), "ACCEPTED", "DECLINED");
        String reason = decision.equals("DECLINED") ? Inputs.text(body, "reason", 1000) : null;
        reservationLock();
        identity.lockActor(actor);
        var row = lockBooking(id);
        var attendee = activeAttendee(actor, id);
        available(row);
        requireNotStarted(row);
        if ("DECLINED".equals(attendee.get("reply")) && decision.equals("ACCEPTED")
                && !Boolean.TRUE.equals(row.get("urgent"))) {
            checkPeople(id, Set.of(actor.userId()), time(row, "starts_at"), time(row, "ends_at"));
        }
        if (body.containsKey("materials")) {
            if (!decision.equals("ACCEPTED")) throw ApiFault.invalid("请通过提交并参加保存会前材料");
            var kinds = new HashSet<String>();
            for (var material : Inputs.objects(body.get("materials"), 4)) {
                String kind = materialKind(material.get("kind"));
                if (!kinds.add(kind)) throw ApiFault.invalid("同一材料类型只能提交一次");
                saveStructuredMaterial(actor, id, request, material, kind, row, attendee);
            }
        }
        db.update("UPDATE portal.meeting_attendee SET reply=?,decline_reason=?,replied_at=CURRENT_TIMESTAMP WHERE id=?",
                decision, reason, attendee.get("id"));
        event(actor, request, id, "MEETING_REPLY");
    }

    /** 受邀员工确认自己看到的共享资料版本，材料变化后须重新确认。 */
    @Transactional
    public void confirmReading(Actor actor, UUID id, UUID request, String revision) {
        identity.lockActor(actor);
        var meeting = lockBooking(id);
        var attendee = activeAttendee(actor, id);
        available(meeting);
        requireNotStarted(meeting);
        if (!Boolean.TRUE.equals(meeting.get("share_all")) || "DECLINED".equals(attendee.get("reply")))
            throw ApiFault.denied();
        if (!revision.equals(readingRevision(readingMaterials(id), actor.userId())))
            throw ApiFault.conflict("会前资料已变化，请刷新后再确认阅读");
        if (revision.equals(attendee.get("read_material_revision"))) return;
        db.update("""
                UPDATE portal.meeting_attendee SET read_material_revision=?,read_material_at=CURRENT_TIMESTAMP WHERE id=?
                """, revision, attendee.get("id"));
        event(actor, request, id, "MEETING_MATERIALS_READ");
    }

    /** 每个作者的材料版本依据内容和提交时间，排除已移出会议的员工。 */
    List<Map<String, Object>> readingMaterials(UUID meeting) {
        return db.rows("""
                SELECT a.user_id, md5(jsonb_build_array(a.report, NULL::text, a.material_at,
                    jsonb_agg(jsonb_build_array(m.kind,m.submitted_at,m.payload) ORDER BY m.kind))::text) AS revision
                FROM portal.meeting_attendee a LEFT JOIN portal.meeting_material m ON m.attendee_id=a.id AND m.kind<>'TOPICS'
                WHERE a.meeting_id=? AND a.removed_at IS NULL AND (m.kind IS NOT NULL OR a.report IS NOT NULL)
                GROUP BY a.id ORDER BY a.user_id
                """, meeting);
    }

    /** 本人材料改动不会使本人的阅读确认失效。 */
    static String readingRevision(List<Map<String, Object>> materials, UUID viewer) {
        var others = materials.stream().filter(row -> !viewer.equals(row.get("user_id"))).toList();
        return others.isEmpty() ? null : Crypto.hash(Crypto.json(others));
    }

    /** 只接受本人提交，既有材料保留到会议结束及之后。 */
    @Transactional
    public void materials(Actor actor, UUID id, UUID request, Map<String, Object> body) {
        if (body.containsKey("kind")) {
            String kind = materialKind(body.get("kind"));
            identity.lockActor(actor);
            var meeting = lockBooking(id);
            var attendee = activeAttendee(actor, id);
            available(meeting);
            requireNotStarted(meeting);
            if (!eligibleForMaterials(meeting, attendee)) throw ApiFault.denied();
            saveStructuredMaterial(actor, id, request, body, kind, meeting, attendee);
            return;
        }
        Inputs.only(body, "report", "topics");
        String report = optional(body, "report", 4000);
        String topics = optional(body, "topics", 4000);
        if (report == null && topics == null) throw ApiFault.invalid("请至少填写汇报或会议议题");
        identity.lockActor(actor);
        var row = lockBooking(id);
        var attendee = activeAttendee(actor, id);
        available(row);
        requireNotStarted(row);
        if (row.get("material_types") != null || !eligibleForMaterials(row, attendee)) throw ApiFault.denied();
        db.update("UPDATE portal.meeting_attendee SET report=?,topics=?,material_at=CURRENT_TIMESTAMP WHERE id=?",
                report, topics, attendee.get("id"));
        event(actor, request, id, "MEETING_MATERIALS");
    }

    /** 预约、参会回复和独立材料提交共用相同的材料类型。 */
    private static String materialKind(Object value) {
        return Inputs.choice(value, "REPORT", "WEEKLY_PLAN", "TOPICS", "COORDINATION");
    }

    /** 新材料按类型保存，填写人和所属部门从员工档案取值。 */
    private void saveStructuredMaterial(Actor actor, UUID id, UUID request, Map<String, Object> body,
            String kind, Map<String, Object> meeting, Map<String, Object> attendee) {
        var payload = structuredPayload(body, kind, meeting, attendee);
        storeMaterial((UUID) attendee.get("id"), kind, payload);
        event(actor, request, id, "MEETING_MATERIALS");
    }

    /** 线上填报和代导入共用字段规则，材料作者取被填报的受邀员工。 */
    Map<String, Object> structuredPayload(Map<String, Object> body, String kind,
            Map<String, Object> meeting, Map<String, Object> attendee) {
        Inputs.only(body, "kind", "html", "text", "rows");
        if (!(meeting.get("material_types") instanceof List<?> types) || !types.contains(kind))
            throw ApiFault.denied();
        Map<String,Object> payload;
        if (kind.equals("REPORT") || kind.equals("WEEKLY_PLAN")) {
            payload = Sql.map("text", Inputs.text(body,"text",8000));
            if(body.containsKey("html"))payload.put("html",Inputs.text(body,"html",12000));
        } else {
            var rows = Inputs.objects(body.get("rows"),30);
            if (rows.isEmpty()) throw ApiFault.invalid("请至少填写一条");
            payload = Sql.map("rows", rows.stream().map(row -> structuredRow(kind, row)).toList());
        }
        return addMaterialAuthor(payload, attendee);
    }

    /** 校验一行议题或协调事项，代导入可准确标出发生错误的 Excel 行。 */
    Map<String, Object> structuredRow(String kind, Map<String, Object> row) {
        Inputs.only(row, "subject", "expected", "departmentId", "personId", "category", "support");
        String subject = optional(row, "subject", 500);
        String expected = optional(row, "expected", 1000);
        if (subject == null || expected == null) throw ApiFault.invalid("请填写事项和期望结果");
        var entry = Sql.map("subject", subject, "expected", expected);
        if (kind.equals("COORDINATION")) {
            UUID department = Inputs.nullableUuid(row.get("departmentId"));
            // 旧版表单没有事项分类，原有协调事项按部门配合归类。
            String category = Inputs.choice(row.getOrDefault("category", "DEPARTMENT"), "COMPANY", "DEPARTMENT");
            entry.put("category", category);
            String support = optional(row, "support", 1000);
            if (support != null) entry.put("support", support);
            if (department == null) throw ApiFault.invalid("请选择配合部门");
            String partner = coordinationDepartmentName(department);
            UUID assignee = Inputs.nullableUuid(row.get("personId"));
            if (assignee == null) throw ApiFault.invalid("请选择配合部门的一名责任人");
            entry.put("department", partner);
            entry.put("departmentId", department);
            entry.put("assigneeId", assignee);
            entry.put("assigneeName", coordinationAssignee(assignee, department).get("name"));
        }
        return entry;
    }

    /** 会前填报与会中改派都要求新选部门处于启用状态。 */
    private String coordinationDepartmentName(UUID department) {
        var partner = db.one("SELECT name FROM iam.department WHERE id=? AND status='ACTIVE'", department);
        if (partner == null) throw ApiFault.invalid("请选择有效的配合部门");
        return (String) partner.get("name");
    }

    /** 线上填报、Excel 导入与发起人修改共用配合部门的在职员工范围。 */
    private Map<String, Object> coordinationAssignee(UUID assignee, UUID department) {
        UUID scope = coordinationDepartmentScope(department);
        var person = db.one("SELECT name FROM iam.user_account WHERE id=? AND " + ACTIVE_EMPLOYEE
                + " AND (?::uuid IS NULL OR department_id=?)", assignee, scope, scope);
        if (person == null) throw ApiFault.invalid(scope == null ? "责任人须为公司的有效员工" : "责任人须为配合部门的有效员工");
        return person;
    }

    /** 公司根节点代表全公司，普通部门只包含本部门。空值兼容旧协调事项。 */
    private UUID coordinationDepartmentScope(UUID department) {
        if (department != null && db.one("SELECT id FROM iam.department WHERE id=? AND code='ROOT'", department) != null)
            return null;
        return department;
    }

    /** 作者取所归属的受邀员工，代填后仍显示外地填报人的姓名和部门。 */
    Map<String, Object> addMaterialAuthor(Map<String, Object> payload, Map<String, Object> attendee) {
        var author = db.required("""
                SELECT u.name,d.name AS department FROM iam.user_account u
                JOIN iam.department d ON d.id=u.department_id WHERE u.id=?
                """, attendee.get("user_id"));
        payload.put("author", author);
        return payload;
    }

    /** 保存一位员工的一类材料；重复导入相同内容时保留原提交时间。 */
    void storeMaterial(UUID attendeeId, String kind, Map<String, Object> payload) {
        db.update("""
                INSERT INTO portal.meeting_material(attendee_id,kind,payload) VALUES (?,?,?::jsonb)
                ON CONFLICT(attendee_id,kind) DO UPDATE SET payload=EXCLUDED.payload,submitted_at=CURRENT_TIMESTAMP
                WHERE portal.meeting_material.payload IS DISTINCT FROM EXCLUDED.payload
                """, attendeeId,kind,Crypto.json(payload));
    }

    /** 管理员可查询配置，包括停用地点。 */
    public List<Map<String, Object>> rooms(Actor actor) {
        requireRoomAdmin(actor);
        return db.rows("SELECT id,name,enabled FROM portal.meeting_room ORDER BY name,id");
    }

    /** 地点更新和预约共用时段写锁，历史会议继续引用原地点。 */
    @Transactional
    public UUID saveRoom(Actor actor, UUID id, UUID request, Map<String, Object> body) {
        Inputs.only(body, "name", "enabled");
        requireRoomAdmin(actor);
        String name = Inputs.text(body, "name", 100);
        boolean enabled = flag(body, "enabled", true);
        reservationLock();
        identity.lockActor(actor);
        if (id == null) {
            id = UUID.randomUUID();
            db.update("INSERT INTO portal.meeting_room(id,name,enabled) VALUES (?,?,?)", id, name, enabled);
        } else if (db.update("UPDATE portal.meeting_room SET name=?,enabled=? WHERE id=?", name, enabled, id) != 1) {
            throw ApiFault.hidden();
        }
        audit.event(actor.userId(), "PLATFORM", "MEETING_ROOM", id, "MEETING_ROOM_SAVE", "SUCCEEDED", request);
        return id;
    }

    /** 同一业务事务以数据库时钟判断截止，锁等待期间也按真实时间推进。 */
    Instant now() {
        return time(db.required("SELECT clock_timestamp() AS now"), "now");
    }

    Map<String, Object> owned(Actor actor, UUID id) {
        var row = db.required("SELECT *,minutes::text AS minutes_json FROM portal.meeting_booking WHERE id=? AND organizer_id=? FOR NO KEY UPDATE",
                id, actor.userId());
        available(row);
        return row;
    }

    List<Map<String, Object>> attendees(UUID id) {
        return db.rows("""
                SELECT a.*,u.name,u.employee_no FROM portal.meeting_attendee a
                JOIN iam.user_account u ON u.id=a.user_id
                WHERE a.meeting_id=? AND a.removed_at IS NULL ORDER BY u.name,u.id
                """, id);
    }

    /** 只按工号匹配代填人员及责任人，姓名用于复核。 */
    Map<String, Object> workbookEmployee(String employeeNo) {
        return db.one("SELECT u.id AS user_id,u.name,u.employee_no,d.name AS department FROM iam.user_account u"
                + " JOIN iam.department d ON d.id=u.department_id AND d.status='ACTIVE'"
                + " WHERE upper(u.employee_no)=upper(?) AND u.status='ACTIVE' AND NOT u.it_shared_account", employeeNo);
    }

    /** 确认导入与预约改期使用相同锁顺序。预览阶段不新增邀请。 */
    void lockWorkbookImport(Actor actor) {
        reservationLock();
        identity.lockActor(actor);
    }

    /** Excel 新增受邀人员沿用会前邀请校验和通知，出席回复仍由员工办理。 */
    UUID inviteWorkbookEmployee(Actor actor, UUID id, Map<String, Object> meeting, UUID user) {
        validatePeople(actor, Set.of(user));
        if (db.count("SELECT count(*) FROM portal.meeting_attendee WHERE meeting_id=? AND removed_at IS NULL", id) >= 500)
            throw ApiFault.invalid("本场会议的受邀员工已达 500 人");
        if (!Boolean.TRUE.equals(meeting.get("urgent")))
            checkPeople(id, Set.of(user), time(meeting, "starts_at"), time(meeting, "ends_at"));
        invite(id, user, true);
        db.update("UPDATE portal.meeting_booking SET row_version=row_version+1 WHERE id=?", id);
        notify(id, null, user, "MEETING_INVITED", "会议邀请 " + meeting.get("subject"));
        return (UUID) db.required("SELECT id FROM portal.meeting_attendee WHERE meeting_id=? AND user_id=?", id, user).get("id");
    }

    /** 纪要只共享议题与协调事项，汇报仍通过纪要正文发布。 */
    List<Map<String, Object>> minuteTables(UUID meeting) {
        return db.rows("""
                SELECT x.attendee_id,x.kind,x.payload::text AS payload,x.submitted_at
                FROM portal.meeting_material x JOIN portal.meeting_attendee a ON a.id=x.attendee_id
                JOIN iam.user_account u ON u.id=a.user_id
                WHERE a.meeting_id=? AND a.removed_at IS NULL AND x.kind IN ('TOPICS','COORDINATION')
                ORDER BY u.name,u.id,x.kind
                """, meeting).stream().map(row -> Sql.map("kind",row.get("kind"),
                "data",Crypto.JSON.readTree(row.get("payload").toString()),"submittedAt",row.get("submitted_at"),
                "attendeeId",row.get("attendee_id"))).toList();
    }

    /** 可编辑材料底稿按员工顺序汇总，在已保存草稿存在时使用草稿。 */
    Map<String, Object> minutes(Map<String, Object> row, List<Map<String, Object>> attendees, boolean full, UUID viewer) {
        UUID meeting = (UUID) row.get("id");
        if (row.get("minutes_json") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> saved = Crypto.JSON.readValue((String) row.get("minutes_json"), Map.class);
            var shared = Crypto.JSON.valueToTree(saved.get("sharedSummaries"));
            boolean legacy = row.get("share_all") == null;
            boolean reportsShared = legacy && shared.path("REPORT").asBoolean();
            var result = Sql.map("summary", saved.get("summary"), "decisions", saved.get("decisions"),
                    "publishedAt", row.get("minutes_published_at"), "reportsShared", reportsShared);
            // 旧纪要没有汇报开关，保留已发布的摘要；新纪要的汇报正文和摘要统一受控。
            if (full || (legacy && (reportsShared || !shared.has("REPORT")))) result.put("materialsText", saved.get("materialsText"));
            var types = summaryTypes(row);
            if (!full) {
                if (!legacy) types = List.of();
                else if (saved.containsKey("sharedSummaries"))
                    types = types.stream().filter(kind -> shared.path(kind).asBoolean()).toList();
            }
            var visibleTypes = types;
            result.put("summaryTypes", visibleTypes);
            var tables = Crypto.JSON.valueToTree(saved.containsKey("materialTables") ? saved.get("materialTables") : minuteTables(meeting));
            var visibleTables = new ArrayList<>();
            tables.forEach(table -> {
                if (visibleTypes.contains(table.path("kind").asText())) visibleTables.add(table);
            });
            result.put("materialTables", visibleTables);
            var noteReaders = Crypto.JSON.valueToTree(saved.get("noteReaderIds"));
            boolean mayReadNotes = full;
            for (var reader : noteReaders) {
                if (legacy && viewer.toString().equals(reader.asText())) mayReadNotes = true;
            }
            if (mayReadNotes) result.put("notes", saved.get("notes"));
            if (full && legacy) {
                result.put("noteReaderIds", saved.getOrDefault("noteReaderIds", List.of()));
                result.put("sharedSummaries", saved.get("sharedSummaries"));
            }
            if (full && row.get("minutes_published_at") == null) {
                result.put("items", db.rows("""
                        SELECT x.content,x."assigneeId" AS assignee_id,x."dueAt" AS due_at,u.name AS assignee_name
                        FROM jsonb_to_recordset(?::jsonb->'items') AS x(content text,"assigneeId" uuid,"dueAt" text)
                        JOIN iam.user_account u ON u.id=x."assigneeId"
                        """, row.get("minutes_json")).stream()
                        .map(item -> Sql.view(item, "content,assignee_id,due_at,assignee_name")).toList());
            }
            return result;
        }
        if (!"AWAITING_MINUTES".equals(row.get("state"))) return null;
        var sections = new ArrayList<String>();
        for (var person : attendees) {
            var materials = db.rows("""
                    SELECT kind,payload->>'text' AS text FROM portal.meeting_material
                    WHERE attendee_id=? AND kind IN ('REPORT','WEEKLY_PLAN') ORDER BY kind
                    """, person.get("id"));
            if (person.get("report") == null && materials.isEmpty()) continue;
            var lines = new ArrayList<String>();
            lines.add(person.get("name") + "（" + person.get("employee_no") + "）");
            if (person.get("report") != null) lines.add("汇报 " + FormattedText.preview((String) person.get("report")));
            for (var material : materials)
                lines.add(("REPORT".equals(material.get("kind")) ? "工作汇报 " : "本周计划 ") + material.get("text"));
            sections.add(String.join("\n", lines));
        }
        return Sql.map("materialsText", String.join("\n\n", sections), "decisions", row.get("live_decisions"), "notes", row.get("live_notes"),
                "publishedAt", null, "items", List.of(), "summaryTypes", summaryTypes(row), "materialTables", minuteTables(meeting));
    }

    private static List<String> summaryTypes(Map<String, Object> row) {
        return row.get("material_types") instanceof List<?> types
                ? types.stream().map(Object::toString).filter(kind -> Set.of("TOPICS","COORDINATION").contains(kind)).toList() : List.of();
    }

    boolean allItemsConfirmed(UUID meeting) {
        return db.count("SELECT count(*) FROM portal.meeting_item WHERE meeting_id=? AND state<>'CONFIRMED'", meeting) == 0;
    }

    boolean generalManager(UUID user) {
        return db.count("""
                SELECT count(*) FROM iam.company_duty
                WHERE user_id=? AND duty_code='GENERAL_MANAGER' AND valid_from<=CURRENT_TIMESTAMP
                  AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP)
                """, user) > 0;
    }

    void employee(UUID user) {
        if (!isEmployee(user)) throw ApiFault.denied();
    }

    void event(Actor actor, UUID request, UUID id, String action) {
        audit.event(actor.userId(), "PLATFORM", "MEETING", id, action, "SUCCEEDED", request);
    }

    void notify(UUID meeting, UUID item, UUID user, String kind, String text) {
        db.update("""
                INSERT INTO portal.meeting_message(id,user_id,meeting_id,item_id,kind,content)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), user, meeting, item, kind, text);
    }

    void notifyAttendees(UUID meeting, String kind, String text) {
        db.update("""
                INSERT INTO portal.meeting_message(id,user_id,meeting_id,kind,content)
                SELECT gen_random_uuid(),user_id,meeting_id,?,? FROM portal.meeting_attendee
                WHERE meeting_id=? AND removed_at IS NULL
                """, kind, text, meeting);
    }

    static Instant time(Map<String, Object> row, String field) {
        return Instant.parse((String) row.get(field));
    }

    static Instant inputTime(Object value) {
        if (!(value instanceof String text)) throw ApiFault.invalid("请填写日期和时间");
        try {
            return Instant.parse(text).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw ApiFault.invalid("日期时间需包含时区");
        }
    }

    static String optional(Map<String, ?> body, String field, int max) {
        Object raw = body.get(field);
        if (raw == null) return null;
        if (!(raw instanceof String text) || text.length() > max) throw ApiFault.invalid(field + " 超过长度限制或格式不正确");
        return text.isBlank() ? null : text.strip();
    }

    static boolean flag(Map<String, Object> body, String name, boolean fallback) {
        if (!body.containsKey(name)) return fallback;
        if (body.get(name) instanceof Boolean value) return value;
        throw ApiFault.invalid(name + " 必须为布尔值");
    }

    static void checkVersion(Map<String, Object> row, int version) {
        if (version != ((Number) row.get("row_version")).intValue()) throw ApiFault.conflict("会议已变更，请刷新后再提交");
    }

    private record Schedule(Instant starts, Instant ends, UUID room, Set<UUID> attendees, Set<UUID> materialUsers) {}

    private static Schedule schedule(Map<String, Object> body) {
        Instant starts = inputTime(body.get("startsAt")), ends = inputTime(body.get("endsAt"));
        if (!ends.isAfter(starts)) throw ApiFault.invalid("会议结束时间须晚于开始时间");
        Set<UUID> attendees = users(body.get("attendeeIds"));
        if (attendees.isEmpty()) throw ApiFault.invalid("请至少选择一位受邀员工");
        if (!body.containsKey("roomId")) throw ApiFault.invalid("请选择会议室或其他地点");
        return new Schedule(starts, ends, Inputs.nullableUuid(body.get("roomId")), attendees,
                users(body.getOrDefault("materialUserIds", List.of())));
    }

    static Set<UUID> users(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 500) throw ApiFault.invalid("请选择有效的员工名单");
        Set<UUID> ids = new LinkedHashSet<>();
        for (Object user : list) if (!ids.add(Inputs.uuid(user))) throw ApiFault.invalid("员工名单有重复");
        return ids;
    }

    private boolean isEmployee(UUID user) {
        return db.count("SELECT count(*) FROM iam.user_account WHERE id=? AND " + ACTIVE_EMPLOYEE, user) == 1;
    }

    private void validatePeople(Actor actor, Set<UUID> people) {
        if (people.contains(actor.userId())) throw ApiFault.invalid("发起人已计入时段占用，请从邀请名单移除本人");
        if (db.count("SELECT count(*) FROM iam.user_account WHERE id=ANY(?::uuid[]) AND " + ACTIVE_EMPLOYEE,
                uuidArray(people)) != people.size()) throw ApiFault.invalid("请重新选择仍在职的员工");
    }

    private Set<UUID> materialUsers(Schedule schedule, boolean weekly, boolean collect) {
        if (weekly) {
            var rows = db.rows("""
                    SELECT DISTINCT m.user_id FROM iam.department_manager m
                    JOIN iam.user_account u ON u.id=m.user_id AND u.department_id=m.department_id
                    JOIN iam.department d ON d.id=m.department_id AND d.status='ACTIVE'
                    WHERE m.user_id=ANY(?::uuid[]) AND m.duty_type='DEPARTMENT_MANAGER' AND m.valid_from<=CURRENT_TIMESTAMP
                      AND (m.valid_until IS NULL OR m.valid_until>CURRENT_TIMESTAMP)
                    """, uuidArray(schedule.attendees()));
            Set<UUID> managers = new HashSet<>();
            for (var row : rows) managers.add((UUID) row.get("user_id"));
            return managers;
        }
        if (!schedule.attendees().containsAll(schedule.materialUsers())
                || (!collect && !schedule.materialUsers().isEmpty())) throw ApiFault.invalid("会前征集人员须来自受邀名单");
        return schedule.materialUsers();
    }

    private void invite(UUID meeting, UUID user, boolean materialRequired) {
        db.update("""
                INSERT INTO portal.meeting_attendee(id,meeting_id,user_id,material_required) VALUES (?,?,?,?)
                ON CONFLICT (meeting_id,user_id) DO UPDATE SET removed_at=NULL,invited_at=CURRENT_TIMESTAMP,
                    material_required=EXCLUDED.material_required
                """, UUID.randomUUID(), meeting, user, materialRequired);
    }

    private void reservationLock() {
        // 唯一的预约写锁封闭同一会议室或员工被两个请求同时预约的检查窗口。
        db.rows("SELECT pg_advisory_xact_lock(61427063)");
    }

    /** 临时地点写在说明中，固定会议室仍以配置编号判断占用。 */
    private String locationDescription(UUID room, String description) {
        if (room == null && description == null) throw ApiFault.invalid("请在会议说明中填写具体地点");
        return description;
    }

    private void requireRoom(UUID room) {
        if (db.count("SELECT count(*) FROM portal.meeting_room WHERE id=? AND enabled", room) != 1)
            throw ApiFault.invalid("请选择启用的会议室");
    }

    private void requireRoomAdmin(Actor actor) {
        if (!actor.systemAdministrator()) throw ApiFault.denied();
    }

    private Map<String, Object> lockBooking(UUID id) {
        return db.required("SELECT * FROM portal.meeting_booking WHERE id=? FOR NO KEY UPDATE", id);
    }

    private Map<String, Object> activeAttendee(Actor actor, UUID id) {
        return db.required("SELECT * FROM portal.meeting_attendee WHERE meeting_id=? AND user_id=? AND removed_at IS NULL",
                id, actor.userId());
    }

    static boolean eligibleForMaterials(Map<String, Object> meeting, Map<String, Object> attendee) {
        if (meeting.get("material_types") instanceof List<?> types)
            return !types.isEmpty() && !"DECLINED".equals(attendee.get("reply"));
        return Boolean.TRUE.equals(attendee.get("material_required"))
                && ("WEEKLY".equals(meeting.get("kind")) || !"DECLINED".equals(attendee.get("reply")));
    }

    private static void available(Map<String, Object> row) {
        if (row.get("cancelled_at") != null) throw ApiFault.conflict("会议已取消");
    }

    static void requireNotStarted(Map<String, Object> row) {
        if (row.get("actual_started_at") != null) throw ApiFault.conflict("会议已开始，当前操作已截止");
    }

    private void requireFutureStart(Instant starts) {
        if (!now().isBefore(starts)) throw ApiFault.conflict("开始时间已过，请重新选择");
    }

    private void checkConflicts(UUID exclude, UUID organizer, UUID room, Instant starts, Instant ends,
                                Set<UUID> attendees, boolean urgent) {
        var occupied = room == null ? null : db.one("""
                SELECT r.name,LEAST(m.starts_at,m.actual_started_at) AS starts_at,
                    COALESCE(m.actual_ended_at,m.ends_at) AS ends_at FROM portal.meeting_booking m
                JOIN portal.meeting_room r ON r.id=m.room_id
                WHERE m.id IS DISTINCT FROM ? AND m.cancelled_at IS NULL AND m.room_id=?
                    AND LEAST(m.starts_at,m.actual_started_at)<? AND COALESCE(m.actual_ended_at,m.ends_at)>?
                ORDER BY starts_at,m.id LIMIT 1
                """, exclude, room, Timestamp.from(ends), Timestamp.from(starts));
        if (occupied != null) throw new ApiFault(409, "MEETING_ROOM_CONFLICT",
                "会议室 " + occupied.get("name") + " 已被占用，重叠时段 " + overlap(occupied, starts, ends));
        if (!urgent) {
            Set<UUID> people = new LinkedHashSet<>(attendees);
            people.add(organizer);
            checkPeople(exclude, people, starts, ends);
        }
    }

    private void checkPeople(UUID exclude, Set<UUID> people, Instant starts, Instant ends) {
        var conflicts = db.rows("""
                SELECT DISTINCT u.name,u.employee_no,LEAST(m.starts_at,m.actual_started_at) AS starts_at,
                    COALESCE(m.actual_ended_at,m.ends_at) AS ends_at
                FROM portal.meeting_booking m
                JOIN iam.user_account u ON u.id=ANY(?::uuid[]) AND (u.id=m.organizer_id OR EXISTS (
                    SELECT 1 FROM portal.meeting_attendee a WHERE a.meeting_id=m.id AND a.user_id=u.id
                        AND a.removed_at IS NULL AND a.reply<>'DECLINED'))
                WHERE m.id IS DISTINCT FROM ? AND m.cancelled_at IS NULL AND LEAST(m.starts_at,m.actual_started_at)<? AND COALESCE(m.actual_ended_at,m.ends_at)>?
                ORDER BY u.name,u.employee_no,starts_at,ends_at
                """, uuidArray(people), exclude, Timestamp.from(ends), Timestamp.from(starts));
        if (!conflicts.isEmpty()) throw new ApiFault(409, "MEETING_PERSON_CONFLICT",
                "人员时间冲突\n" + String.join("\n", conflicts.stream().map(row -> row.get("name") + "（"
                        + row.get("employee_no") + "） " + overlap(row, starts, ends)).toList()));
    }

    private static String uuidArray(Collection<UUID> people) {
        return "{" + String.join(",", people.stream().map(UUID::toString).toList()) + "}";
    }

    private static String overlap(Map<String, Object> row, Instant starts, Instant ends) {
        Instant existingStarts = time(row, "starts_at"), existingEnds = time(row, "ends_at");
        return CONFLICT_TIME.format(starts.isAfter(existingStarts) ? starts : existingStarts) + " 至 "
                + CONFLICT_TIME.format(ends.isBefore(existingEnds) ? ends : existingEnds);
    }
}

