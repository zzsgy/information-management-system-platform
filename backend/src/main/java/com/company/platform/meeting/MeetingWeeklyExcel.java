package com.company.platform.meeting;

import com.company.platform.*;
import com.company.platform.intake.MeetingWeeklyWorkbook;
import com.company.platform.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 将本场本部门的 XLSX 交给文档服务，最终保存仅接受已签名的原生回调。 */
@Service
public class MeetingWeeklyExcel {
    private final Sql db;
    private final MeetingWeeklyReports reports;
    private final OfficeGateway gateway;
    private final ServiceIdentities services;
    private final FileScanEngine scanner;
    private final TransactionTemplate tx;

    public MeetingWeeklyExcel(Sql db, MeetingWeeklyReports reports, OfficeGateway gateway,
            ServiceIdentities services, FileScanEngine scanner, PlatformTransactionManager manager) {
        this.db = db;
        this.reports = reports;
        this.gateway = gateway;
        this.services = services;
        this.scanner = scanner;
        this.tx = new TransactionTemplate(manager);
    }

    /** 浏览器先确认脚本可加载，再创建协作会话，避免留下未进入编辑器的会话。 */
    public Map<String, Object> editorStatus(Actor actor, UUID meeting, UUID department) {
        reports.requireRead(reports.access(actor, meeting, false), department);
        return Sql.map("editorScriptUrl", gateway.browserOrigin().isEmpty() ? null :
                gateway.browserOrigin() + "/web-apps/apps/api/documents/api.js");
    }

    /** 本部门成员共享编辑代次，发起人、历史版本及非本部门阅览使用只读代次。 */
    public Map<String, Object> open(Actor actor, UUID meeting, UUID department, Integer version) {
        reports.requireRead(reports.access(actor, meeting, false), department);
        gateway.verifyVersion();
        return tx.execute(ignored -> {
            var access = reports.access(actor, meeting, true);
            reports.requireRead(access, department);
            var sheet = db.required("""
                    SELECT name,submitted_version FROM portal.meeting_weekly_department WHERE meeting_id=? AND department_id=?
                    """, meeting, department);
            boolean edit = version == null && sheet.get("submitted_version") == null && reports.canEdit(access, department);
            services.require(gateway.service(), "OFFICE", "DRIVE", department, edit ? "EDIT" : "VIEW");
            if (version != null) db.required("""
                    SELECT version FROM portal.meeting_weekly_submission WHERE meeting_id=? AND department_id=? AND version=?
                    """, meeting, department, version);
            var document = db.one("SELECT revision FROM portal.meeting_weekly_excel WHERE meeting_id=? AND department_id=?", meeting, department);
            if (document == null) {
                byte[] initial = reports.initialWorkbook(meeting, department, (String) sheet.get("name"));
                db.update("INSERT INTO portal.meeting_weekly_excel(meeting_id,department_id,workbook) VALUES (?,?,?)", meeting, department, initial);
                document = Map.of("revision", 1);
            }
            db.update("""
                    UPDATE portal.meeting_weekly_excel_session SET state='FAILED',message='此前编辑已超时，请核对最后保存的工作簿'
                    WHERE meeting_id=? AND department_id=? AND state='EDITING' AND expires_at<=CURRENT_TIMESTAMP
                    """, meeting, department);
            var active = edit ? db.one("""
                    SELECT id FROM portal.meeting_weekly_excel_session WHERE meeting_id=? AND department_id=? AND state='EDITING'
                    """, meeting, department) : null;
            UUID id = active == null ? UUID.randomUUID() : (UUID) active.get("id");
            if (active == null) db.update("""
                    INSERT INTO portal.meeting_weekly_excel_session(id,meeting_id,department_id,state,base_revision,submission_version)
                    VALUES (?,?,?,?,?,?)
                    """, id, meeting, department, edit ? "EDITING" : "READING", document.get("revision"), version);
            db.update("""
                    INSERT INTO portal.meeting_weekly_excel_participant(session_id,user_id,login_session_id) VALUES (?,?,?)
                    ON CONFLICT(session_id,user_id) DO UPDATE SET login_session_id=EXCLUDED.login_session_id
                    """, id, actor.userId(), actor.sessionId());
            String title = sheet.get("name") + " · " + access.meeting().get("subject") + ".xlsx";
            var config = Sql.map("type", "desktop", "documentType", "cell", "height", "100%", "width", "100%",
                    "document", Sql.map("fileType", "xlsx", "title", title, "key", id.toString(), "url", gateway.weeklySource(id),
                            "permissions", Sql.map("edit", edit, "download", false, "print", false, "comment", false)),
                    "editorConfig", Sql.map("mode", edit ? "edit" : "view", "lang", "zh-CN",
                            "callbackUrl", gateway.weeklyCallback(id), "user", Sql.map("id", actor.userId(), "name", actor.user().get("name")),
                            "customization", Sql.map("autosave", true, "forcesave", false)));
            config.put("token", gateway.sign(config));
            return Sql.map("sessionId", id, "editorConfig", config, "editable", edit, "title", title);
        });
    }

