package com.company.platform.storage;

import com.company.platform.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

/** 转换版本与专用取源接口分离；普通VIEW不能调用服务取原件入口。 */
@RestController
public class OfficeConversionController {
    private final OfficeConversions conversions;private final OfficeGateway gateway;
    public OfficeConversionController(OfficeConversions conversions,OfficeGateway gateway){this.conversions=conversions;this.gateway=gateway;}
    @PostMapping("/api/v1/drive/documents/{id}/convert")
    public Map<String,Object> convert(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request){return DriveLifecycleController.ok(conversions.convert(DriveLifecycleController.context(request),id,body),request);}
    /** outbox签名与服务预登记的版本同时有效才允许取源，不接受浏览器Cookie替代。 */
    @GetMapping("/integrations/onlyoffice/conversion-source/{id}")
    public void source(@PathVariable UUID id,HttpServletRequest request,HttpServletResponse response)throws IOException{String token=request.getHeader("Authorization");if(token==null||!token.startsWith("Bearer "))throw ApiFault.denied();var payload=gateway.nativePayload(token.substring(7),true);var object=conversions.source(id,payload);response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setContentType("application/octet-stream");response.setContentLengthLong(((Number)object.get("byte_size")).longValue());conversions.streamSource(id,payload,response.getOutputStream());}
}
