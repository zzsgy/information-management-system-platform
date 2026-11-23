package com.company.platform.meeting;

import com.company.platform.*;
import com.company.platform.intake.WeeklySummaryWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;

/** 发起人在已有会议内补录部门汇总及协调事宜，沿用标准会议材料和纪要。 */
@Service
public class MeetingWeeklyImports {
    private final Sql db;
    private final MeetingBookings bookings;
    private final MeetingWorkbooks workbooks;

    public MeetingWeeklyImports(Sql db, MeetingBookings bookings, MeetingWorkbooks workbooks) {
        this.db = db;
        this.bookings = bookings;
        this.workbooks = workbooks;
    }

    private record Key(UUID employee, String kind) {}
    private record Material(Key key, String sheet, String department, String name, Object content) {}

    /** 预览不写入；确认时重新核对人员、内容和已有材料，按整批事务写入。 */
    @Transactional(rollbackFor = IOException.class)
    public Map<String, Object> importSummary(Actor actor, UUID meetingId, UUID request, LocalDate date,
                                             String confirmation, InputStream stream) throws IOException {
        if (confirmation != null) bookings.lockWorkbookImport(actor);
        var meeting = bookings.owned(actor, meetingId);
        if (meeting.get("minutes_published_at") != null) throw ApiFault.conflict("纪要已发布，不能再导入周会汇总");

        var file = Files.createTempFile("ims-weekly-summary-", ".xlsx");
        WeeklySummaryWorkbook.Summary summary;
        try {
            Files.copy(stream, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(file) > WeeklySummaryWorkbook.MAX_BYTES) throw ApiFault.invalid("周会汇总 Excel 文件须在 50 MB 以内");
            summary = WeeklySummaryWorkbook.read(file, date);
        } finally {
            Files.deleteIfExists(file);
        }

        var errors = new ArrayList<String>();
        var materials = prepare(summary, errors);
        if (materials.isEmpty() && errors.isEmpty()) errors.add("所选日期没有可导入的部门材料，协调事宜也为空");
        var current = new HashMap<Key, Object>();
        var attendees = new HashMap<UUID, UUID>();
        for (var row : db.rows("""
                SELECT a.id,a.user_id,x.kind,x.payload::text AS payload FROM portal.meeting_attendee a
                LEFT JOIN portal.meeting_material x ON x.attendee_id=a.id
                WHERE a.meeting_id=?
                """, meetingId)) {
            UUID user = (UUID) row.get("user_id");
            attendees.put(user, (UUID) row.get("id"));
            if (row.get("kind") != null) current.put(new Key(user, (String) row.get("kind")), row.get("payload"));
        }

        var entries = materials.values().stream().map(material -> Sql.map(
                "kind", material.key().kind(), "sheet", material.sheet(), "department", material.department(),
                "name", material.name(), "content", material.content(), "conflict", current.containsKey(material.key())
        )).toList();
        String preview = Crypto.hash(Crypto.json(List.of(meetingId, date.toString(), meeting.get("row_version"),
                materials.keySet(), entries, errors, summary.emptyDepartments())));
        if (confirmation == null) return Sql.map("preview", preview, "errors", errors, "materials", entries,
                "emptyDepartments", summary.emptyDepartments());
        if (!preview.equals(confirmation)) throw ApiFault.conflict("导入内容或会议已变化，请重新预览");
        if (!errors.isEmpty()) throw ApiFault.invalid("请修正预览中的问题后再确认导入");

        int imported = 0;
        var kinds = new LinkedHashSet<String>();
        if (meeting.get("material_types") instanceof List<?> enabled)
            enabled.forEach(kind -> kinds.add(kind.toString()));
        for (var material : materials.values()) {
            var key = material.key();
            if (current.containsKey(key)) continue;
            UUID attendee = bookings.registerWeeklyWorkbookAuthor(meetingId, key.employee(), attendees.get(key.employee()));
            attendees.put(key.employee(), attendee);
            var payload = Sql.map(key.kind().equals("COORDINATION") ? "rows" : "text", material.content(),
                    "source", "EXCEL", "weeklyWorkbook", true, "author",
                    Sql.map("name", material.name(), "department", material.department()));
            bookings.storeMaterial(attendee, key.kind(), payload);
            kinds.add(key.kind());
            imported++;
        }
        if (imported > 0) {
            db.update("UPDATE portal.meeting_booking SET collect_materials=true,material_types=?::text[],row_version=row_version+1 WHERE id=?",
                    "{" + String.join(",", kinds) + "}", meetingId);
            bookings.event(actor, request, meetingId, "MEETING_WEEKLY_IMPORT");
        }
        return Sql.map("imported", imported, "skipped", materials.size() - imported);
    }

