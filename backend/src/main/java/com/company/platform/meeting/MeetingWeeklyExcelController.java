package com.company.platform.meeting;

import com.company.platform.*;
import com.company.platform.intake.ImportWorkbook;
import com.company.platform.storage.OfficeGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 周会原生工作簿的浏览器会话和文档服务回调。 */
@RestController
public class MeetingWeeklyExcelController {
    private final MeetingWeeklyExcel excel;
    private final OfficeGateway gateway;

    public MeetingWeeklyExcelController(MeetingWeeklyExcel excel, OfficeGateway gateway) {
        this.excel = excel;
        this.gateway = gateway;
    }

    /** 当前用户须有对应部门的会议读取权限，返回部署配置中的受信编辑器脚本。 */
    @GetMapping("/api/v1/meetings/{meeting}/weekly-report/departments/{department}/excel")
    public Map<String, Object> status(@PathVariable UUID meeting, @PathVariable UUID department, HttpServletRequest request) {
        return ok(excel.editorStatus(actor(request), meeting, department), request);
    }

    /** 沿用受邀与部门权限签发工作簿，指定历史版本时始终以只读打开。 */
    @PostMapping("/api/v1/meetings/{meeting}/weekly-report/departments/{department}/excel")
    public Map<String, Object> open(@PathVariable UUID meeting, @PathVariable UUID department,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Inputs.only(body, "version");
        Integer version = body.containsKey("version") ? Inputs.integer(body.get("version"), 0, 1, Integer.MAX_VALUE) : null;
        if (version != null && version < 1) throw ApiFault.invalid("周报版本号无效");
        return ok(excel.open(actor(request), meeting, department, version), request);
    }

    /** 检查本人加入的编辑批次和已确认保存结果。 */
    @GetMapping("/api/v1/meetings/weekly-excel-sessions/{id}")
    public Map<String, Object> progress(@PathVariable UUID id, HttpServletRequest request) {
        return ok(excel.status(actor(request), id), request);
    }

    /** 工作簿源地址仅接受文档服务器签名。 */
    @GetMapping("/integrations/onlyoffice/weekly-source/{id}")
    public ResponseEntity<byte[]> source(@PathVariable UUID id, HttpServletRequest request) {
        byte[] bytes = excel.source(id, gateway.nativePayload(bearer(request), true));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ImportWorkbook.MEDIA))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    /** 原生编辑器回调只采用签名字段，限制正文体积并返回该服务的固定错误结构。 */
    @PostMapping("/integrations/onlyoffice/weekly-callback/{id}")
    public Map<String, Object> callback(@PathVariable UUID id, HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(65537);
            if (bytes.length > 65536) throw ApiFault.invalid("回调过大");
            var json = Crypto.JSON.readTree(bytes);
            boolean header = json.path("token").isMissingNode() || json.path("token").isNull();
            excel.callback(id, gateway.nativePayload(header ? bearer(request) : json.path("token").asText(), header));
            return Map.of("error", 0);
        } catch (Exception failure) {
            return Map.of("error", 1);
        }
    }

    private String bearer(HttpServletRequest request) {
        String value = request.getHeader("Authorization");
        if (value == null || !value.startsWith("Bearer ")) throw ApiFault.denied();
        return value.substring(7);
    }

    private Actor actor(HttpServletRequest request) {
        if (!(request.getAttribute(HttpBoundary.ACTOR) instanceof Actor actor)) throw ApiFault.session();
        return actor;
    }

    private Map<String, Object> ok(Object data, HttpServletRequest request) {
        return ApiController.envelope("OK", "操作成功", data, ApiController.requestId(request));
    }
}