    /** 页面轮询确认当前参与状态，撤权后不再保持编辑器打开。 */
    @Transactional(readOnly = true)
    public Map<String, Object> status(Actor actor, UUID id) {
        var session = db.required("""
                SELECT s.* FROM portal.meeting_weekly_excel_session s JOIN portal.meeting_weekly_excel_participant p ON p.session_id=s.id
                WHERE s.id=? AND p.user_id=? AND p.login_session_id=?
                """, id, actor.userId(), actor.sessionId());
        UUID meeting = (UUID) session.get("meeting_id"), department = (UUID) session.get("department_id");
        var access = reports.access(actor, meeting, false);
        reports.requireRead(access, department);
        if ("EDITING".equals(session.get("state")) && !reports.canEdit(access, department)) throw ApiFault.denied();
        return Sql.view(session, "state,message");
    }

    /** 服务器只能读取本次签名绑定的工作簿版本。浏览器登录凭据不能代替该签名。 */
    @Transactional
    public byte[] source(UUID id, Map<String, Object> payload) {
        if (!gateway.weeklySource(id).equals(payload.get("url"))) throw ApiFault.denied();
        var session = db.required("SELECT * FROM portal.meeting_weekly_excel_session WHERE id=?", id);
        boolean edit = "EDITING".equals(session.get("state"));
        if (!edit && !"READING".equals(session.get("state"))) throw ApiFault.denied();
        requireMembers(id, edit);
        services.require(gateway.service(), "OFFICE", "DRIVE", (UUID) session.get("department_id"), edit ? "EDIT" : "VIEW");
        if (session.get("submission_version") instanceof Number version)
            return reports.historicalWorkbook((UUID) session.get("meeting_id"), (UUID) session.get("department_id"), version.intValue());
        var source = db.required("SELECT workbook FROM portal.meeting_weekly_excel WHERE meeting_id=? AND department_id=? AND revision=?",
                session.get("meeting_id"), session.get("department_id"), session.get("base_revision"));
        return (byte[]) source.get("workbook");
    }

