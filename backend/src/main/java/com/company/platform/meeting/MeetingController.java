package com.company.platform.meeting;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import com.company.platform.intake.ImportWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 会议接口复用平台的会话、CSRF、统一响应和错误包装。 */
@RestController
@RequestMapping("/api/v1")
public class MeetingController {
    private final MeetingBookings bookings;
    private final MeetingFollowUp followUp;
    private final MeetingPermissions permissions;
    /** 发起人下载、预检和导入本次会议的 Excel 服务。 */
    private final MeetingWorkbooks workbooks;
    private final Commands commands;
    private final MeetingIncompleteExport incomplete;

    public MeetingController(MeetingBookings bookings, MeetingFollowUp followUp, MeetingPermissions permissions,
            Commands commands, MeetingWorkbooks workbooks, MeetingIncompleteExport incomplete) {
        this.incomplete=incomplete;
        this.bookings = bookings;
        this.followUp = followUp;
        this.permissions = permissions;
        this.commands = commands;
        this.workbooks = workbooks;
    }

    @GetMapping("/meetings/options")
    public Map<String, Object> options(HttpServletRequest r) {
        return ok(bookings.options(actor(r)), r);
    }

    @GetMapping("/meetings/people")
    public Map<String, Object> people(@RequestParam Map<String, String> query, HttpServletRequest r) {
        return ok(bookings.people(actor(r), query), r);
    }

    @GetMapping("/meetings")
    public Map<String, Object> list(@RequestParam Map<String, String> query, HttpServletRequest r) {
        return ok(bookings.list(actor(r), query), r);
    }

    @GetMapping("/meetings/{id}")
    public Map<String, Object> detail(@PathVariable String id, HttpServletRequest r) {
        return ok(bookings.detail(actor(r), Inputs.uuid(id)), r);
    }

