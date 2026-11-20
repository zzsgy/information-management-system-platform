package com.company.platform.meeting;

import com.company.platform.*;
import com.company.platform.intake.ImportWorkbook;
import com.company.platform.intake.MeetingReportWorkbook;
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
    /** 一份填报表的一个可独立覆盖的材料栏目。 */
    private record Prepared(String kind, List<Integer> rows, Map<String, Object> payload,
                            List<Map<String, Object>> entries) {}

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
            return MeetingReportWorkbook.template(id);
        }
        columns.addAll(1, List.of("name|填报人", "departmentName|填报部门"));
        if (types(meeting).contains("COORDINATION")) {
            columns.addAll(List.of("conclusion|会议结论", "assignee|唯一责任人", "dueAt|截止时间"));
        }
        columns.add("meetingDecision|会议决议");
        return ImportWorkbook.meetingWorkbook("会议汇总", Objects.toString(meeting.get("subject")), columns,
                exportRows(id, meeting));
    }

    /** 按一位填报人归档。新增邀请与材料写入一起提交，预检只返回待确认变化。 */
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
        if (body.containsKey("preview")) bookings.lockWorkbookImport(actor);
        var meeting = bookings.owned(actor, id);
        MeetingBookings.requireNotStarted(meeting);
        if (Boolean.TRUE.equals(meeting.get("online_weekly_report")))
            throw ApiFault.conflict("本场使用在线周会表，请由各部门填写并提交");
        if (meeting.get("material_types") == null || columns(meeting).size() == 1)
            throw ApiFault.invalid("本场会议未开启可导入的材料征集");
        var report = MeetingReportWorkbook.read(bytes, id);
        var author = resolveEmployee(report.author(), "「协调事宜」F2 的填报人");
        var user = (UUID) author.get("user_id");
        if (user.equals(actor.userId())) throw ApiFault.invalid("会议发起人不能作为本场受邀员工导入");
        var invite = db.one("SELECT id,reply,removed_at FROM portal.meeting_attendee WHERE meeting_id=? AND user_id=?", id, user);
        boolean newInvitee = invite == null || invite.get("removed_at") != null;
        if (!newInvitee && "DECLINED".equals(invite.get("reply")))
            throw ApiFault.invalid("该员工已拒绝本场会议，请先确认其参会安排");

        var errors = new ArrayList<String>();
        var prepared = new ArrayList<Prepared>();
        prepareText(prepared, errors, meeting, author, "REPORT", report.work());
        prepareText(prepared, errors, meeting, author, "WEEKLY_PLAN", report.plan());
        var lines = new ArrayList<Integer>();
        var entries = new ArrayList<Map<String, Object>>();
        for (var row : report.coordination()) {
            try {
                var values = row.values();
                if (values.containsKey("author") && !user.equals(resolveEmployee(
                        Inputs.text(values, "author", 128), "填报人").get("user_id")))
                    throw ApiFault.invalid("同一份表只能填写一位员工");
                if (values.containsKey("authorDepartment") && !author.get("department").equals(values.get("authorDepartment")))
                    throw ApiFault.invalid("填报部门与员工档案不一致");
                if (values.keySet().stream().allMatch(Set.of("author", "authorDepartment")::contains)) continue;
                if (!types(meeting).contains("COORDINATION")) throw ApiFault.invalid("本场会议未征集协调事宜");
                entries.add(coordination(values));
                lines.add(row.number());
            } catch (ApiFault fault) {
                errors.add("「协调事宜」第 " + row.number() + " 行：" + fault.getMessage());
            }
        }
        if (entries.size() > 30) errors.add("「协调事宜」最多填写 30 条");
        else if (!entries.isEmpty()) prepared.add(new Prepared("COORDINATION", lines,
                bookings.addMaterialAuthor(Sql.map("rows", entries), author), entries));
        if (prepared.isEmpty() && errors.isEmpty()) errors.add("请至少填写一项本场会议征集的材料");

        var current = new HashMap<String, Object>();
        if (invite != null) {
            for (var row : db.rows("SELECT kind,payload::text AS payload FROM portal.meeting_material WHERE attendee_id=?",
                    invite.get("id"))) current.put((String) row.get("kind"), row.get("payload"));
        }
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("user", user);
        snapshot.put("invitation", invite);
        for (var group : prepared) snapshot.put(group.kind(), current.get(group.kind()));
        String preview = Crypto.hash(Crypto.hash(file) + Crypto.json(snapshot));
        if (!body.containsKey("preview")) {
            return Sql.map("preview", preview, "errors", errors, "newInvitee", newInvitee,
                    "employeeNo", author.get("employee_no"), "name", author.get("name"),
                    "materials", prepared.stream().map(group -> Sql.map(
                            "key", group.kind(), "rows", group.rows(), "kind", group.kind(),
                            "employeeNo", author.get("employee_no"), "name", author.get("name"),
                            "conflict", current.containsKey(group.kind()), "content", group.entries())).toList());
        }
        if (!preview.equals(Inputs.text(body, "preview", 64))) throw ApiFault.conflict("填报内容已变化，请重新预览");
        if (!errors.isEmpty() || prepared.isEmpty()) throw ApiFault.invalid("请修正文件中的问题后重新预览");
        var kinds = prepared.stream().map(Prepared::kind).toList();
        if (!(body.getOrDefault("overwrite", List.of()) instanceof List<?> overwrite) || !kinds.containsAll(overwrite))
            throw ApiFault.invalid("覆盖选项须对应预览中的材料");
        UUID attendeeId = newInvitee ? bookings.inviteWorkbookEmployee(actor, id, meeting, user) : (UUID) invite.get("id");
        int imported = 0;
        for (var group : prepared) {
            if (current.containsKey(group.kind()) && !overwrite.contains(group.kind())) continue;
            group.payload().put("source", "EXCEL");
            bookings.storeMaterial(attendeeId, group.kind(), group.payload());
            imported++;
        }
        if (imported > 0 || newInvitee) bookings.event(actor, request, id, "MEETING_MATERIALS_IMPORT");
        return Sql.map("imported", imported, "skipped", prepared.size() - imported, "inviteeAdded", newInvitee);
    }

    /** 精确匹配工号，姓名复核避免材料归错员工。 */
    private Map<String, Object> resolveEmployee(String value, String field) {
        var match = java.util.regex.Pattern.compile("^(.+?)[\\s+＋（(]+([A-Za-z0-9-]{1,64})[）)]?$").matcher(value.strip());
        if (!match.matches()) throw ApiFault.invalid(field + "请填写姓名和工号，例如 张三 BLK27001");
        var employee = bookings.workbookEmployee(match.group(2));
        if (employee == null || !match.group(1).strip().equals(employee.get("name")))
            throw ApiFault.invalid(field + "的姓名或工号与有效员工档案不符");
        return employee;
    }

    /** 在预检中保留工作表名称和正文单元格，便于员工退回修正。 */
    private void prepareText(List<Prepared> prepared, List<String> errors, Map<String, Object> meeting,
            Map<String, Object> author, String kind, String text) {
        if (text == null) return;
        var value = Sql.map("text", text);
        try {
            if (!types(meeting).contains(kind)) throw ApiFault.invalid("本场会议未征集此材料");
            prepared.add(new Prepared(kind, List.of(2), bookings.structuredPayload(value, kind, meeting, author), List.of(value)));
        } catch (ApiFault fault) {
            errors.add("「" + ("REPORT".equals(kind) ? "上周工作内容" : "本周工作计划") + "」A2：" + fault.getMessage());
        }
    }

    /** 将 Excel 部门与责任人转换为线上材料校验接受的员工标识。 */
    private Map<String, Object> coordination(Map<String, Object> values) {
        String category = switch (Objects.toString(values.get("category"), "")) {
            case "需公司协调" -> "COMPANY";
            case "需其他部门配合" -> "DEPARTMENT";
            default -> throw ApiFault.invalid("请选择事项类别「需公司协调」或「需其他部门配合」");
        };
        String department = Inputs.text(values, "department", 128);
        var matches = db.rows("SELECT id FROM iam.department WHERE status='ACTIVE' AND (code=? OR name=?)", department, department);
        if (matches.size() != 1) throw ApiFault.invalid("请填写有效且唯一的配合部门名称或编码");
        var assignee = resolveEmployee(Inputs.text(values, "assignee", 128), "责任人");
        return bookings.structuredRow("COORDINATION", Sql.map("category", category, "subject", values.get("subject"),
                "support", values.get("support"), "expected", values.get("expected"),
                "departmentId", matches.getFirst().get("id").toString(), "personId", assignee.get("user_id").toString()));
    }


    /** 汇总保留填报人信息，配合部门与责任人优先展示发起人改派结果。 */
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
                case "TOPICS" -> { }
                case "COORDINATION" -> {
                    int index = 0;
                    for (var row : payload.path("rows")) {
                        var line = new LinkedHashMap<>(author);
                        line.put("category", "COMPANY".equals(value(row, "category")) ? "需公司协调" : "需其他部门配合");
                        line.put("coord", value(row, "subject"));
                        for (String field : List.of("support", "expected")) line.put(field, value(row, field));
                        var result = results.get(material.get("attendee_id") + ":" + index);
                        line.put("department", result != null && result.get("department") != null
                                ? result.get("department") : value(row, "department"));
                        line.put("assignee", result != null && result.get("assigneeName") != null
                                ? result.get("assigneeName") : value(row, "assigneeName"));
                        if (result != null) {
                            line.put("conclusion", result.get("conclusion"));
                            if (result.get("dueAt") instanceof String dueAt)
                                line.put("dueAt", EXPORT_TIME.format(Instant.parse(dueAt)));
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
                if (attendee.get("report") == null) continue;
                output.add(Sql.map("employeeNo", attendee.get("employee_no"), "name", attendee.get("name"),
                        "report", FormattedText.preview(Objects.toString(attendee.get("report"), ""))));
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
        if (types.contains("COORDINATION")) columns.addAll(List.of("category|事项类别（需公司协调／需其他部门配合）",
                "coord|协调或配合事项", "support|需要协调或配合的内容", "expected|期望结果或验收标准", "department|配合部门"));
        return columns;
    }

    private static List<?> types(Map<String, Object> meeting) {
        return meeting.get("material_types") instanceof List<?> types ? types : List.of("REPORT");
    }

    private static String value(JsonNode node, String field) { return node.path(field).asText(""); }
}
