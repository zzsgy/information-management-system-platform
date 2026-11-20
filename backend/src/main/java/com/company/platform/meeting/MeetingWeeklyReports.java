package com.company.platform.meeting;

import com.company.platform.*;
import com.company.platform.intake.MeetingWeeklyWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.*;
import java.util.regex.Pattern;

/** 周会按受邀部门共填草稿，由该部门当前负责人提交版本，发起人提取到正式材料。 */
@Service
public class MeetingWeeklyReports {
    private static final String SOURCE = "ONLINE_WEEKLY";
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]\\r\\n]*]\\(inline-image/([0-9a-f-]{36})\\)");
    private static final String ROW_FIELDS = "id,department_id,kind,author_id,author_name,employee_no,updated_by,updated_at,version";
    private final Sql db;
    private final MeetingBookings bookings;
    private final IdentityService identity;

    public MeetingWeeklyReports(Sql db, MeetingBookings bookings, IdentityService identity) {
        this.db = db;
        this.bookings = bookings;
        this.identity = identity;
    }

    /** 同一份草稿中仅本部门受邀员工可写，会议共享只增加其它部门的读取权限。 */
    record Access(Map<String, Object> meeting, Map<String, Object> attendee, boolean organizer, boolean readAll) {
        UUID department() { return attendee == null ? null : (UUID) attendee.get("weekly_department_id"); }
        boolean open() { return meeting.get("actual_started_at") == null && meeting.get("cancelled_at") == null; }
    }

    /** 核对会议或受邀关系，在写操作中先锁预约，与发起、改期、开始会议使用同一状态。 */
    Access access(Actor actor, UUID meeting, boolean write) {
        if (write) identity.lockActor(actor);
        var booking = db.required("SELECT * FROM portal.meeting_booking WHERE id=? AND online_weekly_report"
                + (write ? " FOR NO KEY UPDATE" : ""), meeting);
        boolean organizer = actor.userId().equals(booking.get("organizer_id"));
        boolean manager = !organizer && bookings.generalManager(actor.userId());
        var attendee = db.one("""
                SELECT a.id,a.weekly_department_id,u.name,u.employee_no,u.department_id
                FROM portal.meeting_attendee a JOIN iam.user_account u ON u.id=a.user_id
                WHERE a.meeting_id=? AND a.user_id=? AND a.removed_at IS NULL
                """, meeting, actor.userId());
        if (!organizer && !manager && attendee == null) throw ApiFault.hidden();
        return new Access(booking, attendee, organizer, organizer || manager || Boolean.TRUE.equals(booking.get("share_all")));
    }

    /** 负责人与受邀部门按准确 ID 对应，子部门不继承上级的提交权限。 */
    private List<Map<String, Object>> sheets(UUID meeting) {
        return db.rows("""
                SELECT w.*,d.sort_order,h.user_id AS head_id,h.name AS head_name,
                    EXISTS (SELECT 1 FROM portal.meeting_attendee a
                        WHERE a.meeting_id=w.meeting_id AND a.weekly_department_id=w.department_id
                          AND a.removed_at IS NULL) AS active,
                    EXISTS (SELECT 1 FROM portal.meeting_attendee a
                        WHERE a.meeting_id=w.meeting_id AND a.weekly_department_id=w.department_id
                          AND a.user_id=h.user_id AND a.removed_at IS NULL) AS head_invited
                FROM portal.meeting_weekly_department w JOIN iam.department d ON d.id=w.department_id
                LEFT JOIN LATERAL (
                    SELECT u.id AS user_id,u.name FROM iam.department_manager h
                    JOIN iam.user_account u ON u.id=h.user_id AND u.department_id=h.department_id
                    WHERE h.department_id=w.department_id AND h.duty_type='DEPARTMENT_MANAGER'
                      AND h.valid_from<=CURRENT_TIMESTAMP AND (h.valid_until IS NULL OR h.valid_until>CURRENT_TIMESTAMP)
                      AND u.status='ACTIVE' AND NOT u.it_shared_account AND d.status='ACTIVE'
                    ORDER BY h.valid_from DESC,u.id LIMIT 1
                ) h ON true
                WHERE w.meeting_id=? ORDER BY d.sort_order,w.name,w.department_id
                """, meeting);
    }

    /** 部门负责人决定是否提交或重开，录入权由同部门的有效受邀身份决定。 */
    boolean canEdit(Access access, UUID department) {
        return access.open() && department.equals(access.department())
                && department.equals(access.attendee().get("department_id"));
    }

    /** 未开启会议共享时隐藏其他部门表，包括版本和截图。 */
    void requireRead(Access access, UUID department) {
        if (!access.readAll() && !department.equals(access.department())) throw ApiFault.hidden();
    }

    /** 展示本次会议草稿、已提交状态及其历史版本，图片按需单独读取。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> view(Actor actor, UUID meeting) {
        Access access = access(actor, meeting, false);
        var departments = sheets(meeting).stream().filter(row -> access.readAll()
                || row.get("department_id").equals(access.department())).map(row -> {
            UUID id = (UUID) row.get("department_id");
            boolean editable = canEdit(access, id);
            var sheet = Sql.view(row, "department_id,name,head_name,head_invited,active,submitted_version,extracted_version");
            boolean head = editable && actor.userId().equals(row.get("head_id"));
            sheet.put("canEdit", editable && row.get("submitted_version") == null);
            sheet.put("canSubmit", head && row.get("submitted_version") == null);
            sheet.put("canReopen", head && row.get("submitted_version") != null);
            return sheet;
        }).toList();
        var rows = db.rows("""
                SELECT r.*,r.data::text AS data_json,d.name AS partner,u.name AS assignee_name,u.employee_no AS assignee_no
                FROM portal.meeting_weekly_row r
                LEFT JOIN iam.department d ON d.id::text=r.data->>'departmentId'
                LEFT JOIN iam.user_account u ON u.id::text=r.data->>'personId'
                WHERE r.meeting_id=? AND NOT deleted AND (? OR r.department_id=?)
                ORDER BY r.created_at,r.id
                """, meeting, access.readAll(), access.department()).stream().map(this::rowView).toList();
        var versions = db.rows("""
                SELECT department_id,version,submitted_by,submitted_at
                FROM portal.meeting_weekly_submission WHERE meeting_id=? AND (? OR department_id=?)
                ORDER BY version DESC,department_id
                """, meeting, access.readAll(), access.department());
        return Sql.map("departments", departments, "rows", rows, "versions",
                versions.stream().map(row -> Sql.view(row, "department_id,version,submitted_by,submitted_at")).toList(),
                "canExtract", access.open() && access.organizer(), "ownDepartmentId", access.department());
    }

    /** 返回已提交的行快照，仍走当前会议阅览范围。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JsonNode version(Actor actor, UUID meeting, UUID department, int version) {
        requireRead(access(actor, meeting, false), department);
        var snapshot = db.required("""
                SELECT rows::text AS snapshot FROM portal.meeting_weekly_submission
                WHERE meeting_id=? AND department_id=? AND version=?
                """, meeting, department, version);
        return Crypto.JSON.readTree((String) snapshot.get("snapshot"));
    }

    /** 同部门协作按行自动保存。提交时再检查整张表和协调行的必填字段。 */
    @Transactional
    public Map<String, Object> save(Actor actor, UUID meeting, UUID id, Map<String, Object> body) {
        Inputs.only(body, "kind", "version", "markdown", "note", "images", "subject", "support", "expected",
                "category", "departmentId", "personId");
        Access access = access(actor, meeting, true);
        UUID department = access.department();
        requireEditable(access, meeting);
        String kind = Inputs.choice(body.get("kind"), "REPORT", "WEEKLY_PLAN", "COORDINATION");
        int expected = Inputs.integer(body.get("version"), 0, 0, Integer.MAX_VALUE);
        var prior = db.one("SELECT *,data::text AS data_json FROM portal.meeting_weekly_row WHERE id=?", id);
        if (prior != null && (!meeting.equals(prior.get("meeting_id")) || !department.equals(prior.get("department_id"))
                || !kind.equals(prior.get("kind")) || Boolean.TRUE.equals(prior.get("deleted")))) throw ApiFault.hidden();
        var images = kind.equals("COORDINATION") ? Map.<UUID, InlineImages.Image>of() : InlineImages.prepare(body.get("images"));
        Map<String, Object> data = kind.equals("COORDINATION") ? draftCoordination(body) : draftNarrative(id, body, images);
        if (prior != null && Crypto.JSON.readTree((String) prior.get("data_json")).equals(Crypto.JSON.readTree(Crypto.json(data))))
            return rowView(prior);
        int actual = prior == null ? 0 : ((Number) prior.get("version")).intValue();
        if (expected != actual) throw ApiFault.conflict("这行已被同事修改，请刷新后再编辑");
        String name = (String) access.attendee().get("name");
        if (prior == null) {
            if (db.count("""
                    SELECT count(*) FROM portal.meeting_weekly_row WHERE meeting_id=? AND department_id=? AND kind=? AND NOT deleted
                    """, meeting, department, kind) >= 100) throw ApiFault.invalid("该页每类材料最多填写 100 行");
            db.update("""
                    INSERT INTO portal.meeting_weekly_row(id,meeting_id,department_id,kind,author_id,author_name,employee_no,updated_by,data)
                    VALUES (?,?,?,?,?,?,?,?,?::jsonb)
                    """, id, meeting, department, kind, access.attendee().get("id"), name,
                    access.attendee().get("employee_no"), name, Crypto.json(data));
        } else {
            db.update("""
                    UPDATE portal.meeting_weekly_row SET data=?::jsonb,updated_by=?,updated_at=CURRENT_TIMESTAMP,version=version+1
                    WHERE id=?
                    """, Crypto.json(data), name, id);
        }
        images.forEach((key, image) -> db.update("""
                INSERT INTO portal.meeting_weekly_image(row_id,image_key,media_type,image_bytes)
                VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                """, id, key, image.mediaType(), image.bytes()));
        return rowView(db.required("SELECT *,data::text AS data_json FROM portal.meeting_weekly_row WHERE id=?", id));
    }

    /** 从草稿移除事项；已提交版本及其图片保留。 */
    @Transactional
    public void delete(Actor actor, UUID meeting, UUID row, int version) {
        Access access = access(actor, meeting, true);
        requireEditable(access, meeting);
        var stored = db.required("""
                SELECT version,deleted FROM portal.meeting_weekly_row WHERE id=? AND meeting_id=? AND department_id=?
                """, row, meeting, access.department());
        if (Boolean.TRUE.equals(stored.get("deleted"))) return;
        if (version != ((Number) stored.get("version")).intValue())
            throw ApiFault.conflict("这行已被同事修改，请刷新后再删除");
        db.update("""
                UPDATE portal.meeting_weekly_row SET deleted=true,updated_by=?,updated_at=CURRENT_TIMESTAMP,version=version+1
                WHERE id=?
                """, access.attendee().get("name"), row);
    }

    /** 只有本组未提交时可以继续写草稿；他组和发起人均不能代填。 */
    private void requireEditable(Access access, UUID meeting) {
        UUID department = access.department();
        if (department == null || !canEdit(access, department)) throw ApiFault.denied();
        var sheet = db.required("""
                SELECT submitted_version FROM portal.meeting_weekly_department WHERE meeting_id=? AND department_id=?
                """, meeting, department);
        if (sheet.get("submitted_version") != null) throw ApiFault.conflict("部门周报已提交，请由负责人重新开放");
        if (db.count("SELECT count(*) FROM portal.meeting_weekly_excel WHERE meeting_id=? AND department_id=?", meeting, department) != 0)
            throw ApiFault.conflict("该部门已使用在线 Excel，请在工作簿中修改");
    }

    /** 草稿允许先填写部分字段，责任人和配合范围在部门提交时按会议规则验证。 */
    private Map<String, Object> draftCoordination(Map<String, Object> body) {
        return Sql.map("subject", MeetingBookings.optional(body, "subject", 500),
                "support", MeetingBookings.optional(body, "support", 1000),
                "expected", MeetingBookings.optional(body, "expected", 1000),
                "category", Inputs.choice(body.getOrDefault("category", "DEPARTMENT"), "COMPANY", "DEPARTMENT"),
                "departmentId", Inputs.nullableUuid(body.get("departmentId")), "personId", Inputs.nullableUuid(body.get("personId")));
    }

    /** 正文只引用本行已有或本次上传的图片，以便重开后仍能阅读旧版本。 */
    private Map<String, Object> draftNarrative(UUID row, Map<String, Object> body, Map<UUID, InlineImages.Image> images) {
        String markdown = MeetingBookings.optional(body, "markdown", 8000);
        var keys = imageKeys(markdown);
        var known = new HashSet<>(images.keySet());
        known.addAll(db.rows("SELECT image_key FROM portal.meeting_weekly_image WHERE row_id=?", row)
                .stream().map(record -> (UUID) record.get("image_key")).toList());
        if (keys.size() > 3 || !known.containsAll(keys) || !keys.containsAll(images.keySet()))
            throw ApiFault.invalid("每行最多引用本行的 3 张图片");
        return Sql.map("markdown", markdown, "note", MeetingBookings.optional(body, "note", 2000), "imageKeys", keys);
    }

    /** 返回 Markdown 中按出现顺序记录的插图编号。 */
    private static Set<UUID> imageKeys(String markdown) {
        Set<UUID> ids = new LinkedHashSet<>();
        IMAGE.matcher(Objects.toString(markdown, "")).results().forEach(match -> ids.add(Inputs.uuid(match.group(1))));
        return ids;
    }

    /** 按草稿行格式输出身份与已保存内容。 */
    private Map<String, Object> rowView(Map<String, Object> row) {
        var view = Sql.view(row, ROW_FIELDS);
        view.put("data", Crypto.JSON.readTree((String) row.get("data_json")));
        view.put("partner", row.get("partner"));
        view.put("assigneeName", row.get("assignee_name"));
        view.put("assigneeNo", row.get("assignee_no"));
        return view;
    }

    /** 提交时检查两个正文区块和所有协调行，写入不可变的部门版本。 */
    @Transactional
    public void submit(Actor actor, UUID meeting, UUID department, UUID request) {
        Access access = access(actor, meeting, true);
        var sheet = requireHead(actor, access, meeting, department);
        if (sheet.get("submitted_version") != null) return;
        if (db.count("""
                SELECT count(*) FROM portal.meeting_weekly_excel_session
                WHERE meeting_id=? AND department_id=? AND state='EDITING' AND expires_at>CURRENT_TIMESTAMP
                """, meeting, department) > 0)
            throw ApiFault.conflict("请等本部门所有人关闭 Excel 编辑页，并确认最终保存后再提交");
        var lastEdit = db.one("""
                SELECT state FROM portal.meeting_weekly_excel_session
                WHERE meeting_id=? AND department_id=? AND submission_version IS NULL AND state<>'READING'
                ORDER BY expires_at DESC,id DESC LIMIT 1
                """, meeting, department);
        if (lastEdit != null && !"CLOSED".equals(lastEdit.get("state")))
            throw ApiFault.conflict("上次 Excel 保存尚未成功，请在编辑页核对后再提交");
        var workbook = db.one("SELECT workbook FROM portal.meeting_weekly_excel WHERE meeting_id=? AND department_id=?", meeting, department);
        byte[] bytes = workbook == null ? null : (byte[]) workbook.get("workbook");
        if (bytes != null) loadExcelDraft(meeting, department, (String) sheet.get("name"), bytes);
        var rows = db.rows("""
                SELECT r.*,r.data::text AS data_json,a.removed_at FROM portal.meeting_weekly_row r
                JOIN portal.meeting_attendee a ON a.id=r.author_id
                WHERE r.meeting_id=? AND r.department_id=? AND NOT r.deleted ORDER BY r.created_at,r.id
                """, meeting, department);
        var snapshot = new ArrayList<Map<String, Object>>();
        var completed = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var view = rowView(row);
            String kind = (String) row.get("kind");
            JsonNode data = (JsonNode) view.get("data");
            if (row.get("removed_at") != null) throw ApiFault.conflict("请处理已移出会议的员工所填行，再提交该部门");
            try {
                if (kind.equals("COORDINATION")) {
                    var fields = Sql.map("subject", text(data, "subject"), "expected", text(data, "expected"),
                            "support", text(data, "support"), "category", text(data, "category"),
                            "departmentId", text(data, "departmentId"), "personId", text(data, "personId"));
                    view.put("data", bookings.structuredRow(kind, fields));
                } else {
                    if (text(data, "markdown") == null) throw ApiFault.invalid("请填写事项说明");
                    completed.add(kind);
                }
                snapshot.add(view);
            } catch (ApiFault invalid) {
                throw ApiFault.invalid("第 " + (i + 1) + " 条填报（" + row.get("author_name") + "）：" + invalid.getMessage());
            }
        }
        if (!completed.containsAll(Set.of("REPORT", "WEEKLY_PLAN")))
            throw ApiFault.invalid("请分别填写上周总结和本周计划，没有内容可填写「无」");
        validateNarrativeLength(snapshot);
        int version = (int) db.count("""
                SELECT COALESCE(max(version),0)+1 FROM portal.meeting_weekly_submission
                WHERE meeting_id=? AND department_id=?
                """, meeting, department);
        db.update("""
                INSERT INTO portal.meeting_weekly_submission(meeting_id,department_id,version,submitted_by,rows,workbook)
                VALUES (?,?,?,?,?::jsonb,?)
                """, meeting, department, version, access.attendee().get("name"), Crypto.json(snapshot), bytes);
        db.update("UPDATE portal.meeting_weekly_department SET submitted_version=? WHERE meeting_id=? AND department_id=?",
                version, meeting, department);
        bookings.event(actor, request, meeting, "MEETING_WEEKLY_REPORT_SUBMIT");
    }

    /** 迁移旧填报时保持原填报人、备注和截图，首次进入即可继续原数据。 */
    byte[] initialWorkbook(UUID meeting, UUID department, String name) {
        var drafts = db.rows("""
                SELECT r.*,r.data::text AS data_json,d.name AS partner,u.name AS assignee_name,u.employee_no AS assignee_no
                FROM portal.meeting_weekly_row r
                LEFT JOIN iam.department d ON d.id::text=r.data->>'departmentId'
                LEFT JOIN iam.user_account u ON u.id::text=r.data->>'personId'
                WHERE r.meeting_id=? AND r.department_id=? AND NOT deleted ORDER BY r.created_at,r.id
                """, meeting, department);
        return writeWorkbook(drafts, name);
    }

    /** V81 已提交的版本也能在原生编辑器中只读打开。 */
    byte[] historicalWorkbook(UUID meeting, UUID department, int version) {
        var snapshot = db.required("""
                SELECT w.name,s.workbook FROM portal.meeting_weekly_submission s
                JOIN portal.meeting_weekly_department w USING(meeting_id,department_id)
                WHERE s.meeting_id=? AND s.department_id=? AND s.version=?
                """, meeting, department, version);
        if (snapshot.get("workbook") instanceof byte[] workbook) return workbook;
        var drafts = db.rows("""
                SELECT (r->>'id')::uuid AS id,r->>'kind' AS kind,r->>'authorName' AS author_name,r->>'employeeNo' AS employee_no,
                    (r->'data')::text AS data_json,r->'data'->>'department' AS partner,
                    r->'data'->>'assigneeName' AS assignee_name,u.employee_no AS assignee_no
                FROM portal.meeting_weekly_submission s CROSS JOIN LATERAL jsonb_array_elements(s.rows) WITH ORDINALITY AS row(r,n)
                LEFT JOIN iam.user_account u ON u.id::text=r->'data'->>'assigneeId'
                WHERE s.meeting_id=? AND s.department_id=? AND s.version=? ORDER BY n
                """, meeting, department, version);
        return writeWorkbook(drafts, (String) snapshot.get("name"));
    }

    /** 汇总工作簿以协调事宜为首页，其余页按已提交且有权查看的部门列出。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] summaryWorkbook(Actor actor, UUID meeting) {
        Access access = access(actor, meeting, false);
        var coordination = new ArrayList<MeetingWeeklyWorkbook.Line>();
        var workbook = new ArrayList<MeetingWeeklyWorkbook.Sheet>();
        workbook.add(new MeetingWeeklyWorkbook.Sheet("协调事宜", MeetingWeeklyWorkbook.COORDINATION, coordination));
        var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        names.add("协调事宜");
        for (var unit : sheets(meeting)) {
            UUID department = (UUID) unit.get("department_id");
            if ((!access.readAll() && !department.equals(access.department())) || !(unit.get("submitted_version") instanceof Number version))
                continue;
            var parts = MeetingWeeklyWorkbook.read(historicalWorkbook(meeting, department, version.intValue()));
            for (var row : parts.getFirst().lines()) {
                var cells = new ArrayList<>(row.cells());
                if (cells.get(4).isEmpty()) cells.set(4, (String) unit.get("name"));
                cells.set(0, String.valueOf(coordination.size() + 1));
                coordination.add(new MeetingWeeklyWorkbook.Line(coordination.size() + 2, cells, row.images()));
            }
            var rows = new ArrayList<MeetingWeeklyWorkbook.Line>();
            for (var part : parts.subList(1, parts.size())) {
                int number = 0;
                for (var row : part.lines()) {
                    var cells = new ArrayList<>(row.cells());
                    cells.set(0, String.valueOf(++number));
                    cells.addFirst(part.name());
                    rows.add(new MeetingWeeklyWorkbook.Line(rows.size() + 2, cells, row.images()));
                }
            }
            String name = ((String) unit.get("name")).replaceAll("[\\\\/?*:\\[\\]]", "_").replaceAll("^'+|'+$", "");
            if (name.isBlank()) name = "部门";
            String base = name.substring(0, Math.min(name.length(), 28));
            name = base;
            for (int i = 2; !names.add(name); i++) name = base + "_" + i;
            workbook.add(new MeetingWeeklyWorkbook.Sheet(name, List.of("名称", "序号", "事项说明", "备注", "填报人+工号"), rows));
        }
        return MeetingWeeklyWorkbook.write(workbook);
    }

    private byte[] writeWorkbook(List<Map<String, Object>> drafts, String name) {
        var result = new ArrayList<MeetingWeeklyWorkbook.Sheet>();
        var kinds = List.of("COORDINATION", "REPORT", "WEEKLY_PLAN");
        for (int i = 0; i < kinds.size(); i++) {
            String kind = kinds.get(i);
            var lines = new ArrayList<MeetingWeeklyWorkbook.Line>();
            for (var draft : drafts) {
                if (!kind.equals(draft.get("kind"))) continue;
                var data = Crypto.JSON.readTree((String) draft.get("data_json"));
                int number = lines.size() + 2;
                String author = draft.get("author_name") + "+" + draft.get("employee_no");
                var cells = List.of(Integer.toString(number - 1), data.path("markdown").asText(""), data.path("note").asText(""), author);
                var images = new ArrayList<InlineImages.Image>();
                if (i == 0) {
                    cells = List.of(Integer.toString(number - 1), data.path("subject").asText(""), data.path("support").asText(""),
                            data.path("expected").asText(""), name, author, Objects.toString(draft.get("partner"), ""),
                            draft.get("assignee_name") == null ? "" : draft.get("assignee_name") + "+" + draft.get("assignee_no"),
                            "COMPANY".equals(data.path("category").asText()) ? "需公司协调" : "需其他部门配合");
                } else {
                    cells = List.of(cells.get(0), IMAGE.matcher(cells.get(1)).replaceAll("").strip(), cells.get(2), author);
                    for (var key : data.path("imageKeys")) {
                        var image = db.required("SELECT media_type,image_bytes FROM portal.meeting_weekly_image WHERE row_id=? AND image_key=?",
                                draft.get("id"), Inputs.uuid(key.asText()));
                        images.add(new InlineImages.Image((String) image.get("media_type"), (byte[]) image.get("image_bytes")));
                    }
                }
                lines.add(new MeetingWeeklyWorkbook.Line(number, cells, images));
            }
            result.add(new MeetingWeeklyWorkbook.Sheet(MeetingWeeklyWorkbook.NAMES.get(i),
                    i == 0 ? MeetingWeeklyWorkbook.COORDINATION : MeetingWeeklyWorkbook.NARRATIVE, lines));
        }
        return MeetingWeeklyWorkbook.write(result);
    }

    /** 将已保存的原生工作簿作为完整替换，校验失败时整个部门保持上一版。 */
    private void loadExcelDraft(UUID meeting, UUID department, String name, byte[] bytes) {
        var sheets = MeetingWeeklyWorkbook.read(bytes);
        var authors = new HashMap<String, Map<String, Object>>();
        for (var a : db.rows("""
                SELECT a.id,u.name,u.employee_no FROM portal.meeting_attendee a JOIN iam.user_account u ON u.id=a.user_id
                WHERE a.meeting_id=? AND a.weekly_department_id=? AND a.removed_at IS NULL
                """, meeting, department))
            authors.put(a.get("name") + "+" + a.get("employee_no"), a);
        db.update("""
                UPDATE portal.meeting_weekly_row SET deleted=true WHERE meeting_id=? AND department_id=? AND NOT deleted
                """, meeting, department);
        var kinds = List.of("COORDINATION", "REPORT", "WEEKLY_PLAN");
        for (int i = 0; i < sheets.size(); i++) {
            var sheet = sheets.get(i);
            String kind = kinds.get(i);
            for (var row : sheet.lines()) {
                try {
                    loadExcelRow(meeting, department, name, authors, kind, row);
                } catch (ApiFault invalid) {
                    throw ApiFault.invalid("「" + sheet.name() + "」第 " + row.number() + " 行：" + invalid.getMessage());
                }
            }
        }
    }

    /** Excel 每一行按填报人栏定位受邀员工，责任人仍复用原会前协调规则。 */
    private void loadExcelRow(UUID meeting, UUID department, String name, Map<String, Map<String, Object>> authors,
            String kind, MeetingWeeklyWorkbook.Line row) {
        boolean coordination = kind.equals("COORDINATION");
        var cells = row.cells();
        String label = cells.get(coordination ? 5 : 3).replace('＋', '+').replaceAll("\\s*\\+\\s*", "+");
        var author = authors.get(label);
        if (author == null) throw ApiFault.invalid("填报人须为本部门受邀员工，格式为姓名+工号");
        UUID id = UUID.randomUUID();
        var images = new LinkedHashMap<UUID, InlineImages.Image>();
        Map<String, Object> data;
        if (coordination) {
            if (!row.images().isEmpty()) throw ApiFault.invalid("协调事宜请填写文本，截图可放在周报内容中");
            if (!cells.get(4).isEmpty() && !name.equals(cells.get(4))) throw ApiFault.invalid("填报部门须为本部门");
            var partner = db.one("SELECT id FROM iam.department WHERE name=? AND status='ACTIVE'", cells.get(6));
            int separator = cells.get(7).lastIndexOf('+');
            if (separator < 1) throw ApiFault.invalid("责任人须填写姓名+工号");
            var person = bookings.workbookEmployee(cells.get(7).substring(separator + 1).strip());
            if (person == null || !person.get("name").equals(cells.get(7).substring(0, separator).strip()))
                throw ApiFault.invalid("责任人的姓名或工号有误");
            String category = switch (cells.get(8)) {
                case "需公司协调" -> "COMPANY";
                case "需其他部门配合" -> "DEPARTMENT";
                default -> throw ApiFault.invalid("请选择事项类别");
            };
            data = Sql.map("subject", cells.get(1), "support", cells.get(2), "expected", cells.get(3), "category", category,
                    "departmentId", partner == null ? null : partner.get("id"), "personId", person.get("user_id"));
        } else {
            var markdown = new StringBuilder(cells.get(1));
            for (var image : row.images()) {
                UUID key = UUID.randomUUID();
                images.put(key, image);
                markdown.append("\n\n![图片](inline-image/").append(key).append(")");
            }
            data = Sql.map("markdown", MeetingBookings.optional(Map.of("markdown", markdown.toString()), "markdown", 8000),
                    "note", MeetingBookings.optional(Map.of("note", cells.get(2)), "note", 2000), "imageKeys", images.keySet());
        }
        db.update("""
                INSERT INTO portal.meeting_weekly_row(id,meeting_id,department_id,kind,author_id,author_name,employee_no,updated_by,data)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb)
                """, id, meeting, department, kind, author.get("id"), author.get("name"), author.get("employee_no"), author.get("name"), Crypto.json(data));
        images.forEach((key, image) -> db.update("""
                INSERT INTO portal.meeting_weekly_image(row_id,image_key,media_type,image_bytes) VALUES (?,?,?,?)
                """, id, key, image.mediaType(), image.bytes()));
    }

    /** 每人每类正文仍与现有周报的内容上限一致。 */
    private void validateNarrativeLength(List<Map<String, Object>> snapshot) {
        var lengths = new HashMap<String, Integer>();
        for (var row : snapshot) {
            if ("COORDINATION".equals(row.get("kind"))) continue;
            var data = (JsonNode) row.get("data");
            String key = row.get("authorId") + ":" + row.get("kind");
            int count = lengths.merge(key, data.path("markdown").asText().length() + data.path("note").asText("").length(), Integer::sum);
            if (count > 8000) throw ApiFault.invalid(row.get("authorName") + " 的同类周报正文和备注合计不能超过 8000 字");
        }
    }

    /** 负责人更正草稿时保留之前所有提交及已经提取的正式材料。 */
    @Transactional
    public void reopen(Actor actor, UUID meeting, UUID department, UUID request) {
        Access access = access(actor, meeting, true);
        var sheet = requireHead(actor, access, meeting, department);
        if (sheet.get("submitted_version") == null) return;
        db.update("UPDATE portal.meeting_weekly_department SET submitted_version=NULL WHERE meeting_id=? AND department_id=?",
                meeting, department);
        bookings.event(actor, request, meeting, "MEETING_WEEKLY_REPORT_REOPEN");
    }

    /** 权限使用部门有效任职和受邀身份，职员及上级负责人不能代提交。 */
    private Map<String, Object> requireHead(Actor actor, Access access, UUID meeting, UUID department) {
        if (!canEdit(access, department)) throw ApiFault.denied();
        var sheet = sheets(meeting).stream().filter(row -> department.equals(row.get("department_id"))).findFirst()
                .orElseThrow(ApiFault::hidden);
        if (!actor.userId().equals(sheet.get("head_id"))) throw ApiFault.denied();
        return sheet;
    }

    /** 发起人预览各组缺报状态和本次待提取的版本。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> preview(Actor actor, UUID meeting) {
        requireOrganizing(access(actor, meeting, false));
        return projection(meeting);
    }

    private void requireOrganizing(Access access) {
        if (!access.organizer()) throw ApiFault.hidden();
        if (!access.open()) throw ApiFault.conflict("会前才可提取周报材料");
    }

    /** 预览绑定版本与受邀人员，部门重开或名单变化后不能确认旧结果。 */
    private Map<String, Object> projection(UUID meeting) {
        var departments = sheets(meeting).stream().filter(row -> Boolean.TRUE.equals(row.get("active"))).toList();
        var ready = departments.stream().filter(row -> row.get("submitted_version") != null
                        && !Objects.equals(row.get("submitted_version"), row.get("extracted_version")))
                .map(row -> Sql.view(row, "department_id,name,submitted_version")).toList();
        var missing = departments.stream().filter(row -> row.get("submitted_version") == null)
                .map(row -> Sql.view(row, "department_id,name")).toList();
        var attendees = db.rows("""
                SELECT id FROM portal.meeting_attendee WHERE meeting_id=? AND removed_at IS NULL ORDER BY id
                """, meeting);
        var errors = db.rows("""
                SELECT DISTINCT w.name FROM portal.meeting_weekly_department w
                JOIN portal.meeting_weekly_submission s ON s.meeting_id=w.meeting_id
                  AND s.department_id=w.department_id AND s.version=w.submitted_version
                CROSS JOIN LATERAL jsonb_array_elements(s.rows) AS r
                JOIN portal.meeting_attendee a ON a.id=(r->>'authorId')::uuid
                WHERE w.meeting_id=? AND a.removed_at IS NOT NULL
                  AND w.submitted_version IS DISTINCT FROM w.extracted_version
                  AND EXISTS (SELECT 1 FROM portal.meeting_attendee active WHERE active.meeting_id=w.meeting_id
                              AND active.weekly_department_id=w.department_id AND active.removed_at IS NULL)
                ORDER BY w.name
                """, meeting).stream().map(row -> row.get("name") + " 有已移出会议的填报人，请由部门负责人重开并处理相关行").toList();
        return Sql.map("ready", ready, "missing", missing, "errors", errors,
                "token", Crypto.hash(Crypto.json(List.of(ready, attendees))));
    }

    /** 只有发起人能确认，原子替换本部门上一版材料，并按填报人归并汇报和协调事项。 */
    @Transactional
    public int extract(Actor actor, UUID meeting, UUID request, String token) {
        requireOrganizing(access(actor, meeting, true));
        var preview = projection(meeting);
        if (!token.equals(preview.get("token"))) throw ApiFault.conflict("提交版本已变化，请重新预览");
        if (!((List<?>) preview.get("errors")).isEmpty()) throw ApiFault.conflict("请先处理预览中的问题");
        int count = 0;
        for (var ready : Inputs.objects(preview.get("ready"), 500)) {
            extractDepartment(meeting, (UUID) ready.get("departmentId"), (String) ready.get("name"),
                    ((Number) ready.get("submittedVersion")).intValue());
            count++;
        }
        if (count > 0) bookings.event(actor, request, meeting, "MEETING_WEEKLY_REPORT_EXTRACT");
        return count;
    }

    /** 旧版未出现在新快照中的条目同步移除，草稿及提交历史可继续查阅。 */
    private void extractDepartment(UUID meeting, UUID department, String name, int version) {
        var record = db.required("""
                SELECT rows::text AS snapshot FROM portal.meeting_weekly_submission
                WHERE meeting_id=? AND department_id=? AND version=?
                """, meeting, department, version);
        var materials = new LinkedHashMap<UUID, Map<String, List<JsonNode>>>();
        for (var row : Crypto.JSON.readTree((String) record.get("snapshot"))) {
            materials.computeIfAbsent(Inputs.uuid(row.path("authorId").asText()), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(row.path("kind").asText(), ignored -> new ArrayList<>()).add(row);
        }
        for (var author : materials.entrySet()) {
            for (var material : author.getValue().entrySet()) {
                extractMaterial(author.getKey(), material.getKey(), material.getValue(), department, name);
            }
        }
        var existing = db.rows("""
                SELECT x.attendee_id,x.kind FROM portal.meeting_material x
                JOIN portal.meeting_attendee a ON a.id=x.attendee_id
                WHERE a.meeting_id=? AND x.payload->>'source'=? AND x.payload->>'weeklyDepartmentId'=?
                """, meeting, SOURCE, department.toString());
        for (var previous : existing) {
            UUID author = (UUID) previous.get("attendee_id");
            String kind = (String) previous.get("kind");
            if (!materials.getOrDefault(author, Map.of()).containsKey(kind))
                db.update("DELETE FROM portal.meeting_material WHERE attendee_id=? AND kind=?", author, kind);
        }
        db.update("UPDATE portal.meeting_weekly_department SET extracted_version=? WHERE meeting_id=? AND department_id=?",
                version, meeting, department);
    }

    /** 提取保留正文、备注和图片，协调事项继续使用现有结论与会后待办流程。 */
    private void extractMaterial(UUID attendee, String kind, List<JsonNode> rows, UUID department, String name) {
        var payload = Sql.map("source", SOURCE, "weeklyDepartmentId", department, "author",
                Sql.map("name", rows.getFirst().path("authorName").asText(), "department", name));
        if (kind.equals("COORDINATION")) {
            payload.put("rows", rows.stream().map(row -> row.path("data")).toList());
            bookings.storeMaterial(attendee, kind, payload);
            return;
        }
        var entries = new ArrayList<String>();
        Set<UUID> allKeys = new LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode data = rows.get(i).path("data");
            String content = data.path("markdown").asText();
            allKeys.addAll(imageKeys(content));
            String note = text(data, "note");
            entries.add("**" + (i + 1) + ".** " + content + (note == null ? "" : "\n\n备注：" + note));
        }
        String markdown = String.join("\n\n", entries);
        payload.put("markdown", markdown);
        payload.put("text", IMAGE.matcher(markdown).replaceAll("[图片]").replace("**", ""));
        payload.put("imageKeys", allKeys);
        bookings.storeMaterial(attendee, kind, payload);
        for (var row : rows) {
            db.update("""
                    INSERT INTO portal.meeting_material_image(attendee_id,kind,image_key,media_type,image_bytes)
                    SELECT ?,?,i.image_key,i.media_type,i.image_bytes FROM portal.meeting_weekly_image i
                    WHERE i.row_id=? AND i.image_key::text IN (SELECT jsonb_array_elements_text(?::jsonb))
                    ON CONFLICT DO NOTHING
                    """, attendee, kind, Inputs.uuid(row.path("id").asText()), Crypto.json(row.path("data").path("imageKeys")));
        }
    }

    /** 截图只按周报所属会议和部门授权，旧版本保留相同边界。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InlineImages.Image image(Actor actor, UUID meeting, UUID row, UUID key) {
        Access access = access(actor, meeting, false);
        var stored = db.required("""
                SELECT r.department_id,i.media_type,i.image_bytes FROM portal.meeting_weekly_image i
                JOIN portal.meeting_weekly_row r ON r.id=i.row_id
                WHERE r.meeting_id=? AND i.row_id=? AND i.image_key=?
                """, meeting, row, key);
        requireRead(access, (UUID) stored.get("department_id"));
        return new InlineImages.Image((String) stored.get("media_type"), (byte[]) stored.get("image_bytes"));
    }

    private static String text(JsonNode value, String field) {
        return value.path(field).asText(null);
    }
}