    /** 结束协作后以最终保存为准。未变化和回调重试不增加修订号。 */
    public void callback(UUID id, Map<String, Object> payload) throws IOException {
        if (!id.toString().equals(payload.get("key"))) throw ApiFault.denied();
        int state = Inputs.integer(payload.get("status"), 0, 1, 7);
        var session = db.required("SELECT * FROM portal.meeting_weekly_excel_session WHERE id=?", id);
        if (!"EDITING".equals(session.get("state"))) return;
        if (state == 1 || state == 6 || state == 7) return;
        if (state == 4) {
            db.update("UPDATE portal.meeting_weekly_excel_session SET state='CLOSED' WHERE id=? AND state='EDITING'", id);
            return;
        }
        if (state != 2) {
            fail(id, "文档服务未能完成最终保存");
            return;
        }
        Path candidate = null;
        try {
            requireMembers(id, true);
            services.require(gateway.service(), "OFFICE", "DRIVE", (UUID) session.get("department_id"), "EDIT");
            candidate = gateway.candidate(Inputs.text(payload, "url", 8192));
            MeetingWeeklyWorkbook.validate(candidate);
            if (scanner.scan(candidate) != ClamFileScanner.Verdict.CLEAN) {
                fail(id, "工作簿未通过文件检测，请联系管理员");
                return;
            }
            byte[] bytes = Files.readAllBytes(candidate);
            tx.executeWithoutResult(ignored -> {
                db.required("SELECT id FROM portal.meeting_booking WHERE id=? FOR NO KEY UPDATE", session.get("meeting_id"));
                var current = db.required("SELECT state FROM portal.meeting_weekly_excel_session WHERE id=? FOR UPDATE", id);
                if (!"EDITING".equals(current.get("state"))) return;
                requireMembers(id, true);
                if (db.update("""
                        UPDATE portal.meeting_weekly_excel SET workbook=?,revision=revision+1,saved_at=CURRENT_TIMESTAMP
                        WHERE meeting_id=? AND department_id=? AND revision=?
                        """, bytes, session.get("meeting_id"), session.get("department_id"), session.get("base_revision")) != 1)
                    throw ApiFault.conflict("工作簿已有新版，请重新打开");
                db.update("UPDATE portal.meeting_weekly_excel_session SET state='CLOSED' WHERE id=?", id);
            });
        } catch (ApiFault failure) {
            fail(id, failure.getMessage());
            throw failure;
        } finally {
            if (candidate != null) Files.deleteIfExists(candidate);
        }
    }

    private void fail(UUID session, String message) {
        db.update("UPDATE portal.meeting_weekly_excel_session SET state='FAILED',message=? WHERE id=? AND state='EDITING'", message, session);
    }

    /** 来源和最终稿件均检查当前会话、受邀关系和部门；只读权限沿用会议共享开关。 */
    private void requireMembers(UUID id, boolean edit) {
        if (db.count("""
                SELECT count(*) FROM portal.meeting_weekly_excel_session s
                JOIN portal.meeting_booking m ON m.id=s.meeting_id
                WHERE s.id=? AND s.expires_at>CURRENT_TIMESTAMP AND
                  (NOT ? OR (m.actual_started_at IS NULL AND m.cancelled_at IS NULL
                    AND NOT EXISTS (SELECT 1 FROM portal.meeting_weekly_department w
                        WHERE w.meeting_id=m.id AND w.department_id=s.department_id AND w.submitted_version IS NOT NULL)))
                  AND EXISTS (SELECT 1 FROM portal.meeting_weekly_excel_participant p WHERE p.session_id=s.id)
                  AND NOT EXISTS (
                    SELECT 1 FROM portal.meeting_weekly_excel_participant p
                    JOIN iam.user_account u ON u.id=p.user_id JOIN iam.session l ON l.id=p.login_session_id
                    WHERE p.session_id=s.id AND (u.status<>'ACTIVE' OR l.revoked_at IS NOT NULL OR
                      l.expires_at<=CURRENT_TIMESTAMP OR l.last_activity_at<=CURRENT_TIMESTAMP-interval '30 minutes' OR
                      l.auth_version<>u.auth_version OR l.session_version<>u.session_version OR
                      (? AND (u.department_id IS DISTINCT FROM s.department_id OR NOT EXISTS (
                        SELECT 1 FROM portal.meeting_attendee a WHERE a.meeting_id=m.id AND a.user_id=u.id
                          AND a.weekly_department_id=s.department_id AND a.removed_at IS NULL))) OR
                      (NOT ? AND u.id<>m.organizer_id AND NOT EXISTS (
                        SELECT 1 FROM portal.meeting_attendee a WHERE a.meeting_id=m.id AND a.user_id=u.id
                          AND a.removed_at IS NULL AND (a.weekly_department_id=s.department_id OR m.share_all))
                        AND NOT EXISTS (SELECT 1 FROM iam.user_role ur JOIN iam.role r ON r.id=ur.role_id
                          WHERE ur.user_id=u.id AND ur.module='DRIVE' AND ur.role_code='GENERAL_MANAGER'
                            AND ur.state='ACTIVE' AND r.status='ACTIVE' AND ur.valid_from<=CURRENT_TIMESTAMP
                            AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP)))))
                """, id, edit, edit, edit) != 1) throw ApiFault.denied();
    }
}