    @PostMapping("/meetings")
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest r) {
        return saved(bookings.create(actor(r), ApiController.requestId(r), body), r);
    }

    @PutMapping("/meetings/{id}")
    public Map<String, Object> change(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        bookings.change(actor(r), meeting, ApiController.requestId(r), version(r), body);
        return saved(meeting, r);
    }

    /** 发起人开始会议；预约过期或提前占用冲突时保留原状态。 */
    @PostMapping("/meetings/{id}/start")
    public Map<String, Object> start(@PathVariable String id, HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        bookings.start(actor(r), meeting, ApiController.requestId(r));
        return saved(meeting, r);
    }

    /** 发起人结束进行中的会议，之后可填写纪要。 */
    @PostMapping("/meetings/{id}/end")
    public Map<String, Object> end(@PathVariable String id, HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        bookings.end(actor(r), meeting, ApiController.requestId(r));
        return saved(meeting, r);
    }

    /** 保存现场会议记录，仅发起人可在会议进行中操作。 */
    @PutMapping("/meetings/{id}/live-notes")
    public Map<String, Object> liveNotes(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        bookings.saveLiveNotes(actor(r), meeting, body);
        return saved(meeting, r);
    }

    /** 进行中的会议只允许发起人保存决议。 */
    @PutMapping("/meetings/{id}/live-decisions")
    public Map<String, Object> liveDecisions(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        bookings.saveLiveDecisions(actor(r), meeting, body);
        return saved(meeting, r);
    }

    @PostMapping("/meetings/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        bookings.cancel(actor(r), meeting, ApiController.requestId(r), body);
        return saved(meeting, r);
    }

    @PostMapping("/meetings/{id}/reply")
    public Map<String, Object> reply(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        bookings.reply(actor(r), meeting, ApiController.requestId(r), body);
        return saved(meeting, r);
    }

    @PutMapping("/meetings/{id}/materials")
    public Map<String, Object> materials(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        bookings.materials(actor(r), meeting, ApiController.requestId(r), body);
        return saved(meeting, r);
    }

    /** 本场受邀员工确认阅读；版本过期须刷新后再次确认。 */
    @PostMapping("/meetings/{id}/reading")
    public Map<String, Object> confirmReading(@PathVariable String id, @RequestBody Map<String, Object> body,
            HttpServletRequest r) {
        Inputs.only(body, "revision");
        UUID meeting = Inputs.uuid(id);
        bookings.confirmReading(actor(r), meeting, ApiController.requestId(r), Inputs.text(body, "revision", 64));
        return saved(meeting, r);
    }

    /** 只有发起人可在开会后更正事项，发布后同步关联待办。 */
    @PutMapping("/meetings/{id}/coordination")
    public Map<String, Object> coordination(@PathVariable String id, @RequestBody Map<String, Object> body,
            HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        followUp.saveCoordination(actor(r), meeting, ApiController.requestId(r), body);
        return saved(meeting, r);
    }

    /** 发起人随时导出本场或本人全部会议的未完成事项，普通受邀人和其他发起人不可越权。 */
    @GetMapping({"/meetings/incomplete-export","/meetings/{id}/incomplete-export"})
    public ResponseEntity<byte[]> incomplete(@PathVariable(required=false) UUID id,HttpServletRequest r){
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .header("Content-Disposition",ContentDisposition.attachment().filename(id==null?"我发起会议未完成汇总.xlsx":"本场会议未完成情况.xlsx",java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(ImportWorkbook.MEDIA)).body(incomplete.download(actor(r),id));
    }

    /** 发起人下载会前填报模板，或导出当前材料和会中结论。 */
    @GetMapping("/meetings/{id}/workbooks/{mode}")
    public ResponseEntity<byte[]> workbook(@PathVariable String id, @PathVariable String mode, HttpServletRequest r) {
        boolean template = Inputs.choice(mode, "template", "summary").equals("template");
        byte[] workbook = workbooks.download(actor(r), Inputs.uuid(id), template);
        return ResponseEntity.ok()
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(template ? "会议填报模板.xlsx" : "会议汇总.xlsx", java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(MediaType.parseMediaType(ImportWorkbook.MEDIA)).body(workbook);
    }

    /** 发起人会前预检或确认导入，发现行错误或预检失效时不写入。 */
    @PostMapping("/meetings/{id}/workbooks/import")
    public Map<String, Object> importWorkbook(@PathVariable String id, @RequestBody Map<String, Object> body,
            HttpServletRequest r) {
        return ok(workbooks.importMaterials(actor(r), Inputs.uuid(id), ApiController.requestId(r), body), r);
    }

    @PutMapping("/meetings/{id}/minutes")
    public Map<String, Object> minutes(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        followUp.saveMinutes(actor(r), meeting, ApiController.requestId(r), version(r), body);
        return saved(meeting, r);
    }

    @GetMapping("/meetings/items/{id}")
    public Map<String, Object> item(@PathVariable String id, HttpServletRequest r) {
        return ok(followUp.item(actor(r), Inputs.uuid(id)), r);
    }

    /** 纪要发布后仅发起人能更正决议，旧版本返回409。 */
    @PutMapping("/meetings/{id}/minutes/decisions")
    public Map<String, Object> reviseDecisions(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        followUp.reviseDecisions(actor(r), meeting, ApiController.requestId(r), version(r), body);
        return saved(meeting, r);
    }

    /** 发起人更正独立待办。属于协调事项的待办须从协调结果修改。 */
    @PutMapping("/meetings/items/{id}")
    public Map<String, Object> reviseItem(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        UUID item = Inputs.uuid(id);
        followUp.reviseItem(actor(r), item, ApiController.requestId(r), body);
        return saved(item, r);
    }

    @PostMapping("/meetings/items/{id}/feedback")
    public Map<String, Object> feedback(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        UUID item = Inputs.uuid(id);
        followUp.feedback(actor(r), item, ApiController.requestId(r), body);
        return saved(item, r);
    }

    @PostMapping("/meetings/items/{id}/review")
    public Map<String, Object> review(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        UUID item = Inputs.uuid(id);
        followUp.review(actor(r), item, ApiController.requestId(r), body);
        return saved(item, r);
    }

    @PostMapping("/meetings/{id}/close")
    public Map<String, Object> close(@PathVariable String id, HttpServletRequest r) {
        UUID meeting = Inputs.uuid(id);
        followUp.close(actor(r), meeting, ApiController.requestId(r));
        return saved(meeting, r);
    }

    @GetMapping("/admin/meetings/rooms")
    public Map<String, Object> rooms(HttpServletRequest r) {
        return ok(Sql.map("items", bookings.rooms(actor(r))), r);
    }

    @PostMapping("/admin/meetings/rooms")
    public Map<String, Object> createRoom(@RequestBody Map<String, Object> body, HttpServletRequest r) {
        return saved(bookings.saveRoom(actor(r), null, ApiController.requestId(r), body), r);
    }

    @PutMapping("/admin/meetings/rooms/{id}")
    public Map<String, Object> updateRoom(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        return saved(bookings.saveRoom(actor(r), Inputs.uuid(id), ApiController.requestId(r), body), r);
    }

    /** 查看配置中的周会与紧急会议授权名单。 */
    @GetMapping("/admin/meetings/permissions")
    public Map<String, Object> permissions(HttpServletRequest r) {
        return ok(Sql.map("items", permissions.list(actor(r))), r);
    }

    /** 本人验证绑定目标员工及授权动作，任职变更使用平台命令事务。 */
    @PutMapping("/admin/meetings/permissions/{id}")
    public Map<String, Object> permission(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
        Inputs.only(body, "code", "granted");
        UUID user = Inputs.uuid(id);
        String code = Inputs.choice(body.get("code"), "MEETING_ORGANIZER", "MEETING_URGENT");
        if (!(body.get("granted") instanceof Boolean granted)) throw ApiFault.invalid("请指定授予或撤销");
        String action = (granted ? "GRANT_" : "REVOKE_") + code;
        var ctx = new Commands.Context(actor(r), ApiController.requestId(r),
                Inputs.uuid(r.getHeader("X-Operation-Id")), r.getMethod() + " " + r.getRequestURI(),
                r.getHeader("X-Reauth-Token"), null);
        return ok(commands.run(ctx, body, "USER", user, action, () -> permissions.authorize(ctx.actor()),
                () -> permissions.change(ctx, user, code, granted), key -> Sql.map("id", key)), r);
    }

    private int version(HttpServletRequest r) {
        return Inputs.integer(r.getHeader("X-Expected-Version"), 0, 1, Integer.MAX_VALUE);
    }

    private Actor actor(HttpServletRequest r) {
        if (!(r.getAttribute(HttpBoundary.ACTOR) instanceof Actor actor)) throw ApiFault.session();
        return actor;
    }

    private Map<String, Object> saved(UUID id, HttpServletRequest r) {
        return ok(Sql.map("id", id), r);
    }

    private Map<String, Object> ok(Object data, HttpServletRequest r) {
        return ApiController.envelope("OK", "操作成功", data, ApiController.requestId(r));
    }
}

