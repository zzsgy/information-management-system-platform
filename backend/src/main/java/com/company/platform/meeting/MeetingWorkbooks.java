package com.company.platform.meeting;

import com.company.platform.*;
import com.company.platform.intake.ImportWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 会议工作簿按本场勾选项生成，代填沿用会前材料的业务校验和存储。 */
@Service
public class MeetingWorkbooks {
    /** 导出截止时间与会议页面使用同一时区。 */
    private static final DateTimeFormatter EXPORT_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    /** 行分组与会前材料的一位员工、一类记录对应。 */
    private record Group(Map<String, Object> attendee, String kind, List<Integer> lines,
                         List<Map<String, Object>> entries) {
        String key() { return attendee.get("id") + ":" + kind; }
    }

    /** 已通过材料校验的预检组，保留原行号和文件变更预览。 */
    private record Prepared(Group group, Map<String, Object> payload, Map<String, Object> preview) {}

    private final MeetingBookings bookings;
    private final Sql db;

    public MeetingWorkbooks(MeetingBookings bookings, Sql db) {
        this.bookings = bookings;
        this.db = db;
    }

    /** 仅发起人可下载；填报模板写入会议标识，不能用于其他会议。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public byte[] download(Actor actor, UUID id, boolean template) {
        var meeting = db.required("SELECT * FROM portal.meeting_booking WHERE id=? AND organizer_id=?", id, actor.userId());
        var columns = columns(meeting);
        if (template) {
            MeetingBookings.requireNotStarted(meeting);
            if (meeting.get("cancelled_at") != null || meeting.get("material_types") == null || columns.size() == 1)
                throw ApiFault.invalid("本场会议当前未征集可导入的材料");
            return ImportWorkbook.meetingWorkbook("会议填报", marker(id), columns, List.of());
        }
        columns.addAll(1, List.of("name|填报人", "departmentName|填报部门"));
        if (types(meeting).contains("COORDINATION")) {
            columns.addAll(List.of("conclusion|会议结论", "assignee|唯一责任人", "dueAt|截止时间"));
        }
        columns.add("meetingDecision|会议决议");
        return ImportWorkbook.meetingWorkbook("会议汇总", Objects.toString(meeting.get("subject")), columns,
                exportRows(id, meeting));
    }

    /** 会前预检并按当前冲突选择导入，预检和提交之间的材料变更须重新预检。 */
    @Transactional
    public Map<String, Object> importMaterials(Actor actor, UUID id, UUID request, Map<String, Object> body) {
        Inputs.only(body, "file", "preview", "overwrite");
        String file = Inputs.text(body, "file", 5_592_408);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(file);
        } catch (IllegalArgumentException invalid) {
            throw ApiFault.invalid("会议 Excel 文件编码无效");
        }
        var meeting = bookings.owned(actor, id);
        MeetingBookings.requireNotStarted(meeting);
        var columns = columns(meeting);
        if (meeting.get("material_types") == null || columns.size() == 1)
            throw ApiFault.invalid("本场会议未开启可导入的材料征集");
        var rows = ImportWorkbook.readMeeting(bytes, marker(id), columns);
        var invitees = new HashMap<String, Map<String, Object>>();
        for (var attendee : bookings.attendees(id)) {
            if (MeetingBookings.eligibleForMaterials(meeting, attendee))
                invitees.put(Objects.toString(attendee.get("employee_no")), attendee);
        }
        var errors = new ArrayList<String>();
        var groups = new LinkedHashMap<String, Group>();
        for (var row : rows) {
            try {
                collectRow(row, invitees, groups);
            } catch (ApiFault fault) {
                errors.add("第 " + row.number() + " 行：" + fault.getMessage());
            }
        }
        var existing = new HashMap<String, Object>();
        for (var material : meetingMaterials(id))
            existing.put(material.get("attendee_id") + ":" + material.get("kind"), material.get("payload"));
        var prepared = new ArrayList<Prepared>();
        var snapshot = new LinkedHashMap<String, Object>();
        for (var group : groups.values()) {
            try {
                var payload = preparedPayload(group, meeting);
                Object current = existing.get(group.key());
                snapshot.put(group.key(), current);
                prepared.add(new Prepared(group, payload, Sql.map(
                        "key", group.key(), "rows", group.lines(), "kind", group.kind(),
                        "employeeNo", group.attendee().get("employee_no"), "name", group.attendee().get("name"),
                        "conflict", current != null, "content", group.entries())));
            } catch (ApiFault fault) {
                errors.add("第 " + group.lines() + " 行：" + fault.getMessage());
            }
        }
        String preview = Crypto.hash(Crypto.hash(file) + Crypto.json(snapshot));
        if (!body.containsKey("preview")) {
            return Sql.map("preview", preview, "errors", errors, "materials", prepared.stream().map(Prepared::preview).toList());
        }
        if (!preview.equals(Inputs.text(body, "preview", 64))) throw ApiFault.conflict("填报内容已变化，请重新预览");
        if (!errors.isEmpty() || prepared.isEmpty()) throw ApiFault.invalid("请修正文件中的问题后重新预览");
        if (!(body.getOrDefault("overwrite", List.of()) instanceof List<?> overwrite)
                || !snapshot.keySet().containsAll(overwrite))
            throw ApiFault.invalid("覆盖选项须对应预览中的材料");
        int imported = 0;
        for (var entry : prepared) {
            var group = entry.group();
            if (existing.containsKey(group.key()) && !overwrite.contains(group.key())) continue;
            bookings.storeMaterial((UUID) group.attendee().get("id"), group.kind(), entry.payload());
            imported++;
        }
        if (imported > 0) bookings.event(actor, request, id, "MEETING_MATERIALS_IMPORT");
        return Sql.map("imported", imported, "skipped", prepared.size() - imported);
    }

    /** 将同一工号的多行议题按原顺序汇总，正文每人每栏只允许填一处。 */
    private void collectRow(ImportWorkbook.Row row, Map<String, Map<String, Object>> invitees, Map<String, Group> groups) {
        var values = row.values();
        var attendee = invitees.get(values.get("employeeNo"));
        if (attendee == null) throw ApiFault.invalid("工号须对应本次参加或尚未回复的受邀员工");
        if (values.size() == 1) throw ApiFault.invalid("请填写至少一项材料");
        if (values.containsKey("report")) addGroup(groups, attendee, "REPORT", row.number(), Sql.map("text", values.get("report")));
        if (values.containsKey("plan")) addGroup(groups, attendee, "WEEKLY_PLAN", row.number(), Sql.map("text", values.get("plan")));
        if (values.containsKey("topic") || values.containsKey("topicExpected")) {
            var topic = bookings.structuredRow("TOPICS", Sql.map("subject", values.get("topic"), "expected", values.get("topicExpected")));
            addGroup(groups, attendee, "TOPICS", row.number(), topic);
        }
        if (List.of("category", "coord", "support", "expected", "department").stream().anyMatch(values::containsKey)) {
            String category = switch (Objects.toString(values.get("category"), "")) {
                case "需公司协调" -> "COMPANY";
                case "需其他部门配合" -> "DEPARTMENT";
                default -> throw ApiFault.invalid("事项类别请填写「需公司协调」或「需其他部门配合」");
            };
            UUID department = null;
            if (values.containsKey("department")) {
                Object value = values.get("department");
                var matches = db.rows("SELECT id FROM iam.department WHERE status='ACTIVE' AND (code=? OR name=?)", value, value);
                if (matches.size() != 1) throw ApiFault.invalid("找不到唯一的配合部门，请填写部门编码");
                department = (UUID) matches.getFirst().get("id");
            }
            var coordination = bookings.structuredRow("COORDINATION", Sql.map("category", category,
                    "subject", values.get("coord"), "support", values.get("support"), "expected", values.get("expected"),
                    "departmentId", department == null ? null : department.toString()));
            addGroup(groups, attendee, "COORDINATION", row.number(), coordination);
        }
    }

    /** 保存原表行号，便于定位同一作者的一组材料。 */
    private static void addGroup(Map<String, Group> groups, Map<String, Object> attendee, String kind, int line,
            Map<String, Object> value) {
        var group = groups.computeIfAbsent(attendee.get("id") + ":" + kind,
                key -> new Group(attendee, kind, new ArrayList<>(), new ArrayList<>()));
        group.lines().add(line);
        group.entries().add(value);
    }

    /** 把表格数据转换为线上保存入口使用的业务结构。 */
    private Map<String, Object> preparedPayload(Group group, Map<String, Object> meeting) {
        if (Set.of("REPORT", "WEEKLY_PLAN").contains(group.kind())) {
            if (group.entries().size() != 1) throw ApiFault.invalid("同一人同一栏目只需填写一次");
            return bookings.structuredPayload(group.entries().getFirst(), group.kind(), meeting, group.attendee());
        }
        if (group.entries().size() > 30) throw ApiFault.invalid("同一人同一类事项最多 30 条");
        return bookings.addMaterialAuthor(Sql.map("rows", group.entries()), group.attendee());
    }

    /** 汇总导出沿用提交时的姓名和部门，协调结论按原事项行关联。 */
    private List<Map<String, Object>> exportRows(UUID id, Map<String, Object> meeting) {
        var results = new HashMap<Object, Map<String, Object>>();
        for (var result : bookings.coordinationResults(meeting)) results.put(result.get("key"), result);
        var output = new ArrayList<Map<String, Object>>();
        for (var material : meetingMaterials(id)) {
            var payload = Crypto.JSON.readTree(material.get("payload").toString());
            var author = Sql.map("employeeNo", material.get("employee_no"),
                    "name", value(payload.path("author"), "name"), "departmentName", value(payload.path("author"), "department"));
            switch (material.get("kind").toString()) {
                case "REPORT", "WEEKLY_PLAN" -> {
                    author.put("REPORT".equals(material.get("kind")) ? "report" : "plan", value(payload, "text"));
                    output.add(author);
                }
                case "TOPICS", "COORDINATION" -> {
                    int index = 0;
                    for (var row : payload.path("rows")) {
                        var line = new LinkedHashMap<>(author);
                        if ("TOPICS".equals(material.get("kind"))) {
                            line.put("topic", value(row, "subject"));
                            line.put("topicExpected", value(row, "expected"));
                        } else {
                            line.put("category", "COMPANY".equals(value(row, "category")) ? "需公司协调" : "需其他部门配合");
                            line.put("coord", value(row, "subject"));
                            for (String field : List.of("support", "expected", "department")) line.put(field, value(row, field));
                            var result = results.get(material.get("attendee_id") + ":" + index);
                            if (result != null) {
                                line.put("conclusion", result.get("conclusion"));
                                line.put("assignee", result.get("assigneeName"));
                                if (result.get("dueAt") instanceof String dueAt)
                                    line.put("dueAt", EXPORT_TIME.format(Instant.parse(dueAt)));
                            }
                        }
                        output.add(line);
                        index++;
                    }
                }
                default -> throw new IllegalStateException("无法导出的会议材料类型");
            }
        }
        if (meeting.get("material_types") == null) {
            for (var attendee : bookings.attendees(id)) {
                if (attendee.get("report") == null && attendee.get("topics") == null) continue;
                output.add(Sql.map("employeeNo", attendee.get("employee_no"), "name", attendee.get("name"),
                        "report", FormattedText.preview(Objects.toString(attendee.get("report"), "")),
                        "topic", FormattedText.preview(Objects.toString(attendee.get("topics"), ""))));
            }
        }
        Object savedMinutes = meeting.get("minutes");
        String decision = savedMinutes == null
                ? Objects.toString(meeting.get("live_decisions"), "")
                : value(Crypto.JSON.readTree(savedMinutes.toString()), "decisions");
        if (!decision.isBlank()) output.add(Sql.map("meetingDecision", exportText(decision)));
        return output;
    }

    /** 将会议富文本封装还原为 Excel 文本，换行保留在同一单元格。 */
    private static String exportText(String content) {
        if (!content.startsWith("IMS-RICH-1\n")) return content;
        int markup = content.indexOf('\n', "IMS-RICH-1\n".length());
        if (markup < 0) return content;
        return HtmlUtils.htmlUnescape(content.substring(markup + 1)
                .replaceAll("(?i)<br\\s*/?>|</(?:p|div|li)>", "\n")
                .replaceAll("<[^>]*>", "")).strip();
    }

    /** 读取本场有效邀请下的结构化材料，排序保证导出顺序稳定。 */
    private List<Map<String, Object>> meetingMaterials(UUID id) {
        return db.rows("""
                SELECT m.attendee_id, m.kind, m.payload::text AS payload, u.employee_no
                FROM portal.meeting_material m JOIN portal.meeting_attendee a ON a.id=m.attendee_id
                JOIN iam.user_account u ON u.id=a.user_id
                WHERE a.meeting_id=? AND a.removed_at IS NULL ORDER BY u.employee_no,m.kind
                """, id);
    }

    /** 仅使用发起人开启的材料列，历史自由文本材料仍可汇总导出。 */
    private static List<String> columns(Map<String, Object> meeting) {
        var columns = new ArrayList<>(List.of("employeeNo|工号"));
        var types = types(meeting);
        if (types.contains("REPORT")) columns.add("report|上周工作内容");
        if (types.contains("WEEKLY_PLAN")) columns.add("plan|本周工作计划");
        if (types.contains("TOPICS")) columns.addAll(List.of("topic|会议议题", "topicExpected|议题期望结果"));
        if (types.contains("COORDINATION")) columns.addAll(List.of("category|事项类别（需公司协调／需其他部门配合）",
                "coord|协调或配合事项", "support|需要协调或配合的内容", "expected|期望结果或验收标准", "department|配合部门"));
        return columns;
    }

    private static List<?> types(Map<String, Object> meeting) {
        return meeting.get("material_types") instanceof List<?> types ? types : List.of("REPORT", "TOPICS");
    }

    /** 模板绑定本场会议，避免同字段的其他会议表格误导入。 */
    private static String marker(UUID id) { return "IMS-MEETING-1:" + id; }

    private static String value(JsonNode node, String field) { return node.path(field).asText(""); }
}
