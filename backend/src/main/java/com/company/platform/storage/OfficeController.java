package com.company.platform.storage;

import com.company.platform.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Office人机接口与文档服务回调分开；后者只能用独立outbox签名调用。 */
@RestController
public class OfficeController {
    /** 配置适配器与保存状态机，控制器不直接修改正式版本。 */
    private final OfficeSessions sessions;private final OfficeGateway gateway;
    public OfficeController(OfficeSessions sessions,OfficeGateway gateway){this.sessions=sessions;this.gateway=gateway;}
    /** 返回不含密钥的可用性，电脑端据此解释未部署状态。 */
    @GetMapping("/api/v1/drive/office/status") public Map<String,Object> status(HttpServletRequest request){DriveLifecycleController.actor(request).require("DRIVE.ACCESS");var state=gateway.status();if(Boolean.TRUE.equals(state.get("enabled")))state.put("editorScriptUrl",gateway.browserOrigin()+"/web-apps/apps/api/documents/api.js");return DriveLifecycleController.ok(state,request);}
    /** 单文档加入协作；当前VIEW和EDIT、协作开关与版本必须同时有效。 */
    @PostMapping("/api/v1/drive/documents/{id}/edit-sessions") public Map<String,Object> open(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request){return DriveLifecycleController.ok(sessions.open(DriveLifecycleController.actor(request),id,body,ApiController.requestId(request)),request);}
    /** 状态轮询不续登录会话，发现撤权时电脑端关闭编辑器。 */
    @GetMapping("/api/v1/drive/edit-sessions/{id}") public Map<String,Object> detail(@PathVariable UUID id,HttpServletRequest request){return DriveLifecycleController.ok(sessions.detail(DriveLifecycleController.actor(request),id),request);}
    /** 202只表示保存命令受理，正式版本成功需回执为SAVED。 */
    @PostMapping("/api/v1/drive/edit-sessions/{id}/checkpoints") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Map<String,Object> checkpoint(@PathVariable UUID id,HttpServletRequest request){return DriveLifecycleController.ok(sessions.checkpoint(DriveLifecycleController.actor(request),id,Inputs.uuid(request.getHeader("X-Operation-Id")),ApiController.requestId(request)),request);}
    /** 最后一人离开后等待最终保存，不提前显示保存成功。 */
    @DeleteMapping("/api/v1/drive/edit-sessions/{id}/participants/me") public Map<String,Object> leave(@PathVariable UUID id,HttpServletRequest request){return DriveLifecycleController.ok(sessions.leave(DriveLifecycleController.actor(request),id),request);}
    /** 回执列表只对当前参与者公开，最多100项。 */
    @GetMapping("/api/v1/drive/save-receipts") public Map<String,Object> receipts(@RequestParam UUID sessionId,HttpServletRequest request){return DriveLifecycleController.ok(Sql.map("items",sessions.receipts(DriveLifecycleController.actor(request),sessionId)),request);}
    /** 原生回调返回ONLYOFFICE要求的error格式；JWT之外的字段不参与保存决定。 */
    @PostMapping("/integrations/onlyoffice/callback/{id}") public Map<String,Object> callback(@PathVariable UUID id,HttpServletRequest request) {
        try {if(request.getContentLengthLong()>65536)throw ApiFault.invalid("回调过大");byte[] bytes=request.getInputStream().readNBytes(65537);if(bytes.length>65536)throw ApiFault.invalid("回调过大");
            @SuppressWarnings("unchecked") Map<String,Object> body=Crypto.JSON.readValue(new String(bytes,StandardCharsets.UTF_8),Map.class);
            boolean header=body.get("token")==null;String token=header?bearer(request):Objects.toString(body.get("token"),"");return Sql.map("error",sessions.callback(id,gateway.nativePayload(token,header)));
        }catch(Exception failure){return Sql.map("error",1);}
    }
    /** 专用服务器读取固定原件；浏览器即使看到地址也没有outbox签名，不能绕过DOWNLOAD。 */
    @GetMapping("/integrations/onlyoffice/source/{id}") public void source(@PathVariable UUID id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        var payload=gateway.nativePayload(bearer(request),true);var object=sessions.source(id,payload);response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setContentType("application/octet-stream");response.setContentLengthLong(((Number)object.get("byte_size")).longValue());sessions.streamSource(id,payload,response.getOutputStream());
    }
    /** 仅接受原生服务Authorization头，不接受查询参数或浏览器Cookie替代。 */
    private String bearer(HttpServletRequest request){String value=request.getHeader("Authorization");if(value==null||!value.startsWith("Bearer "))throw ApiFault.denied();return value.substring(7);}
}