    private Map<Key, Material> prepare(WeeklySummaryWorkbook.Summary summary, List<String> errors) {
        var materials = new LinkedHashMap<Key, Material>();
        for (var page : summary.departments()) {
            try {
                var unit = department(page.sheet());
                var leads = db.rows("""
                        SELECT u.id,u.name FROM iam.department_manager m
                        JOIN iam.user_account u ON u.id=m.user_id AND u.department_id=m.department_id
                        WHERE m.department_id=? AND m.duty_type='DEPARTMENT_MANAGER' AND m.valid_from<=CURRENT_TIMESTAMP
                          AND (m.valid_until IS NULL OR m.valid_until>CURRENT_TIMESTAMP)
                          AND u.status='ACTIVE' AND NOT u.it_shared_account
                        """, unit.get("id"));
                if (leads.size() != 1) throw ApiFault.invalid("请为该部门配置唯一的有效部门负责人");
                addReport(materials, page.sheet(), unit, leads.getFirst(), "REPORT", page.work());
                addReport(materials, page.sheet(), unit, leads.getFirst(), "WEEKLY_PLAN", page.plan());
            } catch (ApiFault error) {
                errors.add("「" + page.sheet() + "」" + error.getMessage());
            }
        }
        for (var row : summary.coordination()) {
            try {
                var values = new LinkedHashMap<>(row.values());
                var author = workbooks.resolveEmployee(Inputs.text(values, "author", 128), "填报人");
                var unit = department(Inputs.text(values, "authorDepartment", 128));
                if (!unit.get("id").equals(author.get("department_id")))
                    throw ApiFault.invalid("填报部门与员工档案不一致");
                values.put("department", department(Inputs.text(values, "department", 128)).get("code"));
                var entry = workbooks.coordination(values);
                if (values.containsKey("deadlineText")) entry.put("deadlineText", values.get("deadlineText"));
                var key = new Key((UUID) author.get("user_id"), "COORDINATION");
                var material = materials.computeIfAbsent(key, ignored -> new Material(key, "协调事宜",
                        departmentPath(unit), (String) author.get("name"), new ArrayList<Map<String, Object>>()));
                @SuppressWarnings("unchecked")
                var lines = (List<Map<String, Object>>) material.content();
                lines.add(entry);
            } catch (ApiFault error) {
                errors.add("「协调事宜」第 " + row.number() + " 行：" + error.getMessage());
            }
        }
        return materials;
    }

    /** 同一负责人同类材料只对应一张部门表，避免重复页静默覆盖。 */
    private void addReport(Map<Key, Material> materials, String sheet, Map<String, Object> unit,
                           Map<String, Object> lead, String kind, String text) {
        if (text == null) return;
        if (text.length() > 50_000) throw ApiFault.invalid("单个栏目超过 50000 字符，请核对所选日期");
        var key = new Key((UUID) lead.get("id"), kind);
        if (materials.putIfAbsent(key, new Material(key, sheet, departmentPath(unit), (String) lead.get("name"), text)) != null)
            throw ApiFault.invalid("该部门已经有同一类工作表");
    }

    /** 部门简称采用组织编码，子部门可用名称、编码或“上级/下级”。 */
    private Map<String, Object> department(String label) {
        String[] path = label.split("[/／>＞]", 2);
        String name = path.length == 2 ? path[1].strip() : label;
        String parent = path.length == 2 ? path[0].strip() : "";
        var units = db.rows("""
                SELECT d.id,d.code,d.name,p.code AS parent_code,p.name AS parent_name FROM iam.department d
                LEFT JOIN iam.department p ON p.id=d.parent_id
                WHERE d.status='ACTIVE' AND (upper(d.code)=upper(?) OR d.name=? OR
                    ((upper(d.code)=upper(?) OR d.name=?) AND ?<>'' AND (upper(p.code)=upper(?) OR p.name=?)))
                """, label, label, name, name, parent, parent, parent);
        if (units.size() != 1) throw ApiFault.invalid("未能唯一匹配部门「" + label + "」");
        return units.getFirst();
    }

    private static String departmentPath(Map<String, Object> unit) {
        return unit.get("parent_name") == null || "ROOT".equals(unit.get("parent_code"))
                ? (String) unit.get("name") : unit.get("parent_name") + " / " + unit.get("name");
    }
}
